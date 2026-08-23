package com.clonemaster.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the runtime's config parsing + decision logic.
 * org.json on the JVM matches the Android framework API used by the runtime.
 */
public class RuntimeConfigTest {

    private static final String RUNTIME_META =
            "{\"originalApplication\":\"mark.via.gp.ViaApplication\",\"runtimeVersion\":1}";

    @Test
    public void parsesOriginalApplication() {
        RuntimeConfig cfg = RuntimeConfig.parse(RUNTIME_META, null);
        assertEquals("mark.via.gp.ViaApplication", cfg.originalApplication);
        assertFalse(cfg.hasActiveFeatures());
    }

    @Test
    public void parsesFeaturesFromCloneConfigShape() {
        // Exact Gson output shape of the manager's CloneConfig for the fields we consume.
        String cloneConfig = "{"
                + "\"clonePackage\":\"mark.via.gp.clone1\","
                + "\"appName\":\"Via Clone\","
                + "\"privacy\":{\"disableScreenshots\":true},"
                + "\"display\":{\"keepScreenAwake\":true,\"orientationLock\":1}"
                + "}";
        RuntimeConfig cfg = RuntimeConfig.parse(RUNTIME_META, cloneConfig);
        assertEquals("mark.via.gp.clone1", cfg.clonePackage);
        assertTrue(cfg.disableScreenshots);
        assertTrue(cfg.keepScreenAwake);
        assertEquals(1, cfg.orientationLock);
        assertTrue(cfg.hasActiveFeatures());
        // FLAG_SECURE 0x2000 | FLAG_KEEP_SCREEN_ON 0x80 = 0x2080
        assertEquals(0x2080, cfg.windowFlagsToSet());
    }

    @Test
    public void defaultsAreAllOff() {
        RuntimeConfig cfg = RuntimeConfig.parse(null, null);
        assertNull(cfg.originalApplication);
        assertFalse(cfg.disableScreenshots);
        assertFalse(cfg.keepScreenAwake);
        assertEquals(-1, cfg.orientationLock);
        assertEquals(0, cfg.windowFlagsToSet());
        assertFalse(cfg.hasActiveFeatures());
    }

    @Test
    public void fileLogDefaultsFalseForV1Meta() {
        RuntimeConfig cfg = RuntimeConfig.parse(RUNTIME_META, null);
        assertFalse(cfg.fileLog);
    }

    @Test
    public void fileLogParsedFromV2Meta() {
        String metaV2 =
                "{\"originalApplication\":\"mark.via.gp.ViaApplication\",\"runtimeVersion\":2,\"fileLog\":true}";
        RuntimeConfig cfg = RuntimeConfig.parse(metaV2, null);
        assertEquals("mark.via.gp.ViaApplication", cfg.originalApplication);
        assertTrue(cfg.fileLog);
    }

    @Test
    public void hookModeDefaultsToWrapForLegacyMeta() {
        RuntimeConfig cfg = RuntimeConfig.parse(RUNTIME_META, null);
        assertEquals("wrap", cfg.hookMode);
    }

    @Test
    public void hookModeParsedFromV3Meta() {
        String metaV3 =
                "{\"originalApplication\":null,\"runtimeVersion\":3,\"hookMode\":\"factory\",\"fileLog\":true}";
        RuntimeConfig cfg = RuntimeConfig.parse(metaV3, null);
        assertNull(cfg.originalApplication);
        assertEquals("factory", cfg.hookMode);
        assertTrue(cfg.fileLog);
    }

    @Test
    public void malformedJsonDegradesToDefaults() {
        RuntimeConfig cfg = RuntimeConfig.parse("{broken", "{\"privacy\": null}");
        assertNull(cfg.originalApplication);
        assertFalse(cfg.hasActiveFeatures());
    }

    @Test
    public void missingSectionsMeanFeaturesOff() {
        RuntimeConfig cfg = RuntimeConfig.parse("{}", "{\"clonePackage\":\"a.b.clone1\"}");
        assertEquals("a.b.clone1", cfg.clonePackage);
        assertEquals(0, cfg.windowFlagsToSet());
    }

    @Test
    public void screenshotsOnlySetsExactlyFlagSecure() {
        String clone = "{\"privacy\":{\"disableScreenshots\":true}}";
        assertEquals(0x2000, RuntimeConfig.parse("{}", clone).windowFlagsToSet());
    }
}
