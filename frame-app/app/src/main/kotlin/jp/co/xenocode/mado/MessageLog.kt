package jp.co.xenocode.mado

/**
 * Every alert the frame has been sent, newest last, with what became of each. Plain data with
 * its own text form, so it can live in preferences and outlast a restart.
 */
class MessageLog {
    class Message(val id: Long, val level: String, val text: String, val source: String?, val at: Long, var fate: String)

    private val list = ArrayList<Message>()
    val all: List<Message> get() = list

    /** `at` is the frame's clock in milliseconds, which reads 1970 for a while after boot. */
    fun arrived(id: Long, level: String, text: String, source: String?, at: Long, held: Boolean) {
        if (list.size == MAX) list.removeAt(0)
        list.add(Message(id, level, text, source, at, if (held) HELD else WAITING))
    }

    /** Morning: a message kept through the night is on its way to the glass. */
    fun released(id: Long) {
        find(id)?.fate = WAITING
    }

    /** Too many came in one night for all of them to be shown in the morning. */
    fun dropped(id: Long) {
        find(id)?.fate = LOST
    }

    /**
     * It left the glass, by a "tap" or because it "expired". `levelThen` is how it looked as it
     * went: a whisper that was opened leaves as a nudge, and so was read.
     */
    fun left(id: Long, levelThen: String, reason: String) {
        val m = find(id) ?: return
        m.fate = when {
            reason == "tap" || m.level != levelThen -> READ
            m.level == AlertView.WHISPER -> UNOPENED
            else -> PASSED
        }
    }

    // The hub counts its ids from one again each time it starts, so the newest match is the one meant.
    private fun find(id: Long) = list.lastOrNull { it.id == id }

    fun text() = list.joinToString("\n") { m ->
        listOf(m.at.toString(), m.id.toString(), m.level, m.fate, escape(m.source ?: ""), escape(m.text)).joinToString("\t")
    }

    fun restore(text: String?) {
        text?.lines()?.forEach { line ->
            val f = line.split('\t')
            val at = f.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val id = f.getOrNull(1)?.toLongOrNull() ?: return@forEach
            if (f.size != 6) return@forEach
            // Whatever was waiting when the app last stopped never reached the glass.
            val fate = if (f[3] == WAITING || f[3] == HELD) LOST else f[3]
            list.add(Message(id, f[2], unescape(f[5]), unescape(f[4]).ifEmpty { null }, at, fate))
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i == s.length) {
                out.append(c)
                continue
            }
            out.append(
                when (val next = s[i++]) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> next
                }
            )
        }
        return out.toString()
    }

    companion object {
        const val MAX = 200
        const val WAITING = "waiting"
        const val HELD = "held"
        const val READ = "read"
        const val PASSED = "passed"
        const val UNOPENED = "unopened"
        const val LOST = "lost"
    }
}
