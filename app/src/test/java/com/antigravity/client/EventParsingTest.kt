package com.antigravity.client

import com.antigravity.client.data.remote.dto.NormalizedStepDto
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EventParsingTest {

    private val gson = Gson()

    @Test
    fun testParseNormalizedStepFromJson() {
        val jsonStr = """
            {
                "step_index": 1,
                "source": "MODEL",
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-09-29T14:00:00Z",
                "thinking": "The user wants to write a file.",
                "content": "File created.",
                "tool_calls": [
                    {
                        "name": "write_to_file",
                        "args": {
                            "TargetFile": "/root/test.txt",
                            "Overwrite": true
                        }
                    }
                ],
                "diffs": [
                    {
                        "file": "/root/test.txt",
                        "action": "create",
                        "replacement_content": "Hello World"
                    }
                ]
            }
        """.trimIndent()

        val step = gson.fromJson(jsonStr, NormalizedStepDto::class.java)
        assertEquals(1, step.stepIndex)
        assertEquals("MODEL", step.source)
        assertEquals("PLANNER_RESPONSE", step.type)
        assertEquals("DONE", step.status)
        assertEquals("The user wants to write a file.", step.thinking)
        assertEquals(1, step.toolCalls?.size)
        assertEquals("write_to_file", step.toolCalls?.get(0)?.name)
        assertEquals("/root/test.txt", step.toolCalls?.get(0)?.args?.get("TargetFile"))
        assertEquals(1, step.diffs?.size)
        assertEquals("create", step.diffs?.get(0)?.action)
    }

    @Test
    fun testParseUnknownEventTypeGracefully() {
        val unknownJson = """
            {
                "type": "future_unknown_event_type",
                "seq": 999,
                "payload": {"info": "test"}
            }
        """.trimIndent()

        val json = gson.fromJson(unknownJson, JsonObject::class.java)
        assertEquals("future_unknown_event_type", json.get("type").asString)
        assertEquals(999L, json.get("seq").asLong)
    }
}
