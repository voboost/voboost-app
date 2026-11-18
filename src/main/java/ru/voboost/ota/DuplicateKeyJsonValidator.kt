package ru.voboost.ota

/** Rejects duplicate keys before a last-key-wins JSON object is created. */
internal object DuplicateKeyJsonValidator {
    fun validate(json: String) = Parser(json).parseDocument()

    private class Parser(private val input: String) {
        private var offset = 0

        fun parseDocument() {
            value()
            whitespace()
            failIf(offset != input.length, "trailing content")
        }

        private fun value() {
            whitespace()
            when (peek()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail("expected JSON value")
            }
        }

        private fun objectValue() {
            expect('{')
            whitespace()
            val keys = mutableSetOf<String>()
            if (consume('}')) return
            while (true) {
                whitespace()
                val key = stringValue()
                if (!keys.add(key)) fail("duplicate key: $key")
                whitespace()
                expect(':')
                value()
                whitespace()
                if (consume('}')) return
                expect(',')
            }
        }

        private fun arrayValue() {
            expect('[')
            whitespace()
            if (consume(']')) return
            while (true) {
                value()
                whitespace()
                if (consume(']')) return
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < input.length) {
                when (val character = input[offset++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (offset >= input.length) fail("unterminated escape")
                        when (val escaped = input[offset++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (offset + 4 > input.length) fail("short unicode escape")
                                val hex = input.substring(offset, offset + 4)
                                if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) fail("invalid unicode escape")
                                val code = hex.toInt(16)
                                result.append(code.toChar())
                                offset += 4
                            }
                            else -> fail("invalid escape")
                        }
                    }
                    else -> {
                        if (character < ' ') fail("unescaped control")
                        result.append(character)
                    }
                }
            }
            fail("unterminated string")
        }

        private fun number() {
            if (consume('-') && offset >= input.length) fail("incomplete number")
            if (consume('0')) {
                if (peek() in '0'..'9') fail("leading zero")
            } else {
                digits()
            }
            if (consume('.')) digits()
            if (peek() == 'e' || peek() == 'E') {
                offset++
                if (peek() == '+' || peek() == '-') offset++
                digits()
            }
        }

        private fun digits() {
            val start = offset
            while (peek() in '0'..'9') offset++
            if (start == offset) fail("expected digit")
        }

        private fun literal(value: String) {
            if (!input.startsWith(value, offset)) fail("invalid literal")
            offset += value.length
        }

        private fun whitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') offset++
        }

        private fun expect(character: Char) {
            if (!consume(character)) fail("expected $character")
        }

        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            offset++
            return true
        }

        private fun peek(): Char = input.getOrNull(offset) ?: '\u0000'

        private fun failIf(
            condition: Boolean,
            message: String,
        ) {
            if (condition) fail(message)
        }

        private fun fail(message: String): Nothing = throw ProductContractException("Invalid JSON at $offset: $message")
    }
}
