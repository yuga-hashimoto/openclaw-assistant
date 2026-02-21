package com.openclaw.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class TTSUtilsTest {

    @Test
    fun testStripMarkdownForSpeech_WithEmojis() {
        val input = "Hello 😊 🌧️☔"
        val expected = "Hello emoji smiling face emoji rain emoji umbrella"
        val actual = TTSUtils.stripMarkdownForSpeech(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testStripMarkdownForSpeech_WithSpecialChars() {
        val input = "Test | with ~ symbols ^ and <brackets>"
        val expected = "Test with symbols and brackets"
        val actual = TTSUtils.stripMarkdownForSpeech(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testStripMarkdownForSpeech_WithMarkdown() {
        val input = "**Bold** and *Italic* and `code` and [link](http://example.com)"
        val expected = "Bold and Italic and code and link"
        val actual = TTSUtils.stripMarkdownForSpeech(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testStripMarkdownForSpeech_UnknownEmoji() {
        val input = "Alien 👽"
        val expected = "Alien emoji"
        val actual = TTSUtils.stripMarkdownForSpeech(input)
        assertEquals(expected, actual)
    }
}
