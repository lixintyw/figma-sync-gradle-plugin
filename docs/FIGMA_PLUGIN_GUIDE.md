# Figma Token Binding Exporter — Designer Guide

## Overview

The **Token Binding Exporter** Figma plugin extracts color variable bindings from your COMPONENT and INSTANCE nodes. The exported JSON is consumed by the Gradle plugin to generate Android color resources with correct design token references.

## Prerequisites

- Figma Desktop or Web app
- A Figma file with COMPONENT or COMPONENT_SET nodes that use color variables

## Installation

1. In Figma, go to **Plugins → Development → Import plugin from manifest...**
2. Select the `figma-token-exporter/manifest.json` file from your project
3. The plugin appears under **Plugins → Development → Token Binding Exporter**

> **Note:** The plugin has no network access (`networkAccess: none`). All data stays local.

## Usage

### Step 1: Select Nodes

Select the COMPONENT, COMPONENT_SET, or INSTANCE nodes you want to extract bindings from. You can select:
- A single COMPONENT
- Multiple COMPONENTs (Cmd/Ctrl + click)
- A parent FRAME/SECTION (the plugin will recursively find all COMPONENT/INSTANCE children)

### Step 2: Export

1. Run the plugin: **Plugins → Development → Token Binding Exporter**
2. Click **Export Bindings**
3. Review the JSON output
4. Click **Copy to Clipboard**

### Step 3: Save to Project

Paste the JSON into your Android project at:
```
app/src/main/assets/figma_plugin_export.json
```

### Step 4: Configure Gradle

In `app/build.gradle.kts`:
```kotlin
figmaSync {
    tokens {
        enabled = true
        tokenSource = "plugin"  // Use plugin data instead of REST API
    }
}
```

Run `./gradlew syncFigmaTokens` to generate `figma_colors.xml`.

## Output Format

```json
{
  "source": "figma_plugin",
  "exportTime": "2026-06-09T10:00:00Z",
  "fileKey": "G4Gyeg...",
  "fileName": "My Design File",
  "tokens": [
    {
      "name": "primary/green-500",
      "varId": "VariableID:abc123",
      "color": "#FF35B668",
      "resolvedType": "COLOR"
    }
  ],
  "bindings": [
    {
      "componentName": "icon_car_door",
      "nodeId": "1:234",
      "layer": "Icons/Vehicle",
      "fills": [
        {
          "color": "#FF35B668",
          "tokenId": "VariableID:abc123",
          "tokenName": "primary/green-500"
        }
      ]
    }
  ]
}
```

## Token Naming Convention

For best compatibility with the Android resource system:

| Guideline | Example |
|-----------|---------|
| Use semantic names | `primary/green-500` not `color-1` |
| Group by category | `text/...`, `bg/...`, `border/...` |
| Include opacity in name | `primary/white-80` for 80% opacity |
| Avoid special characters | Use `/` and `-` only |

## FAQ

**Q: The plugin shows "No COMPONENT found with bound variables"?**
A: Make sure your selected nodes have fills that use color variables (not hardcoded colors). In Figma, check the Fill panel — the color picker should show a variable name (e.g., `primary/green-500`) instead of a hex value.

**Q: Can I export bindings from a library file?**
A: The plugin works on the current file. For library components, create instances in your file first, then select them for export.

**Q: What if a token is in a Team Library?**
A: The plugin will record the variable ID but may not resolve its name. The token entry will use the variable ID as the name. The designer should define a local variable alias if the name is needed.
