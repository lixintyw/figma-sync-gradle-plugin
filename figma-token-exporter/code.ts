/**
 * Figma Token Binding Exporter — Plugin Sandbox Code
 *
 * Traverses the selected nodes (COMPONENT / COMPONENT_SET / INSTANCE) and extracts
 * fill colors bound to Figma variables (design tokens). Resolves variable names and
 * concrete color values via the Plugin API.
 *
 * Output JSON format (compatible with Gradle plugin's TokenBindingExtractor):
 * {
 *   "source": "figma_plugin",
 *   "exportTime": "2026-06-09T...",
 *   "fileKey": "abc123",
 *   "fileName": "Design File",
 *   "tokens": [
 *     { "name": "primary/green-500", "varId": "VariableID:abc123", "color": "#FF35B668" }
 *   ],
 *   "bindings": [
 *     { "componentName": "icon_car_door", "nodeId": "1:234", "layer": "Icons/Vehicle",
 *       "fills": [{ "color": "#FF35B668", "tokenId": "VariableID:abc123", "tokenName": "primary/green-500" }] }
 *   ]
 * }
 */

/// <reference types="@figma/plugin-typings" />

interface TokenEntry {
  name: string;
  varId: string;
  color: string;
  resolvedType: string;
}

interface FillBinding {
  color: string;
  tokenId: string;
  tokenName: string;
  opacity: number;
}

interface BindingEntry {
  componentName: string;
  nodeId: string;
  layer: string;
  fills: FillBinding[];
}

interface ExportData {
  source: string;
  exportTime: string;
  fileKey: string;
  fileName: string;
  tokens: TokenEntry[];
  bindings: BindingEntry[];
}

// ── Main: show UI ────────────────────────────────────────────────
figma.showUI(__html__, {
  width: 480,
  height: 560,
  title: "Token Binding Exporter"
});

// ── Message handlers ─────────────────────────────────────────────
figma.ui.onmessage = async (msg: { type: string }) => {
  if (msg.type === "export") {
    try {
      const data = await exportTokenBindings();
      figma.ui.postMessage({ type: "result", data: data });
    } catch (err: any) {
      figma.ui.postMessage({ type: "error", message: err.message || String(err) });
    }
  } else if (msg.type === "cancel") {
    figma.closePlugin();
  }
};

// ── Export logic ─────────────────────────────────────────────────

async function exportTokenBindings(): Promise<ExportData> {
  const selection = figma.currentPage.selection;
  if (selection.length === 0) {
    throw new Error("No nodes selected. Select one or more COMPONENT / COMPONENT_SET / INSTANCE nodes.");
  }

  const tokenMap = new Map<string, TokenEntry>();    // varId → token info
  const bindings: BindingEntry[] = [];

  for (const node of selection) {
    await traverseNode(node, "", tokenMap, bindings);
  }

  if (bindings.length === 0) {
    throw new Error("No COMPONENT / COMPONENT_SET / INSTANCE nodes found with bound variables. Did you select the right nodes?");
  }

  const tokens = Array.from(tokenMap.values());

  return {
    source: "figma_plugin",
    exportTime: new Date().toISOString(),
    fileKey: figma.fileKey ?? "",
    fileName: figma.root.name,
    tokens: tokens,
    bindings: bindings
  };
}

/**
 * Recursively walk a node tree, collecting bound-variable fills from
 * COMPONENT, COMPONENT_SET, and INSTANCE nodes.
 */
async function traverseNode(
  node: BaseNode,
  parentLayer: string,
  tokenMap: Map<string, TokenEntry>,
  bindings: BindingEntry[]
): Promise<void> {
  const layerPath = parentLayer ? `${parentLayer}/${node.name}` : node.name;

  // Skip template nodes
  if (isTemplateNode(node.name)) return;

  switch (node.type) {
    case "COMPONENT_SET": {
      // Recurse into variant children
      if ("children" in node) {
        for (const child of (node as ChildrenMixin).children) {
          await traverseNode(child, layerPath, tokenMap, bindings);
        }
      }
      break;
    }

    case "COMPONENT":
    case "INSTANCE": {
      const fills = "fills" in node ? (node as GeometryMixin).fills : [];
      if (Array.isArray(fills) && fills.length > 0) {
        const fillBindings: FillBinding[] = [];

        for (const fill of fills) {
          if (fill.type !== "SOLID" || !fill.boundVariables?.color) continue;

          const varId = fill.boundVariables.color.id;
          const colorHex = rgbaToHex(fill.color);

          // Resolve variable name if not already cached
          let token = tokenMap.get(varId);
          if (!token) {
            try {
              const variable = await figma.variables.getVariableByIdAsync(varId);
              if (variable) {
                token = {
                  name: variable.name,
                  varId: varId,
                  color: colorHex,
                  resolvedType: variable.resolvedType
                };
                tokenMap.set(varId, token);
              }
            } catch {
              // Variable might be in a library — still record with ID
              token = { name: varId, varId: varId, color: colorHex, resolvedType: "COLOR" };
              tokenMap.set(varId, token);
            }
          }

          fillBindings.push({
            color: colorHex,
            tokenId: varId,
            tokenName: token?.name ?? varId,
            opacity: fill.color.a
          });
        }

        if (fillBindings.length > 0) {
          bindings.push({
            componentName: node.name,
            nodeId: node.id,
            layer: parentLayer,
            fills: fillBindings
          });
        }
      }
      break;
    }

    default: {
      // Structural nodes (FRAME, SECTION, GROUP) — recurse into children
      if ("children" in node) {
        for (const child of (node as ChildrenMixin).children) {
          await traverseNode(child, layerPath, tokenMap, bindings);
        }
      }
    }
  }
}

// ── Utilities ────────────────────────────────────────────────────

function isTemplateNode(name: string): boolean {
  return name.includes("模版") || name.toLowerCase().includes("template");
}

function rgbaToHex(color: RGBA): string {
  const r = Math.round(color.r * 255);
  const g = Math.round(color.g * 255);
  const b = Math.round(color.b * 255);
  const a = Math.round(color.a * 255);
  return `#${hex2(a)}${hex2(r)}${hex2(g)}${hex2(b)}`;
}

function hex2(n: number): string {
  return n.toString(16).padStart(2, "0").toUpperCase();
}
