package com.joeykot.dictate.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdditionalParametersTest {
    @Test
    fun mergePreservesNestedSiblingsAndReplacesArraysAndTypes() {
        val base = JSONObject("""{"generation":{"temperature":0.7,"limit":100},"list":[1,2],"scalar":5,"object":{"x":1}}""")
        val extra = JSONObject("""{"generation":{"temperature":0.2},"list":[{"value":3}],"scalar":{"x":4},"object":false}""")
        val merged = AdditionalParameters.merge(base, extra)
        assertEquals(100, merged.getJSONObject("generation").getInt("limit"))
        assertEquals(0.2, merged.getJSONObject("generation").getDouble("temperature"), 0.0)
        assertEquals(1, merged.getJSONArray("list").length())
        assertEquals(3, merged.getJSONArray("list").getJSONObject(0).getInt("value"))
        assertEquals(4, merged.getJSONObject("scalar").getInt("x"))
        assertFalse(merged.getBoolean("object"))
        merged.getJSONObject("generation").put("limit", 1)
        merged.getJSONArray("list").getJSONObject(0).put("value", 99)
        assertEquals(100, base.getJSONObject("generation").getInt("limit"))
        assertEquals(3, extra.getJSONArray("list").getJSONObject(0).getInt("value"))
    }

    @Test
    fun nullDeletesOldAndNewFieldsAtEveryObjectLevel() {
        val base = JSONObject("""{"model":"old","nested":{"keep":true,"remove":1}}""")
        val extra = JSONObject("""{"model":null,"absent":null,"nested":{"remove":null},"new":{"gone":null,"child":{"gone":null}},"array":[null,{"gone":null,"keep":2}]}""")
        val merged = AdditionalParameters.merge(base, extra)
        assertFalse(merged.has("model"))
        assertFalse(merged.has("absent"))
        assertTrue(merged.getJSONObject("nested").getBoolean("keep"))
        assertFalse(merged.getJSONObject("nested").has("remove"))
        assertFalse(merged.getJSONObject("new").has("gone"))
        assertEquals(0, merged.getJSONObject("new").getJSONObject("child").length())
        assertTrue(merged.getJSONArray("array").isNull(0))
        assertFalse(merged.getJSONArray("array").getJSONObject(1).has("gone"))
        assertTrue(extra.has("model"))
        assertTrue(extra.isNull("model"))
        assertEquals("old", base.getString("model"))
    }

    @Test
    fun multipartFieldsUseFinalModelAndSerializeCompleteJson() {
        val fields = AdditionalParameters.transcriptionFields("", """{"model":"override","nested":{"on":true,"remove":null},"array":[1,false,"x"],"remove":null}""")
        assertEquals("override", fields["model"])
        assertEquals("{\"on\":true}", fields["nested"])
        assertEquals("[1,false,\"x\"]", fields["array"])
        assertFalse(fields.containsKey("remove"))
        assertEquals("escaped%22%0D%0Aname", AdditionalParameters.multipartFieldName("escaped\"\r\nname"))
    }

    @Test
    fun deletedModelCannotBeRestoredFromDefaults() {
        assertThrows(IllegalArgumentException::class.java) {
            AdditionalParameters.transcriptionFields("default", """{"model":null}""")
        }
    }

    @Test
    fun jsonMustContainOneObject() {
        listOf("[]", "null", "42", "{", "{} trailing").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { AdditionalParameters.parseObject(it) }
        }
        assertEquals(0, AdditionalParameters.parseObject("  ").length())
    }

    @Test
    fun binaryFileConflictIsReportedBeforeRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            AdditionalParameters.transcriptionFields("model", """{"file":"text"}""")
        }
    }
}
