package com.luckylca.autocrack.agent

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileAgentJsonTest {
    @Test
    fun nullableStringTreatsJsonNullMissingAndBlankAsNull() {
        val json = JSONObject()
            .put("jsonNull", JSONObject.NULL)
            .put("blank", "")
            .put("legacyNull", "null")
            .put("value", "failed")

        assertNull(json.optNonBlankStringOrNull("missing"))
        assertNull(json.optNonBlankStringOrNull("jsonNull"))
        assertNull(json.optNonBlankStringOrNull("blank"))
        assertNull(json.optNonBlankStringOrNull("legacyNull"))
        assertEquals("failed", json.optNonBlankStringOrNull("value"))
    }

    @Test
    fun detectsOnlyLegacyStringNullTaskErrors() {
        val root = JSONObject().put(
            "tasks",
            JSONArray()
                .put(JSONObject().put("error", JSONObject.NULL))
                .put(JSONObject().put("error", "null")),
        )

        assertEquals(true, hasLegacyNullTaskErrors(root))
        root.getJSONArray("tasks").getJSONObject(1).put("error", "real failure")
        assertEquals(false, hasLegacyNullTaskErrors(root))
    }
}
