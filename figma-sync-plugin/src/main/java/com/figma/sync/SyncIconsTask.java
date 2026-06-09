package com.figma.sync;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle Task: syncFigmaIcons
 *
 * Downloads Figma icons as SVG, converts to Android VectorDrawable XML,
 * and places them in res/drawable/ (LTR) and res/drawable-ldrtl/ (RTL).
 *
 * Supports incremental build: if the Figma file version hasn't changed,
 * the task is skipped entirely.
 */
public abstract class SyncIconsTask extends DefaultTask {

    /** Figma Personal Access Token. */
    @Input
    public abstract Property<String> getToken();

    /** Figma file key. */
    @Input
    public abstract Property<String> getFileKey();

    /** Starting node ID in the Figma tree. */
    @Input
    public abstract Property<String> getStartNode();

    /** Render scale (1, 2, 3, 4). */
    @Input
    public abstract Property<Integer> getScale();

    /** Output format: "svg" or "png". */
    @Input
    public abstract Property<String> getFormat();

    /** Declared icon names to download. Empty = download all. */
    @Input
    public abstract ListProperty<String> getDeclarations();

    /** Pin to a specific Figma version. Empty = always fetch latest. */
    @Input
    @Optional
    public abstract Property<String> getPinnedVersion();

    /** Cache file for version + hash tracking (internal, not an up-to-date input). */
    @Internal
    public abstract RegularFileProperty getCacheFile();

    /** Intermediate SVG output directory (LTR). */
    @OutputDirectory
    public abstract DirectoryProperty getSvgDir();

    /** Intermediate SVG output directory (RTL). */
    @OutputDirectory
    public abstract DirectoryProperty getSvgRtlDir();

    /** Final VectorDrawable output directory (LTR). */
    @OutputDirectory
    public abstract DirectoryProperty getDrawableDir();

    /** Final VectorDrawable output directory (RTL). */
    @OutputDirectory
    public abstract DirectoryProperty getDrawableRtlDir();

    @TaskAction
    public void sync() throws Exception {
        String token = getToken().get();
        String fileKey = getFileKey().get();
        String startNode = getStartNode().get();
        int scale = getScale().get();
        String format = getFormat().get();
        List<String> declarations = getDeclarations().getOrElse(new ArrayList<>());
        String pinnedVersion = getPinnedVersion().getOrElse("");

        File svgDir = getSvgDir().get().getAsFile();
        File svgRtlDir = getSvgRtlDir().get().getAsFile();
        File drawableDir = getDrawableDir().get().getAsFile();
        File drawableRtlDir = getDrawableRtlDir().get().getAsFile();
        File cacheFile = getCacheFile().get().getAsFile();

        // Assets metadata output
        File assetsDir = new File(getProject().getProjectDir(), "src/main/assets");
        assetsDir.mkdirs();

        LogCallback logger = msg -> getLogger().lifecycle("[Figma] {}", msg);

        // ── Load cache ──────────────────────────────────────────
        VersionCache cache = new VersionCache(cacheFile);

        // ── Download ────────────────────────────────────────────
        FigmaClient.FetchResult result = FigmaClient.fetchIcons(
            token, fileKey, startNode, scale, format, declarations, pinnedVersion, cache, svgDir, svgRtlDir, logger);

        int ltrCount = result.ltrCount;
        int rtlCount = result.rtlCount;
        getLogger().lifecycle("[Figma] Downloaded {} LTR + {} RTL SVG assets", ltrCount, rtlCount);

        // ── Write metadata JSON to assets ───────────────────────
        if (result.metadataJson != null && !result.metadataJson.isEmpty()) {
            File metadataFile = new File(assetsDir, "figma_assets.json");
            java.nio.file.Files.write(metadataFile.toPath(),
                result.metadataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            getLogger().lifecycle("[Figma] Metadata written → {}", metadataFile.getPath());
        }

        // ── Convert SVG → VectorDrawable ────────────────────────
        int vdCount = SvgToVectorConverter.convertAll(svgDir, drawableDir);
        int vdRtlCount = SvgToVectorConverter.convertAll(svgRtlDir, drawableRtlDir);
        getLogger().lifecycle("[Figma] Converted {} LTR → {}", vdCount, drawableDir.getPath());
        getLogger().lifecycle("[Figma] Converted {} RTL → {}", vdRtlCount, drawableRtlDir.getPath());
    }
}
