package com.figma.sync;

import org.gradle.api.provider.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL extension for the figmaSync plugin.
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
 *     modules {
 *         module("vehicle_control") {
 *             enabled = true
 *             startNode = "14832:59978"
 *             scale = 2
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class FigmaSyncExtension {

    /** Figma Personal Access Token. Reads from local.properties if not set. */
    public abstract Property<String> getToken();

    /** Figma file key (from the file URL). */
    public abstract Property<String> getFileKey();

    /**
     * Pin to a specific Figma file version. When set, the plugin uses cached
     * data from this version instead of fetching the latest.
     * Empty = always fetch latest.
     */
    public abstract Property<String> getPinnedVersion();

    /** Icons sync configuration. */
    private final IconConfig icons = new IconConfig();

    public IconConfig getIcons() {
        return icons;
    }

    public void icons(groovy.lang.Closure<?> closure) {
        closure.setDelegate(icons);
        closure.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
        closure.call();
    }

    /** Kotlin DSL support: icons { enabled = true; ... } */
    public void icons(org.gradle.api.Action<IconConfig> action) {
        action.execute(icons);
    }

    /** Design tokens sync configuration. */
    private final TokensConfig tokens = new TokensConfig();

    public TokensConfig getTokens() {
        return tokens;
    }

    public void tokens(groovy.lang.Closure<?> closure) {
        closure.setDelegate(tokens);
        closure.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
        closure.call();
    }

    public void tokens(org.gradle.api.Action<TokensConfig> action) {
        action.execute(tokens);
    }

    // ── Modules DSL ──────────────────────────────────────────────

    /** Module definitions. Key = module task suffix. */
    private final Map<String, ModuleConfig> modules = new LinkedHashMap<>();

    public Map<String, ModuleConfig> getModules() {
        return modules;
    }

    /** Groovy DSL: modules { module("name") { ... } } */
    public void modules(groovy.lang.Closure<?> closure) {
        closure.setDelegate(new ModulesDelegate(this));
        closure.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
        closure.call();
    }

    /** Kotlin DSL: modules { module("name") { ... } } */
    public void modules(org.gradle.api.Action<ModulesDelegate> action) {
        action.execute(new ModulesDelegate(this));
    }

    /** Delegate for the modules { } block. */
    public static class ModulesDelegate {
        private final FigmaSyncExtension ext;
        ModulesDelegate(FigmaSyncExtension ext) { this.ext = ext; }

        public void module(String name, groovy.lang.Closure<?> closure) {
            ModuleConfig mc = ext.modules.computeIfAbsent(name, k -> new ModuleConfig(name));
            closure.setDelegate(mc);
            closure.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
            closure.call();
        }

        public void module(String name, org.gradle.api.Action<ModuleConfig> action) {
            ModuleConfig mc = ext.modules.computeIfAbsent(name, k -> new ModuleConfig(name));
            action.execute(mc);
        }
    }

    // ── Config classes ───────────────────────────────────────────

    /** Icons sub-configuration. Fields are public for Kotlin DSL access. */
    public static class IconConfig {
        public boolean enabled = false;
        public String startNode = "";
        public int scale = 2;
        /**
         * Declared icon names to download.
         * If empty → download all icons.
         * If set → only download icons whose cleanName is in this list.
         * RTL variants are automatically included for each declared name.
         */
        public java.util.List<String> declarations = new java.util.ArrayList<>();
        /** Extract color token bindings from icon components. */
        public boolean extractTokens = false;
    }

    /**
     * Per-module icon sync configuration.
     * Each module maps to a Figma section/frame with its own startNode.
     */
    public static class ModuleConfig {
        public final String name;
        public boolean enabled = true;
        public String startNode = "";
        public int scale = 2;
        /** Module-specific icon declarations. Empty = use global declarations or download all. */
        public java.util.List<String> declarations = new java.util.ArrayList<>();

        public ModuleConfig(String name) {
            this.name = name;
        }
    }

    /** Design tokens sub-configuration. */
    public static class TokensConfig {
        public boolean enabled = false;
        /** Output file name: "figma_colors.xml" (Android values) or "FigmaColors.kt" (Compose). */
        public String output = "figma_colors.xml";
        /** When true, resolving a token also downloads all tokens it references (alias chain). */
        public boolean chainDownload = true;
        /** Specific variable collection names to include. Empty = all collections. */
        public java.util.List<String> collections = new java.util.ArrayList<>();
        /**
         * Data source mode: "rest_api" (default, uses Figma Variables REST API)
         * or "plugin" (reads figma_plugin_export.json from Figma plugin).
         * Use "plugin" when REST API is unavailable (e.g. free Figma plan).
         */
        public String tokenSource = "rest_api";
        /** Path to Figma plugin export JSON, relative to project root. Default: src/main/assets/figma_plugin_export.json */
        public String pluginExportFile = "src/main/assets/figma_plugin_export.json";
    }
}
