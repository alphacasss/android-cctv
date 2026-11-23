package id.cctv.monitor.data

/**
 * Format penyimpanan log aktivitas: satu entri per baris `at|title|detail`.
 * Karakter `|`, `\`, dan newline di-escape supaya bisa diuji di JVM tanpa
 * bergantung pada stub Android `org.json`.
 */
object ActivityLogCodec {

    fun encode(entries: List<ActivityLogEntry>): String =
        entries.joinToString("\n") { entry ->
            listOf(entry.at.toString(), entry.title, entry.detail)
                .joinToString("|") { escape(it) }
        }

    fun decode(raw: String?): List<ActivityLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = split(line) ?: return@mapNotNull null
            if (parts.size != 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            ActivityLogEntry(at, parts[1], parts[2])
        }
    }

    fun append(raw: String?, entry: ActivityLogEntry, max: Int = 30): String {
        val entries = ArrayList<ActivityLogEntry>(decode(raw))
        entries.add(0, entry)
        return encode(entries.take(max))
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '|' -> append("\\|")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
    }

    private fun split(line: String): List<String>? {
        val parts = ArrayList<String>(3)
        val current = StringBuilder()
        var escaped = false
        for (ch in line) {
            when {
                escaped -> {
                    current.append(
                        when (ch) {
                            'n' -> '\n'
                            '|' -> '|'
                            '\\' -> '\\'
                            else -> ch
                        }
                    )
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '|' -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (escaped) return null
        parts.add(current.toString())
        return parts
    }
}
