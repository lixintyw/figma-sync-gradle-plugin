package com.figma.sync;

import groovy.json.JsonSlurper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-level cache for Figma asset downloads.
 *
 * Level 1: Figma file version — if unchanged, skip all downloads.
 * Level 2: Per-asset MD5 hash — if hash matches cached, skip file write.
 *
 * Cache file format (.figma-cache.json):
 * <pre>
 * {
 *   "version": "1234567890",
 *   "assets": {
 *     "6:642": {"h": "abc123", "n": "icon_quick_nor", "r": false}
 *   }
 * }
 * </pre>
 */
public class VersionCache {

    private final File cacheFile;

    // Loaded cache data
    private String cachedVersion = "";
    private int cachedLtrCount = 0;
    private int cachedRtlCount = 0;
    private String cachedMetadata = "";
    private final LinkedHashMap<String, CacheEntry> cachedAssets = new LinkedHashMap<>();

    public VersionCache(File cacheFile) {
        this.cacheFile = cacheFile;
        load();
    }

    /** Check if the given Figma file version matches the cached version. */
    public boolean isVersionCurrent(String fileVersion) {
        return !fileVersion.isEmpty() && fileVersion.equals(cachedVersion);
    }

    /** Get cached LTR count (valid when version is current). */
    public int getCachedLtrCount() { return cachedLtrCount; }

    /** Get cached RTL count (valid when version is current). */
    public int getCachedRtlCount() { return cachedRtlCount; }

    /** Get cached metadata JSON (valid when version is current). */
    public String getCachedMetadata() { return cachedMetadata; }

    /** Get the cached hash for a nodeId, or empty string if not cached. */
    public String getCachedHash(String nodeId) {
        CacheEntry entry = cachedAssets.get(nodeId);
        return entry != null ? entry.hash : "";
    }

    /** Get the cached name for a nodeId, or empty string if not cached. */
    public String getCachedName(String nodeId) {
        CacheEntry entry = cachedAssets.get(nodeId);
        return entry != null ? entry.name : "";
    }

    /** Put or update a cache entry for a node. */
    public void putAsset(String nodeId, String hash, String cleanName, boolean isRtl) {
        cachedAssets.put(nodeId, new CacheEntry(hash, cleanName, isRtl));
    }

    /** Save cache to disk with the given version and counts. */
    public void save(String fileVersion, int ltrCount, int rtlCount, String metadataJson) {
        this.cachedMetadata = metadataJson != null ? metadataJson : "";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"version\": \"").append(esc(fileVersion)).append("\",\n");
            sb.append("  \"ltrCount\": ").append(ltrCount).append(",\n");
            sb.append("  \"rtlCount\": ").append(rtlCount).append(",\n");
            if (!this.cachedMetadata.isEmpty()) {
                // Embed metadata directly for fast-path retrieval
                sb.append("  \"metadata\": ").append(this.cachedMetadata).append(",\n");
            }
            sb.append("  \"assets\": {\n");
            int i = 0;
            for (Map.Entry<String, CacheEntry> e : cachedAssets.entrySet()) {
                CacheEntry ce = e.getValue();
                sb.append("    \"").append(esc(e.getKey())).append("\": {");
                sb.append("\"h\":\"").append(esc(ce.hash)).append("\",");
                sb.append("\"n\":\"").append(esc(ce.name)).append("\",");
                sb.append("\"r\":").append(ce.rtl ? "true" : "false");
                sb.append("}");
                if (++i < cachedAssets.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }\n");
            sb.append("}\n");
            Files.write(cacheFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Cache write failure is non-fatal
        }
    }

    /** Compute MD5 hex string for binary data. */
    public static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    // ── internal ──

    @SuppressWarnings("unchecked")
    private void load() {
        if (!cacheFile.exists()) return;
        try {
            String json = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
            Map<?, ?> root = (Map<?, ?>) new JsonSlurper().parseText(json);

            Object v = root.get("version");
            if (v != null) cachedVersion = v.toString();

            Object l = root.get("ltrCount");
            Object r = root.get("rtlCount");
            if (l != null) cachedLtrCount = Integer.parseInt(l.toString());
            if (r != null) cachedRtlCount = Integer.parseInt(r.toString());

            Object meta = root.get("metadata");
            if (meta != null) cachedMetadata = meta.toString();

            Object assets = root.get("assets");
            if (assets instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) assets).entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<?, ?> entry = (Map<?, ?>) e.getValue();
                        String h = entry.get("h") != null ? entry.get("h").toString() : "";
                        String n = entry.get("n") != null ? entry.get("n").toString() : "";
                        boolean rt = "true".equals(entry.get("r") != null ? entry.get("r").toString() : "");
                        cachedAssets.put(e.getKey().toString(), new CacheEntry(h, n, rt));
                    }
                }
            }
        } catch (Exception ignored) {
            // Corrupt cache — start fresh
            cachedVersion = "";
            cachedAssets.clear();
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class CacheEntry {
        final String hash;
        final String name;
        final boolean rtl;

        CacheEntry(String hash, String name, boolean rtl) {
            this.hash = hash;
            this.name = name;
            this.rtl = rtl;
        }
    }
}
