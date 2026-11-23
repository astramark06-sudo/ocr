package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderCsvTest {
    @Test
    fun emitsRealRecordTerminatorAndQuotesFields() {
        val encoded = RecorderCsv.encodeRow(listOf("SAMPLE", "a,b", "quote\"value", "line\nbreak"))
        assertEquals("\"SAMPLE\",\"a,b\",\"quote\"\"value\",\"line break\"\n", encoded)
        assertEquals(1, encoded.count { it == '\n' })
        assertFalse(encoded.contains("\\n"))
    }

    @Test
    fun emptyFieldsRemainExplicitCsvColumns() {
        assertEquals("\"EVENT\",\"123\",\"\",\"\",\"details\"\n",
            RecorderCsv.encodeRow(listOf("EVENT", "123", "", "", "details")))
    }
}
