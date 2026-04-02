package morph.parser

import morph.ast.BaseType
import morph.ast.Block
import morph.ast.BlockExpr
import morph.ast.BoolLiteral
import morph.ast.CastExpr
import morph.ast.CmpOp
import morph.ast.ComparisonExpr
import morph.ast.Constraint
import morph.ast.ConstructorExpr
import morph.ast.EntryFile
import morph.ast.Expr
import morph.ast.FieldAccessExpr
import morph.ast.FloatLiteral
import morph.ast.FunSig
import morph.ast.FunsFile
import morph.ast.ImplDecl
import morph.ast.ImplFile
import morph.ast.IntLiteral
import morph.ast.ItExpr
import morph.ast.LinkBinding
import morph.ast.LinkFile
import morph.ast.LiteralExpr
import morph.ast.LogicalExpr
import morph.ast.LogicalOp
import morph.ast.MatchArm
import morph.ast.MatchExpr
import morph.ast.MethodCallExpr
import morph.ast.MorphFile
import morph.ast.MorphParseError
import morph.ast.NamedType
import morph.ast.ParsedSourceFile
import morph.ast.Pattern
import morph.ast.PrimitiveType
import morph.ast.ProductType
import morph.ast.QueryType
import morph.ast.ResultType
import morph.ast.SourceLocation
import morph.ast.StringLiteral
import morph.ast.SumType
import morph.ast.TypeClassDecl
import morph.ast.TypeClassImpl
import morph.ast.TypeDecl
import morph.ast.TypesFile
import morph.ast.UnresolvedCallExpr
import morph.ast.UuidLiteral
import morph.ast.ValDecl
import morph.ast.ValRefExpr
import morph.ast.Variant
import morph.ast.VariantPattern
import morph.ast.VoField
import morph.ast.WildcardPattern
import morph.ast.itPath
import morph.lexer.Token
import morph.lexer.TokenType

class Parser(
    private val tokens: List<Token>,
) {
    private var index = 0

    fun parseFile(): MorphFile {
        val first = peek()
        return when {
            check(TokenType.TYPE) || check(TokenType.TYPECLASS) || isTypeClassImplStart() -> parseTypesFile()
            check(TokenType.FUN) -> parseFunsFile()
            check(TokenType.IMPL) -> parseImplFile()
            check(TokenType.LINK) -> parseLinkFile()
            check(TokenType.ENTRY) -> parseEntryFile()
            else -> throw error(first, "Unable to determine Morph file kind")
        }
    }

    fun parseExpression(): Expr {
        val expr = parseExpr()
        consume(TokenType.EOF, "Expected end of expression")
        return expr
    }

    private fun parseTypesFile(): TypesFile {
        val location = peek().location
        val decls = mutableListOf<TypeDecl>()
        while (!check(TokenType.EOF)) {
            decls += when {
                match(TokenType.TYPE) -> parseTypeDecl(previous().location)
                match(TokenType.TYPECLASS) -> parseTypeClassDecl(previous().location)
                isTypeClassImplStart() -> parseTypeClassImpl()
                else -> throw error(peek(), "Expected type declaration")
            }
        }
        return TypesFile(decls, location)
    }

    private fun parseFunsFile(): FunsFile {
        val location = peek().location
        val sigs = mutableListOf<FunSig>()
        while (!check(TokenType.EOF)) {
            sigs += parseFunSig()
        }
        return FunsFile(sigs, location)
    }

    private fun parseImplFile(): ImplFile {
        val location = peek().location
        val impls = mutableListOf<ImplDecl>()
        while (!check(TokenType.EOF)) {
            impls += parseImplDecl()
        }
        return ImplFile(impls, location)
    }

    private fun parseLinkFile(): LinkFile {
        val start = consume(TokenType.LINK, "Expected 'link'").location
        consume(TokenType.LBRACE, "Expected '{' after 'link'")
        val bindings = mutableListOf<LinkBinding>()
        while (!check(TokenType.RBRACE)) {
            bindings += parseLinkBinding()
        }
        consume(TokenType.RBRACE, "Expected '}' after link bindings")
        consume(TokenType.EOF, "Expected end of file")
        return LinkFile(bindings, start)
    }

    private fun parseEntryFile(): EntryFile {
        val start = consume(TokenType.ENTRY, "Expected 'entry'").location
        consume(TokenType.EQUALS, "Expected '=' after 'entry'")
        val block = parseBlock()
        if (!block.isUnsafe) {
            throw error(peek(), "Entry point must use an unsafe block")
        }
        consume(TokenType.EOF, "Expected end of file")
        return EntryFile(block, start)
    }

    private fun parseTypeDecl(start: SourceLocation): TypeDecl {
        val name = consume(TokenType.UPPER_IDENT, "Expected type name").lexeme
        if (match(TokenType.COLON)) {
            return parseQualifiedTypeDecl(start, name)
        }

        consume(TokenType.EQUALS, "Expected '=' after type name")
        return when {
            check(TokenType.LBRACE) -> parseBracedTypeDecl(start, name)
            check(TokenType.UPPER_IDENT) -> parseSimpleOrSumTypeDecl(start, name)
            else -> throw error(peek(), "Expected type body")
        }
    }

    private fun parseQualifiedTypeDecl(start: SourceLocation, name: String): TypeDecl {
        return when {
            match(TokenType.QUERY) -> {
                consume(TokenType.EQUALS, "Expected '=' after Query")
                val fields = parsePositionalTypeBlock("Query type")
                QueryType(name, fields, start)
            }

            match(TokenType.RESULT) -> {
                consume(TokenType.EQUALS, "Expected '=' after Result")
                ResultType(name, parseVariantSeries(), start)
            }

            else -> throw error(peek(), "Expected Query or Result after ':'")
        }
    }

    private fun parseBracedTypeDecl(start: SourceLocation, name: String): TypeDecl {
        val content = parseBracedTypeContent()
        if (match(TokenType.COLON)) {
            return when {
                match(TokenType.QUERY) -> {
                    val fields = when (content) {
                        is BracedTypeContent.Named -> throw error(peek(), "Query types cannot use named fields")
                        is BracedTypeContent.Positional -> content.types
                    }
                    QueryType(name, fields, start)
                }

                else -> throw error(peek(), "Expected Query after ':'")
            }
        }

        val constraint = if (match(TokenType.WHERE)) parseConstraint(previous().location) else null
        return when (content) {
            is BracedTypeContent.Named -> NamedType(name, content.fields, constraint, start)
            is BracedTypeContent.Positional -> ProductType(name, content.types, start)
        }
    }

    private fun parseSimpleOrSumTypeDecl(start: SourceLocation, name: String): TypeDecl {
        val firstName = consume(TokenType.UPPER_IDENT, "Expected type body").lexeme
        val firstLocation = previous().location
        val hasVariantPayload = check(TokenType.LPAREN)
        val firstVariant = parseVariantAfterName(firstName, firstLocation)
        if (match(TokenType.PIPE)) {
            val variants = mutableListOf(firstVariant)
            do {
                variants += parseVariant()
            } while (match(TokenType.PIPE))
            if (match(TokenType.COLON)) {
                if (!match(TokenType.RESULT)) {
                    throw error(peek(), "Expected Result after ':'")
                }
                return ResultType(name, variants, start)
            }
            return SumType(name, variants, start)
        }

        if (match(TokenType.COLON)) {
            if (!match(TokenType.RESULT)) {
                throw error(peek(), "Expected Result after ':'")
            }
            return ResultType(name, listOf(firstVariant), start)
        }

        if (hasVariantPayload || firstVariant.fields.isNotEmpty()) {
            throw error(peek(), "Expected '|' after variant declaration")
        }
        val constraint = if (match(TokenType.WHERE)) parseConstraint(previous().location) else null
        return PrimitiveType(name, baseTypeFor(firstName), constraint, start)
    }

    private fun parseTypeClassDecl(start: SourceLocation): TypeClassDecl {
        val name = consume(TokenType.UPPER_IDENT, "Expected typeclass name").lexeme
        consume(TokenType.LBRACKET, "Expected '[' after typeclass name")
        val param = consume(TokenType.UPPER_IDENT, "Expected type parameter").lexeme
        consume(TokenType.RBRACKET, "Expected ']' after type parameter")
        consume(TokenType.EQUALS, "Expected '=' after typeclass header")
        consume(TokenType.LBRACE, "Expected '{' after typeclass header")
        val sigs = mutableListOf<FunSig>()
        while (!check(TokenType.RBRACE)) {
            sigs += parseFunSig()
        }
        consume(TokenType.RBRACE, "Expected '}' after typeclass body")
        return TypeClassDecl(name, param, sigs, start)
    }

    private fun parseTypeClassImpl(): TypeClassImpl {
        val start = consume(TokenType.IMPL, "Expected 'impl'").location
        val name = consume(TokenType.UPPER_IDENT, "Expected typeclass name").lexeme
        consume(TokenType.LBRACKET, "Expected '[' after typeclass name")
        val forType = parseTypeRef()
        consume(TokenType.RBRACKET, "Expected ']' after target type")
        consume(TokenType.EQUALS, "Expected '=' after typeclass impl header")
        consume(TokenType.LBRACE, "Expected '{' after typeclass impl header")
        val impls = mutableListOf<ImplDecl>()
        while (!check(TokenType.RBRACE)) {
            impls += parseImplDecl()
        }
        consume(TokenType.RBRACE, "Expected '}' after typeclass impl body")
        return TypeClassImpl(name, forType, impls, start)
    }

    private fun parseFunSig(): FunSig {
        val start = consume(TokenType.FUN, "Expected 'fun'").location
        val name = consume(TokenType.UPPER_IDENT, "Expected function name").lexeme
        consume(TokenType.COLON, "Expected ':' after function name")
        val inputType = parseTypeRef()
        consume(TokenType.ARROW, "Expected '->' in function signature")
        val outputType = parseTypeRef()
        return FunSig(name, inputType, outputType, start)
    }

    private fun parseImplDecl(): ImplDecl {
        val start = consume(TokenType.IMPL, "Expected 'impl'").location
        val name = consume(TokenType.UPPER_IDENT, "Expected implementation name").lexeme
        val deps = if (match(TokenType.LPAREN)) parseUpperIdentList() else emptyList()
        consume(TokenType.EQUALS, "Expected '=' after implementation name")
        val body = parseBlock()
        return ImplDecl(name, deps, body, start)
    }

    private fun parseLinkBinding(): LinkBinding {
        val funName = consume(TokenType.UPPER_IDENT, "Expected function name").lexeme
        val start = previous().location
        consume(TokenType.ARROW, "Expected '->' in link binding")
        val implName = consume(TokenType.UPPER_IDENT, "Expected implementation name").lexeme
        val injected = if (match(TokenType.LPAREN)) parseUpperIdentList() else emptyList()
        return LinkBinding(funName, implName, injected, start)
    }

    private fun parseUpperIdentList(): List<String> {
        val names = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            do {
                names += consume(TokenType.UPPER_IDENT, "Expected uppercase identifier").lexeme
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return names
    }

    private fun parseBlock(): Block {
        val isUnsafe = match(TokenType.UNSAFE)
        val start = if (isUnsafe) previous().location else peek().location
        consume(TokenType.LBRACE, "Expected '{' to start block")
        val vals = mutableListOf<ValDecl>()
        while (match(TokenType.VAL)) {
            vals += parseValDecl(previous().location)
        }
        val result = parseExpr()
        consume(TokenType.RBRACE, "Expected '}' after block")
        return Block(vals, result, isUnsafe, start)
    }

    private fun parseValDecl(start: SourceLocation): ValDecl {
        val name = consume(TokenType.LOWER_IDENT, "Expected binding name").lexeme
        consume(TokenType.EQUALS, "Expected '=' after binding name")
        val expr = parseExpr()
        return ValDecl(name, expr, start)
    }

    private fun parseExpr(): Expr = parseCast()

    private fun parseCast(): Expr {
        var expr = parseOr()
        while (match(TokenType.CAST_ARROW)) {
            val start = previous().location
            expr = CastExpr(expr, parseTypeRef(), start)
        }
        return expr
    }

    private fun parseOr(): Expr {
        var expr = parseAnd()
        while (match(TokenType.OR)) {
            val op = previous().location
            expr = LogicalExpr(expr, LogicalOp.OR, parseAnd(), op)
        }
        return expr
    }

    private fun parseAnd(): Expr {
        var expr = parseComparison()
        while (match(TokenType.AND)) {
            val op = previous().location
            expr = LogicalExpr(expr, LogicalOp.AND, parseComparison(), op)
        }
        return expr
    }

    private fun parseComparison(): Expr {
        var expr = parsePostfix()
        while (true) {
            val op = when {
                match(TokenType.GT) -> CmpOp.GT
                match(TokenType.LT) -> CmpOp.LT
                match(TokenType.GTE) -> CmpOp.GTE
                match(TokenType.LTE) -> CmpOp.LTE
                match(TokenType.EQEQ) -> CmpOp.EQEQ
                match(TokenType.NEQ) -> CmpOp.NEQ
                else -> return expr
            }
            val location = previous().location
            val rhs = parsePostfix()
            expr = expr.itPath()?.let { ItExpr(it, op, rhs, location) }
                ?: ComparisonExpr(expr, op, rhs, location)
        }
    }

    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            if (!match(TokenType.DOT)) {
                return expr
            }
            val fieldToken = consumeAny(
                "Expected field or method name after '.'",
                TokenType.UPPER_IDENT,
                TokenType.LOWER_IDENT,
                TokenType.IT,
            )
            expr = if (match(TokenType.LPAREN)) {
                MethodCallExpr(expr, fieldToken.lexeme, parseArgumentListAfterOpenParen(), fieldToken.location)
            } else {
                FieldAccessExpr(expr, fieldToken.lexeme, fieldToken.location)
            }
        }
    }

    private fun parsePrimary(): Expr {
        return when {
            match(TokenType.MATCH) -> parseMatchExpr(previous().location)
            check(TokenType.UNSAFE) || check(TokenType.LBRACE) -> {
                val block = parseBlock()
                BlockExpr(block, block.location)
            }

            match(TokenType.LPAREN) -> {
                val expr = parseExpr()
                consume(TokenType.RPAREN, "Expected ')' after expression")
                expr
            }

            match(TokenType.LOWER_IDENT) -> ValRefExpr(previous().lexeme, previous().location)
            match(TokenType.IT) -> ValRefExpr("it", previous().location)
            match(TokenType.INT_LIT) -> LiteralExpr(IntLiteral(previous().lexeme.toLong()), previous().location)
            match(TokenType.FLOAT_LIT) -> LiteralExpr(FloatLiteral(previous().lexeme.toDouble()), previous().location)
            match(TokenType.STRING_LIT) -> LiteralExpr(StringLiteral(previous().lexeme), previous().location)
            match(TokenType.BOOL_LIT) -> LiteralExpr(BoolLiteral(previous().lexeme.toBoolean()), previous().location)
            match(TokenType.UUID_LIT) -> LiteralExpr(UuidLiteral(previous().lexeme), previous().location)
            match(TokenType.UPPER_IDENT) -> parseUpperInvocation(previous())
            else -> throw error(peek(), "Expected expression")
        }
    }

    private fun parseUpperInvocation(token: Token): Expr {
        val args = if (match(TokenType.LPAREN)) {
            parseArgumentListAfterOpenParen()
        } else {
            emptyList()
        }
        return UnresolvedCallExpr(token.lexeme, args, token.location)
    }

    private fun parseArgumentListAfterOpenParen(): List<Expr> {
        val args = mutableListOf<Expr>()
        if (!check(TokenType.RPAREN)) {
            do {
                args += parseExpr()
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return args
    }

    private fun parseMatchExpr(start: SourceLocation): MatchExpr {
        val subject = parseExpr()
        consume(TokenType.LBRACE, "Expected '{' after match subject")
        val arms = mutableListOf<MatchArm>()
        while (!check(TokenType.RBRACE)) {
            val armStart = peek().location
            val pattern = parsePattern()
            consume(TokenType.ARROW, "Expected '->' after match pattern")
            val body = if (check(TokenType.UNSAFE) || check(TokenType.LBRACE)) {
                val block = parseBlock()
                BlockExpr(block, block.location)
            } else {
                parseExpr()
            }
            arms += MatchArm(pattern, body, armStart)
        }
        consume(TokenType.RBRACE, "Expected '}' after match arms")
        return MatchExpr(subject, arms, start)
    }

    private fun parsePattern(): Pattern {
        return when {
            check(TokenType.LOWER_IDENT) && peek().lexeme == "_" -> {
                val token = advance()
                WildcardPattern(token.location)
            }

            match(TokenType.UPPER_IDENT) -> {
                val nameToken = previous()
                val bindings = if (match(TokenType.LPAREN)) parseLowerBindingList() else emptyList()
                VariantPattern(nameToken.lexeme, bindings, nameToken.location)
            }

            else -> throw error(peek(), "Expected match pattern")
        }
    }

    private fun parseLowerBindingList(): List<String> {
        val bindings = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            do {
                bindings += consume(TokenType.LOWER_IDENT, "Expected binding name").lexeme
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')'")
        return bindings
    }

    private fun parseConstraint(start: SourceLocation): Constraint {
        consume(TokenType.LBRACE, "Expected '{' after where")
        val expr = parseExpr()
        consume(TokenType.RBRACE, "Expected '}' after constraint")
        return Constraint(expr, renderExpr(expr), start)
    }

    private fun parseBracedTypeContent(): BracedTypeContent {
        consume(TokenType.LBRACE, "Expected '{'")
        val content = if (check(TokenType.LOWER_IDENT) && checkNext(TokenType.COLON)) {
            val fields = mutableListOf<VoField>()
            do {
                val name = consume(TokenType.LOWER_IDENT, "Expected field name")
                consume(TokenType.COLON, "Expected ':' after field name")
                fields += VoField(name.lexeme, parseTypeRef(), name.location)
            } while (match(TokenType.COMMA))
            BracedTypeContent.Named(fields)
        } else {
            val types = mutableListOf<String>()
            if (!check(TokenType.RBRACE)) {
                do {
                    types += parseTypeRef()
                } while (match(TokenType.COMMA))
            }
            BracedTypeContent.Positional(types)
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return content
    }

    private fun parsePositionalTypeBlock(context: String): List<String> {
        val content = parseBracedTypeContent()
        return when (content) {
            is BracedTypeContent.Named -> throw error(peek(), "$context cannot use named fields")
            is BracedTypeContent.Positional -> content.types
        }
    }

    private fun parseVariantSeries(): List<Variant> {
        val variants = mutableListOf<Variant>()
        variants += parseVariant()
        while (match(TokenType.PIPE)) {
            variants += parseVariant()
        }
        return variants
    }

    private fun parseVariant(): Variant {
        val nameToken = consume(TokenType.UPPER_IDENT, "Expected variant name")
        return parseVariantAfterName(nameToken.lexeme, nameToken.location)
    }

    private fun parseVariantAfterName(name: String, location: SourceLocation): Variant {
        val fields = if (match(TokenType.LPAREN)) {
            val args = mutableListOf<String>()
            if (!check(TokenType.RPAREN)) {
                do {
                    args += parseTypeRef()
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.RPAREN, "Expected ')'")
            args
        } else {
            emptyList()
        }
        return Variant(name, fields, location)
    }

    private fun parseTypeRef(): String {
        val base = consume(TokenType.UPPER_IDENT, "Expected type name").lexeme
        if (!match(TokenType.LBRACKET)) {
            return base
        }
        val args = mutableListOf<String>()
        do {
            args += parseTypeRef()
        } while (match(TokenType.COMMA))
        consume(TokenType.RBRACKET, "Expected ']' after type arguments")
        return "$base[${args.joinToString(", ")}]"
    }

    private fun baseTypeFor(name: String): BaseType = when (name) {
        "Int" -> BaseType.IntBase
        "String" -> BaseType.StringBase
        "Float" -> BaseType.FloatBase
        "Bool" -> BaseType.BoolBase
        "UUID" -> BaseType.UuidBase
        else -> BaseType.NamedBase(name)
    }

    private fun renderExpr(expr: Expr): String = when (expr) {
        is LiteralExpr -> when (val value = expr.value) {
            is IntLiteral -> value.value.toString()
            is FloatLiteral -> value.value.toString()
            is StringLiteral -> "\"${value.value}\""
            is BoolLiteral -> value.value.toString()
            is UuidLiteral -> value.value
        }

        is ValRefExpr -> expr.name
        is FieldAccessExpr -> "${renderExpr(expr.target)}.${expr.field}"
        is MethodCallExpr -> "${renderExpr(expr.receiver)}.${expr.method}(${expr.args.joinToString(", ") { renderExpr(it) }})"
        is MatchExpr -> "match ..."
        is CastExpr -> "${renderExpr(expr.expr)} |> ${expr.targetType}"
        is ItExpr -> "it${expr.path.joinToString(separator = ".", prefix = if (expr.path.isEmpty()) "" else ".")} ${expr.op.render()} ${renderExpr(expr.rhs)}"
        is ComparisonExpr -> "${renderExpr(expr.left)} ${expr.op.render()} ${renderExpr(expr.rhs)}"
        is LogicalExpr -> "${renderExpr(expr.left)} ${expr.op.render()} ${renderExpr(expr.right)}"
        is UnresolvedCallExpr -> "${expr.name}(${expr.args.joinToString(", ") { renderExpr(it) }})"
        is ConstructorExpr -> "${expr.typeName}(${expr.args.joinToString(", ") { renderExpr(it) }})"
        is BlockExpr -> if (expr.block.isUnsafe) "unsafe { ... }" else "{ ... }"
        is morph.ast.FunCallExpr -> "${expr.name}(${renderExpr(expr.arg)})"
    }

    private fun CmpOp.render(): String = when (this) {
        CmpOp.GT -> ">"
        CmpOp.LT -> "<"
        CmpOp.GTE -> ">="
        CmpOp.LTE -> "<="
        CmpOp.EQEQ -> "=="
        CmpOp.NEQ -> "!="
    }

    private fun LogicalOp.render(): String = when (this) {
        LogicalOp.AND -> "&&"
        LogicalOp.OR -> "||"
    }

    private fun isTypeClassImplStart(): Boolean =
        check(TokenType.IMPL) && checkNext(TokenType.UPPER_IDENT) && tokenAt(index + 2)?.type == TokenType.LBRACKET

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) {
            return advance()
        }
        throw error(peek(), message)
    }

    private fun consumeAny(message: String, vararg types: TokenType): Token {
        for (type in types) {
            if (check(type)) {
                return advance()
            }
        }
        throw error(peek(), message)
    }

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun checkNext(type: TokenType): Boolean = tokenAt(index + 1)?.type == type

    private fun advance(): Token {
        if (!isAtEnd()) {
            index += 1
        }
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): Token = tokens[index]

    private fun previous(): Token = tokens[index - 1]

    private fun tokenAt(position: Int): Token? = tokens.getOrNull(position)

    private fun error(token: Token, message: String): MorphParseError = MorphParseError(token.location, message)

    private sealed class BracedTypeContent {
        data class Named(val fields: List<VoField>) : BracedTypeContent()
        data class Positional(val types: List<String>) : BracedTypeContent()
    }
}
