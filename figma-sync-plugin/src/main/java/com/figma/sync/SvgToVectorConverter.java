package com.figma.sync;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Converts Figma SVG files to Android VectorDrawable XML format.
 * Handles: path, rect, circle, g (opacity), fill, stroke, fill-rule.
 */
public class SvgToVectorConverter {

    public static int convertAll(File svgDir, File drawableDir) throws Exception {
        drawableDir.mkdirs();
        File[] svgFiles = svgDir.listFiles((d, name) -> name.endsWith(".svg"));
        if (svgFiles == null) return 0;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        int count = 0;
        for (File svgFile : svgFiles) {
            try {
                String name = svgFile.getName();
                String baseName = name.substring(0, name.length() - 4);
                File outFile = new File(drawableDir, baseName + ".xml");

                // Skip if output XML is already newer than source SVG
                if (outFile.exists() && outFile.lastModified() >= svgFile.lastModified()) {
                    count++;
                    continue;
                }

                String content = new String(Files.readAllBytes(svgFile.toPath()), StandardCharsets.UTF_8);
                String vdXml = convertSvg(content, dbf);
                if (vdXml == null) continue;

                Files.write(outFile.toPath(), vdXml.getBytes(StandardCharsets.UTF_8));
                count++;
            } catch (Exception e) {
                System.err.println("[SvgConverter] Failed: " + svgFile.getName() + " — " + e.getMessage());
            }
        }
        return count;
    }

    private static String convertSvg(String svgContent, DocumentBuilderFactory dbf) {
        try {
            Document svgDoc = dbf.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(svgContent)));
            Element svgRoot = svgDoc.getDocumentElement();

            String viewBox = svgRoot.getAttribute("viewBox");
            if (viewBox.isEmpty()) return null;

            String[] vb = viewBox.trim().split("\\s+");
            if (vb.length < 4) return null;

            String width = svgRoot.getAttribute("width").replaceAll("[^0-9.]", "");
            String height = svgRoot.getAttribute("height").replaceAll("[^0-9.]", "");
            if (width.isEmpty()) width = vb[2];
            if (height.isEmpty()) height = vb[3];

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            xml.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
            xml.append("    android:width=\"").append(width).append("dp\"\n");
            xml.append("    android:height=\"").append(height).append("dp\"\n");
            xml.append("    android:viewportWidth=\"").append(vb[2]).append("\"\n");
            xml.append("    android:viewportHeight=\"").append(vb[3]).append("\">\n");

            NodeList children = svgRoot.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element) {
                    convertElement((Element) child, xml, 1.0, "    ");
                }
            }

            xml.append("</vector>\n");
            return xml.toString();
        } catch (Exception e) {
            System.err.println("[SvgConverter] Parse error: " + e.getMessage());
            return null;
        }
    }

    private static void convertElement(Element el, StringBuilder xml, double parentOpacity, String indent) {
        String tag = el.getTagName().toLowerCase(Locale.ROOT);

        double opacity = parentOpacity;
        String groupOpacity = el.getAttribute("opacity");
        if (!groupOpacity.isEmpty()) {
            try { opacity *= Double.parseDouble(groupOpacity); } catch (NumberFormatException ignored) {}
        }
        String fillOpacity = el.getAttribute("fill-opacity");
        if (!fillOpacity.isEmpty()) {
            try { opacity *= Double.parseDouble(fillOpacity); } catch (NumberFormatException ignored) {}
        }

        switch (tag) {
            case "g": {
                NodeList children = el.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child instanceof Element) {
                        convertElement((Element) child, xml, opacity, indent);
                    }
                }
                break;
            }
            case "path": {
                String d = el.getAttribute("d");
                if (d.isEmpty()) break;
                appendPathElement(xml, d, el, opacity, indent);
                break;
            }
            case "rect": {
                String pathData = rectToPathData(el);
                if (pathData.isEmpty()) break;
                appendPathElement(xml, pathData, el, opacity, indent);
                break;
            }
            case "circle": {
                String pathData = circleToPathData(el);
                if (pathData.isEmpty()) break;
                appendPathElement(xml, pathData, el, opacity, indent);
                break;
            }
        }
    }

    private static void appendPathElement(StringBuilder xml, String pathData, Element el, double opacity, String indent) {
        xml.append(indent).append("<path\n");
        xml.append(indent).append("    android:pathData=\"").append(escapeXml(pathData.trim())).append("\"\n");

        String fill = el.getAttribute("fill");
        if (!fill.isEmpty() && !"none".equalsIgnoreCase(fill)) {
            int alpha = (int) Math.round(opacity * 255);
            String androidFill = colorToAndroid(fill, alpha);
            if (androidFill != null && !androidFill.isEmpty()) {
                xml.append(indent).append("    android:fillColor=\"").append(androidFill).append("\"\n");
            }
        }

        String fillRule = el.getAttribute("fill-rule");
        if (!fillRule.isEmpty()) {
            if ("evenodd".equalsIgnoreCase(fillRule)) {
                xml.append(indent).append("    android:fillType=\"evenOdd\"\n");
            }
        }

        String stroke = el.getAttribute("stroke");
        if (!stroke.isEmpty() && !"none".equalsIgnoreCase(stroke)) {
            String strokeOpacity = el.getAttribute("stroke-opacity");
            double sAlpha = opacity;
            if (!strokeOpacity.isEmpty()) {
                try { sAlpha *= Double.parseDouble(strokeOpacity); } catch (NumberFormatException ignored) {}
            }
            int sAlphaInt = (int) Math.round(sAlpha * 255);
            String androidStroke = colorToAndroid(stroke, sAlphaInt);
            if (androidStroke != null && !androidStroke.isEmpty()) {
                xml.append(indent).append("    android:strokeColor=\"").append(androidStroke).append("\"\n");
            }

            String strokeWidth = el.getAttribute("stroke-width");
            if (!strokeWidth.isEmpty()) {
                xml.append(indent).append("    android:strokeWidth=\"").append(strokeWidth).append("\"\n");
            }
        }

        String strokeLinecap = el.getAttribute("stroke-linecap");
        if (!strokeLinecap.isEmpty()) {
            String cap = strokeLinecap.equalsIgnoreCase("round") ? "round" :
                         strokeLinecap.equalsIgnoreCase("square") ? "square" : "butt";
            xml.append(indent).append("    android:strokeLineCap=\"").append(cap).append("\"\n");
        }

        String strokeLinejoin = el.getAttribute("stroke-linejoin");
        if (!strokeLinejoin.isEmpty()) {
            String join = strokeLinejoin.equalsIgnoreCase("round") ? "round" :
                          strokeLinejoin.equalsIgnoreCase("bevel") ? "bevel" : "miter";
            xml.append(indent).append("    android:strokeLineJoin=\"").append(join).append("\"\n");
        }

        xml.append(indent).append("/>\n");
    }

    private static String rectToPathData(Element rect) {
        try {
            double x = safeDouble(rect.getAttribute("x"));
            double y = safeDouble(rect.getAttribute("y"));
            double w = safeDouble(rect.getAttribute("width"));
            double h = safeDouble(rect.getAttribute("height"));
            double rx = safeDouble(rect.getAttribute("rx"));
            double ry = safeDouble(rect.getAttribute("ry"));
            if (ry == 0) ry = rx;
            if (rx == 0) rx = ry;

            if (w <= 0 || h <= 0) return "";

            if (rx <= 0 || ry <= 0) {
                return String.format(Locale.US, "M%.4f %.4f h%.4f v%.4f h%.4f z", x, y, w, h, -w);
            } else {
                rx = Math.min(rx, w / 2);
                ry = Math.min(ry, h / 2);
                return String.format(Locale.US,
                    "M%.4f %.4f h%.4f a%.4f %.4f 0 0 1 %.4f %.4f v%.4f a%.4f %.4f 0 0 1 %.4f %.4f h%.4f a%.4f %.4f 0 0 1 %.4f %.4f v%.4f a%.4f %.4f 0 0 1 %.4f %.4f z",
                    x + rx, y,
                    w - 2 * rx, rx, ry, rx, ry,
                    h - 2 * ry, rx, ry, -rx, ry,
                    -(w - 2 * rx), rx, ry, -rx, -ry,
                    -(h - 2 * ry), rx, ry, rx, -ry);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String circleToPathData(Element circle) {
        try {
            double cx = safeDouble(circle.getAttribute("cx"));
            double cy = safeDouble(circle.getAttribute("cy"));
            double r = safeDouble(circle.getAttribute("r"));
            if (r <= 0) return "";

            return String.format(Locale.US,
                "M%.4f %.4f a%.4f %.4f 0 1 1 %.4f 0 a%.4f %.4f 0 1 1 %.4f 0 z",
                cx + r, cy, r, r, -2 * r, r, r, 2 * r);
        } catch (Exception e) {
            return "";
        }
    }

    private static double safeDouble(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static String colorToAndroid(String color, int alpha) {
        if (color == null || color.isEmpty()) return null;

        String lc = color.toLowerCase(Locale.ROOT).trim();
        Integer known = KNOWN_COLORS.get(lc);
        if (known != null) {
            int r = (known >> 16) & 0xFF;
            int g = (known >> 8) & 0xFF;
            int b = known & 0xFF;
            return String.format("#%02X%02X%02X%02X", alpha, r, g, b);
        }

        return parseHexColor(color, alpha);
    }

    private static String parseHexColor(String color, int alpha) {
        String hex = color.trim();
        if (!hex.startsWith("#")) return null;

        hex = hex.substring(1);
        int r, g, b, a = alpha;

        if (hex.length() == 3) {
            r = Integer.parseInt(hex.substring(0, 1).repeat(2), 16);
            g = Integer.parseInt(hex.substring(1, 2).repeat(2), 16);
            b = Integer.parseInt(hex.substring(2, 3).repeat(2), 16);
        } else if (hex.length() == 6) {
            r = Integer.parseInt(hex.substring(0, 2), 16);
            g = Integer.parseInt(hex.substring(2, 4), 16);
            b = Integer.parseInt(hex.substring(4, 6), 16);
        } else if (hex.length() == 8) {
            a = Integer.parseInt(hex.substring(0, 2), 16);
            r = Integer.parseInt(hex.substring(2, 4), 16);
            g = Integer.parseInt(hex.substring(4, 6), 16);
            b = Integer.parseInt(hex.substring(6, 8), 16);
        } else {
            return null;
        }

        return String.format("#%02X%02X%02X%02X", a, r, g, b);
    }

    private static final java.util.Map<String, Integer> KNOWN_COLORS = new java.util.HashMap<>();
    static {
        KNOWN_COLORS.put("white",    0xFFFFFF);
        KNOWN_COLORS.put("black",    0x000000);
        KNOWN_COLORS.put("red",      0xFF0000);
        KNOWN_COLORS.put("green",    0x00FF00);
        KNOWN_COLORS.put("blue",     0x0000FF);
        KNOWN_COLORS.put("yellow",   0xFFFF00);
        KNOWN_COLORS.put("cyan",     0x00FFFF);
        KNOWN_COLORS.put("magenta",  0xFF00FF);
        KNOWN_COLORS.put("gray",     0x808080);
        KNOWN_COLORS.put("grey",     0x808080);
        KNOWN_COLORS.put("orange",   0xFFA500);
        KNOWN_COLORS.put("purple",   0x800080);
        KNOWN_COLORS.put("pink",     0xFFC0CB);
        KNOWN_COLORS.put("brown",    0xA52A2A);
        KNOWN_COLORS.put("navy",     0x000080);
        KNOWN_COLORS.put("teal",     0x008080);
        KNOWN_COLORS.put("lime",     0x00FF00);
        KNOWN_COLORS.put("maroon",   0x800000);
        KNOWN_COLORS.put("olive",    0x808000);
        KNOWN_COLORS.put("silver",   0xC0C0C0);
        KNOWN_COLORS.put("aqua",     0x00FFFF);
        KNOWN_COLORS.put("fuchsia",  0xFF00FF);
        KNOWN_COLORS.put("transparent", 0x000000);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
