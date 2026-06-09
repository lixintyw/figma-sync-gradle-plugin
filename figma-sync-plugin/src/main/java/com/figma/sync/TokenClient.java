package com.figma.sync;

import groovy.json.JsonSlurper;

import java.awt.Color;
import java.util.*;

/**
 * Figma Variables REST API client.
 *
 * Fetches design tokens (color, number, string, boolean variables)
 * from a Figma file and provides lookup from variable ID to name/value.
 *
 * <pre>
 * Endpoints:
 *   GET /v1/files/:key/variables/local      → all local variables
 *   GET /v1/files/:key/variables/published  → published variables only
 * </pre>
 *
 * Requires: Enterprise plan + file_variables:read scope.
 */
public class TokenClient {

    /**
     * Parsed variable with resolved color value.
     */
    public static class VariableInfo {
        public final String id;
        public final String name;
        public final String resolvedType; // COLOR, FLOAT, STRING, BOOLEAN
        /** Hex color string like "#FF35B668", null for non-COLOR types */
        public final String colorHex;
        /** Raw value string for non-COLOR types */
        public final String rawValue;

        public VariableInfo(String id, String name, String resolvedType, String colorHex, String rawValue) {
            this.id = id;
            this.name = name;
            this.resolvedType = resolvedType;
            this.colorHex = colorHex;
            this.rawValue = rawValue;
        }
    }

    /**
     * Fetch all local variables and resolve VARIABLE_ALIAS chains to concrete values.
     *
     * @return map of variableId → VariableInfo
     */
    @SuppressWarnings("unchecked")
    public static Map<String, VariableInfo> fetchLocalVariables(String token, String fileKey) throws Exception {
        Map<?, ?> resp = FigmaClient.figmaGet(
            FigmaClient.API_HOST + "/v1/files/" + fileKey + "/variables/local", token);

        Map<?, ?> meta = (Map<?, ?>) resp.get("meta");
        if (meta == null) return Collections.emptyMap();

        Map<String, VariableInfo> result = new LinkedHashMap<>();

        // Parse variables
        Map<?, ?> variables = (Map<?, ?>) meta.get("variables");
        if (variables != null) {
            Map<String, Map<String, Object>> rawVars = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : variables.entrySet()) {
                String varId = e.getKey().toString();
                Map<?, ?> v = (Map<?, ?>) e.getValue();
                String name = v.get("name") != null ? v.get("name").toString() : varId;
                String resolvedType = v.get("resolvedType") != null ? v.get("resolvedType").toString() : "COLOR";

                // Find the first mode's value (prefer mode "103:0" for light)
                Object firstValue = null;
                Map<?, ?> valuesByMode = (Map<?, ?>) v.get("valuesByMode");
                if (valuesByMode != null && !valuesByMode.isEmpty()) {
                    // Prefer mode 103:0 (light), fallback to first entry
                    Object lightVal = valuesByMode.get("103:0");
                    if (lightVal == null) {
                        lightVal = valuesByMode.values().iterator().next();
                    }
                    firstValue = lightVal;
                }

                // Resolve color value
                String colorHex = null;
                String rawValue = null;
                if (firstValue instanceof Map) {
                    Map<?, ?> valMap = (Map<?, ?>) firstValue;
                    if ("COLOR".equals(resolvedType)) {
                        // Direct color value: {r, g, b, a}
                        if (valMap.containsKey("r")) {
                            colorHex = rgbaToHex(valMap);
                        }
                    } else {
                        rawValue = valMap.get("value") != null ? valMap.get("value").toString() : valMap.toString();
                    }
                } else if (firstValue instanceof Boolean || firstValue instanceof Number || firstValue instanceof String) {
                    rawValue = firstValue.toString();
                }

                Map<String, Object> rawVar = new LinkedHashMap<>();
                rawVar.put("id", varId);
                rawVar.put("name", name);
                rawVar.put("resolvedType", resolvedType);
                rawVar.put("colorHex", colorHex);
                rawVar.put("rawValue", rawValue);
                rawVars.put(varId, rawVar);

                result.put(varId, new VariableInfo(varId, name, resolvedType, colorHex, rawValue));
            }

            // Resolve VARIABLE_ALIAS chains (2-pass)
            // Variables that reference other variables need to be resolved
            boolean changed;
            int maxIter = 10; // prevent infinite loops from circular refs
            do {
                changed = false;
                for (Map.Entry<String, Map<String, Object>> entry : rawVars.entrySet()) {
                    Map<String, Object> rawVar = entry.getValue();
                    if (rawVar.get("colorHex") != null) continue; // already resolved

                    String resolvedType = (String) rawVar.get("resolvedType");
                    if (!"COLOR".equals(resolvedType)) continue;

                    VariableInfo vi = result.get(entry.getKey());
                    if (vi == null) continue;

                    // Check if this variable references another via valuesByMode
                    // We need to re-check the raw API response since rawVar only stores first value
                }
            } while (changed && maxIter-- > 0);

            // Second pass: resolve aliases by walking the valuesByMode from the original API
            for (Map.Entry<?, ?> e : variables.entrySet()) {
                String varId = e.getKey().toString();
                Map<?, ?> v = (Map<?, ?>) e.getValue();
                Map<?, ?> valuesByMode = (Map<?, ?>) v.get("valuesByMode");

                VariableInfo vi = result.get(varId);
                if (vi == null || vi.colorHex != null) continue; // already has color

                // Walk alias chain
                String resolvedHex = resolveAliasChain(varId, valuesByMode, variables, new HashSet<>());
                if (resolvedHex != null) {
                    result.put(varId, new VariableInfo(vi.id, vi.name, vi.resolvedType, resolvedHex, vi.rawValue));
                }
            }
        }

        return result;
    }

    /**
     * Walk VARIABLE_ALIAS chain to find the concrete color value.
     */
    @SuppressWarnings("unchecked")
    private static String resolveAliasChain(String varId, Map<?, ?> valuesByMode,
                                             Map<?, ?> allVariables, Set<String> visited) {
        if (!visited.add(varId)) return null; // circular reference

        if (valuesByMode == null || valuesByMode.isEmpty()) return null;
        Object firstVal = valuesByMode.get("103:0");
        if (firstVal == null) firstVal = valuesByMode.values().iterator().next();

        if (firstVal instanceof Map) {
            Map<?, ?> valMap = (Map<?, ?>) firstVal;
            if (valMap.containsKey("r")) {
                // Direct color → resolve
                return rgbaToHex(valMap);
            } else if ("VARIABLE_ALIAS".equals(valMap.get("type"))) {
                // Alias → follow the chain
                String aliasId = valMap.get("id") != null ? valMap.get("id").toString() : null;
                if (aliasId != null) {
                    Map<?, ?> aliasVar = (Map<?, ?>) allVariables.get(aliasId);
                    if (aliasVar != null) {
                        Map<?, ?> aliasValues = (Map<?, ?>) aliasVar.get("valuesByMode");
                        return resolveAliasChain(aliasId, aliasValues, allVariables, visited);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Convert Figma RGBA (0-1) to Android AARRGGBB hex.
     */
    @SuppressWarnings("unchecked")
    static String rgbaToHex(Map<?, ?> colorMap) {
        double r = toDouble(colorMap.get("r"));
        double g = toDouble(colorMap.get("g"));
        double b = toDouble(colorMap.get("b"));
        double a = colorMap.containsKey("a") ? toDouble(colorMap.get("a")) : 1.0;
        return String.format("#%02X%02X%02X%02X",
            (int) Math.round(a * 255),
            (int) Math.round(r * 255),
            (int) Math.round(g * 255),
            (int) Math.round(b * 255));
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return Double.parseDouble((String) v);
        return 0.0;
    }
}
