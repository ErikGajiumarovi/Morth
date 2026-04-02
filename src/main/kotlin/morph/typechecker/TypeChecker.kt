package morph.typechecker

import morph.ast.BaseType
import morph.ast.Block
import morph.ast.BlockExpr
import morph.ast.BoolLiteral
import morph.ast.CastExpr
import morph.ast.CmpOp
import morph.ast.ComparisonExpr
import morph.ast.Constraint
import morph.ast.ConstructorExpr
import morph.ast.Expr
import morph.ast.FieldAccessExpr
import morph.ast.FloatLiteral
import morph.ast.FunCallExpr
import morph.ast.FunSig
import morph.ast.ImplDecl
import morph.ast.IntLiteral
import morph.ast.ItExpr
import morph.ast.LinkBinding
import morph.ast.LiteralExpr
import morph.ast.LogicalExpr
import morph.ast.LogicalOp
import morph.ast.MatchArm
import morph.ast.MatchExpr
import morph.ast.MethodCallExpr
import morph.ast.MorphLinkError
import morph.ast.MorphProgram
import morph.ast.MorphRuntimeError
import morph.ast.MorphTypeCheckError
import morph.ast.NamedType
import morph.ast.Pattern
import morph.ast.PrimitiveType
import morph.ast.ProductType
import morph.ast.QueryType
import morph.ast.ResultType
import morph.ast.SourceLocation
import morph.ast.StringLiteral
import morph.ast.SumType
import morph.ast.TypeClassImpl
import morph.ast.TypeDecl
import morph.ast.UnresolvedCallExpr
import morph.ast.UuidLiteral
import morph.ast.ValRefExpr
import morph.ast.Variant
import morph.ast.VariantPattern
import morph.ast.WildcardPattern
import morph.ast.asTypeName
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

data class VariantInfo(
    val ownerType: String,
    val variant: Variant,
)

data class CheckedProgram(
    val program: MorphProgram,
    val types: Map<String, TypeDecl>,
    val funs: Map<String, FunSig>,
    val resolvedImpls: Map<String, ImplDecl>,
    val links: Map<String, LinkBinding>,
    val variants: Map<String, VariantInfo>,
)

object MorphSemantics {
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun normalizePrimitiveArgument(base: BaseType, value: MorphValue, location: SourceLocation): MorphValue {
        return when (base) {
            BaseType.IntBase -> when (value) {
                is IntValue -> value
                else -> throw MorphRuntimeError(location, "Expected Int value")
            }

            BaseType.FloatBase -> when (value) {
                is FloatValue -> value
                is IntValue -> FloatValue(value.v.toDouble())
                else -> throw MorphRuntimeError(location, "Expected Float value")
            }

            BaseType.StringBase -> when (value) {
                is StringValue -> value
                else -> throw MorphRuntimeError(location, "Expected String value")
            }

            BaseType.BoolBase -> when (value) {
                is BoolValue -> value
                else -> throw MorphRuntimeError(location, "Expected Bool value")
            }

            BaseType.UuidBase -> when (value) {
                is UuidValue -> value
                is StringValue -> {
                    if (!uuidRegex.matches(value.v)) {
                        throw MorphRuntimeError(location, "Invalid UUID literal: ${value.v}")
                    }
                    UuidValue(value.v)
                }

                else -> throw MorphRuntimeError(location, "Expected UUID-compatible value")
            }

            is BaseType.NamedBase -> value
        }
    }

    fun evaluateConstraint(
        constraint: Constraint,
        currentValue: MorphValue,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): Boolean = asBoolean(evaluateConstraintExpr(constraint.expr, currentValue, types, location), location)

    fun evaluateConstraintExpr(
        expr: Expr,
        currentValue: MorphValue,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): MorphValue {
        return when (expr) {
            is LiteralExpr -> literalValue(expr)
            is ValRefExpr -> {
                if (expr.name != "it") {
                    throw MorphRuntimeError(expr.location, "Only 'it' is available in constraints")
                }
                unwrapPrimitiveIfNeeded(currentValue, types)
            }

            is FieldAccessExpr -> accessField(evaluateConstraintExpr(expr.target, currentValue, types, location), expr.field, types, expr.location)
            is MethodCallExpr -> {
                val receiver = unwrapPrimitiveIfNeeded(evaluateConstraintExpr(expr.receiver, currentValue, types, location), types)
                when (expr.method) {
                    "contains" -> {
                        val arg = expr.args.singleOrNull()
                            ?: throw MorphRuntimeError(expr.location, "contains() expects exactly one argument")
                        val argument = unwrapPrimitiveIfNeeded(evaluateConstraintExpr(arg, currentValue, types, location), types)
                        if (receiver !is StringValue || argument !is StringValue) {
                            throw MorphRuntimeError(expr.location, "contains() expects String receiver and argument")
                        }
                        BoolValue(receiver.v.contains(argument.v))
                    }

                    else -> throw MorphRuntimeError(expr.location, "Unsupported constraint method: ${expr.method}")
                }
            }

            is ItExpr -> {
                val lhs = projectConstraintValue(currentValue, expr.path, types, expr.location)
                val rhs = evaluateConstraintExpr(expr.rhs, currentValue, types, location)
                compareValues(lhs, rhs, expr.op, expr.location, types)
            }

            is ComparisonExpr -> {
                val lhs = evaluateConstraintExpr(expr.left, currentValue, types, location)
                val rhs = evaluateConstraintExpr(expr.rhs, currentValue, types, location)
                compareValues(lhs, rhs, expr.op, expr.location, types)
            }

            is LogicalExpr -> {
                val left = asBoolean(evaluateConstraintExpr(expr.left, currentValue, types, location), expr.location)
                when (expr.op) {
                    LogicalOp.AND -> if (!left) {
                        BoolValue(false)
                    } else {
                        BoolValue(asBoolean(evaluateConstraintExpr(expr.right, currentValue, types, location), expr.location))
                    }

                    LogicalOp.OR -> if (left) {
                        BoolValue(true)
                    } else {
                        BoolValue(asBoolean(evaluateConstraintExpr(expr.right, currentValue, types, location), expr.location))
                    }
                }
            }

            else -> throw MorphRuntimeError(location, "Unsupported constraint expression")
        }
    }

    fun accessField(target: MorphValue, field: String, types: Map<String, TypeDecl>, location: SourceLocation): MorphValue {
        val effectiveTarget = unwrapPrimitiveIfNeeded(target, types)
        return when {
            effectiveTarget is ProductValue -> effectiveTarget.fields[field]
                ?: throw MorphRuntimeError(location, "Unknown field '$field' on ${effectiveTarget.typeName}")

            effectiveTarget is StringValue && field == "length" -> IntValue(effectiveTarget.v.length.toLong())
            else -> throw MorphRuntimeError(location, "Cannot access field '$field' on value")
        }
    }

    fun projectConstraintValue(
        value: MorphValue,
        path: List<String>,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): MorphValue {
        var current = unwrapPrimitiveIfNeeded(value, types)
        if (path.isEmpty()) {
            return current
        }
        for (segment in path) {
            current = accessField(current, segment, types, location)
            current = unwrapPrimitiveIfNeeded(current, types)
        }
        return current
    }

    fun compareValues(
        left: MorphValue,
        right: MorphValue,
        op: CmpOp,
        location: SourceLocation,
        types: Map<String, TypeDecl>,
    ): BoolValue {
        val lhs = unwrapPrimitiveIfNeeded(left, types)
        val rhs = unwrapPrimitiveIfNeeded(right, types)
        return BoolValue(
            when {
                lhs is IntValue && rhs is IntValue -> compareOrdered(lhs.v, rhs.v, op)
                lhs is FloatValue && rhs is FloatValue -> compareOrdered(lhs.v, rhs.v, op)
                lhs is IntValue && rhs is FloatValue -> compareOrdered(lhs.v.toDouble(), rhs.v, op)
                lhs is FloatValue && rhs is IntValue -> compareOrdered(lhs.v, rhs.v.toDouble(), op)
                lhs is StringValue && rhs is StringValue -> compareOrdered(lhs.v, rhs.v, op)
                lhs is BoolValue && rhs is BoolValue -> compareBoolean(lhs.v, rhs.v, op, location)
                lhs is UuidValue && rhs is UuidValue -> compareOrdered(lhs.v, rhs.v, op)
                else -> throw MorphRuntimeError(location, "Unsupported comparison")
            },
        )
    }

    fun unwrapPrimitiveIfNeeded(value: MorphValue, types: Map<String, TypeDecl>): MorphValue {
        return if (value is ProductValue) {
            when (val decl = types[value.typeName]) {
                is PrimitiveType -> value.fields[decl.name] ?: value
                else -> value
            }
        } else {
            value
        }
    }

    private fun literalValue(expr: LiteralExpr): MorphValue = when (val literal = expr.value) {
        is IntLiteral -> IntValue(literal.value)
        is FloatLiteral -> FloatValue(literal.value)
        is StringLiteral -> StringValue(literal.value)
        is BoolLiteral -> BoolValue(literal.value)
        is UuidLiteral -> UuidValue(literal.value)
    }

    private fun <T : Comparable<T>> compareOrdered(left: T, right: T, op: CmpOp): Boolean {
        return when (op) {
            CmpOp.GT -> left > right
            CmpOp.LT -> left < right
            CmpOp.GTE -> left >= right
            CmpOp.LTE -> left <= right
            CmpOp.EQEQ -> left == right
            CmpOp.NEQ -> left != right
        }
    }

    private fun compareBoolean(left: Boolean, right: Boolean, op: CmpOp, location: SourceLocation): Boolean {
        return when (op) {
            CmpOp.EQEQ -> left == right
            CmpOp.NEQ -> left != right
            else -> throw MorphRuntimeError(location, "Boolean values only support == and !=")
        }
    }

    private fun asBoolean(value: MorphValue, location: SourceLocation): Boolean {
        if (value is BoolValue) {
            return value.v
        }
        throw MorphRuntimeError(location, "Constraint expression did not evaluate to Bool")
    }
}

class TypeChecker {
    private val builtins = setOf("Int", "String", "Float", "Bool", "UUID")

    fun check(program: MorphProgram): CheckedProgram {
        val typeRegistry = buildTypeRegistry(program)
        val variants = buildVariantRegistry(typeRegistry)
        val funs = validateFunctionSignatures(program, typeRegistry)
        val links = validateLinkGraph(program, funs)
        val resolvedImpls = links.mapValues { (_, binding) ->
            findImpl(program, binding.implName) ?: throw MorphLinkError(binding.location, "Unknown impl: ${binding.implName}")
        }

        resolvedImpls.forEach { (funName, impl) ->
            val sig = funs.getValue(funName)
            inferBlock(
                block = impl.body,
                context = TypeContext(
                    values = mapOf("it" to sig.inputType),
                    constValues = emptyMap(),
                    currentFunction = funName,
                ),
                expectedType = sig.outputType,
                typeRegistry = typeRegistry,
                variants = variants,
                funs = funs,
                resolvedImpls = resolvedImpls,
            )
        }

        program.entryFiles.forEach { entry ->
            inferBlock(
                block = entry.body,
                context = TypeContext(emptyMap(), emptyMap(), null),
                expectedType = null,
                typeRegistry = typeRegistry,
                variants = variants,
                funs = funs,
                resolvedImpls = resolvedImpls,
            )
        }

        return CheckedProgram(program, typeRegistry, funs, resolvedImpls, links, variants)
    }

    private fun buildTypeRegistry(program: MorphProgram): Map<String, TypeDecl> {
        val registry = linkedMapOf<String, TypeDecl>()
        for (file in program.typesFiles) {
            for (decl in file.decls) {
                if (decl is TypeClassImpl) {
                    continue
                }
                val previous = registry.putIfAbsent(decl.name, decl)
                if (previous != null) {
                    throw MorphTypeCheckError(decl.location, "Duplicate type name: ${decl.name}")
                }
            }
        }
        return registry
    }

    private fun buildVariantRegistry(types: Map<String, TypeDecl>): Map<String, VariantInfo> {
        val variants = linkedMapOf<String, VariantInfo>()
        for ((typeName, decl) in types) {
            val variantDecls = when (decl) {
                is SumType -> decl.variants
                is ResultType -> decl.variants
                else -> emptyList()
            }
            for (variant in variantDecls) {
                val previous = variants.putIfAbsent(variant.name, VariantInfo(typeName, variant))
                if (previous != null) {
                    throw MorphTypeCheckError(variant.location, "Duplicate variant name: ${variant.name}")
                }
            }
        }
        return variants
    }

    private fun validateFunctionSignatures(
        program: MorphProgram,
        types: Map<String, TypeDecl>,
    ): Map<String, FunSig> {
        val funs = linkedMapOf<String, FunSig>()
        for (file in program.funsFiles) {
            for (sig in file.sigs) {
                val previous = funs.putIfAbsent(sig.name, sig)
                if (previous != null) {
                    throw MorphTypeCheckError(sig.location, "Duplicate function name: ${sig.name}")
                }
                if (!typeExists(sig.inputType, types)) {
                    throw MorphTypeCheckError(sig.location, "Unknown input type: ${sig.inputType}")
                }
                if (!typeExists(sig.outputType, types)) {
                    throw MorphTypeCheckError(sig.location, "Unknown output type: ${sig.outputType}")
                }
            }
        }
        return funs
    }

    private fun validateLinkGraph(
        program: MorphProgram,
        funs: Map<String, FunSig>,
    ): Map<String, LinkBinding> {
        val implsByName = program.implFiles.flatMap { it.impls }.associateBy { it.name }
        val links = linkedMapOf<String, LinkBinding>()

        for (file in program.linkFiles) {
            for (binding in file.bindings) {
                if (binding.funName !in funs) {
                    throw MorphLinkError(binding.location, "Unknown fun in link: ${binding.funName}")
                }
                val previous = links.putIfAbsent(binding.funName, binding)
                if (previous != null) {
                    throw MorphLinkError(binding.location, "Duplicate impl binding for fun: ${binding.funName}")
                }
                val impl = implsByName[binding.implName]
                    ?: throw MorphLinkError(binding.location, "Unknown impl: ${binding.implName}")
                if (binding.injected.isNotEmpty() && impl.deps.isNotEmpty() && binding.injected != impl.deps) {
                    throw MorphLinkError(binding.location, "Dependency mismatch for ${binding.implName}")
                }
            }
        }

        for (sig in funs.values) {
            if (sig.name !in links) {
                throw MorphLinkError(sig.location, "Missing impl for fun: ${sig.name}")
            }
        }

        val adjacency = linkedMapOf<String, List<String>>()
        for ((funName, binding) in links) {
            val impl = implsByName.getValue(binding.implName)
            val deps = if (binding.injected.isNotEmpty()) binding.injected else impl.deps
            deps.forEach { dep ->
                if (dep !in funs) {
                    throw MorphLinkError(binding.location, "Unknown dependency: $dep")
                }
                if (dep !in links) {
                    throw MorphLinkError(binding.location, "Unbound dependency: $dep")
                }
            }
            adjacency[funName] = deps
        }

        val state = mutableMapOf<String, VisitState>()
        val path = mutableListOf<String>()
        for (node in adjacency.keys) {
            detectCycle(node, adjacency, state, path)
        }

        return links
    }

    private fun detectCycle(
        node: String,
        adjacency: Map<String, List<String>>,
        state: MutableMap<String, VisitState>,
        path: MutableList<String>,
    ) {
        when (state[node]) {
            VisitState.DONE -> return
            VisitState.VISITING -> {
                val cycleStart = path.indexOf(node)
                val cycle = (path.drop(cycleStart) + node).joinToString(" -> ")
                throw MorphLinkError(SourceLocation("<link>", 1, 1), "Cycle detected: $cycle")
            }

            null -> Unit
        }

        state[node] = VisitState.VISITING
        path += node
        for (next in adjacency[node].orEmpty()) {
            detectCycle(next, adjacency, state, path)
        }
        path.removeLast()
        state[node] = VisitState.DONE
    }

    private fun findImpl(program: MorphProgram, name: String): ImplDecl? =
        program.implFiles.asSequence().flatMap { it.impls.asSequence() }.firstOrNull { it.name == name }

    private fun inferBlock(
        block: Block,
        context: TypeContext,
        expectedType: String?,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val values = context.values.toMutableMap()
        val constValues = context.constValues.toMutableMap()
        for (decl in block.vals) {
            val inferred = inferExpr(
                decl.expr,
                TypeContext(values, constValues, context.currentFunction),
                typeRegistry,
                variants,
                funs,
                resolvedImpls,
            )
            val typeName = inferred.typeName
                ?: throw MorphTypeCheckError(decl.location, "Could not infer type for '${decl.name}'")
            values[decl.name] = typeName
            constValues[decl.name] = inferred.constValue
        }
        val result = inferExpr(
            block.result,
            TypeContext(values, constValues, context.currentFunction),
            typeRegistry,
            variants,
            funs,
            resolvedImpls,
        )
        if (expectedType != null && result.typeName != null && result.typeName != expectedType) {
            throw MorphTypeCheckError(block.result.location, "Expected $expectedType but found ${result.typeName}")
        }
        return result
    }

    private fun inferExpr(
        expr: Expr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        return when (expr) {
            is LiteralExpr -> inferLiteral(expr)
            is ValRefExpr -> {
                val typeName = context.values[expr.name]
                    ?: throw MorphTypeCheckError(expr.location, "Unknown binding: ${expr.name}")
                Inference(typeName, context.constValues[expr.name], null)
            }

            is FieldAccessExpr -> inferFieldAccess(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is MethodCallExpr -> inferMethodCall(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is ItExpr -> inferItExpr(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is ComparisonExpr -> inferComparisonExpr(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is LogicalExpr -> inferLogicalExpr(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is MatchExpr -> inferMatchExpr(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is CastExpr -> inferCastExpr(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is BlockExpr -> inferBlock(expr.block, context, null, typeRegistry, variants, funs, resolvedImpls)
            is UnresolvedCallExpr -> inferUnresolvedCall(expr, context, typeRegistry, variants, funs, resolvedImpls)
            is ConstructorExpr -> inferConstructor(expr.typeName, expr.args, expr.location, context, typeRegistry, variants, funs, resolvedImpls)
            is FunCallExpr -> {
                val sig = funs[expr.name] ?: throw MorphTypeCheckError(expr.location, "Unknown function: ${expr.name}")
                val arg = inferExpr(expr.arg, context, typeRegistry, variants, funs, resolvedImpls)
                validateAssignable(arg.typeName, sig.inputType, expr.location)
                Inference(sig.outputType, null, null)
            }
        }
    }

    private fun inferLiteral(expr: LiteralExpr): Inference = when (val literal = expr.value) {
        is IntLiteral -> Inference("Int", IntValue(literal.value), null)
        is FloatLiteral -> Inference("Float", FloatValue(literal.value), null)
        is StringLiteral -> Inference("String", StringValue(literal.value), null)
        is BoolLiteral -> Inference("Bool", BoolValue(literal.value), null)
        is UuidLiteral -> Inference("UUID", UuidValue(literal.value), null)
    }

    private fun inferFieldAccess(
        expr: FieldAccessExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val target = inferExpr(expr.target, context, typeRegistry, variants, funs, resolvedImpls)
        val targetType = target.typeName ?: throw MorphTypeCheckError(expr.location, "Unknown target type")
        val fieldType = resolveFieldType(targetType, expr.field, typeRegistry, expr.location)
        val constValue = target.constValue?.let {
            try {
                MorphSemantics.accessField(it, expr.field, typeRegistry, expr.location)
            } catch (_: MorphRuntimeError) {
                null
            }
        }
        return Inference(fieldType, constValue, null)
    }

    private fun inferMethodCall(
        expr: MethodCallExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val receiver = inferExpr(expr.receiver, context, typeRegistry, variants, funs, resolvedImpls)
        val effectiveType = comparableTypeName(receiver.typeName, typeRegistry)
        return when (expr.method) {
            "contains" -> {
                if (effectiveType != "String") {
                    throw MorphTypeCheckError(expr.location, "contains() requires a String receiver")
                }
                val argExpr = expr.args.singleOrNull()
                    ?: throw MorphTypeCheckError(expr.location, "contains() expects exactly one argument")
                val arg = inferExpr(argExpr, context, typeRegistry, variants, funs, resolvedImpls)
                validateAssignable(arg.typeName, "String", argExpr.location)
                val constValue = if (receiver.constValue is StringValue && arg.constValue is StringValue) {
                    BoolValue(receiver.constValue.v.contains(arg.constValue.v))
                } else {
                    null
                }
                Inference("Bool", constValue, null)
            }

            else -> throw MorphTypeCheckError(expr.location, "Unknown method: ${expr.method}")
        }
    }

    private fun inferItExpr(
        expr: ItExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val itType = context.values["it"]
            ?: throw MorphTypeCheckError(expr.location, "'it' is not available here")
        val lhsType = resolvePathType(itType, expr.path, typeRegistry, expr.location)
        val rhs = inferExpr(expr.rhs, context, typeRegistry, variants, funs, resolvedImpls)
        validateComparison(lhsType, rhs.typeName, expr.location, typeRegistry)
        return Inference("Bool", null, null)
    }

    private fun inferComparisonExpr(
        expr: ComparisonExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val left = inferExpr(expr.left, context, typeRegistry, variants, funs, resolvedImpls)
        val right = inferExpr(expr.rhs, context, typeRegistry, variants, funs, resolvedImpls)
        validateComparison(left.typeName, right.typeName, expr.location, typeRegistry)
        val constValue = if (left.constValue != null && right.constValue != null) {
            MorphSemantics.compareValues(left.constValue, right.constValue, expr.op, expr.location, typeRegistry)
        } else {
            null
        }
        return Inference("Bool", constValue, null)
    }

    private fun inferLogicalExpr(
        expr: LogicalExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val left = inferExpr(expr.left, context, typeRegistry, variants, funs, resolvedImpls)
        val right = inferExpr(expr.right, context, typeRegistry, variants, funs, resolvedImpls)
        validateAssignable(left.typeName, "Bool", expr.left.location)
        validateAssignable(right.typeName, "Bool", expr.right.location)
        val constValue = if (left.constValue is BoolValue && right.constValue is BoolValue) {
            BoolValue(
                when (expr.op) {
                    LogicalOp.AND -> left.constValue.v && right.constValue.v
                    LogicalOp.OR -> left.constValue.v || right.constValue.v
                },
            )
        } else {
            null
        }
        return Inference("Bool", constValue, null)
    }

    private fun inferMatchExpr(
        expr: MatchExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val subject = inferExpr(expr.subject, context, typeRegistry, variants, funs, resolvedImpls)
        val subjectType = subject.typeName ?: throw MorphTypeCheckError(expr.subject.location, "Could not infer match subject")
        val seenVariants = linkedSetOf<String>()
        var wildcard = false
        var armType: String? = null

        for (arm in expr.arms) {
            val bindings = bindPattern(arm, subjectType, typeRegistry, variants)
            val armValues = context.values.toMutableMap()
            val armConsts = context.constValues.toMutableMap()
            for ((name, bindingType) in bindings) {
                armValues[name] = bindingType
                armConsts[name] = null
            }
            when (val pattern = arm.pattern) {
                is VariantPattern -> if (variants[pattern.typeName]?.ownerType == subjectType) {
                    seenVariants += pattern.typeName
                }

                is WildcardPattern -> wildcard = true
            }

            val inferred = inferExpr(
                arm.body,
                TypeContext(armValues, armConsts, context.currentFunction),
                typeRegistry,
                variants,
                funs,
                resolvedImpls,
            )
            if (armType == null) {
                armType = inferred.typeName
            } else if (inferred.typeName != null && inferred.typeName != armType) {
                throw MorphTypeCheckError(arm.location, "Match arms must return the same type")
            }
        }

        val decl = typeRegistry[subjectType]
        if (decl is SumType || decl is ResultType) {
            val declaredVariants = when (decl) {
                is SumType -> decl.variants.map { it.name }
                is ResultType -> decl.variants.map { it.name }
            }
            val missing = declaredVariants.filterNot { it in seenVariants }
            if (missing.isNotEmpty() && !wildcard) {
                throw MorphTypeCheckError(expr.location, "Non-exhaustive match on type $subjectType: missing variants [${missing.joinToString(", ")}]")
            }
        }

        return Inference(armType, null, null)
    }

    private fun inferCastExpr(
        expr: CastExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val source = inferExpr(expr.expr, context, typeRegistry, variants, funs, resolvedImpls)
        val target = typeRegistry[expr.targetType]
            ?: throw MorphTypeCheckError(expr.location, "Unknown cast target type: ${expr.targetType}")
        if (target !is QueryType) {
            throw MorphTypeCheckError(expr.location, "Cast target must be a Query type")
        }
        val variant = source.variantInfo
            ?: throw MorphTypeCheckError(expr.location, "Cast source must be a concrete Result variant")
        val owner = typeRegistry[variant.ownerType]
        if (owner !is ResultType) {
            throw MorphTypeCheckError(expr.location, "Cast source must originate from a Result variant")
        }
        val missing = variant.variant.fields.filterNot { it in target.fields }
        if (missing.isNotEmpty()) {
            throw MorphTypeCheckError(
                expr.location,
                "Cannot cast ${variant.variant.name}(${variant.variant.fields.joinToString(", ")}) to ${expr.targetType}: missing type ${missing.first()}",
            )
        }
        return Inference(expr.targetType, null, null)
    }

    private fun inferUnresolvedCall(
        expr: UnresolvedCallExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        return when {
            expr.name in funs -> {
                if (expr.args.size != 1) {
                    throw MorphTypeCheckError(expr.location, "Function calls require exactly one argument")
                }
                val sig = funs.getValue(expr.name)
                val arg = inferExpr(expr.args.single(), context, typeRegistry, variants, funs, resolvedImpls)
                validateAssignable(arg.typeName, sig.inputType, expr.location)
                Inference(sig.outputType, null, null)
            }

            expr.name in typeRegistry -> inferConstructor(expr.name, expr.args, expr.location, context, typeRegistry, variants, funs, resolvedImpls)
            expr.name in variants -> inferVariantConstructor(expr, context, typeRegistry, variants, funs, resolvedImpls)
            else -> throw MorphTypeCheckError(expr.location, "Unknown symbol: ${expr.name}")
        }
    }

    private fun inferVariantConstructor(
        expr: UnresolvedCallExpr,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val info = variants.getValue(expr.name)
        if (expr.args.size != info.variant.fields.size) {
            throw MorphTypeCheckError(expr.location, "Variant ${expr.name} expects ${info.variant.fields.size} argument(s)")
        }
        val values = mutableListOf<MorphValue>()
        expr.args.zip(info.variant.fields).forEach { (argExpr, expectedType) ->
            val arg = inferExpr(argExpr, context, typeRegistry, variants, funs, resolvedImpls)
            validateAssignable(arg.typeName, expectedType, argExpr.location)
            if (arg.constValue != null) {
                values += arg.constValue
            }
        }
        val constValue = if (values.size == expr.args.size) {
            VariantValue(info.ownerType, info.variant.name, values)
        } else {
            null
        }
        return Inference(info.ownerType, constValue, info)
    }

    private fun inferConstructor(
        typeName: String,
        args: List<Expr>,
        location: SourceLocation,
        context: TypeContext,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
        funs: Map<String, FunSig>,
        resolvedImpls: Map<String, ImplDecl>,
    ): Inference {
        val decl = typeRegistry[typeName]
            ?: throw MorphTypeCheckError(location, "Unknown constructor type: $typeName")
        return when (decl) {
            is PrimitiveType -> {
                if (args.size != 1) {
                    throw MorphTypeCheckError(location, "$typeName expects exactly one argument")
                }
                val arg = inferExpr(args.single(), context, typeRegistry, variants, funs, resolvedImpls)
                validatePrimitiveArgument(arg.typeName, decl.base, location)
                val constValue = arg.constValue?.let {
                    val normalized = MorphSemantics.normalizePrimitiveArgument(decl.base, it, location)
                    ProductValue(typeName, mapOf(typeName to normalized))
                }
                validateConstraintIfStatic(typeName, decl.constraint, constValue, location, typeRegistry)
                Inference(typeName, constValue, null)
            }

            is NamedType -> {
                if (args.size != decl.fields.size) {
                    throw MorphTypeCheckError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                val constFields = linkedMapOf<String, MorphValue>()
                decl.fields.zip(args).forEach { (field, argExpr) ->
                    val arg = inferExpr(argExpr, context, typeRegistry, variants, funs, resolvedImpls)
                    validateAssignable(arg.typeName, field.typeName, argExpr.location)
                    if (arg.constValue != null) {
                        constFields[field.name] = arg.constValue
                    }
                }
                val constValue = if (constFields.size == args.size) ProductValue(typeName, constFields) else null
                validateConstraintIfStatic(typeName, decl.constraint, constValue, location, typeRegistry)
                Inference(typeName, constValue, null)
            }

            is ProductType -> {
                if (args.size != decl.fields.size) {
                    throw MorphTypeCheckError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                val constFields = linkedMapOf<String, MorphValue>()
                decl.fields.zip(args).forEach { (fieldType, argExpr) ->
                    val arg = inferExpr(argExpr, context, typeRegistry, variants, funs, resolvedImpls)
                    validateAssignable(arg.typeName, fieldType, argExpr.location)
                    if (arg.constValue != null) {
                        constFields[fieldType] = arg.constValue
                    }
                }
                val constValue = if (constFields.size == args.size) ProductValue(typeName, constFields) else null
                Inference(typeName, constValue, null)
            }

            is QueryType -> {
                if (args.size != decl.fields.size) {
                    throw MorphTypeCheckError(location, "$typeName expects ${decl.fields.size} argument(s)")
                }
                val constFields = linkedMapOf<String, MorphValue>()
                decl.fields.zip(args).forEach { (fieldType, argExpr) ->
                    val arg = inferExpr(argExpr, context, typeRegistry, variants, funs, resolvedImpls)
                    validateAssignable(arg.typeName, fieldType, argExpr.location)
                    if (arg.constValue != null) {
                        constFields[fieldType] = arg.constValue
                    }
                }
                val constValue = if (constFields.size == args.size) ProductValue(typeName, constFields) else null
                Inference(typeName, constValue, null)
            }

            else -> throw MorphTypeCheckError(location, "$typeName is not directly constructible")
        }
    }

    private fun validateConstraintIfStatic(
        typeName: String,
        constraint: Constraint?,
        constValue: MorphValue?,
        location: SourceLocation,
        types: Map<String, TypeDecl>,
    ) {
        if (constraint == null || constValue == null) {
            return
        }
        val valid = MorphSemantics.evaluateConstraint(constraint, constValue, types, location)
        if (!valid) {
            throw MorphTypeCheckError(location, "Constraint violated: ${renderValue(constValue, types)} where { ${constraint.source} }")
        }
    }

    private fun bindPattern(
        arm: MatchArm,
        subjectType: String,
        typeRegistry: Map<String, TypeDecl>,
        variants: Map<String, VariantInfo>,
    ): Map<String, String> {
        return when (val pattern = arm.pattern) {
            is WildcardPattern -> emptyMap()
            is VariantPattern -> {
                val variant = variants[pattern.typeName]
                if (variant != null) {
                    if (variant.ownerType != subjectType) {
                        throw MorphTypeCheckError(pattern.location, "Variant ${pattern.typeName} does not belong to $subjectType")
                    }
                    if (pattern.bindings.size != variant.variant.fields.size) {
                        throw MorphTypeCheckError(pattern.location, "Pattern ${pattern.typeName} expects ${variant.variant.fields.size} binding(s)")
                    }
                    pattern.bindings.zip(variant.variant.fields).toMap()
                } else {
                    if (pattern.typeName != subjectType) {
                        throw MorphTypeCheckError(pattern.location, "Pattern ${pattern.typeName} does not match $subjectType")
                    }
                    val fieldTypes = destructureType(subjectType, typeRegistry, pattern.location)
                    if (pattern.bindings.size != fieldTypes.size) {
                        throw MorphTypeCheckError(pattern.location, "Pattern ${pattern.typeName} expects ${fieldTypes.size} binding(s)")
                    }
                    pattern.bindings.zip(fieldTypes).toMap()
                }
            }
        }
    }

    private fun destructureType(
        typeName: String,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): List<String> {
        return when (val decl = types[typeName]) {
            is PrimitiveType -> listOf(decl.base.asTypeName())
            is NamedType -> decl.fields.map { it.typeName }
            is ProductType -> decl.fields
            is QueryType -> decl.fields
            else -> throw MorphTypeCheckError(location, "Type $typeName cannot be pattern-matched positionally")
        }
    }

    private fun resolveFieldType(
        targetType: String,
        field: String,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): String {
        val decl = types[targetType]
        return when (decl) {
            is NamedType -> decl.fields.find { it.name == field }?.typeName
                ?: throw MorphTypeCheckError(location, "Unknown field '$field' on $targetType")

            is ProductType -> decl.fields.find { it == field }
                ?: throw MorphTypeCheckError(location, "Unknown field '$field' on $targetType")

            is QueryType -> decl.fields.find { it == field }
                ?: throw MorphTypeCheckError(location, "Unknown field '$field' on $targetType")

            is PrimitiveType -> {
                if (comparableTypeName(targetType, types) == "String" && field == "length") {
                    "Int"
                } else {
                    throw MorphTypeCheckError(location, "Type $targetType does not expose field '$field'")
                }
            }

            else -> throw MorphTypeCheckError(location, "Type $targetType does not support field access")
        }
    }

    private fun resolvePathType(
        rootType: String,
        path: List<String>,
        types: Map<String, TypeDecl>,
        location: SourceLocation,
    ): String {
        var actualCurrent = rootType
        var comparable = comparableTypeName(rootType, types)
        if (path.isEmpty()) {
            return comparable
        }
        for (segment in path) {
            comparable = if (comparable == "String" && segment == "length") {
                "Int"
            } else {
                resolveFieldType(actualCurrent, segment, types, location)
            }
            actualCurrent = comparable
            comparable = comparableTypeName(comparable, types)
        }
        return comparable
    }

    private fun validatePrimitiveArgument(actualType: String?, base: BaseType, location: SourceLocation) {
        val actual = actualType ?: throw MorphTypeCheckError(location, "Unknown constructor argument type")
        val expected = base.asTypeName()
        if ((expected == "UUID" && actual == "String") || (expected == "Float" && actual == "Int")) {
            return
        }
        if (actual != expected) {
            throw MorphTypeCheckError(location, "Expected $expected but found $actual")
        }
    }

    private fun validateAssignable(actualType: String?, expectedType: String, location: SourceLocation) {
        val actual = actualType ?: throw MorphTypeCheckError(location, "Could not infer type")
        if (actual != expectedType) {
            throw MorphTypeCheckError(location, "Expected $expectedType but found $actual")
        }
    }

    private fun validateComparison(
        leftType: String?,
        rightType: String?,
        location: SourceLocation,
        types: Map<String, TypeDecl>,
    ) {
        val left = comparableTypeName(leftType ?: throw MorphTypeCheckError(location, "Unknown comparison type"), types)
        val right = comparableTypeName(rightType ?: throw MorphTypeCheckError(location, "Unknown comparison type"), types)
        val valid = when {
            left == right -> true
            left == "Float" && right == "Int" -> true
            left == "Int" && right == "Float" -> true
            else -> false
        }
        if (!valid) {
            throw MorphTypeCheckError(location, "Cannot compare $left and $right")
        }
    }

    private fun comparableTypeName(typeName: String?, types: Map<String, TypeDecl>): String {
        val name = typeName ?: return ""
        return when (val decl = types[name]) {
            is PrimitiveType -> decl.base.asTypeName()
            else -> name
        }
    }

    private fun typeExists(name: String, types: Map<String, TypeDecl>, typeParams: Set<String> = emptySet()): Boolean {
        if (name in builtins || name in typeParams || name in types) {
            return true
        }
        val base = name.substringBefore('[', missingDelimiterValue = name)
        if (base == name || !name.endsWith("]")) {
            return false
        }
        val args = splitTypeArgs(name.substringAfter('[').removeSuffix("]"))
        return when (base) {
            "List", "Set" -> args.size == 1 && args.all { typeExists(it, types, typeParams) }
            "Map" -> args.size == 2 && args.all { typeExists(it, types, typeParams) }
            else -> false
        }
    }

    private fun splitTypeArgs(value: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in value) {
            when (ch) {
                '[' -> {
                    depth += 1
                    current.append(ch)
                }

                ']' -> {
                    depth -= 1
                    current.append(ch)
                }

                ',' -> if (depth == 0) {
                    args += current.toString().trim()
                    current.clear()
                } else {
                    current.append(ch)
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            args += current.toString().trim()
        }
        return args
    }

    private fun renderValue(value: MorphValue, types: Map<String, TypeDecl>): String = when (value) {
        is IntValue -> value.v.toString()
        is FloatValue -> value.v.toString()
        is StringValue -> "\"${value.v}\""
        is BoolValue -> value.v.toString()
        is UuidValue -> "\"${value.v}\""
        is ProductValue -> {
            when (val decl = types[value.typeName]) {
                is PrimitiveType -> "${value.typeName}(${renderValue(value.fields.getValue(decl.name), types)})"
                is NamedType -> "${value.typeName}(${decl.fields.joinToString(", ") { "${it.name}: ${renderValue(value.fields.getValue(it.name), types)}" }})"
                is ProductType -> "${value.typeName}(${decl.fields.joinToString(", ") { renderValue(value.fields.getValue(it), types) }})"
                is QueryType -> "${value.typeName}(${decl.fields.joinToString(", ") { renderValue(value.fields.getValue(it), types) }})"
                else -> "${value.typeName}(...)"
            }
        }

        is VariantValue -> if (value.fields.isEmpty()) value.variantName else "${value.variantName}(${value.fields.joinToString(", ") { renderValue(it, types) }})"
        is ListValue -> "[${value.items.joinToString(", ") { renderValue(it, types) }}]"
        is SetValue -> "{${value.items.joinToString(", ") { renderValue(it, types) }}}"
        is MapValue -> "{${value.entries.entries.joinToString(", ") { "${renderValue(it.key, types)}: ${renderValue(it.value, types)}" }}}"
        UnitValue -> "Unit"
    }

    private enum class VisitState {
        VISITING,
        DONE,
    }

    private data class TypeContext(
        val values: Map<String, String>,
        val constValues: Map<String, MorphValue?>,
        val currentFunction: String?,
    )

    private data class Inference(
        val typeName: String?,
        val constValue: MorphValue?,
        val variantInfo: VariantInfo?,
    )
}
