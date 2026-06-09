# Figma Sync Plugin — Troubleshooting Guide

## Common Issues

### 1. Figma API 403 Forbidden

**Symptom:** `Figma API 403 Forbidden. Token may be invalid.`

**Causes & fixes:**
- Token expired: Regenerate your Figma personal access token at [Figma Account Settings](https://www.figma.com/settings)
- Token missing scopes: Ensure `file_content:read` scope is enabled. For tokens sync, also enable `file_variables:read`
- Token not set: Add `figma.token=figd_xxx` to `local.properties`

```bash
# Verify token is reachable
curl -H "X-Figma-Token: figd_xxx" https://api.figma.com/v1/me
```

### 2. Figma API HTTP 400 "Request too large"

**Symptom:** Error when fetching file tree.

**Fix:** The plugin automatically falls back from `depth=4` to `depth=3` to `depth=2`. If it still fails at `depth=2`, your Figma file is very large. Consider:
- Using modules to split by Section/Frame
- Reducing the scope of `startNode` to a smaller subtree

### 3. Variables API Returns Empty / 404

**Symptom:** `syncFigmaTokens` produces empty output or fails.

**Causes:**
- **Free Figma plan:** The `/v1/files/:key/variables/local` endpoint requires Enterprise plan.
- **Missing scope:** Token needs `file_variables:read` scope.

**Workaround:** Use the Figma Plugin fallback:
1. Designer exports token bindings via the plugin (see FIGMA_PLUGIN_GUIDE.md)
2. Set `tokenSource = "plugin"` in build config
3. Place the exported JSON at `app/src/main/assets/figma_plugin_export.json`

### 4. Duplicate Resource Names in figma_colors.xml

**Symptom:** "Resource found more than one time" build error.

**Fix:** The plugin automatically appends `_2`, `_3`, etc. to deduplicate names. If you still see errors, check that your Figma variable names are unique after sanitization (the plugin converts names to lowercase, replaces non-alphanumeric with `_`).

### 5. Icons Not Updating After Figma Changes

**Symptom:** `syncFigmaIcons` reports "unchanged" but Figma has new designs.

**Causes:**
- Pinned version: Check if `pinnedVersion` is set in `build.gradle.kts`. Remove it to fetch latest.
- Cache stale: Delete `build/figma/.figma-cache.json` and re-run.

```bash
rm build/figma/.figma-cache.json
rm -rf build/figma/svg build/figma/svg-rtl
./gradlew syncFigmaIcons --rerun-tasks
```

### 6. Module Tasks Not Registered

**Symptom:** `./gradlew syncFigmaIcons_vehicleControl` → "Task not found".

**Fix:** Module tasks are registered in `project.afterEvaluate`. Ensure:
- Module config is inside `modules { }` block (not `icons { }`)
- Module `enabled` is `true`
- Module `startNode` is not empty

### 7. RTL Icons Not Separated

**Symptom:** RTL icons appear in `drawable/` instead of `drawable-ldrtl/`.

**Fix:** The plugin auto-detects RTL by:
1. Component name containing "RTL" (case-insensitive)
2. `componentProperties` value `"RTL"`

Ensure your Figma components follow one of these conventions.

### 8. CI Workflow Failures

**Symptom:** GitHub Actions `figma-sync.yml` workflow fails.

**Common fixes:**
- `FIGMA_TOKEN` secret not set: Add to repository Settings → Secrets → Actions
- Network timeout: Figma API can be slow for large files. The workflow has a 30-minute timeout.
- Check the auto-created GitHub issue for failure details
- Run locally to isolate: `bash scripts/figma-ci-check.sh`

### 9. Version Freeze/Restore Errors

**Symptom:** `figmaFreezeVersion` → "Cache file not found".

**Fix:** Run `syncFigmaIcons` first to populate the cache:
```bash
./gradlew syncFigmaIcons
./gradlew figmaFreezeVersion -PfreezeName="HMI v2.1"
```

### 10. Figma Plugin Export — "No nodes selected"

**Symptom:** Plugin shows error about no selection.

**Fix:** Select COMPONENT, COMPONENT_SET, or INSTANCE nodes in Figma before running the plugin. FRAME/SECTION containers with COMPONENT children also work — the plugin recurses into them.

## Diagnostic Commands

```bash
# Check token validity
curl -H "X-Figma-Token: $FIGMA_TOKEN" https://api.figma.com/v1/me | python3 -m json.tool

# List cached versions
./gradlew figmaListVersions

# Force re-sync (ignore cache)
./gradlew syncFigmaIcons --rerun-tasks --info

# Run CI check locally
bash scripts/figma-ci-check.sh
```

## Getting Help

If these steps don't resolve the issue, collect the following and file a bug report:
1. Gradle output with `--info` flag
2. Figma file structure (node IDs are helpful, skip content)
3. Plugin version and Gradle version
4. Figma plan type (Free / Professional / Enterprise)
