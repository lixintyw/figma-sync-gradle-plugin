#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
# Local CI Simulation: Figma Sync + Change Detection
#
# Runs the same steps as the CI workflow, for local testing:
#   1. Record pre-sync cache state
#   2. Run syncFigmaTokens + syncFigmaIcons
#   3. Detect changes
#   4. Output summary
#
# Usage:
#   bash scripts/figma-ci-check.sh [--module <name>] [--freeze <version>]
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

MODULE=""
FREEZE_VERSION=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --module) MODULE="$2"; shift 2 ;;
        --freeze) FREEZE_VERSION="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

echo "════════════════════════════════════════════════════════"
echo "  Figma CI Check (Local Simulation)"
echo "  Project: $(pwd)"
echo "  Module:  ${MODULE:-all}"
echo "════════════════════════════════════════════════════════"
echo

# ── Step 1: Record pre-sync state ─────────────────────────────────
echo "── Step 1: Recording pre-sync state ──"
CACHE_FILE="build/figma/.figma-cache.json"
if [ -f "$CACHE_FILE" ]; then
    if command -v jq &>/dev/null; then
        PREV_VERSION=$(jq -r '.version // "none"' "$CACHE_FILE")
        PREV_COUNT=$(jq -r '.assets | length // 0' "$CACHE_FILE")
    else
        PREV_VERSION="unknown"
        PREV_COUNT="0"
    fi
    echo "  Previous version: $PREV_VERSION ($PREV_COUNT assets)"
else
    PREV_VERSION="none"
    PREV_COUNT="0"
    echo "  No previous cache found (first sync)"
fi
echo

# ── Step 2: Sync tokens ───────────────────────────────────────────
echo "── Step 2: Syncing Figma tokens ──"
./gradlew syncFigmaTokens --no-daemon 2>&1 | tail -5
COLORS_COUNT=$(grep -c '<color' app/src/main/res/values/figma_colors.xml 2>/dev/null || echo 0)
echo "  Colors synced: $COLORS_COUNT"
echo

# ── Step 3: Sync icons ────────────────────────────────────────────
echo "── Step 3: Syncing Figma icons ──"
if [ -n "$MODULE" ]; then
    TASK="syncFigmaIcons_${MODULE}"
else
    TASK="syncFigmaIcons"
fi
echo "  Task: $TASK"
./gradlew "$TASK" --no-daemon 2>&1 | tail -10
echo

# ── Step 4: Record post-sync state ────────────────────────────────
echo "── Step 4: Recording post-sync state ──"
if [ -f "$CACHE_FILE" ]; then
    NEW_VERSION=$(jq -r '.version // "none"' "$CACHE_FILE" 2>/dev/null || echo "unknown")
    NEW_COUNT=$(jq -r '.assets | length // 0' "$CACHE_FILE" 2>/dev/null || echo "0")
    echo "  New version: $NEW_VERSION ($NEW_COUNT assets)"
else
    NEW_VERSION="none"
    NEW_COUNT="0"
    echo "  No cache file found"
fi
echo

# ── Step 5: Change summary ────────────────────────────────────────
echo "── Step 5: Change summary ──"
bash .github/scripts/figma-change-summary.sh \
    "$PREV_VERSION" "$NEW_VERSION" "$PREV_COUNT" "$NEW_COUNT"
echo

# ── Step 6: Git status ────────────────────────────────────────────
echo "── Step 6: Git change detection ──"
if git diff --quiet && git diff --cached --quiet; then
    echo "  No file changes detected. Figma is up to date."
else
    echo "  Changes detected:"
    git diff --stat
fi
echo

# ── Step 7: Optional freeze ───────────────────────────────────────
if [ -n "$FREEZE_VERSION" ]; then
    echo "── Step 7: Freezing version '$FREEZE_VERSION' ──"
    ./gradlew figmaFreezeVersion -PfreezeName="$FREEZE_VERSION" --no-daemon 2>&1 | tail -3
fi

echo
echo "════════════════════════════════════════════════════════"
echo "  CI Check Complete"
echo "════════════════════════════════════════════════════════"
