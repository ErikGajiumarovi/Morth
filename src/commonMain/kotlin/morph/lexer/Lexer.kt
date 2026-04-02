package morph.lexer

import morph.ast.MorphParseError
import morph.ast.SourceLocation

enum class TokenType {
    TYPE,
    FUN,
    IMPL,
    LINK,
    ENTRY,
    UNSAFE,
    MATCH,
    VAL,
    WHERE,
    TYPECLASS,
    QUERY,
    RESULT,
    IT,
    UPPER_IDENT,
    LOWER_IDENT,
    ARROW,
    CAST_ARROW,
    PIPE,
    COLON,
    EQUALS,
    COMMA,
    DOT,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    AND,
    OR,
    GT,
    LT,
    GTE,
    LTE,
    EQEQ,
    NEQ,
    INT_LIT,
    FLOAT_LIT,
    STRING_LIT,
    BOOL_LIT,
    UUID_LIT,
    EOF,
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val location: SourceLocation,
)

class Lexer(
    private val source: String,
    private val file: String,
) {
    private var index = 0
    private var line = 1
    private var column = 1

    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun lex(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (!isAtEnd()) {
            skipTrivia()
            if (isAtEnd()) {
                break
            }
            val location = currentLocation()
            when {
                match("->") -> tokens += Token(TokenType.ARROW, "->", location)
                match("|>") -> tokens += Token(TokenType.CAST_ARROW, "|>", location)
                match("&&") -> tokens += Token(TokenType.AND, "&&", location)
                match("||") -> tokens += Token(TokenType.OR, "||", location)
                match(">=") -> tokens += Token(TokenType.GTE, ">=", location)
                match("<=") -> tokens += Token(TokenType.LTE, "<=", location)
                match("==") -> tokens += Token(TokenType.EQEQ, "==", location)
                match("!=") -> tokens += Token(TokenType.NEQ, "!=", location)
                peekChar() == '"' -> tokens += readString()
                tryUuidLiteral(location)?.also { tokens += it } != null -> Unit
                peekChar().isDigit() -> tokens += readNumber()
                peekChar().isUpperCase() -> tokens += readUpperIdent()
                peekChar().isLowerCase() || peekChar() == '_' -> tokens += readLowerIdent()
                else -> tokens += readSymbol(location)
            }
        }
        tokens += Token(TokenType.EOF, "", currentLocation())
        return tokens
    }

    private fun readString(): Token {
        val location = currentLocation()
        advance()
        val value = StringBuilder()
        while (!isAtEnd() && peekChar() != '"') {
            val ch = advance()
            if (ch == '\\') {
                val escaped = if (isAtEnd()) {
                    throw MorphParseError(location, "Unterminated string literal")
                } else {
                    advance()
                }
                value.append(
                    when (escaped) {
                        '"', '\\' -> escaped
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> escaped
                    },
                )
            } else {
                value.append(ch)
            }
        }
        if (isAtEnd()) {
            throw MorphParseError(location, "Unterminated string literal")
        }
        advance()
        return Token(TokenType.STRING_LIT, value.toString(), location)
    }

    private fun tryUuidLiteral(location: SourceLocation): Token? {
        if (!peekChar().isDigit()) {
            return null
        }
        val match = uuidRegex.find(source.substring(index)) ?: return null
        val lexeme = match.value
        val next = source.getOrNull(index + lexeme.length)
        if (next != null && (next.isLetterOrDigit() || next == '_' || next == '-')) {
            return null
        }
        repeat(lexeme.length) { advance() }
        return Token(TokenType.UUID_LIT, lexeme, location)
    }

    private fun readNumber(): Token {
        val location = currentLocation()
        val builder = StringBuilder()
        while (!isAtEnd() && peekChar().isDigit()) {
            builder.append(advance())
        }
        var type = TokenType.INT_LIT
        if (!isAtEnd() && peekChar() == '.' && peekChar(1).isDigit()) {
            type = TokenType.FLOAT_LIT
            builder.append(advance())
            while (!isAtEnd() && peekChar().isDigit()) {
                builder.append(advance())
            }
        }
        return Token(type, builder.toString(), location)
    }

    private fun readUpperIdent(): Token {
        val location = currentLocation()
        val builder = StringBuilder()
        builder.append(advance())
        while (!isAtEnd() && peekChar().isLetterOrDigit()) {
            builder.append(advance())
        }
        val lexeme = builder.toString()
        val type = when (lexeme) {
            "Query" -> TokenType.QUERY
            "Result" -> TokenType.RESULT
            else -> TokenType.UPPER_IDENT
        }
        return Token(type, lexeme, location)
    }

    private fun readLowerIdent(): Token {
        val location = currentLocation()
        val builder = StringBuilder()
        builder.append(advance())
        while (!isAtEnd() && (peekChar().isLetterOrDigit() || peekChar() == '_')) {
            builder.append(advance())
        }
        val lexeme = builder.toString()
        return Token(keywordType(lexeme), lexeme, location)
    }

    private fun keywordType(lexeme: String): TokenType = when (lexeme) {
        "type" -> TokenType.TYPE
        "fun" -> TokenType.FUN
        "impl" -> TokenType.IMPL
        "link" -> TokenType.LINK
        "entry" -> TokenType.ENTRY
        "unsafe" -> TokenType.UNSAFE
        "match" -> TokenType.MATCH
        "val" -> TokenType.VAL
        "where" -> TokenType.WHERE
        "typeclass" -> TokenType.TYPECLASS
        "Query" -> TokenType.QUERY
        "Result" -> TokenType.RESULT
        "it" -> TokenType.IT
        "true", "false" -> TokenType.BOOL_LIT
        else -> TokenType.LOWER_IDENT
    }

    private fun readSymbol(location: SourceLocation): Token {
        val ch = advance()
        val type = when (ch) {
            '|' -> TokenType.PIPE
            ':' -> TokenType.COLON
            '=' -> TokenType.EQUALS
            ',' -> TokenType.COMMA
            '.' -> TokenType.DOT
            '(' -> TokenType.LPAREN
            ')' -> TokenType.RPAREN
            '{' -> TokenType.LBRACE
            '}' -> TokenType.RBRACE
            '[' -> TokenType.LBRACKET
            ']' -> TokenType.RBRACKET
            '>' -> TokenType.GT
            '<' -> TokenType.LT
            else -> throw MorphParseError(location, "Unexpected character '$ch'")
        }
        return Token(type, ch.toString(), location)
    }

    private fun skipTrivia() {
        while (!isAtEnd()) {
            when {
                peekChar().isWhitespace() -> advance()
                peekChar() == '/' && peekChar(1) == '/' -> skipComment()
                else -> return
            }
        }
    }

    private fun skipComment() {
        while (!isAtEnd() && peekChar() != '\n') {
            advance()
        }
    }

    private fun match(text: String): Boolean {
        if (!source.startsWith(text, index)) {
            return false
        }
        repeat(text.length) { advance() }
        return true
    }

    private fun currentLocation(): SourceLocation = SourceLocation(file, line, column)

    private fun peekChar(offset: Int = 0): Char = source.getOrElse(index + offset) { '\u0000' }

    private fun isAtEnd(): Boolean = index >= source.length

    private fun advance(): Char {
        val ch = source[index++]
        if (ch == '\n') {
            line += 1
            column = 1
        } else {
            column += 1
        }
        return ch
    }
}
