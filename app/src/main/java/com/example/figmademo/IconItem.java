package com.example.figmademo;

/**
 * Data model for a single Figma icon displayed in the list.
 */
public class IconItem {
    public final String name;
    public final String nodeId;
    public final String layer;
    public final boolean isRtl;
    public final String drawableName;   // resolved resource name
    public final int drawableResId;     // resolved resource ID, 0 if not found
    public final String lastModified;   // Figma file lastModified timestamp
    public final String downloadTime;   // local download time

    public IconItem(String name, String nodeId, String layer, boolean isRtl,
                    String drawableName, int drawableResId,
                    String lastModified, String downloadTime) {
        this.name = name;
        this.nodeId = nodeId;
        this.layer = layer;
        this.isRtl = isRtl;
        this.drawableName = drawableName;
        this.drawableResId = drawableResId;
        this.lastModified = lastModified;
        this.downloadTime = downloadTime;
    }

    /** Human-readable edit time from Figma lastModified timestamp. */
    public String formatEditTime() {
        if (lastModified == null || lastModified.isEmpty()) return "";
        // lastModified format: "2026-05-29T10:30:00Z"
        String s = lastModified.replace("T", " ").replace("Z", "");
        if (s.length() > 16) s = s.substring(0, 16);
        return "Figma edited: " + s;
    }
}
