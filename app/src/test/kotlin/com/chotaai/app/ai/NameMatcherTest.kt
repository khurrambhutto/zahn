package com.chotaai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class NameMatcherTest {
    private data class Item(val id: Long, val name: String)

    private val items = listOf(Item(1, "Buy milk"), Item(2, "Call mom"), Item(3, "Buy bread"), Item(4, "Read 10 pages"))

    private fun match(query: String) = NameMatcher.match(items, query, { it.id }, { it.name })

    @Test fun partialTitle() = assertEquals(MatchResult.Found(items[0]), match("milk"))
    @Test fun caseAndPunctuation() = assertEquals(MatchResult.Found(items[1]), match("CALL MOM!"))
    @Test fun extraWords() = assertEquals(MatchResult.Found(items[1]), match("call my mom"))
    @Test fun byId() = assertEquals(MatchResult.Found(items[2]), match("#3"))
    @Test fun wordPrefix() = assertEquals(MatchResult.Found(items[3]), match("reading"))
    @Test fun ambiguous() = assertEquals(MatchResult.Ambiguous(listOf(items[0], items[2])), match("buy"))
    @Test fun notFound() = assertEquals(MatchResult.NotFound, match("dentist"))
}
