package com.example.chineseime.data.local

object Utf8CsvReader {
    fun read(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        val points = text.codePoints().toArray()
        var quoted = false
        var index = 0
        while (index < points.size) {
            val cp = points[index]
            when {
                cp == '"'.code && quoted && index + 1 < points.size && points[index + 1] == '"'.code -> {
                    field.appendCodePoint(cp); index++
                }
                cp == '"'.code -> quoted = !quoted
                cp == ','.code && !quoted -> { row += field.toString(); field.setLength(0) }
                (cp == '\n'.code || cp == '\r'.code) && !quoted -> {
                    if (cp == '\r'.code && index + 1 < points.size && points[index + 1] == '\n'.code) index++
                    row += field.toString(); field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                }
                else -> field.appendCodePoint(cp)
            }
            index++
        }
        if (quoted) throw IllegalArgumentException("Unclosed quoted CSV field")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row.toList()
        }
        return rows
    }
}