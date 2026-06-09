package com.figma.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for two-level version + hash cache.
 */
class VersionCacheTest {

    @Test
    void isVersionCurrent_matches() {
        File cacheFile = new File("/tmp/test_cache.json");
        cacheFile.deleteOnExit();

        VersionCache cache = new VersionCache(cacheFile);
        assertFalse(cache.isVersionCurrent("any"), "Empty cache should not match");

        cache.putAsset("1:1", "abc", "icon_a", false);
        cache.save("v12345", 1, 0, "");

        // Reload
        VersionCache cache2 = new VersionCache(cacheFile);
        assertTrue(cache2.isVersionCurrent("v12345"));
        assertFalse(cache2.isVersionCurrent("v99999"));
        assertFalse(cache2.isVersionCurrent(""));
    }

    @Test
    void md5Hex_consistent() {
        byte[] data = "hello figma".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash1 = VersionCache.md5Hex(data);
        String hash2 = VersionCache.md5Hex(data);

        assertEquals(hash1, hash2, "MD5 should be deterministic");
        assertEquals(32, hash1.length(), "MD5 hex should be 32 chars");
    }

    @Test
    void md5Hex_differentData() {
        String hash1 = VersionCache.md5Hex("alpha".getBytes());
        String hash2 = VersionCache.md5Hex("beta".getBytes());
        assertNotEquals(hash1, hash2, "Different data should produce different hashes");
    }

    @Test
    void putAndGet(@TempDir Path tempDir) {
        File cacheFile = tempDir.resolve("cache.json").toFile();
        VersionCache cache = new VersionCache(cacheFile);

        assertEquals("", cache.getCachedHash("nonexistent"));
        assertEquals("", cache.getCachedName("nonexistent"));

        cache.putAsset("1:234", "hash_abc", "icon_car_door", false);
        cache.putAsset("2:345", "hash_def", "icon_rtl_door", true);

        assertEquals("hash_abc", cache.getCachedHash("1:234"));
        assertEquals("icon_car_door", cache.getCachedName("1:234"));
        assertEquals("hash_def", cache.getCachedHash("2:345"));
        assertEquals("icon_rtl_door", cache.getCachedName("2:345"));
    }

    @Test
    void saveAndReload(@TempDir Path tempDir) {
        File cacheFile = tempDir.resolve("persist.json").toFile();
        VersionCache cache = new VersionCache(cacheFile);

        cache.putAsset("A:1", "hash111", "icon_one", false);
        cache.putAsset("A:2", "hash222", "icon_two_rtl", true);
        cache.save("version_42", 1, 1, "{\"meta\":true}");

        // Reload
        VersionCache reloaded = new VersionCache(cacheFile);
        assertTrue(reloaded.isVersionCurrent("version_42"));
        assertEquals(1, reloaded.getCachedLtrCount());
        assertEquals(1, reloaded.getCachedRtlCount());
        assertTrue(reloaded.getCachedMetadata().contains("meta"));

        assertEquals("hash111", reloaded.getCachedHash("A:1"));
        assertEquals("icon_one", reloaded.getCachedName("A:1"));
        assertEquals("hash222", reloaded.getCachedHash("A:2"));
        assertEquals("icon_two_rtl", reloaded.getCachedName("A:2"));
    }

    @Test
    void updateExistingAsset(@TempDir Path tempDir) {
        File cacheFile = tempDir.resolve("update.json").toFile();
        VersionCache cache = new VersionCache(cacheFile);

        cache.putAsset("N:1", "old_hash", "old_name", false);
        cache.putAsset("N:1", "new_hash", "new_name", true);

        assertEquals("new_hash", cache.getCachedHash("N:1"));
        assertEquals("new_name", cache.getCachedName("N:1"));
    }

    @Test
    void corruptCacheFile_handledGracefully(@TempDir Path tempDir) throws Exception {
        File cacheFile = tempDir.resolve("corrupt.json").toFile();
        Files.write(cacheFile.toPath(), "this is not json {{{".getBytes());

        VersionCache cache = new VersionCache(cacheFile);
        // Should load without crashing, falling back to empty state
        assertEquals("", cache.getCachedHash("anything"));
        assertFalse(cache.isVersionCurrent("v1"));
    }

    @Test
    void emptyCounts_defaultToZero(@TempDir Path tempDir) {
        File cacheFile = tempDir.resolve("empty_counts.json").toFile();
        VersionCache cache = new VersionCache(cacheFile);
        assertEquals(0, cache.getCachedLtrCount());
        assertEquals(0, cache.getCachedRtlCount());
    }
}
