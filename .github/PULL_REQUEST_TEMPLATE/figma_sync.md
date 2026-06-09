## Figma Sync Summary

> Auto-generated PR from the [Figma Sync CI](${{ github.server_url }}/${{ github.repository }}/actions/workflows/figma-sync.yml).

### What changed

<!-- The CI workflow populates this section with change details -->

### Checklist

- [ ] Visual review: check affected drawable XML files for correctness
- [ ] Build check: `./gradlew assembleDebug` passes
- [ ] Token check: `figma_colors.xml` entries match expected design tokens
- [ ] RTL check: RTL variants in `drawable-ldrtl/` are correct

### Rollback

If this sync introduces issues, restore the previous version:

```bash
./gradlew figmaListVersions
./gradlew figmaRestoreVersion -PfreezeName="<previous-version>"
```

### Figma Link

[Open Figma file](https://www.figma.com/file/${{ secrets.FIGMA_FILE_KEY }})
