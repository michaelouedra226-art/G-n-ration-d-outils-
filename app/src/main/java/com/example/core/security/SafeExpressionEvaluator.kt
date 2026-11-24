package com.example.core.security

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin, zero-reflection recursive-descent expression evaluator for sandboxed tool logic.
 *
 * Supports numeric arithmetic (`+`, `-`, `*`, `/`, `%`, `^`), parentheses, variables resolved
 * exclusively from an immutable sandbox context map, comparisons (`>`, `<`, `>=`, `<=`, `==`, `!=`),
 * and safe whitelisted functions (`sqrt`, `abs`, `round`, `floor`, `ceil`, `min`, `max`, `pow`,
 * `sin`, `cos`, `ln`, `log10`, `if`).
 *
 * Enforces strict length and recursion depth limits to prevent CPU/stack exhaustion.
 */
object SafeExpressionEvaluator {

    private const val MAX_EXPRESSION_LENGTH = 1024
    private const val MAX_RECURSION_DEPTH = 32

    /**
     * Evaluates a numeric or conditional expression against [variables].
     *
     * @throws IllegalArgumentException if the syntax is invalid, a variable is missing,
     * an unknown function is invoked, division by zero occurs, or limits are exceeded.
     */
    fun evaluate(expression: String, variables: Map<String, Double> = emptyMap()): Double {
        val trimmed = expression.trim()
        require(trimmed.isNotEmpty()) { "Expression vide." }
        require(trimmed.length <= MAX_EXPRESSION_LENGTH) {
            "Expression trop longue (max $MAX_EXPRESSION_LENGTH caractères)."
        }
        val tokens = tokenize(trimmed)
        val parser = Parser(tokens, variables)
        val result = parser.parseExpression(depth = 0)
        if (!parser.isAtEnd()) {
            throw IllegalArgumentException("Jeton inattendu dans l'expression : '${parser.peek().raw}'")
        }
        require(!result.isNaN() && !result.isInfinite()) {
            "Résultat mathématique invalide (NaN ou infini)."
        }
        return result
    }

    /**
     * Validates expression syntax without executing it against real values.
     */
    fun validateSyntax(expression: String, knownVariables: Set<String> = emptySet()): Result<Unit> =
        runCatching {
            val placeholderMap = knownVariables.associateWith { 1.0 }
            val trimmed = expression.trim()
            require(trimmed.isNotEmpty()) { "Expression vide." }
            require(trimmed.length <= MAX_EXPRESSION_LENGTH) {
                "Expression dépasse $MAX_EXPRESSION_LENGTH caractères."
            }
            val tokens = tokenize(trimmed)
            val parser = Parser(tokens, placeholderMap, lenientVariables = true)
            parser.parseExpression(depth = 0)
            if (!parser.isAtEnd()) {
                throw IllegalArgumentException("Syntaxe invalide près de '${parser.peek().raw}'")
            }
        }

    private enum class TokenType {
        NUMBER, IDENTIFIER, PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
        LPAREN, RPAREN, COMMA, GT, LT, GTE, LTE, EQ, NEQ, EOF
    }

    private data class Token(val type: TokenType, val raw: String)

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                    val start = i
                    var hasDot = false
                    while (i < input.length && (input[i].isDigit() || (input[i] == '.' && !hasDot))) {
                        if (input[i] == '.') hasDot = true
                        i++
                    }
                    tokens.add(Token(TokenType.NUMBER, input.substring(start, i)))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) {
                        i++
                    }
                    tokens.add(Token(TokenType.IDENTIFIER, input.substring(start, i)))
                }
                c == '+' -> { tokens.add(Token(TokenType.PLUS, "+")); i++ }
                c == '-' -> { tokens.add(Token(TokenType.MINUS, "-")); i++ }
                c == '*' -> { tokens.add(Token(TokenType.STAR, "*")); i++ }
                c == '/' -> { tokens.add(Token(TokenType.SLASH, "/")); i++ }
                c == '%' -> { tokens.add(Token(TokenType.PERCENT, "%")); i++ }
                c == '^' -> { tokens.add(Token(TokenType.CARET, "^")); i++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN, "(")); i++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN, ")")); i++ }
                c == ',' -> { tokens.add(Token(TokenType.COMMA, ",")); i++ }
                c == '>' -> {
                    if (i + 1 < input.length && input[i + 1] == '=') {
                        tokens.add(Token(TokenType.GTE, ">=")); i += 2
                    } else {
                        tokens.add(Token(TokenType.GT, ">")); i++
                    }
                }
                c == '<' -> {
                    if (i + 1 < input.length && input[i + 1] == '=') {
                        tokens.add(Token(TokenType.LTE, "<=")); i += 2
                    } else {
                        tokens.add(Token(TokenType.LT, "<")); i++
                    }
                }
                c == '=' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.EQ, "==")); i += 2
                }
                c == '!' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.NEQ, "!=")); i += 2
                }
                else -> throw IllegalArgumentException("Caractère non autorisé dans l'expression : '$c'")
            }
        }
        tokens.add(Token(TokenType.EOF, ""))
        return tokens
    }

    private class Parser(
        private val tokens: List<Token>,
        private val variables: Map<String, Double>,
        private val lenientVariables: Boolean = false
    ) {
        private var pos = 0

        fun isAtEnd(): Boolean = peek().type == TokenType.EOF
        fun peek(): Token = tokens[pos]
        private fun advance(): Token = tokens[pos++]
        private fun check(type: TokenType): Boolean = peek().type == type

        fun parseExpression(depth: Int): Double {
            require(depth <= MAX_RECURSION_DEPTH) { "Profondeur d'expression excessive." }
            return parseComparison(depth + 1)
        }

        private fun parseComparison(depth: Int): Double {
            var left = parseAdditive(depth + 1)
            while (peek().type in listOf(
                    TokenType.GT, TokenType.LT, TokenType.GTE,
                    TokenType.LTE, TokenType.EQ, TokenType.NEQ
                )
            ) {
                val op = advance().type
                val right = parseAdditive(depth + 1)
                left = when (op) {
                    TokenType.GT -> if (left > right) 1.0 else 0.0
                    TokenType.LT -> if (left < right) 1.0 else 0.0
                    TokenType.GTE -> if (left >= right) 1.0 else 0.0
                    TokenType.LTE -> if (left <= right) 1.0 else 0.0
                    TokenType.EQ -> if (abs(left - right) < 1e-9) 1.0 else 0.0
                    TokenType.NEQ -> if (abs(left - right) >= 1e-9) 1.0 else 0.0
                    else -> left
                }
            }
            return left
        }

        private fun parseAdditive(depth: Int): Double {
            var left = parseMultiplicative(depth + 1)
            while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
                val op = advance().type
                val right = parseMultiplicative(depth + 1)
                left = if (op == TokenType.PLUS) left + right else left - right
            }
            return left
        }

        private fun parseMultiplicative(depth: Int): Double {
            var left = parsePower(depth + 1)
            while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
                val op = advance().type
                val right = parsePower(depth + 1)
                left = when (op) {
                    TokenType.STAR -> left * right
                    TokenType.SLASH -> {
                        require(abs(right) > 1e-12) { "Division par zéro interdite." }
                        left / right
                    }
                    TokenType.PERCENT -> {
                        require(abs(right) > 1e-12) { "Modulo par zéro interdit." }
                        left % right
                    }
                    else -> left
                }
            }
            return left
        }

        private fun parsePower(depth: Int): Double {
            val base = parseUnary(depth + 1)
            if (check(TokenType.CARET)) {
                advance()
                val exponent = parsePower(depth + 1)
                return base.pow(exponent)
            }
            return base
        }

        private fun parseUnary(depth: Int): Double {
            if (check(TokenType.MINUS)) {
                advance()
                return -parseUnary(depth + 1)
            }
            if (check(TokenType.PLUS)) {
                advance()
                return parseUnary(depth + 1)
            }
            return parsePrimary(depth + 1)
        }

        private fun parsePrimary(depth: Int): Double {
            require(depth <= MAX_RECURSION_DEPTH) { "Profondeur d'expression excessive." }
            val token = peek()
            return when (token.type) {
                TokenType.NUMBER -> {
                    advance()
                    token.raw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Nombre invalide : '${token.raw}'")
                }
                TokenType.IDENTIFIER -> {
                    advance()
                    if (check(TokenType.LPAREN)) {
                        advance() // consume '('
                        val args = mutableListOf<Double>()
                        if (!check(TokenType.RPAREN)) {
                            do {
                                args.add(parseExpression(depth + 1))
                            } while (check(TokenType.COMMA).also { if (it) advance() })
                        }
                        require(check(TokenType.RPAREN)) { "Parenthèse fermante attendue après '${token.raw}'" }
                        advance()
                        invokeFunction(token.raw, args)
                    } else {
                        when (token.raw.lowercase()) {
                            "pi" -> Math.PI
                            "e" -> Math.E
                            else -> variables[token.raw]
                                ?: if (lenientVariables) 1.0
                                else throw IllegalArgumentException("Variable inconnue : '${token.raw}'")
                        }
                    }
                }
                TokenType.LPAREN -> {
                    advance()
                    val value = parseExpression(depth + 1)
                    require(check(TokenType.RPAREN)) { "Parenthèse fermante ')' manquante." }
                    advance()
                    value
                }
                else -> throw IllegalArgumentException("Expression invalide près de '${token.raw}'")
            }
        }

        private fun invokeFunction(name: String, args: List<Double>): Double {
            return when (name.lowercase()) {
                "sqrt" -> {
                    requireArgCount(name, args, 1)
                    require(args[0] >= 0.0) { "sqrt() requiert une valeur >= 0" }
                    sqrt(args[0])
                }
                "abs" -> { requireArgCount(name, args, 1); abs(args[0]) }
                "round" -> { requireArgCount(name, args, 1); round(args[0]) }
                "floor" -> { requireArgCount(name, args, 1); floor(args[0]) }
                "ceil" -> { requireArgCount(name, args, 1); ceil(args[0]) }
                "sin" -> { requireArgCount(name, args, 1); sin(args[0]) }
                "cos" -> { requireArgCount(name, args, 1); cos(args[0]) }
                "ln" -> {
                    requireArgCount(name, args, 1)
                    require(args[0] > 0.0) { "ln() requiert une valeur > 0" }
                    ln(args[0])
                }
                "log10" -> {
                    requireArgCount(name, args, 1)
                    require(args[0] > 0.0) { "log10() requiert une valeur > 0" }
                    log10(args[0])
                }
                "pow" -> { requireArgCount(name, args, 2); args[0].pow(args[1]) }
                "min" -> { require(args.isNotEmpty()) { "min() requiert au moins 1 argument" }; args.min() }
                "max" -> { require(args.isNotEmpty()) { "max() requiert au moins 1 argument" }; args.max() }
                "if" -> {
                    requireArgCount(name, args, 3)
                    if (args[0] != 0.0) args[1] else args[2]
                }
                else -> throw IllegalArgumentException("Fonction non autorisée dans le sandbox : '$name'")
            }
        }

        private fun requireArgCount(name: String, args: List<Double>, expected: Int) {
            require(args.size == expected) {
                "La fonction $name() attend $expected argument(s), reçu ${args.size}."
            }
        }
    }
}
