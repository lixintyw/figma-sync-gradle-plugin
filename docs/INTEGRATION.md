# Figma Sync Gradle Plugin — 接入指南

> 版本：1.0.0 | 适用于 Android 项目（AGP 8.x + Gradle 9.x）

---

## 1. 概览

`figma-sync-gradle-plugin` 将 Figma 设计稿中的图标、颜色令牌自动同步到 Android 资源目录，打通「设计 → 代码」链路。

| 能力 | 说明 |
|------|------|
| 图标同步 | Figma COMPONENT → SVG 下载 → VectorDrawable XML |
| 颜色令牌 | Figma Variables API → `colors.xml` + `token_bindings.json` |
| RTL 变体 | 自动识别 RTL 组件，输出到 `drawable-ldrtl/` |
| 增量同步 | version + MD5 两级缓存，未变更资源零网络开销 |
| 模块拆分 | 支持按 Figma Section/Frame 拆分为独立 Task |
| 版本管理 | freeze / restore / diff，HMI 版本可追溯 |
| CI 自动化 | GitHub Actions 定时同步 + 自动 PR |

**接入前提：**
- Figma 账号 + Personal Access Token（[生成地址](https://www.figma.com/settings)）
- token 需勾选 `file_content:read` 范围；颜色令牌同步额外需要 `file_variables:read`（需 Enterprise plan）
- Android 项目使用 Gradle + AGP

---

## 2. 接入步骤

### 2.1 添加插件

**方式一：本地 composite build（推荐开发阶段）**

```
project-root/
├── figma-sync-plugin/          ← 插件源码
│   └── build.gradle.kts
├── app/
│   └── build.gradle.kts
├── settings.gradle.kts
└── build.gradle.kts
```

`settings.gradle.kts`：
```kotlin
pluginManagement {
    includeBuild("figma-sync-plugin")  // composite build
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

`app/build.gradle.kts`：
```kotlin
plugins {
    id("com.android.application")
    id("com.figma.sync")  // 本地插件
}
```

**方式二：Maven 发布（生产环境）**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://your-maven-repo/releases") }
        gradlePluginPortal()
    }
}

// app/build.gradle.kts
plugins {
    id("com.figma.sync") version "1.0.0"
}
```

### 2.2 配置 Figma Token

在项目根目录 `local.properties` 中添加（该文件已加入 `.gitignore`）：

```properties
figma.token=figd_xxxxxxxxxxxxxxxxxxxx
```

> **安全提醒：** 不要将 token 提交到 Git。CI 环境使用 GitHub Secrets → `FIGMA_TOKEN`。

### 2.3 最小化配置

`app/build.gradle.kts`：
```kotlin
figmaSync {
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"  // Figma 文件 URL 中的 key

    icons {
        enabled = true
        startNode = "14832:59978"  // Figma 中图标所在的 Section/Frame 节点 ID
        scale = 2
    }
}
```

Figma 节点 ID 获取方式：在 Figma 中右键 Section/Frame → Copy link → URL 中的 `node-id=14832-59978`，将 `-` 替换为 `:`。

### 2.4 首次同步

```bash
./gradlew syncFigmaIcons
```

输出示例：
```
[Figma] Fetching file structure (depth=4) ...
[Figma] Version: 2361395147552737305 (cached: stale)
[Figma] Found 82 components to render
[Figma] Downloaded 80 LTR + 2 RTL (0 unchanged)
[Figma] Converted 80 LTR → app/src/main/res/drawable/
[Figma] Converted 2 RTL → app/src/main/res/drawable-ldrtl/
```

---

## 3. 完整配置参考

```kotlin
figmaSync {
    // ── 基础配置 ──────────────────────────────────────────
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"
    // pinnedVersion = "2361395147552737305"  // 锁定版本，跳过 API 调用

    // ── 图标同步 ──────────────────────────────────────────
    icons {
        enabled = true
        startNode = "14832:59978"       // 图标 Section 节点 ID
        scale = 2                        // 渲染倍率：1 / 2 / 3 / 4
        extractTokens = true             // 是否同时提取颜色令牌绑定
        declarations = listOf(           // 白名单：只下载声明的图标，空 = 全部
            "icon_car_door",
            "icon_window",
            "icon_low_beam"
        )
    }

    // ── 颜色令牌同步 ──────────────────────────────────────
    tokens {
        enabled = true
        output = "figma_colors.xml"      // 输出文件名
        chainDownload = true             // 令牌链下载：声明 A → 自动下载 A 依赖的全部 token
        collections = listOf(            // 变量集合过滤，空 = 全部
            "Text Color",
            "Color"
        )
        // tokenSource = "plugin"        // 切换为 Figma 插件数据源（降级方案）
        // pluginExportFile = "src/main/assets/figma_plugin_export.json"
    }

    // ── 模块管理（多 Section 拆分） ───────────────────────
    // 配置 modules 后，syncFigmaIcons 自动拆分为独立 Task
    modules {
        module("vehicle_control") {
            enabled = true
            startNode = "14832:59978"
            scale = 2
        }
        module("lighting") {
            enabled = true
            startNode = "14832:60000"
        }
    }
}
```

---

## 4. Gradle Task 清单

| Task | 说明 | 触发方式 |
|------|------|---------|
| `syncFigmaIcons` | 下载图标 → VectorDrawable | `./gradlew syncFigmaIcons` |
| `syncFigmaTokens` | 下载颜色令牌 → `colors.xml` + `token_bindings.json` | `./gradlew syncFigmaTokens` |
| `syncFigmaIcons_<module>` | 按模块同步图标（需配置 modules） | `./gradlew syncFigmaIcons_vehicleControl` |
| `syncFigmaColors` | `syncFigmaTokens` 别名 | `./gradlew syncFigmaColors` |
| `syncFigmaAll` | 运行全部同步 Task | `./gradlew syncFigmaAll` |
| `figmaFreezeVersion` | 冻结当前缓存为命名版本 | `./gradlew figmaFreezeVersion -PfreezeName="HMI v2.1"` |
| `figmaRestoreVersion` | 恢复指定版本的缓存 | `./gradlew figmaRestoreVersion -PfreezeName="HMI v2.0"` |
| `figmaListVersions` | 列出全部已冻结版本 | `./gradlew figmaListVersions` |
| `figmaDiffVersions` | 对比两个版本的差异 | `./gradlew figmaDiffVersions -PdiffFrom="v2.0" -PdiffTo="v2.1"` |

---

## 5. 输出文件

```
app/src/main/
├── res/
│   ├── drawable/               ← LTR 图标 VectorDrawable XML
│   │   ├── icon_car_door.xml
│   │   ├── icon_window.xml
│   │   └── ...
│   ├── drawable-ldrtl/         ← RTL 图标 VectorDrawable XML
│   │   └── icon_car_door.xml
│   └── values/
│       └── figma_colors.xml    ← 设计令牌颜色资源（@color/figma_xxx）
└── assets/
    ├── figma_assets.json       ← 图标元数据（name、nodeId、layer、hash）
    ├── token_bindings.json     ← 图标 → 令牌绑定关系
    └── figma_plugin_export.json ← Figma 插件导出数据（tokenSource=plugin 时使用）
```

**代码中使用示例：**

```kotlin
// 引用图标（VectorDrawable）
imageView.setImageResource(R.drawable.icon_car_door)

// XML 中引用
<ImageView
    android:src="@drawable/icon_car_door"
    app:tint="@color/figma_text_color_icon_1primary_primary_02_80" />

// 引用颜色令牌
view.setBackgroundColor(
    resources.getColor(R.color.figma_color_13bg_common_bg_common_01_100, null)
)
```

---

## 6. 增量同步机制

插件使用两级缓存，避免重复下载：

| 级别 | 缓存键 | 行为 |
|------|--------|------|
| L1 · 版本级 | Figma file version | 版本未变 → 跳过全部下载 |
| L2 · 文件级 | 每张图标的 MD5 | hash 相同 → 跳过文件写入 |

缓存文件：`build/figma/.figma-cache.json`

```json
{
  "version": "2361395147552737305",
  "ltrCount": 80,
  "rtlCount": 2,
  "metadata": { ... },
  "assets": {
    "6:642": { "h": "abc123def456", "n": "icon_quick_nor", "r": false }
  }
}
```

> **注意：** 多人协作时，各开发者本地 cache 独立。建议 CI 作为缓存基准，团队成员首次同步不跳过。

---

## 7. 图标声明（declarations）

声明机制让 App 只下载真正使用的图标，避免资源膨胀：

```kotlin
icons {
    declarations = listOf(
        // 车门
        "icon_car_door",
        "icon_left_door",
        "icon_right_door",
        // 灯光
        "icon_low_beam",
        "icon_high_beam"
    )
}
```

| declarations 值 | 行为 |
|-----------------|------|
| `listOf(...)` 非空 | 只下载列表中匹配的图标 |
| `listOf()` 空 | 下载 startNode 下全部图标 |
| 未配置 | 下载全部 |

> **规则：** 声明的名称匹配 Figma COMPONENT_SET 的 clean name（小写 + 非字母数字替换为 `_`）。RTL 变体自动跟随 LTR 声明。

---

## 8. 版本管理（HMI 交付流程）

```
设计师更新 Figma
      │
      ▼
CI 定时同步（或手动）
      │
      ├── 产物变更？
      │     ├── 是 → 自动 PR
      │     └── 否 → 跳过
      │
      ▼
冻结版本：./gradlew figmaFreezeVersion -PfreezeName="HMI v2.1"
      │
      ▼
归档到 .figma-versions/HMI_v2.1/
      │
      ▼
出包时锁定：pinnedVersion = "2361395147552737305"
```

### 常用操作

```bash
# 冻结当前状态为命名版本
./gradlew figmaFreezeVersion -PfreezeName="HMI v2.1"

# 查看全部版本
./gradlew figmaListVersions
# 输出:
# [Figma] Frozen versions:
#   HMI v2.0  |  2361395147550000000  |  80 icons  |  2026-06-01T10:00:00Z
#   HMI v2.1  |  2361395147552737305  |  82 icons  |  2026-06-05T14:00:00Z  ← current

# 对比版本差异
./gradlew figmaDiffVersions -PdiffFrom="HMI v2.0" -PdiffTo="HMI v2.1"
# 输出:
# [Figma] Diff: HMI v2.0 → HMI v2.1
#   + 2 added, - 0 removed, ~ 1 modified, = 79 unchanged
#   + icon_new_feature
#   + icon_new_feature_rtl
#   ~ icon_car_door

# 回退到旧版本
./gradlew figmaRestoreVersion -PfreezeName="HMI v2.0"
# 下次 ./gradlew syncFigmaIcons 将使用 v2.0 的缓存
```

---

## 9. 多模块拆分

大型项目按 Figma Section 拆分同步，每个模块独立 Task + 独立 Cache：

```kotlin
modules {
    module("vehicle_control") {
        startNode = "14832:59978"  // 车辆控制 Section
        scale = 2
        declarations = listOf("icon_car_door", "icon_window")
    }
    module("lighting") {
        startNode = "14832:60000"  // 灯光 Section
        scale = 2
    }
}
```

```bash
# 只同步车辆控制模块
./gradlew syncFigmaIcons_vehicleControl

# 同步全部模块
./gradlew syncFigmaIcons
```

---

## 10. CI/CD 集成

### GitHub Actions

1. 在 Repo Settings → Secrets → Actions 添加 `FIGMA_TOKEN`

2. 工作流文件 `.github/workflows/figma-sync.yml`（插件已内置模板）

触发方式：

| 触发 | 说明 |
|------|------|
| 定时 | 工作日 09:17（北京时间） |
| 手动 | Actions → Figma Design Sync → Run workflow |

3. CI 行为：

```
检出代码 → 配置 token → syncFigmaTokens → syncFigmaIcons
    → 检测变更 → 有变更 → 创建 PR（含变更摘要）
              → 无变更 → 跳过
```

### 本地预检

```bash
bash scripts/figma-ci-check.sh
bash scripts/figma-ci-check.sh --module vehicle_control
bash scripts/figma-ci-check.sh --freeze "HMI v2.2"
```

---

## 11. Figma 插件（降级方案）

当 REST API 不可用时（免费版 Figma 无 Variables API），使用 Figma 插件提取令牌绑定。

### 设计师操作流程

1. Figma → Plugins → Development → Import plugin from manifest...
2. 选择项目中的 `figma-token-exporter/manifest.json`
3. 选中 COMPONENT 节点 → Plugins → Token Binding Exporter
4. 点击 **Export Bindings** → **Copy to Clipboard**
5. 将 JSON 粘贴到 `app/src/main/assets/figma_plugin_export.json`

### 开发者配置

```kotlin
tokens {
    enabled = true
    tokenSource = "plugin"  // 切换数据源
}
```

```bash
./gradlew syncFigmaTokens
```

---

## 12. 绑定到构建流程

在 `app/build.gradle.kts` 中添加，使每次构建前自动同步：

```kotlin
// 方案 A：preBuild 自动同步（推荐调试阶段）
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("syncFigmaIcons")
}

// 方案 B：只在 CI 或特定 flavor 同步（推荐生产阶段）
android {
    productFlavors {
        create("dev") {
            // 开发 flavor 每次同步
        }
        create("release") {
            // 发布 flavor 使用缓存，不触发网络
        }
    }
}
tasks.matching { it.name == "preDevBuild" }.configureEach {
    dependsOn("syncFigmaIcons")
}
```

---

## 13. 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 403 Forbidden | Token 过期或权限不足 | 重新生成 token，确保勾选 `file_content:read` |
| 400 Request too large | Figma 文件过大 | 自动降级 depth=4→3→2；拆分模块或缩小 startNode |
| 颜色令牌 API 返回空 | 免费版不支持 Variables API | 使用 Figma 插件降级方案（第 11 节） |
| 图标未更新 | 缓存命中 | 删除 `build/figma/.figma-cache.json` 重试 |
| 同名资源冲突 | 多个 token 映射到相同名称 | 插件自动添加 `_2`、`_3` 后缀 |
| RTL 图标未识别 | 命名不规范 | 组件名包含 "RTL"（大小写不敏感）或 `componentProperties` 中 `value="RTL"` |
| 模板节点混入 | Figma 中「模版」Section 被扫描 | 名称包含 "模版" 或 "template" 的节点自动跳过 |

详细排查 → [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)

---

## 14. 架构图

```
┌──────────────────────────────────────────────────────────┐
│                   figma-sync-gradle-plugin                 │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  ┌─────────────┐   ┌──────────────┐   ┌───────────────┐ │
│  │ FigmaClient │   │ TokenClient  │   │ SvgToVector   │ │
│  │ REST API    │   │ Variables    │   │ Converter     │ │
│  │  · icons    │   │  · fetch     │   │  · SVG→VD     │ │
│  │  · version  │   │  · closure   │   │  · @color ref │ │
│  └──────┬──────┘   └──────┬───────┘   └───────┬───────┘ │
│         │                 │                    │          │
│  ┌──────┴─────────────────┴────────────────────┴───────┐ │
│  │                   Gradle Tasks                       │ │
│  │  syncFigmaIcons  syncFigmaTokens  syncFigmaIcons_*  │ │
│  └──────────────────────┬──────────────────────────────┘ │
│                         │                                 │
│  ┌──────────────────────┴──────────────────────────────┐ │
│  │              VersionManager + Cache                   │ │
│  │   freeze / restore / diff   .figma-versions/         │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
              Android res/ + assets/
```

---

## 附录 A：Figma Token 最小权限

| 权限范围 | 用途 | 必需 |
|---------|------|------|
| `file_content:read` | 读取文件结构 + 下载图标 | ✅ |
| `file_variables:read` | 读取设计令牌（Variables API） | 颜色令牌同步需要 |

## 附录 B：Figma 节点 ID 格式

- URL 中：`node-id=14832-59978` → 配置时写 `14832:59978`
- COMPONENT ID：`6:642`
- 变量 ID：`VariableID:9d3256ee81a6.../15431:68`

## 附录 C：Gradle 兼容性

| 插件版本 | Gradle | AGP |
|---------|--------|-----|
| 1.0.0 | 8.x / 9.x | 8.x |

---

> 如有问题，查看 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) 或提交 Issue。
