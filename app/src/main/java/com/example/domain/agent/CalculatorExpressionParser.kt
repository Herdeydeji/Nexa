package com.example.domain.agent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pure Kotlin recursive-descent arithmetic expression parser and evaluator.
 * Supports +, -, *, /, unary +/-, parentheses, decimals, percentages (e.g. 20%, 20% of 16750),
 * and thousands commas (e.g. 14,500). Never executes arbitrary scripts or code.
 */
object CalculatorExpressionParser {

    private val MATH_CTX = MathContext(16, RoundingMode.HALF_UP)

    data class CalculationOutput(
        val normalizedExpression: String,
        val result: BigDecimal,
        val formattedResult: String
    )

    fun evaluate(rawExpression: String): CalculationOutput {
        val trimmed = rawExpression.trim()
        require(trimmed.isNotEmpty()) { "Expression cannot be empty." }
        require(trimmed.length <= 512) { "Expression is too long (max 512 characters)." }

        // Normalize unicode minus/multiply/divide symbols and grouping commas between digits.
        val normalized = trimmed
            .replace('−', '-')
            .replace('×', '*')
            .replace('÷', '/')
            .replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")

        val tokens = tokenize(normalized)
        require(tokens.isNotEmpty()) { "Expression contains no valid math tokens." }

        val parser = Parser(tokens)
        val value = parser.parseExpression()
        if (parser.hasNext()) {
            val extra = parser.peek()
            throw IllegalArgumentException("Unexpected token '${extra.raw}' in expression '$rawExpression'.")
        }

        val clean = value.stripTrailingZeros()
        val formatted = if (clean.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            clean.toPlainString()
        }
        return CalculationOutput(
            normalizedExpression = normalized,
            result = clean,
            formattedResult = formatted
        )
    }

    private sealed interface Token {
        val raw: String
        data class NumberLit(override val raw: String, val value: BigDecimal) : Token
        data class Op(override val raw: String, val symbol: Char) : Token
        data class LParen(override val raw: String = "(") : Token
        data class RParen(override val raw: String = ")") : Token
        data class Percent(override val raw: String = "%") : Token
        data class KeywordOf(override val raw: String = "of") : Token
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    var dotCount = 0
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) {
                        if (input[i] == '.') {
                            dotCount++
                            require(dotCount <= 1) { "Invalid number format near index $start." }
                        }
                        i++
                    }
                    val numStr = input.substring(start, i)
                    require(numStr != ".") { "Standalone '.' is not a valid number." }
                    val dec = runCatching { BigDecimal(numStr) }.getOrNull()
                        ?: throw IllegalArgumentException("Invalid number '$numStr'.")
                    tokens.add(Token.NumberLit(numStr, dec))
                }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' -> {
                    tokens.add(Token.Op(ch.toString(), ch))
                    i++
                }
                ch == '(' -> {
                    tokens.add(Token.LParen())
                    i++
                }
                ch == ')' -> {
                    tokens.add(Token.RParen())
                    i++
                }
                ch == '%' -> {
                    tokens.add(Token.Percent())
                    i++
                }
                ch.isLetter() -> {
                    val start = i
                    while (i < input.length && input[i].isLetter()) {
                        i++
                    }
                    val word = input.substring(start, i)
                    if (word.equals("of", ignoreCase = true)) {
                        tokens.add(Token.KeywordOf(word))
                    } else {
                        throw IllegalArgumentException("Unsupported keyword or identifier '$word' in expression.")
                    }
                }
                else -> throw IllegalArgumentException("Unsupported character '$ch' in expression.")
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun hasNext(): Boolean = pos < tokens.size
        fun peek(): Token = tokens[pos]
        private fun consume(): Token = tokens[pos++]

        private data class EvalTerm(val value: BigDecimal, val isStandalonePercent: Boolean)

        // expression := term (('+' | '-') term)*
        fun parseExpression(): BigDecimal {
            var left = parseTerm().value
            while (hasNext()) {
                val tok = peek()
                if (tok is Token.Op && (tok.symbol == '+' || tok.symbol == '-')) {
                    consume()
                    val right = parseTerm()
                    val effectiveRight = if (right.isStandalonePercent) {
                        left.multiply(right.value, MATH_CTX)
                    } else {
                        right.value
                    }
                    left = if (tok.symbol == '+') {
                        left.add(effectiveRight, MATH_CTX)
                    } else {
                        left.subtract(effectiveRight, MATH_CTX)
                    }
                } else {
                    break
                }
            }
            return left
        }

        // term := factor (('*' | '/' | 'of') factor)*
        private fun parseTerm(): EvalTerm {
            val first = parseFactor()
            var left = first.value
            var isStandalonePercent = first.isStandalonePercent
            while (hasNext()) {
                val tok = peek()
                when {
                    tok is Token.Op && tok.symbol == '*' -> {
                        consume()
                        left = left.multiply(parseFactor().value, MATH_CTX)
                        isStandalonePercent = false
                    }
                    tok is Token.Op && tok.symbol == '/' -> {
                        consume()
                        val right = parseFactor().value
                        if (right.compareTo(BigDecimal.ZERO) == 0) {
                            throw IllegalArgumentException("Division by zero is undefined.")
                        }
                        left = left.divide(right, MATH_CTX)
                        isStandalonePercent = false
                    }
                    tok is Token.KeywordOf -> {
                        consume()
                        left = left.multiply(parseFactor().value, MATH_CTX)
                        isStandalonePercent = false
                    }
                    else -> break
                }
            }
            return EvalTerm(left, isStandalonePercent)
        }

        // factor := ('+' | '-') factor | postfixPercent
        private fun parseFactor(): EvalTerm {
            if (!hasNext()) {
                throw IllegalArgumentException("Unexpected end of expression; expected a value.")
            }
            val tok = peek()
            if (tok is Token.Op && (tok.symbol == '+' || tok.symbol == '-')) {
                consume()
                val inner = parseFactor()
                val value = if (tok.symbol == '-') inner.value.negate(MATH_CTX) else inner.value
                return EvalTerm(value, inner.isStandalonePercent)
            }
            return parsePostfixPercent()
        }

        // postfixPercent := primary ('%')*
        private fun parsePostfixPercent(): EvalTerm {
            var value = parsePrimary()
            var consumedPercent = false
            while (hasNext() && peek() is Token.Percent) {
                consume()
                value = value.divide(BigDecimal(100), MATH_CTX)
                consumedPercent = true
            }
            return EvalTerm(value, consumedPercent)
        }

        // primary := NumberLit | '(' expression ')'
        private fun parsePrimary(): BigDecimal {
            if (!hasNext()) {
                throw IllegalArgumentException("Unexpected end of expression.")
            }
            return when (val tok = consume()) {
                is Token.NumberLit -> tok.value
                is Token.LParen -> {
                    val inner = parseExpression()
                    if (!hasNext() || peek() !is Token.RParen) {
                        throw IllegalArgumentException("Missing closing parenthesis ')'.")
                    }
                    consume()
                    inner
                }
                else -> throw IllegalArgumentException("Unexpected token '${tok.raw}'; expected a number or '('.")
            }
        }
    }
}
