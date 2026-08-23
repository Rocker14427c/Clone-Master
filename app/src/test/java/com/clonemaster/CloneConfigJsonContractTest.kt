package com.clonemaster

import com.clonemaster.cloning.models.CloneConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-contract test: pins the exact key paths inside assets/clone_config.json
 * that the injected clone runtime (com.clonemaster.runtime.RuntimeConfig)
 * navigates with org.json. If Gson serialization of CloneConfig ever drifts
 * (renamed field, moved section), this test fails BEFORE clones silently lose
 * their settings on device.
 *
 * Uses the same serializer as CloneEngine (GsonBuilder, pretty printing).
 */
class CloneConfigJsonContractTest {

    private fun serialize(config: CloneConfig): JSONObject =
        JSONObject(GsonBuilder().setPrettyPrinting().create().toJson(config))

    @Test
    fun `clone_config json contains the runtime-consumed key paths`() {
        val config = CloneConfig(
            originalPackage = "mark.via.gp",
            clonePackage = "mark.via.gp.clone1",
            appName = "Via Clone"
        ).apply {
            privacy.disableScreenshots = true
            display.keepScreenAwake = true
            display.orientationLock = 1
        }
        val json = serialize(config)

        assertEquals("mark.via.gp.clone1", json.getString("clonePackage"))
        assertEquals("Via Clone", json.getString("appName"))
        // runtime path: privacy.disableScreenshots
        assertTrue(json.getJSONObject("privacy").getBoolean("disableScreenshots"))
        // runtime paths: display.keepScreenAwake, display.orientationLock
        assertTrue(json.getJSONObject("display").getBoolean("keepScreenAwake"))
        assertEquals(1, json.getJSONObject("display").getInt("orientationLock"))
    }

    @Test
    fun `defaults serialize features as OFF so clones stay clean`() {
        val json = serialize(CloneConfig(originalPackage = "a.b.c", clonePackage = "a.b.c.clone1"))
        assertEquals(false, json.getJSONObject("privacy").getBoolean("disableScreenshots"))
        assertEquals(false, json.getJSONObject("display").getBoolean("keepScreenAwake"))
        assertEquals(-1, json.getJSONObject("display").getInt("orientationLock"))
    }

    @Test
    fun `plain Gson (HookFramework path) produces the same key paths`() {
        // CloneEngine sometimes falls back to plain Gson() – the contract must hold either way.
        val json = JSONObject(Gson().toJson(CloneConfig(clonePackage = "x.y.z").apply { privacy.disableScreenshots = true }))
        assertTrue(json.getJSONObject("privacy").getBoolean("disableScreenshots"))
        assertTrue(json.has("display"))
    }
}
