package com.figma.sync;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Figma Sync Gradle Plugin.
 *
 * Registers the {@code figmaSync} extension and resource-specific tasks:
 * <ul>
 *   <li>{@code syncFigmaIcons} — download icons from Figma, convert to VectorDrawable</li>
 *   <li>{@code syncFigmaColors} — stub (coming in Week 2)</li>
 *   <li>{@code syncFigmaFonts} — stub (coming in Week 3)</li>
 *   <li>{@code syncFigmaAll} — runs all enabled syncers (lifecycle task)</li>
 * </ul>
 *
 * <pre>
 * figmaSync {
 *     token = "figd_xxx"
 *     fileKey = "G4GyegR4f1uHsW5ZdBuOaY"
 *     icons {
 *         enabled = true
 *         startNode = "14832:59978"
 *         scale = 2
 *     }
 * }
 * </pre>
 */
public class FigmaSyncPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // ── Register extension ──────────────────────────────────
        FigmaSyncExtension extension = project.getExtensions()
            .create("figmaSync", FigmaSyncExtension.class);

        extension.getToken().convention(readTokenFromLocalProperties(project));
        extension.getFileKey().convention("");

        // ── Register tasks ──────────────────────────────────────

        // Icons task(s) — supports single-task and multi-module modes
        registerIconTasks(project, extension);

        // Design tokens task
        project.getTasks().register("syncFigmaTokens", SyncTokensTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Sync Figma design tokens → colors.xml + token_bindings.json");

            task.getToken().set(extension.getToken());
            task.getFileKey().set(extension.getFileKey());
            task.getExtractTokens().convention(
                project.provider(() -> extension.getIcons().extractTokens));
            task.getOutput().convention(
                project.provider(() -> extension.getTokens().output));
            task.getChainDownload().convention(
                project.provider(() -> extension.getTokens().chainDownload));
            task.getCollections().convention(
                project.provider(() -> extension.getTokens().collections));

            task.getColorsXmlFile().convention(
                project.getLayout().getProjectDirectory().file("src/main/res/values/figma_colors.xml"));
            task.getBindingsJsonFile().convention(
                project.getLayout().getProjectDirectory().file("src/main/assets/token_bindings.json"));

            task.onlyIf(t -> extension.getTokens().enabled || extension.getIcons().extractTokens);
        });

        // Colors task (backward compat: delegates to syncFigmaTokens)
        project.getTasks().register("syncFigmaColors", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Sync Figma color styles → colors.xml");
            task.dependsOn("syncFigmaTokens");
        });

        // Fonts task (stub for Week 3)
        project.getTasks().register("syncFigmaFonts", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Sync Figma fonts → res/font/ (coming soon)");
            task.doLast(t -> {
                project.getLogger().lifecycle("[Figma] Font sync not yet implemented.");
            });
        });

        // ── Version management tasks ─────────────────────────────

        // figmaFreezeVersion: snapshot current cache as named version
        project.getTasks().register("figmaFreezeVersion", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Freeze current Figma cache as a named HMI version");
            task.doLast(t -> {
                String versionName = project.hasProperty("freezeName")
                    ? project.property("freezeName").toString() : null;
                if (versionName == null || versionName.isEmpty()) {
                    project.getLogger().lifecycle(
                        "[Figma] Usage: ./gradlew figmaFreezeVersion -PfreezeName=\"HMI v2.1\"");
                    return;
                }
                File cacheFile = project.getLayout().getBuildDirectory()
                    .file("figma/.figma-cache.json").get().getAsFile();
                try {
                    VersionManager vm = new VersionManager(project.getBuildDir());
                    VersionManager.VersionEntry entry = vm.freeze(versionName, cacheFile);
                    project.getLogger().lifecycle("[Figma] Version frozen: {} ({} icons)",
                        entry.name, entry.ltrCount + entry.rtlCount);
                } catch (Exception e) {
                    project.getLogger().error("[Figma] Freeze failed: {}", e.getMessage());
                }
            });
        });

        // figmaRestoreVersion: restore a frozen version
        project.getTasks().register("figmaRestoreVersion", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Restore a frozen Figma version's cache");
            task.doLast(t -> {
                String versionName = project.hasProperty("freezeName")
                    ? project.property("freezeName").toString() : null;
                if (versionName == null || versionName.isEmpty()) {
                    project.getLogger().lifecycle(
                        "[Figma] Usage: ./gradlew figmaRestoreVersion -PfreezeName=\"HMI v2.0\"");
                    return;
                }
                File cacheFile = project.getLayout().getBuildDirectory()
                    .file("figma/.figma-cache.json").get().getAsFile();
                try {
                    VersionManager vm = new VersionManager(project.getBuildDir());
                    VersionManager.VersionEntry entry = vm.restore(versionName, cacheFile);
                    project.getLogger().lifecycle("[Figma] Restored to {} (version: {}, {} icons). "
                        + "Next build will use these cached assets.",
                        entry.name, entry.versionId, entry.ltrCount + entry.rtlCount);
                } catch (Exception e) {
                    project.getLogger().error("[Figma] Restore failed: {}", e.getMessage());
                }
            });
        });

        // figmaListVersions: show all frozen versions
        project.getTasks().register("figmaListVersions", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("List all frozen Figma versions");
            task.doLast(t -> {
                VersionManager vm = new VersionManager(project.getBuildDir());
                VersionManager.VersionIndex index = vm.listVersions();
                if (index.versions.isEmpty()) {
                    project.getLogger().lifecycle("[Figma] No frozen versions yet. "
                        + "Use figmaFreezeVersion -Pname=\"...\" to create one.");
                    return;
                }
                project.getLogger().lifecycle("[Figma] Frozen versions:");
                for (VersionManager.VersionEntry v : index.versions) {
                    String marker = v.name.equals(index.current) ? " ← current" : "";
                    project.getLogger().lifecycle("  {}  |  {}  |  {} icons  |  {} {}",
                        v.name, v.versionId, v.ltrCount + v.rtlCount, v.frozenAt, marker);
                }
            });
        });

        // figmaDiffVersions: compare two frozen versions
        project.getTasks().register("figmaDiffVersions", DefaultTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Compare two frozen Figma versions");
            task.doLast(t -> {
                String from = project.hasProperty("diffFrom")
                    ? project.property("diffFrom").toString() : null;
                String to = project.hasProperty("diffTo")
                    ? project.property("diffTo").toString() : null;
                if (from == null || to == null) {
                    project.getLogger().lifecycle(
                        "[Figma] Usage: ./gradlew figmaDiffVersions -PdiffFrom=\"HMI v2.0\" -PdiffTo=\"HMI v2.1\"");
                    return;
                }
                try {
                    VersionManager vm = new VersionManager(project.getBuildDir());
                    Map<String, Object> diff = vm.diff(from, to);
                    project.getLogger().lifecycle("[Figma] Diff: {} → {}", from, to);

                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> added = (List<Map<String, String>>) diff.get("added");
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> removed = (List<Map<String, String>>) diff.get("removed");
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> modified = (List<Map<String, String>>) diff.get("modified");

                    project.getLogger().lifecycle("  + {} added, - {} removed, ~ {} modified, = {} unchanged",
                        added.size(), removed.size(), modified.size(), diff.get("unchanged"));

                    for (Map<String, String> a : added) {
                        project.getLogger().lifecycle("  + {}", a.get("name"));
                    }
                    for (Map<String, String> r : removed) {
                        project.getLogger().lifecycle("  - {}", r.get("name"));
                    }
                    for (Map<String, String> m : modified) {
                        project.getLogger().lifecycle("  ~ {}", m.get("name"));
                    }
                } catch (Exception e) {
                    project.getLogger().error("[Figma] Diff failed: {}", e.getMessage());
                }
            });
        });

        // Lifecycle task: runs all enabled syncers
        project.getTasks().register("syncFigmaAll", task -> {
            task.setGroup("figma");
            task.setDescription("Run all enabled Figma sync tasks");
            TaskContainer tasks = project.getTasks();
            task.dependsOn(tasks.named("syncFigmaIcons"));
            task.dependsOn(tasks.named("syncFigmaTokens"));
            task.dependsOn(tasks.named("syncFigmaFonts"));
        });

        // ── Hook into preBuild ──────────────────────────────────
        project.getTasks().matching(t -> t.getName().equals("preBuild"))
            .configureEach(t -> t.dependsOn("syncFigmaIcons"));
    }

    /**
     * Register icon download task(s).
     * <p>
     * Single-task mode (no modules configured):
     *   syncFigmaIcons → downloads from icons.startNode
     * <p>
     * Multi-module mode (modules { } configured):
     *   syncFigmaIcons_<name> → downloads each module independently
     *   syncFigmaIcons        → lifecycle task that depends on all module tasks
     */
    private static void registerIconTasks(Project project, FigmaSyncExtension extension) {
        // Register single syncFigmaIcons for backward compat (no modules)
        project.getTasks().register("syncFigmaIcons", SyncIconsTask.class, task -> {
            task.setGroup("figma");
            task.setDescription("Download Figma icons and convert to Android VectorDrawable");

            task.getToken().set(extension.getToken());
            task.getFileKey().set(extension.getFileKey());
            task.getStartNode().convention(
                project.provider(() -> extension.getIcons().startNode));
            task.getScale().convention(
                project.provider(() -> extension.getIcons().scale));
            task.getDeclarations().convention(
                project.provider(() -> extension.getIcons().declarations));
            task.getPinnedVersion().set(extension.getPinnedVersion());
            task.getFormat().convention("svg");

            task.getSvgDir().convention(
                project.getLayout().getBuildDirectory().dir("figma/svg"));
            task.getSvgRtlDir().convention(
                project.getLayout().getBuildDirectory().dir("figma/svg-rtl"));
            task.getDrawableDir().convention(
                project.getLayout().getProjectDirectory().dir("src/main/res/drawable"));
            task.getDrawableRtlDir().convention(
                project.getLayout().getProjectDirectory().dir("src/main/res/drawable-ldrtl"));

            task.getCacheFile().convention(
                project.getLayout().getBuildDirectory().file("figma/.figma-cache.json"));

            // Only execute if icons are enabled AND no modules configured
            task.onlyIf(t -> extension.getIcons().enabled
                && extension.getModules().isEmpty());
        });

        // ── Module tasks (lazy: configured in afterEvaluate) ─────
        project.afterEvaluate(p -> {
            java.util.Map<String, FigmaSyncExtension.ModuleConfig> modules = extension.getModules();
            if (modules.isEmpty()) return; // single-task mode

            List<TaskProvider<SyncIconsTask>> moduleTasks = new ArrayList<>();

            for (java.util.Map.Entry<String, FigmaSyncExtension.ModuleConfig> entry : modules.entrySet()) {
                String moduleName = entry.getKey();
                FigmaSyncExtension.ModuleConfig mc = entry.getValue();
                if (!mc.enabled) continue;

                String taskName = "syncFigmaIcons_" + moduleName;
                TaskProvider<SyncIconsTask> tp = project.getTasks().register(taskName, SyncIconsTask.class, task -> {
                    task.setGroup("figma");
                    task.setDescription("Download Figma icons for module '" + moduleName + "'");

                    task.getToken().set(extension.getToken());
                    task.getFileKey().set(extension.getFileKey());
                    task.getStartNode().set(mc.startNode);
                    task.getScale().set(mc.scale);
                    task.getPinnedVersion().set(extension.getPinnedVersion());
                    task.getFormat().convention("svg");

                    // Module-specific declarations override global ones
                    if (!mc.declarations.isEmpty()) {
                        task.getDeclarations().set(mc.declarations);
                    } else {
                        task.getDeclarations().convention(
                            project.provider(() -> extension.getIcons().declarations));
                    }

                    // Module-specific output dirs to avoid conflicts
                    String dirPrefix = "figma/" + moduleName;
                    task.getSvgDir().convention(
                        project.getLayout().getBuildDirectory().dir(dirPrefix + "/svg"));
                    task.getSvgRtlDir().convention(
                        project.getLayout().getBuildDirectory().dir(dirPrefix + "/svg-rtl"));
                    task.getDrawableDir().convention(
                        project.getLayout().getProjectDirectory().dir("src/main/res/drawable"));
                    task.getDrawableRtlDir().convention(
                        project.getLayout().getProjectDirectory().dir("src/main/res/drawable-ldrtl"));

                    task.getCacheFile().convention(
                        project.getLayout().getBuildDirectory().file(dirPrefix + "/.figma-cache.json"));

                    task.onlyIf(t2 -> mc.enabled);
                });
                moduleTasks.add(tp);
            }

            // Rewire syncFigmaIcons as lifecycle task for modules
            if (!moduleTasks.isEmpty()) {
                TaskProvider<?> lifecycleTask = project.getTasks().named("syncFigmaIcons");
                // Remove the original SyncIconsTask — replace with a plain lifecycle task
                // Since we can't unregister, make the original no-op and add deps
                lifecycleTask.configure(task -> {
                    task.dependsOn(moduleTasks.stream()
                        .map(tp -> (Object) tp.getName())
                        .toArray());
                });
            }
        });
    }

    /** Read figma.token from rootProject/local.properties. */
    private static String readTokenFromLocalProperties(Project project) {
        try {
            File propsFile = new File(project.getRootProject().getProjectDir(), "local.properties");
            if (!propsFile.exists()) return "";
            String content = new String(java.nio.file.Files.readAllBytes(propsFile.toPath()));
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("figma.token=")) {
                    return line.substring("figma.token=".length()).trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
