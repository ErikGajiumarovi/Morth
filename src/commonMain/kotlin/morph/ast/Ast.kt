package morph.ast

data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
) {
    fun render(): String {
        val normalized = file.trimEnd('/', '\\')
        val display = normalized.substringAfterLast('/').substringAfterLast('\\').ifEmpty { file }
        return "$display:$line"
    }
}

sealed class MorphFile {
    abstract val location: SourceLocation
}

data class TypesFile(
    val decls: List<TypeDecl>,
    override val location: SourceLocation,
) : MorphFile()

data class FunsFile(
    val sigs: List<FunSig>,
    override val location: SourceLocation,
) : MorphFile()

data class ImplFile(
    val impls: List<ImplDecl>,
    override val location: SourceLocation,
) : MorphFile()

data class LinkFile(
    val bindings: List<LinkBinding>,
    override val location: SourceLocation,
) : MorphFile()

typealias UnsafeBlock = Block

data class EntryFile(
    val body: UnsafeBlock,
    override val location: SourceLocation,
) : MorphFile()

data class ParsedSourceFile(
    val path: String,
    val file: MorphFile,
)

data class MorphProgram(
    val files: List<ParsedSourceFile>,
) {
    val typesFiles: List<TypesFile>
        get() = files.mapNotNull { it.file as? TypesFile }

    val funsFiles: List<FunsFile>
        get() = files.mapNotNull { it.file as? FunsFile }

    val implFiles: List<ImplFile>
        get() = files.mapNotNull { it.file as? ImplFile }

    val linkFiles: List<LinkFile>
        get() = files.mapNotNull { it.file as? LinkFile }

    val entryFiles: List<EntryFile>
        get() = files.mapNotNull { it.file as? EntryFile }
}

sealed class TypeDecl {
    abstract val name: String
    abstract val location: SourceLocation
}

sealed class BaseType {
    abstract val displayName: String

    data object IntBase : BaseType() {
        override val displayName: String = "Int"
    }

    data object StringBase : BaseType() {
        override val displayName: String = "String"
    }

    data object FloatBase : BaseType() {
        override val displayName: String = "Float"
    }

    data object BoolBase : BaseType() {
        override val displayName: String = "Bool"
    }

    data object UuidBase : BaseType() {
        override val displayName: String = "UUID"
    }

    data class NamedBase(val typeName: String) : BaseType() {
        override val displayName: String = typeName
    }
}

data class Constraint(
    val expr: Expr,
    val source: String,
    val location: SourceLocation,
)

data class PrimitiveType(
    override val name: String,
    val base: BaseType,
    val constraint: Constraint?,
    override val location: SourceLocation,
) : TypeDecl()

data class NamedType(
    override val name: String,
    val fields: List<VoField>,
    val constraint: Constraint?,
    override val location: SourceLocation,
) : TypeDecl()

data class ProductType(
    override val name: String,
    val fields: List<String>,
    override val location: SourceLocation,
) : TypeDecl()

data class SumType(
    override val name: String,
    val variants: List<Variant>,
    override val location: SourceLocation,
) : TypeDecl()

data class QueryType(
    override val name: String,
    val fields: List<String>,
    override val location: SourceLocation,
) : TypeDecl()

data class ResultType(
    override val name: String,
    val variants: List<Variant>,
    override val location: SourceLocation,
) : TypeDecl()

data class TypeClassDecl(
    override val name: String,
    val param: String,
    val sigs: List<FunSig>,
    override val location: SourceLocation,
) : TypeDecl()

data class TypeClassImpl(
    override val name: String,
    val forType: String,
    val impls: List<ImplDecl>,
    override val location: SourceLocation,
) : TypeDecl()

data class Variant(
    val name: String,
    val fields: List<String>,
    val location: SourceLocation,
)

data class VoField(
    val name: String,
    val typeName: String,
    val location: SourceLocation,
)

data class FunSig(
    val name: String,
    val inputType: String,
    val outputType: String,
    val location: SourceLocation,
)

data class ImplDecl(
    val name: String,
    val deps: List<String>,
    val body: Block,
    val location: SourceLocation,
)

data class LinkBinding(
    val funName: String,
    val implName: String,
    val injected: List<String>,
    val location: SourceLocation,
)

sealed class Expr {
    abstract val location: SourceLocation
}

data class FunCallExpr(
    val name: String,
    val arg: Expr,
    override val location: SourceLocation,
) : Expr()

data class ConstructorExpr(
    val typeName: String,
    val args: List<Expr>,
    override val location: SourceLocation,
) : Expr()

data class UnresolvedCallExpr(
    val name: String,
    val args: List<Expr>,
    override val location: SourceLocation,
) : Expr()

data class FieldAccessExpr(
    val target: Expr,
    val field: String,
    override val location: SourceLocation,
) : Expr()

data class MethodCallExpr(
    val receiver: Expr,
    val method: String,
    val args: List<Expr>,
    override val location: SourceLocation,
) : Expr()

data class ValRefExpr(
    val name: String,
    override val location: SourceLocation,
) : Expr()

data class LiteralExpr(
    val value: MorphLiteral,
    override val location: SourceLocation,
) : Expr()

data class MatchExpr(
    val subject: Expr,
    val arms: List<MatchArm>,
    override val location: SourceLocation,
) : Expr()

data class CastExpr(
    val expr: Expr,
    val targetType: String,
    override val location: SourceLocation,
) : Expr()

data class ItExpr(
    val path: List<String>,
    val op: CmpOp,
    val rhs: Expr,
    override val location: SourceLocation,
) : Expr()

data class ComparisonExpr(
    val left: Expr,
    val op: CmpOp,
    val rhs: Expr,
    override val location: SourceLocation,
) : Expr()

data class LogicalExpr(
    val left: Expr,
    val op: LogicalOp,
    val right: Expr,
    override val location: SourceLocation,
) : Expr()

data class BlockExpr(
    val block: Block,
    override val location: SourceLocation,
) : Expr()

data class MatchArm(
    val pattern: Pattern,
    val body: Expr,
    val location: SourceLocation,
)

sealed class Pattern {
    abstract val location: SourceLocation
}

data class VariantPattern(
    val typeName: String,
    val bindings: List<String>,
    override val location: SourceLocation,
) : Pattern()

data class WildcardPattern(
    override val location: SourceLocation,
) : Pattern()

data class Block(
    val vals: List<ValDecl>,
    val result: Expr,
    val isUnsafe: Boolean,
    val location: SourceLocation,
)

data class ValDecl(
    val name: String,
    val expr: Expr,
    val location: SourceLocation,
)

enum class CmpOp {
    GT,
    LT,
    GTE,
    LTE,
    EQEQ,
    NEQ,
}

enum class LogicalOp {
    AND,
    OR,
}

sealed class MorphLiteral

data class IntLiteral(val value: Long) : MorphLiteral()
data class FloatLiteral(val value: Double) : MorphLiteral()
data class StringLiteral(val value: String) : MorphLiteral()
data class BoolLiteral(val value: Boolean) : MorphLiteral()
data class UuidLiteral(val value: String) : MorphLiteral()

open class MorphException(
    val phase: String,
    val errorLocation: SourceLocation,
    message: String,
) : RuntimeException(message)

class MorphParseError(location: SourceLocation, message: String) :
    MorphException("Parse", location, message)

class MorphTypeCheckError(location: SourceLocation, message: String) :
    MorphException("TypeCheck", location, message)

class MorphLinkError(location: SourceLocation, message: String) :
    MorphException("Link", location, message)

class MorphRuntimeError(location: SourceLocation, message: String) :
    MorphException("Runtime", location, message)

fun Expr.itPath(): List<String>? = when (this) {
    is ValRefExpr -> if (name == "it") emptyList() else null
    is FieldAccessExpr -> target.itPath()?.plus(field)
    else -> null
}

fun BaseType.asTypeName(): String = when (this) {
    BaseType.IntBase -> "Int"
    BaseType.StringBase -> "String"
    BaseType.FloatBase -> "Float"
    BaseType.BoolBase -> "Bool"
    BaseType.UuidBase -> "UUID"
    is BaseType.NamedBase -> typeName
}
