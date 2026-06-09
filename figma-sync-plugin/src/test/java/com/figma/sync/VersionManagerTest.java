package com.figma.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for version freeze, restore, list, diff, and delete operations.
 */
class VersionManagerTest {

    @Test
    void freezeAndList(@TempDir Path tempDir) throws Exception {
        // Create a mock cache file
        String cacheJson = "{\n" +
            "  \"version\": \"2361395147552737305\",\n" +
            "  \"ltrCount\": 80,\n" +
            "  \"rtlCount\": 2,\n" +
            "  \"assets\": {\n" +
            "    \"1:234\": {\"h\": \"abc123\", \"n\": \"icon_car_door\", \"r\": false},\n" +
            "    \"2:345\": {\"h\": \"def456\", \"n\": \"icon_low_beam\", \"r\": false}\n" +
            "  }\n" +
            "}";
        File cacheFile = tempDir.resolve("cache.json").toFile();
        Files.write(cacheFile.toPath(), cacheJson.getBytes());

        VersionManager vm = new VersionManager(tempDir.toFile());

        // Freeze
        VersionManager.VersionEntry entry = vm.freeze("HMI v2.1", cacheFile);
        assertEquals("HMI v2.1", entry.name);
        assertEquals("2361395147552737305", entry.versionId);
        assertEquals(80, entry.ltrCount);
        assertEquals(2, entry.rtlCount);

        // List
        VersionManager.VersionIndex index = vm.listVersions();
        assertEquals(1, index.versions.size());
        assertEquals("HMI v2.1", index.versions.get(0).name);
    }

    @Test
    void freeze_overwriteExisting(@TempDir Path tempDir) throws Exception {
        String cacheJson1 = "{\n" +
            "  \"version\": \"111\",\n" +
            "  \"ltrCount\": 50,\"rtlCount\": 1,\n" +
            "  \"assets\": {\"1:1\": {\"h\": \"xxx\", \"n\": \"a\", \"r\": false}}\n" +
            "}";
        File cacheFile1 = tempDir.resolve("cache1.json").toFile();
        Files.write(cacheFile1.toPath(), cacheJson1.getBytes());

        String cacheJson2 = "{\n" +
            "  \"version\": \"222\",\n" +
            "  \"ltrCount\": 55,\"rtlCount\": 2,\n" +
            "  \"assets\": {\"1:2\": {\"h\": \"yyy\", \"n\": \"b\", \"r\": false}}\n" +
            "}";
        File cacheFile2 = tempDir.resolve("cache2.json").toFile();
        Files.write(cacheFile2.toPath(), cacheJson2.getBytes());

        VersionManager vm = new VersionManager(tempDir.toFile());

        vm.freeze("HMI v2.0", cacheFile1);
        vm.freeze("HMI v2.0", cacheFile2); // Overwrite

        VersionManager.VersionIndex index = vm.listVersions();
        assertEquals(1, index.versions.size());
        assertEquals("222", index.versions.get(0).versionId);
    }

    @Test
    void restore(@TempDir Path tempDir) throws Exception {
        // Freeze a version first
        String cacheJson = "{\n" +
            "  \"version\": \"v999\",\n" +
            "  \"ltrCount\": 10,\"rtlCount\": 0,\n" +
            "  \"assets\": {\"X:1\": {\"h\": \"hash1\", \"n\": \"icon_x\", \"r\": false}}\n" +
            "}";
        File cacheFile = tempDir.resolve("active-cache.json").toFile();
        Files.write(cacheFile.toPath(), cacheJson.getBytes());

        VersionManager vm = new VersionManager(tempDir.toFile());
        vm.freeze("HMI test", cacheFile);

        // Now restore to a different cache file
        File restoreTarget = tempDir.resolve("restored-cache.json").toFile();
        VersionManager.VersionEntry entry = vm.restore("HMI test", restoreTarget);

        assertEquals("HMI test", entry.name);
        assertTrue(restoreTarget.exists(), "Restored cache file should exist");
        String content = new String(Files.readAllBytes(restoreTarget.toPath()));
        assertTrue(content.contains("v999"), "Restored cache should contain version v999");
    }

    @Test
    void restore_nonexistentVersion(@TempDir Path tempDir) {
        VersionManager vm = new VersionManager(tempDir.toFile());
        File target = tempDir.resolve("target.json").toFile();
        assertThrows(RuntimeException.class, () -> vm.restore("NoSuchVersion", target));
    }

    @Test
    void freeze_missingCacheFile(@TempDir Path tempDir) {
        VersionManager vm = new VersionManager(tempDir.toFile());
        File nonexistent = tempDir.resolve("nope.json").toFile();
        assertThrows(RuntimeException.class, () -> vm.freeze("test", nonexistent));
    }

    @Test
    void diff_addedModifiedRemoved(@TempDir Path tempDir) throws Exception {
        // Version A: 2 assets
        String cacheA = "{\n" +
            "  \"version\": \"v1\", \"ltrCount\": 2, \"rtlCount\": 0,\n" +
            "  \"assets\": {\n" +
            "    \"1:1\": {\"h\": \"aaa\", \"n\": \"icon_a\", \"r\": false},\n" +
            "    \"1:2\": {\"h\": \"bbb\", \"n\": \"icon_b\", \"r\": false}\n" +
            "  }\n" +
            "}";
        // Version B: icon_a modified, icon_b removed, icon_c added
        String cacheB = "{\n" +
            "  \"version\": \"v2\", \"ltrCount\": 2, \"rtlCount\": 0,\n" +
            "  \"assets\": {\n" +
            "    \"1:1\": {\"h\": \"aaa_MODIFIED\", \"n\": \"icon_a\", \"r\": false},\n" +
            "    \"1:3\": {\"h\": \"ccc\", \"n\": \"icon_c\", \"r\": false}\n" +
            "  }\n" +
            "}";

        VersionManager vm = new VersionManager(tempDir.toFile());

        // Manually set up frozen directories
        File dirA = new File(tempDir.toFile(), ".figma-versions/HMI_v2.0");
        dirA.mkdirs();
        Files.write(new File(dirA, ".figma-cache.json").toPath(), cacheA.getBytes());

        File dirB = new File(tempDir.toFile(), ".figma-versions/HMI_v2.1");
        dirB.mkdirs();
        Files.write(new File(dirB, ".figma-cache.json").toPath(), cacheB.getBytes());

        Map<String, Object> result = vm.diff("HMI_v2.0", "HMI_v2.1");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> added = (List<Map<String, String>>) result.get("added");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> removed = (List<Map<String, String>>) result.get("removed");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> modified = (List<Map<String, String>>) result.get("modified");

        assertEquals(1, added.size());
        assertEquals("icon_c", added.get(0).get("name"));

        assertEquals(1, removed.size());
        assertEquals("icon_b", removed.get(0).get("name"));

        assertEquals(1, modified.size());
        assertEquals("icon_a", modified.get(0).get("name"));
    }

    @Test
    void sanitizeDirName() {
        assertEquals("HMI_v2.1", VersionManager.sanitizeDirName("HMI v2.1"));
        assertEquals("test_name", VersionManager.sanitizeDirName("test/name"));
        assertEquals("a_b_c", VersionManager.sanitizeDirName("a b c"));
    }

    @Test
    void deleteVersion(@TempDir Path tempDir) throws Exception {
        String cacheJson = "{\n" +
            "  \"version\": \"v1\", \"ltrCount\": 1, \"rtlCount\": 0,\n" +
            "  \"assets\": {\"X:1\": {\"h\": \"h\", \"n\": \"x\", \"r\": false}}\n" +
            "}";
        File cacheFile = tempDir.resolve("cache.json").toFile();
        Files.write(cacheFile.toPath(), cacheJson.getBytes());

        VersionManager vm = new VersionManager(tempDir.toFile());
        vm.freeze("to_delete", cacheFile);
        assertEquals(1, vm.listVersions().versions.size());

        vm.deleteVersion("to_delete");
        assertEquals(0, vm.listVersions().versions.size());
    }

    @Test
    void recordFastIteration(@TempDir Path tempDir) throws Exception {
        String cacheJson = "{\n" +
            "  \"version\": \"v123\", \"ltrCount\": 1, \"rtlCount\": 0,\n" +
            "  \"assets\": {\"X:1\": {\"h\": \"h\", \"n\": \"x\", \"r\": false}}\n" +
            "}";
        File cacheFile = tempDir.resolve("cache.json").toFile();
        Files.write(cacheFile.toPath(), cacheJson.getBytes());

        VersionManager vm = new VersionManager(tempDir.toFile());
        vm.recordFastIteration("parent_v1", cacheFile);

        List<Map<String, Object>> iterations = vm.listFastIterations();
        assertEquals(1, iterations.size());
        assertEquals("v123", iterations.get(0).get("versionId"));
        assertEquals("parent_v1", iterations.get(0).get("parent"));
    }
}
