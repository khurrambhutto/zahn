package com.chotaai.app.ai

sealed interface MatchResult<out T> {
    data class Found<T>(val item: T) : MatchResult<T>
    data class Ambiguous<T>(val candidates: List<T>) : MatchResult<T>
    data object NotFound : MatchResult<Nothing>
}

/**
 * Finds the task or habit the user means from a loose reference: an id ("3", "#3"), the exact
 * title, or a few of its words ("milk" → "Buy milk"). Lets the model pass names instead of ids,
 * so it never needs to list items first.
 */
object NameMatcher {
    fun <T> match(items: List<T>, query: String, id: (T) -> Long, name: (T) -> String): MatchResult<T> {
        val q = normalize(query)
        if (q.isEmpty()) return MatchResult.NotFound

        q.removePrefix("#").toLongOrNull()?.let { wanted ->
            items.firstOrNull { id(it) == wanted }?.let { return MatchResult.Found(it) }
        }

        val scored = items
            .map { it to score(q, normalize(name(it))) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return MatchResult.NotFound

        val best = scored.first()
        val runnerUp = scored.getOrNull(1)
        return if (runnerUp == null || best.second - runnerUp.second >= 0.2) {
            MatchResult.Found(best.first)
        } else {
            MatchResult.Ambiguous(scored.takeWhile { best.second - it.second < 0.2 }.map { it.first }.take(5))
        }
    }

    private fun score(query: String, name: String): Double {
        if (query == name) return 1.0
        if (name.contains(query) || query.contains(name)) return 0.8
        val qWords = words(query)
        val nWords = words(name)
        if (qWords.isEmpty() || nWords.isEmpty()) return 0.0
        val common = qWords.count { q -> nWords.any { n -> n == q || (q.length >= 4 && (n.startsWith(q) || q.startsWith(n))) } }
        if (common == 0) return 0.0
        return 0.7 * common / maxOf(qWords.size, nWords.size)
    }

    private val stopWords = setOf("the", "a", "an", "my", "to", "for", "task", "habit", "of")

    private fun words(s: String) = s.split(' ').filter { it.isNotBlank() && it !in stopWords }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("""[^\p{L}\p{N}#\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}
