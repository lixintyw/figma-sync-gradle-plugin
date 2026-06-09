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
 * Tests for TokenClient plugin export parsing and VariableInfo.
 */
class TokenClientTest {

    @Test
    void parsePluginExport_validJson(@TempDir Path tempDir) throws Exception {
        String json = "{\n" +
            "  \"source\": \"figma_plugin\",\n" +
            "  \"exportTime\": \"2026-06-09T10:00:00Z\",\n" +
            "  \"fileKey\": \"G4GyegR4f1uHsW5ZdBuOaY\",\n" +
            "  \"fileName\": \"Design File\",\n" +
            "  \"tokens\": [\n" +
            "    { \"name\": \"primary/green-500\", \"varId\": \"VariableID:abc123\", \"color\": \"#FF35B668\", \"resolvedType\": \"COLOR\" },\n" +
            "    { \"name\": \"neutral/white\", \"varId\": \"VariableID:def456\", \"color\": \"#FFFFFFFF\", \"resolvedType\": \"COLOR\" }\n" +
            "  ],\n" +
            "  \"bindings\": [\n" +
            "    {\n" +
            "      \"componentName\": \"icon_car_door\",\n" +
            "      \"nodeId\": \"1:234\",\n" +
            "      \"layer\": \"Icons/Vehicle\",\n" +
            "      \"fills\": [\n" +
            "        { \"color\": \"#FF35B668\", \"tokenId\": \"VariableID:abc123\", \"tokenName\": \"primary/green-500\" },\n" +
            "        { \"color\": \"#FFFFFFFF\", \"tokenId\": \"VariableID:def456\", \"tokenName\": \"neutral/white\" }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        File jsonFile = tempDir.resolve("plugin_export.json").toFile();
        Files.write(jsonFile.toPath(), json.getBytes());

        TokenClient.PluginExportData data = TokenClient.parsePluginExport(jsonFile);

        // Metadata
        assertEquals("figma_plugin", data.source);
        assertEquals("2026-06-09T10:00:00Z", data.exportTime);
        assertEquals("G4GyegR4f1uHsW5ZdBuOaY", data.fileKey);
        assertEquals("Design File", data.fileName);

        // Tokens
        assertEquals(2, data.tokens.size());
        TokenClient.VariableInfo green = data.tokens.get("VariableID:abc123");
        assertNotNull(green, "Green token should be in map");
        assertEquals("primary/green-500", green.name);
        assertEquals("#FF35B668", green.colorHex);
        assertEquals("COLOR", green.resolvedType);

        TokenClient.VariableInfo white = data.tokens.get("VariableID:def456");
        assertNotNull(white, "White token should be in map");
        assertEquals("#FFFFFFFF", white.colorHex);

        // Bindings
        assertEquals(1, data.bindings.size());
        Map<String, Object> binding = data.bindings.get(0);
        assertEquals("icon_car_door", binding.get("componentName"));
        assertEquals("1:234", binding.get("nodeId"));
        assertEquals("Icons/Vehicle", binding.get("layer"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> fills = (List<Map<String, String>>) binding.get("fills");
        assertEquals(2, fills.size());
        assertEquals("#FF35B668", fills.get(0).get("color"));
        assertEquals("primary/green-500", fills.get(0).get("tokenName"));
    }

    @Test
    void parsePluginExport_emptyFile(@TempDir Path tempDir) throws Exception {
        String json = "{\n" +
            "  \"source\": \"figma_plugin\",\n" +
            "  \"exportTime\": \"\",\n" +
            "  \"fileKey\": \"\",\n" +
            "  \"fileName\": \"\",\n" +
            "  \"tokens\": [],\n" +
            "  \"bindings\": []\n" +
            "}";

        File jsonFile = tempDir.resolve("empty_export.json").toFile();
        Files.write(jsonFile.toPath(), json.getBytes());

        TokenClient.PluginExportData data = TokenClient.parsePluginExport(jsonFile);

        assertEquals(0, data.tokens.size());
        assertEquals(0, data.bindings.size());
    }

    @Test
    void parsePluginExport_fileNotFound() {
        File nonexistent = new File("/tmp/nonexistent_plugin_export.json");
        assertThrows(Exception.class, () -> TokenClient.parsePluginExport(nonexistent));
    }

    @Test
    void variableInfo_constructor() {
        TokenClient.VariableInfo info = new TokenClient.VariableInfo(
            "VariableID:test123", "primary/blue", "COLOR", "#FF0000FF", null);

        assertEquals("VariableID:test123", info.id);
        assertEquals("primary/blue", info.name);
        assertEquals("COLOR", info.resolvedType);
        assertEquals("#FF0000FF", info.colorHex);
        assertNull(info.rawValue);
    }

    @Test
    void variableInfo_nonColorType() {
        TokenClient.VariableInfo info = new TokenClient.VariableInfo(
            "VariableID:num1", "spacing/small", "FLOAT", null, "4.0");

        assertEquals("FLOAT", info.resolvedType);
        assertNull(info.colorHex);
        assertEquals("4.0", info.rawValue);
    }

    @Test
    void computeTokenClosure_directAlias() {
        // Build mock variable map
        // varA → alias to varB → color
        java.util.Map<String, Object> varB = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> valB = new java.util.LinkedHashMap<>();
        valB.put("r", 1.0); valB.put("g", 0.0); valB.put("b", 0.0); valB.put("a", 1.0);
        java.util.Map<String, Object> valuesB = new java.util.LinkedHashMap<>();
        valuesB.put("103:0", valB);
        varB.put("valuesByMode", valuesB);

        java.util.Map<String, Object> varA = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> aliasVal = new java.util.LinkedHashMap<>();
        aliasVal.put("type", "VARIABLE_ALIAS");
        aliasVal.put("id", "VariableID:varB");
        java.util.Map<String, Object> valuesA = new java.util.LinkedHashMap<>();
        valuesA.put("103:0", aliasVal);
        varA.put("valuesByMode", valuesA);

        java.util.Map<String, Object> allVars = new java.util.LinkedHashMap<>();
        allVars.put("VariableID:varA", varA);
        allVars.put("VariableID:varB", varB);

        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        declared.add("VariableID:varA");

        java.util.Set<String> closure = TokenClient.computeTokenClosure(declared, allVars);

        assertEquals(2, closure.size());
        assertTrue(closure.contains("VariableID:varA"));
        assertTrue(closure.contains("VariableID:varB"), "varB should be in closure as alias target");
    }

    @Test
    void computeTokenClosure_noAliases() {
        java.util.Map<String, Object> varA = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> valA = new java.util.LinkedHashMap<>();
        valA.put("r", 1.0); valA.put("g", 1.0); valA.put("b", 1.0); valA.put("a", 1.0);
        java.util.Map<String, Object> valuesA = new java.util.LinkedHashMap<>();
        valuesA.put("103:0", valA);
        varA.put("valuesByMode", valuesA);

        java.util.Map<String, Object> allVars = new java.util.LinkedHashMap<>();
        allVars.put("VariableID:varA", varA);

        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        declared.add("VariableID:varA");

        java.util.Set<String> closure = TokenClient.computeTokenClosure(declared, allVars);

        assertEquals(1, closure.size());
        assertTrue(closure.contains("VariableID:varA"));
    }

    @Test
    void computeTokenClosure_emptyDeclared() {
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        java.util.Set<String> closure = TokenClient.computeTokenClosure(
            declared, new java.util.LinkedHashMap<>());
        assertTrue(closure.isEmpty());
    }
}
