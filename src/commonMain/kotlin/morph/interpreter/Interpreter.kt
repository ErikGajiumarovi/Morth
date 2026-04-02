package morph.interpreter

import morph.ast.Block
import morph.ast.BlockExpr
import morph.ast.CastExpr
import morph.ast.ComparisonExpr
import morph.ast.ConstructorExpr
import morph.ast.EntryFile
import morph.ast.Expr
import morph.ast.FieldAccessExpr
import morph.ast.FunCallExpr
import morph.ast.ImplDecl
import morph.ast.ItExpr
import morph.ast.LogicalExpr
import morph.ast.LogicalOp
import morph.ast.MatchExpr
import morph.ast.MethodCallExpr
import morph.ast.MorphRuntimeError
import morph.ast.NamedType
import morph.ast.Pattern
import morph.ast.PrimitiveType
import morph.ast.ProductType
import morph.ast.QueryType
import morph.ast.TypeDecl
import morph.ast.UnresolvedCallExpr
import morph.ast.ValRefExpr
import morph.ast.VariantPattern
import morph.ast.WildcardPattern
import morph.runtime.BoolValue
import morph.runtime.FloatValue
import morph.runtime.IntValue
import morph.runtime.ListValue
import morph.runtime.MapValue
import morph.runtime.MorphValue
import morph.runtime.ProductValue
import morph.runtime.SetValue
import morph.runtime.StringValue
import morph.runtime.UnitValue
import morph.runtime.UuidValue
import morph.runtime.VariantValue
import morph.platform.RuntimePlatform
import morph.typechecker.CheckedProgram
import morph.typechecker.MorphSemantics

class Environment(
    val types: Map<String, TypeDecl>,
    val funs: Map<String, morph.ast.FunSig>,
    val impls: Map<String, ImplDecl>,
    val vals: MutableMap<String, MorphValue> = mutableMapOf(),
) {
    fun child(): Environment = Environment(types, funs, impls, vals.toMutableMap())
}

class Interpreter(
    private val checked: CheckedProgram,
) {
    fun evaluateEntry(entry: EntryFile): MorphValue {
        val env = Environment(checked.types, checked.funs, checked.resolvedImpls)
        return evalBlock(entry.body, env)
    }

    fun evalExpr(expr: Expr, env: Environment): MorphValue {
        return when (expr) {
            is ValRefExpr -> env.vals[expr.name]
                ?: throw MorphRuntimeError(expr.location, "Unknown binding: ${expr.name}")

            is morph.ast.LiteralExpr -> literalValue(expr)
            is FieldAccessExpr -> MorphSemantics.accessField(evalExpr(expr.target, env), expr.field, checked.types, expr.location)
            is MethodCallExpr -> evalMethodCall(expr, env)
            is ItExpr -> {
                val subject = env.vals["it"] ?: throw MorphRuntimeError(expr.location, "'it' is not bound")
                val lhs = MorphSemantics.projectConstraintValue(subject, expr.path, checked.types, expr.location)
                val rhs = evalExpr(expr.rhs, env)
                MorphSemantics.compareValues(lhs, rhs, expr.op, expr.location, checked.types)
            }

            is ComparisonExpr -> {
                val lhs = evalExpr(expr.left, env)
                val rhs = evalExpr(expr.rhs, env)
                MorphSemantics.compareValues(lhs, rhs, expr.op, expr.location, checked.types)
            }

            is LogicalExpr -> {
                val left = evalBoolean(expr.left, env)
                when (expr.op) {
                    LogicalOp.AND -> if (!left) BoolValue(false) else BoolValue(evalBoolean(expr.right, env))
                    LogicalOp.OR -> if (left) BoolValue(true) else BoolValue(evalBoolean(expr.right, env))
                }
            }

            is MatchExpr -> evalMatch(expr, env)
            is CastExpr -> evalCast(expr, env)
            is BlockExpr -> evalBlock(expr.block, env)
            is UnresolvedCallExpr -> evalUnresolvedCall(expr, env)
            is ConstructorExpr -> constructType(expr.typeName, expr.args.map { evalExpr(it, env) }, expr.location)
            is FunCallExpr -> callFunction(expr.name, evalExpr(expr.arg, env), expr.location, env)
        }
    }

    private fun evalUnresolvedCall(expr: UnresolvedCallExpr, env: Environment): MorphValue {
        return when {
            expr.name in checked.funs -> {
                if (expr.args.size != 1) {
                    throw MorphRuntimeError(expr.location, "Function calls require exactly one argument")
                }
                callFunction(expr.name, evalExpr(expr.args.single(), env), expr.location, env)
            }

            expr.name in checked.types -> constructType(expr.name, expr.args.map { evalExpr(it, env) }, expr.location)
            expr.name in checked.variants -> constructVariant(expr.name, expr.args.map { evalExpr(it, env) }, expr.location)
            else -> throw MorphRuntimeError(expr.location, "Unknown symbol: ${expr.name}")
        }
    }

    private fun callFunction(name: String, arg: MorphValue, location: morph.ast.SourceLocation, env: Environment): MorphValue {
        val impl = env.impls[name] ?: throw MorphRuntimeError(location, "No implementation bound for $name")
        val child = env.child()
        child.vals["it"] = arg
        return evalBlock(impl.body, child)
    }

    private fun constructType(typeName: String, args: List<MorphValue>, location: morph.ast.SourceLocation): MorphValue {
        val decl = checked.types[typeName] ?: throw MorphRuntimeError(location, "Unknown constructor type: $typeName")
        return when (decl) {
            is PrimitiveType -> {
                if (args.size != 1) {
                    throw MorphRuntimeError(location, "$typeName expects exactly one argument")
                }
                val normalized = MorphSemantics.normalizePrimitiveArgument(decl.base, args.single(), location)
                val wrapped = ProductValue(typeName, mapOf(typeName to normalized))
                if (decl.constraint != null && !MorphSemantics.evaluateConstraint(decl.constraint, wrapped, checked.types, location)) {
                    throw MorphRuntimeError(location, "Constraint violated: ${renderValue(wrapped)} where { ${decl.constraint.source} }")
                }
                wrapped
            }

            is NamedType -> {
                if (args.size != decl.fields.size) {
                    throw MorphRuntimeError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                val value = ProductValue(typeName, decl.fields.mapIndexed { index, field -> field.name to args[index] }.toMap())
                if (decl.constraint != null && !MorphSemantics.evaluateConstraint(decl.constraint, value, checked.types, location)) {
                    throw MorphRuntimeError(location, "Constraint violated: ${renderValue(value)} where { ${decl.constraint.source} }")
                }
                value
            }

            is ProductType -> {
                if (args.size != decl.fields.size) {
                    throw MorphRuntimeError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                ProductValue(typeName, decl.fields.mapIndexed { index, field -> field to args[index] }.toMap())
            }

            is QueryType -> {
                if (args.size != decl.fields.size) {
                    throw MorphRuntimeError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                ProductValue(typeName, decl.fields.mapIndexed { index, field -> field to args[index] }.toMap())
            }

            else -> throw MorphRuntimeError(location, "$typeName is not directly constructible")
        }
    }

    private fun constructVariant(name: String, args: List<MorphValue>, location: morph.ast.SourceLocation): MorphValue {
        val info = checked.variants[name] ?: throw MorphRuntimeError(location, "Unknown variant: $name")
        if (args.size != info.variant.fields.size) {
            throw MorphRuntimeError(location, "$name expects ${info.variant.fields.size} argument(s)")
        }
        return VariantValue(info.ownerType, name, args)
    }

    private fun evalMatch(expr: MatchExpr, env: Environment): MorphValue {
        val subject = evalExpr(expr.subject, env)
        for (arm in expr.arms) {
            val bindings = matchPattern(arm.pattern, subject, arm.location)
            if (bindings != null) {
                val child = env.child()
                child.vals.putAll(bindings)
                return evalExpr(arm.body, child)
            }
        }
        throw MorphRuntimeError(expr.location, "No match arm matched value ${renderValue(subject)}")
    }

    private fun matchPattern(
        pattern: Pattern,
        subject: MorphValue,
        location: morph.ast.SourceLocation,
    ): Map<String, MorphValue>? {
        return when (pattern) {
            is WildcardPattern -> emptyMap()
            is VariantPattern -> when (subject) {
                is VariantValue -> {
                    if (subject.variantName != pattern.typeName) {
                        null
                    } else {
                        if (pattern.bindings.size != subject.fields.size) {
                            throw MorphRuntimeError(location, "Pattern ${pattern.typeName} expects ${subject.fields.size} binding(s)")
                        }
                        pattern.bindings.zip(subject.fields).toMap()
                    }
                }

                is ProductValue -> {
                    if (subject.typeName != pattern.typeName) {
                        null
                    } else {
                        val items = destructureProduct(subject, location)
                        if (pattern.bindings.size != items.size) {
                            throw MorphRuntimeError(location, "Pattern ${pattern.typeName} expects ${items.size} binding(s)")
                        }
                        pattern.bindings.zip(items).toMap()
                    }
                }

                else -> null
            }
        }
    }

    private fun destructureProduct(value: ProductValue, location: morph.ast.SourceLocation): List<MorphValue> {
        return when (val decl = checked.types[value.typeName]) {
            is PrimitiveType -> listOf(value.fields.getValue(decl.name))
            is NamedType -> decl.fields.map { value.fields.getValue(it.name) }
            is ProductType -> decl.fields.map { value.fields.getValue(it) }
            is QueryType -> decl.fields.map { value.fields.getValue(it) }
            else -> throw MorphRuntimeError(location, "Type ${value.typeName} cannot be pattern-matched positionally")
        }
    }

    private fun evalCast(expr: CastExpr, env: Environment): MorphValue {
        val source = evalExpr(expr.expr, env)
        if (source !is VariantValue) {
            throw MorphRuntimeError(expr.location, "Cast source must be a variant value")
        }
        val target = checked.types[expr.targetType]
        if (target !is QueryType) {
            throw MorphRuntimeError(expr.location, "Cast target must be a Query type")
        }
        val variantInfo = checked.variants[source.variantName]
            ?: throw MorphRuntimeError(expr.location, "Unknown variant ${source.variantName}")
        val fieldsByType = variantInfo.variant.fields.zip(source.fields).toMap()
        val queryFields = target.fields.associateWith { fieldType ->
            fieldsByType[fieldType]
                ?: throw MorphRuntimeError(expr.location, "Cannot cast ${source.variantName} to ${target.name}: missing type $fieldType")
        }
        return ProductValue(target.name, queryFields)
    }

    private fun evalBlock(block: Block, env: Environment): MorphValue {
        if (block.isUnsafe) {
            RuntimePlatform.stderrLine("[unsafe]")
        }
        val child = env.child()
        for (decl in block.vals) {
            child.vals[decl.name] = evalExpr(decl.expr, child)
        }
        return evalExpr(block.result, child)
    }

    private fun evalMethodCall(expr: MethodCallExpr, env: Environment): MorphValue {
        val receiver = MorphSemantics.unwrapPrimitiveIfNeeded(evalExpr(expr.receiver, env), checked.types)
        return when (expr.method) {
            "contains" -> {
                val arg = expr.args.singleOrNull()
                    ?: throw MorphRuntimeError(expr.location, "contains() expects exactly one argument")
                val value = MorphSemantics.unwrapPrimitiveIfNeeded(evalExpr(arg, env), checked.types)
                if (receiver !is StringValue || value !is StringValue) {
                    throw MorphRuntimeError(expr.location, "contains() expects String receiver and argument")
                }
                BoolValue(receiver.v.contains(value.v))
            }

            else -> throw MorphRuntimeError(expr.location, "Unknown method: ${expr.method}")
        }
    }

    private fun evalBoolean(expr: Expr, env: Environment): Boolean {
        val value = evalExpr(expr, env)
        if (value is BoolValue) {
            return value.v
        }
        throw MorphRuntimeError(expr.location, "Expected Bool value")
    }

    private fun literalValue(expr: morph.ast.LiteralExpr): MorphValue = when (val literal = expr.value) {
        is morph.ast.IntLiteral -> IntValue(literal.value)
        is morph.ast.FloatLiteral -> FloatValue(literal.value)
        is morph.ast.StringLiteral -> StringValue(literal.value)
        is morph.ast.BoolLiteral -> BoolValue(literal.value)
        is morph.ast.UuidLiteral -> UuidValue(literal.value)
    }

    fun renderValue(value: MorphValue): String = when (value) {
        is IntValue -> value.v.toString()
        is FloatValue -> value.v.toString()
        is StringValue -> "\"${value.v}\""
        is BoolValue -> value.v.toString()
        is UuidValue -> "\"${value.v}\""
        is ProductValue -> {
            when (val decl = checked.types[value.typeName]) {
                is PrimitiveType -> "${value.typeName}(${renderValue(value.fields.getValue(decl.name))})"
                is NamedType -> "${value.typeName}(${decl.fields.joinToString(", ") { "${it.name}: ${renderValue(value.fields.getValue(it.name))}" }})"
                is ProductType -> "${value.typeName}(${decl.fields.joinToString(", ") { renderValue(value.fields.getValue(it)) }})"
                is QueryType -> "${value.typeName}(${decl.fields.joinToString(", ") { renderValue(value.fields.getValue(it)) }})"
                else -> "${value.typeName}(...)"
            }
        }

        is VariantValue -> if (value.fields.isEmpty()) value.variantName else "${value.variantName}(${value.fields.joinToString(", ") { renderValue(it) }})"
        is ListValue -> "[${value.items.joinToString(", ") { renderValue(it) }}]"
        is SetValue -> "{${value.items.joinToString(", ") { renderValue(it) }}}"
        is MapValue -> "{${value.entries.entries.joinToString(", ") { "${renderValue(it.key)}: ${renderValue(it.value)}" }}}"
        UnitValue -> "Unit"
    }
}
