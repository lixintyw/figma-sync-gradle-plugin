package com.figma.sync;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/**
 * Manages named Figma versions for HMI release workflow.
 *
 * <pre>
 * Directory layout:
 *   .figma-versions/
 *     index.json              → version registry
 *     HMI_v2.0/
 *       .figma-cache.json     → frozen cache snapshot
 *     HMI_v2.1/
 *       .figma-cache.json
 *     fast-iterations.json    → lightweight version log
 * </pre>
 *
 * Operations:
 *   freeze(name)   → snapshot current cache as named version
 *   restore(name)  → copy frozen cache back to active location
 *   list()         → enumerate all frozen versions
 *   diff(a, b)     → compare two version caches, report added/removed/modified
 */
public class VersionManager {

    private final File versionsDir;
    private final File indexFile;
    private final File fastIterFile;

    public VersionManager(File projectBuildDir) {
        this.versionsDir = new File(projectBuildDir, ".figma-versions");
        this.indexFile = new File(versionsDir, "index.json");
        this.fastIterFile = new File(versionsDir, "fast-iterations.json");
    }

    // ── Freeze: snapshot current cache ──────────────────────────

    /**
     * Freeze the current Figma cache as a named version.
     *
     * @param name       version name, e.g. "HMI v2.1"
     * @param cacheFile  current active cache file (build/figma/.figma-cache.json)
     * @return VersionEntry with metadata
     */
    @SuppressWarnings("unchecked")
    public VersionEntry freeze(String name, File cacheFile) throws Exception {
        versionsDir.mkdirs();
        if (!cacheFile.exists()) {
            throw new RuntimeException("Cache file not found: " + cacheFile.getPath()
                + ". Run syncFigmaIcons first to populate the cache.");
        }

        // Read current cache to get metadata
        Map<?, ?> cacheJson = (Map<?, ?>) new JsonSlurper().parseText(
            new String(Files.readAllBytes(cacheFile.toPath())));
        String versionId = cacheJson.get("version") != null
            ? cacheJson.get("version").toString() : "";
        int ltrCount = cacheJson.get("ltrCount") != null
            ? ((Number) cacheJson.get("ltrCount")).intValue() : 0;
        int rtlCount = cacheJson.get("rtlCount") != null
            ? ((Number) cacheJson.get("rtlCount")).intValue() : 0;

        // Create version directory and copy cache
        File versionDir = new File(versionsDir, sanitizeDirName(name));
        versionDir.mkdirs();
        Files.copy(cacheFile.toPath(), new File(versionDir, ".figma-cache.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Update index
        VersionEntry entry = new VersionEntry(name, versionId, Instant.now().toString(),
            ltrCount, rtlCount, false);
        VersionIndex index = loadIndex();
        // Replace existing entry with same name
        index.versions.removeIf(v -> v.name.equals(name));
        index.versions.add(entry);
        saveIndex(index);

        return entry;
    }

    // ── Restore: switch to a frozen version ─────────────────────

    /**
     * Restore a frozen version's cache to the active location.
     *
     * @param name      version name to restore
     * @param cacheFile active cache file to overwrite
     * @return VersionEntry that was restored
     */
    public VersionEntry restore(String name, File cacheFile) throws Exception {
        VersionIndex index = loadIndex();
        VersionEntry entry = index.versions.stream()
            .filter(v -> v.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Version '" + name
                + "' not found. Use figmaListVersions to see available versions."));

        File versionDir = new File(versionsDir, sanitizeDirName(name));
        File frozenCache = new File(versionDir, ".figma-cache.json");
        if (!frozenCache.exists()) {
            throw new RuntimeException("Frozen cache missing for '" + name
                + "': " + frozenCache.getPath());
        }

        Files.copy(frozenCache.toPath(), cacheFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Update current pointer
        index.current = name;
        saveIndex(index);

        return entry;
    }

    // ── Fast iteration: log a version ID without full snapshot ──

    /**
     * Record a fast iteration entry (lightweight version log).
     * Called automatically after each sync when version changes.
     */
    @SuppressWarnings("unchecked")
    public void recordFastIteration(String parentVersion, File cacheFile) throws Exception {
        versionsDir.mkdirs();
        if (!cacheFile.exists()) return;

        Map<?, ?> cacheJson = (Map<?, ?>) new JsonSlurper().parseText(
            new String(Files.readAllBytes(cacheFile.toPath())));
        String versionId = cacheJson.get("version") != null
            ? cacheJson.get("version").toString() : "";

        List<Map<String, Object>> iterations = loadFastIterations();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("versionId", versionId);
        entry.put("timestamp", Instant.now().toString());
        entry.put("parent", parentVersion);
        iterations.add(entry);

        // Keep only last 50 iterations
        while (iterations.size() > 50) iterations.remove(0);

        Files.write(fastIterFile.toPath(),
            JsonOutput.toJson(iterations).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── List versions ───────────────────────────────────────────

    public VersionIndex listVersions() {
        return loadIndex();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listFastIterations() {
        return loadFastIterations();
    }

    // ── Diff two versions ───────────────────────────────────────

    /**
     * Compare two frozen versions and report differences.
     *
     * @return map with keys: added, removed, modified, unchanged
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> diff(String versionA, String versionB) throws Exception {
        File dirA = new File(versionsDir, sanitizeDirName(versionA));
        File dirB = new File(versionsDir, sanitizeDirName(versionB));
        File cacheA = new File(dirA, ".figma-cache.json");
        File cacheB = new File(dirB, ".figma-cache.json");

        if (!cacheA.exists()) throw new RuntimeException("Version '" + versionA + "' cache not found");
        if (!cacheB.exists()) throw new RuntimeException("Version '" + versionB + "' cache not found");

        Map<?, ?> jsonA = (Map<?, ?>) new JsonSlurper().parseText(
            new String(Files.readAllBytes(cacheA.toPath())));
        Map<?, ?> jsonB = (Map<?, ?>) new JsonSlurper().parseText(
            new String(Files.readAllBytes(cacheB.toPath())));

        Map<?, ?> assetsA = (Map<?, ?>) jsonA.get("assets");
        Map<?, ?> assetsB = (Map<?, ?>) jsonB.get("assets");

        if (assetsA == null) assetsA = Collections.emptyMap();
        if (assetsB == null) assetsB = Collections.emptyMap();

        List<Map<String, String>> added = new ArrayList<>();
        List<Map<String, String>> removed = new ArrayList<>();
        List<Map<String, String>> modified = new ArrayList<>();
        int unchanged = 0;

        // Find added and modified
        for (Map.Entry<?, ?> e : assetsB.entrySet()) {
            String nodeId = e.getKey().toString();
            Map<?, ?> assetB = (Map<?, ?>) e.getValue();
            String hashB = assetB.get("h") != null ? assetB.get("h").toString() : "";
            String nameB = assetB.get("n") != null ? assetB.get("n").toString() : nodeId;

            Map<?, ?> assetA = (Map<?, ?>) assetsA.get(nodeId);
            if (assetA == null) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("nodeId", nodeId);
                item.put("name", nameB);
                added.add(item);
            } else {
                String hashA = assetA.get("h") != null ? assetA.get("h").toString() : "";
                if (!hashA.equals(hashB)) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("nodeId", nodeId);
                    item.put("name", nameB);
                    item.put("oldHash", hashA);
                    item.put("newHash", hashB);
                    modified.add(item);
                } else {
                    unchanged++;
                }
            }
        }

        // Find removed
        for (Map.Entry<?, ?> e : assetsA.entrySet()) {
            String nodeId = e.getKey().toString();
            if (!assetsB.containsKey(nodeId)) {
                Map<?, ?> assetA = (Map<?, ?>) e.getValue();
                Map<String, String> item = new LinkedHashMap<>();
                item.put("nodeId", nodeId);
                item.put("name", assetA.get("n") != null ? assetA.get("n").toString() : nodeId);
                removed.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionA);
        result.put("to", versionB);
        result.put("added", added);
        result.put("removed", removed);
        result.put("modified", modified);
        result.put("unchanged", unchanged);

        return result;
    }

    // ── Delete a frozen version ─────────────────────────────────

    public boolean deleteVersion(String name) {
        VersionIndex index = loadIndex();
        boolean removed = index.versions.removeIf(v -> v.name.equals(name));
        if (removed) {
            saveIndex(index);
            File versionDir = new File(versionsDir, sanitizeDirName(name));
            deleteRecursive(versionDir);
        }
        return removed;
    }

    // ── Internal helpers ────────────────────────────────────────

    private VersionIndex loadIndex() {
        if (!indexFile.exists()) return new VersionIndex();
        try {
            return parseIndex(new String(Files.readAllBytes(indexFile.toPath())));
        } catch (Exception e) {
            return new VersionIndex();
        }
    }

    private void saveIndex(VersionIndex index) {
        try {
            indexFile.getParentFile().mkdirs();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("current", index.current != null ? index.current : "");

            List<Map<String, Object>> vers = new ArrayList<>();
            for (VersionEntry v : index.versions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", v.name);
                m.put("versionId", v.versionId);
                m.put("frozenAt", v.frozenAt);
                m.put("ltrCount", v.ltrCount);
                m.put("rtlCount", v.rtlCount);
                m.put("active", v.active);
                vers.add(m);
            }
            root.put("versions", vers);

            Files.write(indexFile.toPath(),
                JsonOutput.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // non-fatal
        }
    }

    @SuppressWarnings("unchecked")
    private VersionIndex parseIndex(String json) {
        VersionIndex index = new VersionIndex();
        Map<?, ?> root = (Map<?, ?>) new JsonSlurper().parseText(json);
        index.current = root.get("current") != null ? root.get("current").toString() : null;
        Object vers = root.get("versions");
        if (vers instanceof List) {
            for (Object v : (List<?>) vers) {
                if (v instanceof Map) {
                    Map<?, ?> vm = (Map<?, ?>) v;
                    index.versions.add(new VersionEntry(
                        vm.get("name").toString(),
                        vm.get("versionId") != null ? vm.get("versionId").toString() : "",
                        vm.get("frozenAt") != null ? vm.get("frozenAt").toString() : "",
                        vm.get("ltrCount") != null ? ((Number) vm.get("ltrCount")).intValue() : 0,
                        vm.get("rtlCount") != null ? ((Number) vm.get("rtlCount")).intValue() : 0,
                        vm.get("active") != null && (Boolean) vm.get("active")
                    ));
                }
            }
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFastIterations() {
        if (!fastIterFile.exists()) return new ArrayList<>();
        try {
            return (List<Map<String, Object>>) new JsonSlurper().parseText(
                new String(Files.readAllBytes(fastIterFile.toPath())));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static String sanitizeDirName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
    }

    private static void deleteRecursive(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursive(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    // ── Data classes ────────────────────────────────────────────

    public static class VersionEntry {
        public final String name;
        public final String versionId;
        public final String frozenAt;
        public final int ltrCount;
        public final int rtlCount;
        public final boolean active;

        public VersionEntry(String name, String versionId, String frozenAt,
                            int ltrCount, int rtlCount, boolean active) {
            this.name = name;
            this.versionId = versionId;
            this.frozenAt = frozenAt;
            this.ltrCount = ltrCount;
            this.rtlCount = rtlCount;
            this.active = active;
        }
    }

    public static class VersionIndex {
        public String current;
        public final List<VersionEntry> versions = new ArrayList<>();
    }
}
