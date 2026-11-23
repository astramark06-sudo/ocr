package com.batteryscope

/**
 * CSV row encoder. Embedded line breaks are flattened so one sample/event remains one record.
 */
internal object RecorderCsv {
    fun encodeRow(fields: List<String>): String =
        fields.joinToString(",") { quote(it) } + "\n"

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""
}
