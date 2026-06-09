# Figma Sync Gradle Plugin

构建时自动从 Figma 拉取设计资源并转换为 Android 可用格式。设计师改 Figma → 开发者 build 即生效。

## 功能

- **图标同步**：Figma COMPONENT → SVG 下载 → Android VectorDrawable XML
- **增量构建**：两级缓存（文件版本 + 资源 MD5），Figma 无变更时秒级跳过
- **RTL 支持**：自动识别 RTL 变体，输出到 `drawable-ldrtl/`
- **后续规划**：颜色/设计令牌同步、字体同步（Week 2-3）

## 集成

### 1. 将插件模块放入项目

```
your-project/
├── figma-sync-plugin/    ← 复制这个目录
├── app/
├── build.gradle.kts
└── settings.gradle.kts
```

### 2. 配置 settings.gradle.kts

```kotlin
pluginManagement {
    includeBuild("figma-sync-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

### 3. 在 app/build.gradle.kts 中应用插件

```kotlin
plugins {
    id("com.android.application")
    id("com.figma.sync")     // 添加这一行
}
```

## 配置

### Token 认证

两种方式，任选其一：

**方式一：local.properties（推荐，不提交到 Git）**

```properties
figma.token=figd_your_personal_access_token_here
```

**方式二：build.gradle.kts 直接配置**

```kotlin
figmaSync {
    token = "figd_your_personal_access_token_here"
    // ...
}
```

> Figma Personal Access Token 获取：Figma → Settings → Account → Personal Access Tokens

### 图标同步配置

```kotlin
figmaSync {
    // Figma 文件 Key，从 Figma 文件 URL 中提取
    // https://www.figma.com/file/G4GyegR4f1uHsW5ZdBuOaY/xxx
    //                                    ↑ 这一串
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"

    icons {
        enabled = true               // 是否启用图标同步
        startNode = "14832:59978"    // Figma 中起始节点的 ID（右键 → Copy link 获取）
        scale = 2                    // 导出倍率：1 / 2 / 3 / 4
    }
}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `fileKey` | String | 是 | `""` | Figma 文件 Key |
| `token` | String | 否 | 从 `local.properties` 读取 | Figma Personal Access Token |
| `icons.enabled` | Boolean | 否 | `false` | 是否启用图标同步 |
| `icons.startNode` | String | 否 | `""` | 起始节点 ID，右键节点 → Copy link 获取 |
| `icons.scale` | Int | 否 | `2` | SVG 导出倍率（1/2/3/4） |

### 完整配置示例

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.figma.sync")
}

android {
    namespace = "com.example.myapp"
    compileSdk = 34
    // ...
}

figmaSync {
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"
    icons {
        enabled = true
        startNode = "14832:59978"
        scale = 2
    }
}

// 每次构建前自动同步图标
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("syncFigmaIcons")
}
```

## 任务

| 任务 | 说明 | 状态 |
|------|------|------|
| `syncFigmaIcons` | 下载 Figma 图标，转换为 VectorDrawable | 可用 |
| `syncFigmaColors` | Figma 颜色样式 → colors.xml | 开发中 |
| `syncFigmaFonts` | Figma 字体 → res/font/ | 开发中 |
| `syncFigmaAll` | 运行所有已启用的同步任务 | 可用 |

### 常用命令

```bash
# 单独同步图标
./gradlew syncFigmaIcons

# 运行所有同步任务
./gradlew syncFigmaAll

# 查看详细日志（含跳过/下载详情）
./gradlew syncFigmaIcons --info

# 正常构建（preBuild 会自动触发 syncFigmaIcons）
./gradlew assembleDebug
```

## 输出目录

```
app/src/main/res/
├── drawable/           ← LTR 图标（VectorDrawable XML）
│   ├── ic_xxx.xml
│   └── ...
└── drawable-ldrtl/     ← RTL 镜像图标
    ├── ic_xxx.xml
    └── ...
```

构建中间产物：

```
app/build/figma/
├── svg/                ← 下载的原始 SVG（LTR）
├── svg-rtl/            ← 下载的原始 SVG（RTL）
└── .figma-cache.json   ← 增量缓存文件
```

## 增量缓存机制

两级缓存，确保 Figma 无变更时不重复下载：

```
┌─────────────────────────────────────┐
│ Level 1: 文件版本检查                │
│ GET /v1/files/:key → version 字段    │
│ 版本未变 → 直接跳过，任务 UP-TO-DATE │
├─────────────────────────────────────┤
│ Level 2: 资源 MD5 检查               │
│ 版本变了 → 逐资源对比 MD5 hash       │
│ hash 相同 → 跳过写入，不触发重新编译  │
│ hash 不同 → 下载并更新缓存           │
└─────────────────────────────────────┘
```

缓存文件 `.figma-cache.json` 结构：

```json
{
  "version": "2358827276804074006",
  "ltrCount": 73,
  "rtlCount": 10,
  "assets": {
    "14832:60001": {
      "h": "d41d8cd98f00b204e9800998ecf8427f",
      "n": "ic_car_door",
      "r": false
    }
  }
}
```

## RTL 支持

插件自动处理 RTL（Right-to-Left）图标：

- Figma 中带 `Property 1=RTL` 变体的 COMPONENT → 输出到 `drawable-ldrtl/`
- 不带 RTL 变体的 COMPONENT → 输出到 `drawable/`
- 命名带 `_rtl` 后缀的节点自动归类为 RTL

Android 在 RTL 语言环境下会自动从 `drawable-ldrtl/` 加载资源。

## 节点过滤

插件内置以下过滤规则：

- **模版跳过**：Figma 中名为「模版」的节点及其子节点会被自动跳过
- **仅下载 COMPONENT**：普通 Frame/Group 节点不下载，只下载 COMPONENT/COMPONENT_SET
- **COMPONENT_SET 处理**：COMPONENT_SET 下的子 COMPONENT 以 COMPONENT_SET 名称命名

## 故障排查

### Token 无效

```
[Figma] HTTP 403 — Invalid token
```

检查 token 是否正确，是否已过期。重新生成：Figma → Settings → Account → Personal Access Tokens。

### 文件 Key 找不到

```
[Figma] HTTP 404 — File not found
```

确认 `fileKey` 正确，且 token 对应的账号有该文件的访问权限。

### 节点 ID 找不到

```
[Figma] Node xxx not found
```

确认 `startNode` ID 正确：在 Figma 中右键节点 → Copy/Paste as → Copy link，URL 中的 `node-id=` 参数即为节点 ID。

### 速率限制

```
[Figma] HTTP 429 — Rate limit exceeded
```

Figma API 免费额度约 30 请求/分钟。插件内置了请求合并（批量获取图片 URL），一般情况下不会触发。如果频繁触发，等待 1 分钟后重试。

### 图标下载数量不对

- 检查 `startNode` 是否指向了正确的 SECTION 或 FRAME
- 确认目标节点下确实有 COMPONENT（不是 Frame/Group）
- 排查是否有节点被「模版」过滤规则误伤

## 项目结构

```
figma-sync-plugin/
├── build.gradle.kts
└── src/main/java/com/figma/sync/
    ├── FigmaSyncPlugin.java       # 插件入口，注册 extension 和 task
    ├── FigmaSyncExtension.java    # DSL 配置类
    ├── SyncIconsTask.java         # 图标同步 Task
    ├── FigmaClient.java           # Figma REST API 客户端
    ├── SvgToVectorConverter.java  # SVG → VectorDrawable 转换器
    └── VersionCache.java          # 两级增量缓存
```

## License

MIT
