package com.freeturn.app.domain.proxy

/**
 * Tokenizes a command line string into a list of arguments,
 * respecting double quotes, single quotes, and backslash escaping (POSIX shell style).
 */
object CommandLineTokenizer {
    fun tokenize(commandLine: String): List<String> {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var isEscaped = false
        var tokenStarted = false

        for (ch in trimmed) {
            if (isEscaped) {
                current.append(ch)
                isEscaped = false
                tokenStarted = true
                continue
            }

            if (ch == '\\' && !inSingleQuotes) {
                isEscaped = true
                tokenStarted = true
                continue
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
                tokenStarted = true
                continue
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
                tokenStarted = true
                continue
            }

            if (ch.isWhitespace() && !inDoubleQuotes && !inSingleQuotes) {
                if (tokenStarted) {
                    tokens.add(current.toString())
                    current.setLength(0)
                    tokenStarted = false
                }
            } else {
                current.append(ch)
                tokenStarted = true
            }
        }

        if (tokenStarted) {
            tokens.add(current.toString())
        }

        return tokens
    }
}
