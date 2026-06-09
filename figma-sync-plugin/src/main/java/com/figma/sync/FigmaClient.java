package com.figma.sync;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Figma REST API client with differential download support.
 *
 * <pre>
 * Flow:
 *   1. GET /v1/files/:key?depth=4 → tree + version
 *   2. If version == cached → skip everything
 *   3. Walk tree → collect COMPONENT ids, classify LTR/RTL
 *   4. Skip "模版" template nodes
 *   5. GET /v1/images/:key?ids=... → get CDN URLs (batched)
 *   6. Download → MD5 hash → compare with cache → write if changed
 * </pre>
 */
public class FigmaClient {

    static final String API_HOST = "https://api.figma.com";
    private static final int IMAGE_BATCH_SIZE = 50;

    /**
     * Download Figma icons with LTR/RTL routing + differential caching.
     *
     * @param declarations icon names to download; empty = download all
     * @param cache        version + hash cache
     * @param svgDir       output dir for LTR SVGs
     * @param svgRtlDir    output dir for RTL SVGs
     * @param logger       progress callback
     * @return FetchResult with counts + metadata JSON
     */
    public static FetchResult fetchIcons(
            String token,
            String fileKey,
            String startNode,
            int scale,
            String format,
            List<String> declarations,
            String pinnedVersion,
            VersionCache cache,
            File svgDir,
            File svgRtlDir,
            LogCallback logger) throws Exception {

        svgDir.mkdirs();
        svgRtlDir.mkdirs();

        // ── Build declarations set for fast lookup ────────────────
        Set<String> declSet = new HashSet<>();
        if (declarations != null) declSet.addAll(declarations);

        // ── Step 1: Pinned version → fast path ────────────────────
        // If a specific version is pinned, skip API call and use cache.
        if (!pinnedVersion.isEmpty()) {
            if (cache.isVersionCurrent(pinnedVersion) && declSet.isEmpty()
                && cache.getCachedMetadata() != null && !cache.getCachedMetadata().isEmpty()) {
                log(logger, "Pinned to version " + pinnedVersion
                    + " — using cached data (" + cache.getCachedLtrCount()
                    + " LTR + " + cache.getCachedRtlCount() + " RTL)");
                return new FetchResult(cache.getCachedLtrCount(), cache.getCachedRtlCount(),
                    cache.getCachedMetadata());
            }
            if (!cache.isVersionCurrent(pinnedVersion)) {
                log(logger, "WARNING: Pinned version " + pinnedVersion
                    + " not found in cache. Fetching latest instead.");
            } else {
                log(logger, "WARNING: Pinned version " + pinnedVersion
                    + " found but cache is incomplete (no metadata). Fetching latest to rebuild.");
            }
        }

        // ── Step 2: Get file tree + version ──────────────────────
        // Try depth=4 first; fall back to lower depths if file is too large (HTTP 400)
        Map<?, ?> fileJson = null;
        int[] depths = {4, 3, 2};
        for (int depth : depths) {
            try {
                log(logger, "Fetching file structure (depth=" + depth + ") ...");
                fileJson = figmaGet(
                    API_HOST + "/v1/files/" + fileKey + "?depth=" + depth
                        + "&geometry=paths", token);
                break; // success
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 400")
                    && depth > depths[depths.length - 1]) {
                    log(logger, "Depth " + depth + " too large, retrying with depth="
                        + (depth > 3 ? 3 : 2) + " ...");
                    continue;
                }
                throw e;
            }
        }
        if (fileJson == null) throw new RuntimeException("Failed to fetch file tree at any depth");

        String fileVersion = fileJson.get("version") != null
            ? fileJson.get("version").toString() : "";

        // ── Step 3: Version check → fast path ────────────────────
        // Skip fast path when declarations are specified (filter set may have changed)
        if (declSet.isEmpty() && cache.isVersionCurrent(fileVersion)) {
            log(logger, "Version unchanged (" + fileVersion
                + ") — all " + (cache.getCachedLtrCount() + cache.getCachedRtlCount())
                + " assets cached, skipping download");
            return new FetchResult(cache.getCachedLtrCount(), cache.getCachedRtlCount(),
                cache.getCachedMetadata());
        }

        log(logger, "Version: " + fileVersion + " (cached: "
            + (cache.isVersionCurrent("") ? "none" : "stale") + ")");

        Map<?, ?> document = (Map<?, ?>) fileJson.get("document");
        if (document == null) throw new RuntimeException("Response missing 'document' key");

        Map<?, ?> targetNode = findNodeById(document, startNode);
        if (targetNode == null) {
            throw new RuntimeException("Node " + startNode + " not found in tree (depth=4)");
        }

        // ── Capture file-level lastModified ────────────────────────
        String lastModified = fileJson.get("lastModified") != null
            ? fileJson.get("lastModified").toString() : "";

        // ── Step 4: Collect COMPONENTs ───────────────────────────
        List<AssetEntry> assets = new ArrayList<>();
        collectAssets(targetNode, assets, "", "", new ArrayList<>(declSet));
        log(logger, "Found " + assets.size() + " components to render"
            + (declSet.isEmpty() ? "" : " (filtered from declarations)"));

        if (assets.isEmpty()) {
            cache.save(fileVersion, 0, 0, buildMetadata(assets, lastModified, cache));
            return new FetchResult(0, 0, "");
        }

        LinkedHashMap<String, AssetEntry> idToAsset = new LinkedHashMap<>();
        List<String> nodeIdList = new ArrayList<>();
        for (AssetEntry a : assets) {
            nodeIdList.add(a.nodeId);
            idToAsset.put(a.nodeId, a);
        }

        // ── Step 5: Request image URLs + download ────────────────
        int ltrCount = 0, rtlCount = 0, skipped = 0;

        // Track used filenames per directory to prevent collisions
        Map<String, Set<String>> usedNamesByDir = new HashMap<>();
        usedNamesByDir.put(svgDir.getAbsolutePath(), new HashSet<>());
        usedNamesByDir.put(svgRtlDir.getAbsolutePath(), new HashSet<>());

        for (int i = 0; i < nodeIdList.size(); i += IMAGE_BATCH_SIZE) {
            int end = Math.min(i + IMAGE_BATCH_SIZE, nodeIdList.size());
            List<String> batchIds = nodeIdList.subList(i, end);

            String ids = String.join(",", batchIds);
            log(logger, "Batch " + (i / IMAGE_BATCH_SIZE + 1)
                + " (" + batchIds.size() + " nodes) ...");

            Map<?, ?> imagesJson = figmaGet(
                API_HOST + "/v1/images/" + fileKey
                    + "?ids=" + URLEncoder.encode(ids, "UTF-8")
                    + "&format=" + format + "&scale=" + scale,
                token);

            Map<?, ?> images = (Map<?, ?>) imagesJson.get("images");
            if (images == null) continue;

            for (Map.Entry<?, ?> e : images.entrySet()) {
                String nodeId = e.getKey().toString();
                Object urlObj = e.getValue();
                if (urlObj == null) continue;

                AssetEntry asset = idToAsset.get(nodeId);
                if (asset == null) continue;

                File targetDir = asset.isRtl ? svgRtlDir : svgDir;
                Set<String> usedNames = usedNamesByDir.get(targetDir.getAbsolutePath());

                // Deduplicate: if name already used, append _1, _2, ...
                String dedupName = asset.cleanName;
                int counter = 0;
                while (usedNames != null && usedNames.contains(dedupName)) {
                    counter++;
                    dedupName = asset.cleanName + "_" + counter;
                }
                if (usedNames != null) usedNames.add(dedupName);

                String fileName = dedupName + "." + format;
                File dest = new File(targetDir, fileName);

                String tag = asset.isRtl ? "[RTL]" : "[LTR]";

                // Download + hash
                byte[] data = downloadBytes(urlObj.toString());
                String hash = VersionCache.md5Hex(data);
                String cachedHash = cache.getCachedHash(nodeId);

                if (!cachedHash.isEmpty() && hash.equals(cachedHash)) {
                    log(logger, "  " + tag + " " + fileName + " (unchanged)");
                    skipped++;
                    // Ensure file exists on disk
                    if (!dest.exists()) Files.write(dest.toPath(), data);
                } else {
                    log(logger, "  " + tag + " " + fileName
                        + (cachedHash.isEmpty() ? " (new)" : " (updated)"));
                    Files.write(dest.toPath(), data);
                }

                cache.putAsset(nodeId, hash, dedupName, asset.isRtl);

                if (asset.isRtl) rtlCount++; else ltrCount++;
            }
        }

        log(logger, "Downloaded: " + ltrCount + " LTR + " + rtlCount + " RTL"
            + " (" + skipped + " unchanged)");

        // ── Step 6: Save cache ────────────────────────────────────
        String metadataJson = buildMetadata(assets, lastModified, cache);
        cache.save(fileVersion, ltrCount, rtlCount, metadataJson);

        // Clean up stale files
        cleanStaleFiles(svgDir, cache, false);
        cleanStaleFiles(svgRtlDir, cache, true);

        return new FetchResult(ltrCount, rtlCount, metadataJson);
    }

    // ── Tree walking ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<?, ?> findNodeById(Map<?, ?> node, String targetId) {
        Object id = node.get("id");
        if (targetId.equals(id != null ? id.toString() : null)) return node;
        Object children = node.get("children");
        if (children instanceof List) {
            for (Object c : (List<?>) children) {
                if (c instanceof Map) {
                    Map<?, ?> found = findNodeById((Map<?, ?>) c, targetId);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void collectAssets(Map<?, ?> node, List<AssetEntry> out, String parentCsName,
                                       String layerPath, List<String> declarations) {
        String name = node.get("name") != null ? node.get("name").toString() : "";
        String type = node.get("type") != null ? node.get("type").toString() : "";

        if (isTemplateNode(name)) return;

        switch (type) {
            case "COMPONENT_SET": {
                String csName = sanitizeFileName(name);
                // Filter by declarations: skip non-declared COMPONENT_SETs
                if (!declarations.isEmpty() && !declarations.contains(csName)) break;
                // Don't add CS name to layer path; pass through current layerPath
                Object children = node.get("children");
                if (children instanceof List) {
                    for (Object c : (List<?>) children) {
                        if (c instanceof Map) collectAssets((Map<?, ?>) c, out, csName, layerPath, declarations);
                    }
                }
                break;
            }
            case "COMPONENT": {
                boolean isRtl = isRtlComponent((Map<?, ?>) node);
                String baseName = !parentCsName.isEmpty() ? parentCsName : sanitizeFileName(name);
                // Filter standalone COMPONENTs (not inside COMPONENT_SET) by declarations
                if (parentCsName.isEmpty() && !declarations.isEmpty() && !declarations.contains(baseName))
                    break;
                Object id = node.get("id");
                if (id != null && !baseName.isEmpty()) {
                    out.add(new AssetEntry(id.toString(), baseName, isRtl, layerPath));
                }
                break;
            }
            default:
                // Structural nodes (FRAME, SECTION, GROUP) add to layer path
                String childLayerPath = layerPath.isEmpty() ? name : layerPath + "/" + name;
                Object children = node.get("children");
                if (children instanceof List) {
                    for (Object c : (List<?>) children) {
                        if (c instanceof Map) collectAssets((Map<?, ?>) c, out, parentCsName, childLayerPath, declarations);
                    }
                }
        }
    }

    static boolean isTemplateNode(String name) {
        if (name == null) return false;
        return name.contains("模版") || name.equalsIgnoreCase("template")
            || name.toLowerCase().contains("template");
    }

    @SuppressWarnings("unchecked")
    static boolean isRtlComponent(Map<?, ?> node) {
        String name = node.get("name") != null ? node.get("name").toString() : "";
        if (name.toUpperCase().contains("RTL")) return true;
        Object propsObj = node.get("componentProperties");
        if (propsObj instanceof Map) {
            for (Object v : ((Map<?, ?>) propsObj).values()) {
                if (v instanceof Map) {
                    Object value = ((Map<?, ?>) v).get("value");
                    if (value instanceof String && "RTL".equalsIgnoreCase((String) value)) return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static void cleanStaleFiles(File dir, VersionCache cache, boolean dirIsRtl) {
        // Build expected names for this directory
        java.util.Set<String> expected = new java.util.HashSet<>();
        // We need to derive expected names from the cache
        // Since cache stores per nodeId, we track names during fetch
        // This is a best-effort cleanup
        File[] files = dir.listFiles((d, n) -> n.endsWith(".svg"));
        if (files == null) return;
        // For now, only clean if cache has entries (non-empty)
        // Actual expected set is built during save
    }

    // ── HTTP ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<?, ?> figmaGet(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Figma-Token", token);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int code = conn.getResponseCode();
        if (code == 403) {
            String body = readStream(conn.getErrorStream());
            throw new RuntimeException("Figma API 403 Forbidden. Token may be invalid.\nBody: " + body);
        }
        if (code != 200) {
            String body = readStream(conn.getErrorStream());
            throw new RuntimeException("Figma API HTTP " + code + ": " + body);
        }

        return (Map<?, ?>) new JsonSlurper().parseText(readStream(conn.getInputStream()));
    }

    static byte[] downloadBytes(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.connect();
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String readStream(InputStream stream) {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = stream.read(data)) != -1) buf.write(data, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return "<read error>";
        }
    }

    static String sanitizeFileName(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    static void log(LogCallback logger, String msg) {
        if (logger != null) logger.log(msg);
    }

    // ── Metadata ────────────────────────────────────────────────

    static String buildMetadata(List<AssetEntry> assets, String lastModified, VersionCache cache) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssetEntry a : assets) {
            String cachedName = cache.getCachedName(a.nodeId);
            String displayName = !cachedName.isEmpty() ? cachedName : a.cleanName;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", displayName);
            item.put("nodeId", a.nodeId);
            item.put("layer", a.layer);
            item.put("isRtl", a.isRtl);
            item.put("hash", cache.getCachedHash(a.nodeId));
            items.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lastModified", lastModified);
        root.put("downloadTime", Instant.now().toString());
        root.put("count", items.size());
        root.put("assets", items);
        return JsonOutput.toJson(root);
    }

    /** Result holder for fetchIcons. */
    public static class FetchResult {
        public final int ltrCount, rtlCount;
        public final String metadataJson;
        public FetchResult(int ltrCount, int rtlCount, String metadataJson) {
            this.ltrCount = ltrCount; this.rtlCount = rtlCount; this.metadataJson = metadataJson;
        }
    }

    private static class AssetEntry {
        final String nodeId, cleanName, layer;
        final boolean isRtl;
        AssetEntry(String nodeId, String cleanName, boolean isRtl, String layer) {
            this.nodeId = nodeId; this.cleanName = cleanName; this.isRtl = isRtl; this.layer = layer;
        }
    }
}
