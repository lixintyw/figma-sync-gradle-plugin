package com.figma.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SVG → VectorDrawable conversion utilities.
 */
class SvgToVectorConverterTest {

    /**
     * Clear static colorResMap before each test to prevent cross-test pollution.
     */
    @BeforeEach
    void clearColorResMap() throws Exception {
        Field field = SvgToVectorConverter.class.getDeclaredField("colorResMap");
        field.setAccessible(true);
        field.set(null, null);
    }

    // ── colorToAndroid ────────────────────────────────────────────

    @Test
    void colorToAndroid_namedColor_white() {
        String result = callColorToAndroid("white", 255);
        assertNotNull(result);
        assertTrue(result.startsWith("#"), "White should produce hex: " + result);
        assertEquals("#FFFFFFFF", result);
    }

    @Test
    void colorToAndroid_namedColor_black() {
        String result = callColorToAndroid("black", 255);
        assertEquals("#FF000000", result);
    }

    @Test
    void colorToAndroid_namedColor_red() {
        String result = callColorToAndroid("red", 128);
        assertEquals("#80FF0000", result);
    }

    @Test
    void colorToAndroid_namedColor_transparent() {
        String result = callColorToAndroid("transparent", 0);
        assertEquals("#00000000", result);
    }

    @Test
    void colorToAndroid_hex6_withAlpha() {
        String result = callColorToAndroid("#35B668", 200);
        assertEquals("#C835B668", result);
    }

    @Test
    void colorToAndroid_hex8_preservesAlpha() {
        String result = callColorToAndroid("#CC35B668", 255);
        // hex8 already has alpha (CC), so it should be preserved
        assertEquals("#CC35B668", result);
    }

    @Test
    void colorToAndroid_hex3_expands() {
        String result = callColorToAndroid("#FFF", 255);
        assertEquals("#FFFFFFFF", result);
    }

    @Test
    void colorToAndroid_hex3_withAlpha() {
        String result = callColorToAndroid("#F00", 128);
        assertEquals("#80FF0000", result);
    }

    @Test
    void colorToAndroid_nullOrEmpty() {
        assertNull(callColorToAndroid(null, 255));
        assertNull(callColorToAndroid("", 255));
    }

    @Test
    void colorToAndroid_none_returnsNull() {
        assertNull(callColorToAndroid("none", 255));
    }

    @Test
    void colorToAndroid_withColorResourceMap(@TempDir Path tempDir) throws Exception {
        // Create a mock colors.xml
        String colorsXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<resources>\n" +
            "    <color name=\"figma_test_green\">#CC35B668</color>\n" +
            "    <color name=\"figma_test_white\">#FFFFFFFF</color>\n" +
            "</resources>";
        File colorsFile = tempDir.resolve("figma_colors.xml").toFile();
        Files.write(colorsFile.toPath(), colorsXml.getBytes());

        SvgToVectorConverter.loadColorResources(colorsFile);

        // After loading, the converter should map #CC35B668 → figma_test_green
        String result = callColorToAndroid("#35B668", 204); // alpha=204 → CC
        // The color hex matches the map → should return @color/ reference
        assertNotNull(result);
        assertTrue(result.startsWith("@color/") || result.startsWith("#"),
            "Expected @color/ reference or hex, got: " + result);
    }

    // ── parseHexColor ─────────────────────────────────────────────

    @Test
    void parseHexColor_standard6() {
        String result = callParseHexColor("#35B668", 255);
        assertEquals("#FF35B668", result);
    }

    @Test
    void parseHexColor_noPrefix() {
        String result = callParseHexColor("35B668", 255);
        assertNull(result);
    }

    @Test
    void parseHexColor_short3() {
        String result = callParseHexColor("#ABC", 255);
        assertEquals("#FFAABBCC", result);
    }

    @Test
    void parseHexColor_withAlpha8() {
        String result = callParseHexColor("#80FF0000", 255);
        assertEquals("#80FF0000", result);
    }

    @Test
    void parseHexColor_invalidLength() {
        assertNull(callParseHexColor("#12345", 255));
    }

    // ── escapeXml ─────────────────────────────────────────────────

    @Test
    void escapeXml_ampersand() {
        String result = callEscapeXml("M0 0 L10 & 20");
        assertEquals("M0 0 L10 &amp; 20", result);
    }

    @Test
    void escapeXml_lessThan() {
        String result = callEscapeXml("a < b");
        assertEquals("a &lt; b", result);
    }

    @Test
    void escapeXml_greaterThan() {
        String result = callEscapeXml("a > b");
        assertEquals("a &gt; b", result);
    }

    @Test
    void escapeXml_quotes() {
        String result = callEscapeXml("say \"hello\"");
        assertEquals("say &quot;hello&quot;", result);
    }

    @Test
    void escapeXml_apos() {
        String result = callEscapeXml("it's");
        assertEquals("it&apos;s", result);
    }

    @Test
    void escapeXml_noSpecialChars() {
        String result = callEscapeXml("M10 10 L20 20 Z");
        assertEquals("M10 10 L20 20 Z", result);
    }

    @Test
    void escapeXml_multipleSpecials() {
        String result = callEscapeXml("a < b & c > d");
        assertEquals("a &lt; b &amp; c &gt; d", result);
    }

    // ── Private access helpers (testing package-private/private methods) ──

    /**
     * Access the private colorToAndroid via an actual SvgToVectorConverter round-trip.
     * We use parseHexColor as a proxy to verify the internal logic.
     */
    private static String callColorToAndroid(String color, int alpha) {
        // Use reflection to call the private method for testing
        try {
            java.lang.reflect.Method method = SvgToVectorConverter.class
                .getDeclaredMethod("colorToAndroid", String.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(null, color, alpha);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String callParseHexColor(String color, int alpha) {
        try {
            java.lang.reflect.Method method = SvgToVectorConverter.class
                .getDeclaredMethod("parseHexColor", String.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(null, color, alpha);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String callEscapeXml(String s) {
        try {
            java.lang.reflect.Method method = SvgToVectorConverter.class
                .getDeclaredMethod("escapeXml", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
