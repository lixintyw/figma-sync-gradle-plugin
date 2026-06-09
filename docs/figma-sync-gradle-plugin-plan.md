# Figma → Android Gradle 插件：设计-开发自动化管线

## Context

当前设计师使用 Figma 进行设计，Android 开发者手工接收切图并更新 App。变更是通过口头/IM 传递的，没有自动化管线。目标：构建一个 Gradle 插件，在 build 或 sync 时自动从 Figma 拉取资源并转换为 Android 可用格式，实现"设计师改 Figma → 开发者 build 即生效"的闭环。

涉及资源类型：颜色/设计令牌、字体、图标、Lottie 动效、动效参数。

## 技术可行性总览

| 资源类型 | 通路 | 方式 |
|---------|------|------|
| 颜色/设计令牌 | 通 | Figma Styles API → 颜色值 → `colors.xml` / Compose Color.kt |
| 字体检测 | 通 | Figma TEXT 节点 `style` 对象 → `fontFamily`, `fontWeight`, `fontSize` |
| 字体文件 | 部分通 | Figma 不托管字体文件，需 Google Fonts API 补充 + 自定义字体手动管理 |
| 图标 | 通 | 已验证：COMPONENT → SVG → VectorDrawable XML |
| Lottie 动效 | 不通 | Figma REST API 无法导出 Lottie。需 LottieFiles 插件/API 或设计师手动导出 |
| 动效参数 | 部分通 | REST API 可获取组件属性/变体，但原型过渡（缓动曲线/时长）需要 Figma Plugin API 配合 |

### 不通的怎么办

- **Lottie**：设计师用 LottieFiles for Figma 插件导出 → 上传到 LottieFiles → Gradle 插件通过 LottieFiles API 下载 `.json`。或设计师直接提交到 `assets/lottie/`，插件做版本管理。
- **动效参数**：写一个轻量 Figma 插件，把原型数据（reactions/transition/duration/easing）序列化为 JSON，放到 Figma 节点描述或单独文件。Gradle 插件读取后生成 Android 动画代码。

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                  figma-sync-gradle-plugin                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  DSL 配置层 (build.gradle.kts)                           │
│  ┌─────────────────────────────────────────────────┐   │
│  │ figmaSync {                                      │   │
│  │   token = "figd_xxx"    // 或 local.properties   │   │
│  │   fileKey = "G4Gyeg..."                          │   │
│  │   colors { enabled = true; output = "colors.xml" }│   │
│  │   fonts { enabled = true; googleFontsApiKey = "" }│   │
│  │   icons { startNode = "1:414"; scale = 2 }       │   │
│  │   lottie { source = "lottiefiles"; teamId = "" } │   │
│  │   animations { enabled = true }                  │   │
│  │ }                                                │   │
│  └─────────────────────────────────────────────────┘   │
│                         ↓                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │ SyncManager (编排器)                              │   │
│  │  - VersionCache: 文件版本 + 资源 hash             │   │
│  │  - 协调各 Syncer 执行                             │   │
│  └──────┬──────────────────────────────────────────┘   │
│         │                                               │
│    ┌────┴──────────────────────────┐                   │
│    │                               │                   │
│    ▼                               ▼                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ FigmaClient  │  │ LottieClient │  │ GoogleFonts  │ │
│  │ (REST API)   │  │ (REST API)   │  │ Client       │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘ │
│         │                 │                 │          │
│    ┌────┴─────────────────┴─────────────────┴────┐    │
│    │               Syncers (转换器)                 │    │
│    │  ColorSyncer  │ FontSyncer │ IconSyncer      │    │
│    │  LottieSyncer │ AnimationParamSyncer         │    │
│    └──────────────────┬───────────────────────────┘    │
│                       ↓                                │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Android 资源输出                                   │   │
│  │  res/values/colors.xml                           │   │
│  │  res/font/                                       │   │
│  │  res/drawable/ (+ drawable-ldrtl/)               │   │
│  │  res/raw/ (lottie JSON)                          │   │
│  │  src/main/java/.../AnimationSpec.kt (生成代码)    │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 关键设计决策

1. **独立 Gradle Plugin**：从当前 buildSrc 抽取为 `figma-sync-plugin` 模块，可发布到 Maven，跨项目复用
2. **增量构建**：利用 Gradle `Task inputs/outputs` + 缓存文件版本号，实现 FIGMA 无变更时跳过全部同步
3. **资源类型独立**：每个 Syncer 独立 Task，可单独触发，互不阻塞
4. **缓存分层**：文件级 version → 资源级 hash，两级跳过

## 12 周计划

### 第 1 周：项目骨架 + Figma API 客户端

**目标**：Gradle 插件能跑通，API 客户端封装完成

- [ ] 创建独立 Gradle 插件模块 `figma-sync-plugin/`
- [ ] 插件入口 `FigmaSyncPlugin.kt`，注册 extension `figmaSync { }`
- [ ] DSL 配置类 `FigmaSyncExtension`，至少包含 token/fileKey
- [ ] `FigmaClient` 封装：GET 请求、token 鉴权、错误处理、速率限制
- [ ] `VersionCache`：读写 `.figma-cache.json`，version 快速路径
- [ ] 在 FigmaFetchDemo 中集成插件，验证 build 通过

**交付**：插件可配置、可调用 Figma API 并打印文件信息

---

### 第 2 周：颜色/设计令牌同步

**目标**：Figma Color Styles → Android colors.xml

- [ ] `ColorSyncer`：调用 `/v1/files/:key/styles` 获取 FILL 类型样式
- [ ] 批量调用 `/v1/files/:key/nodes?ids=...` 提取 RGBA 值
- [ ] RGBA (0-1 float) → `#AARRGGBB` hex 转换
- [ ] 输出 `res/values/figma_colors.xml`
- [ ] 处理颜色命名规范：Figma `style.name` → Android resource name
- [ ] 增量更新：颜色值未变则跳过写入

**交付**：build 后 `figma_colors.xml` 自动更新

---

### 第 3 周：字体检测 + Google Fonts 下载

**目标**：检测 Figma 中使用的字体，自动下载可用字体文件

- [ ] `FontSyncer`：遍历文档树 TEXT 节点，去重提取 `{fontFamily, fontWeight, fontStyle}`
- [ ] 生成字体清单 `figma_fonts.json`（列出所有使用的字体）
- [ ] `GoogleFontsClient`：通过 Google Fonts API 匹配并下载 woff2/ttf
- [ ] 输出 `res/font/` 目录
- [ ] 自定义字体标记：匹配不到的字体在清单中标注 "manual"，开发者需手动添加

**交付**：自动下载 Google Fonts 字体文件，自定义字体给出清单提示

---

### 第 4 周：图标同步增强（基于已验证管线）

**目标**：将已验证的图标同步能力接入插件，并加强

- [ ] `IconSyncer`：复用已验证的 `collectAssets` + SVG → VectorDrawable 流程
- [ ] 差分下载：version 检查 + MD5 hash 对比（基于调研文档实现）
- [ ] RTL 变体处理：自动识别 `Property 1=RTL` → `drawable-ldrtl/`
- [ ] 模版节点跳过：可配置的排除规则
- [ ] 子集下载：支持按 Figma frame 拆分选择下载
- [ ] `SvgToVectorConverter` 加文件时间戳跳过

**交付**：图标同步完整功能，支持差分 + RTL + 子集选择

---

### 第 5 周：拆分下载 + 版本锁定

**目标**：支持按需选择资源范围，支持锁定 Figma 版本

- [ ] DSL 支持多节点选择：
  ```kotlin
  figmaSync {
      icons {
          sources = listOf("14832:59978", "14832:60000") // 多个起始节点
      }
  }
  ```
- [ ] 拆分 Task：`syncFigmaColors`, `syncFigmaIcons`, `syncFigmaFonts` 可单独执行
- [ ] 版本锁定：支持指定 `pinnedVersion = "2358827276804074006"`，跳过 API 调用使用缓存
- [ ] `figmaSyncCheck` Task：对比本地与远端版本，仅报告差异

**交付**：资源可拆分同步，CI 可锁定版本保证一致性

---

### 第 6 周：Lottie 动效集成调研 + 方案落地

**目标**：确定 Lottie 自动化管线并接入

- [ ] 调研 LottieFiles API：上传/下载/搜索端点
- [ ] 设计 Lottie 命名规范：Figma COMPONENT name → LottieFiles asset name 映射
- [ ] `LottieSyncer`：调用 LottieFiles API 下载 `.json` / `.lottie`
- [ ] 输出 `res/raw/` 目录
- [ ] 降级方案：如果 LottieFiles API 不通，支持从本地 `assets/lottie/` 做版本管理
- [ ] 差分下载：检查远程更新时间戳

**交付**：Lottie 动效自动下载到 `res/raw/`

---

### 第 7 周：动效参数 Figma 插件 + 代码生成

**目标**：打通原型参数 → Android 动画代码

- [ ] 编写 Figma 插件（最小可行版本）：提取选中节点的 `reactions` 数据，输出 JSON
- [ ] 约定 JSON 格式（P0）：
  ```json
  {
    "animations": [{
      "name": "fade_in",
      "trigger": "ON_CLICK",
      "transition": "SMART_ANIMATE",
      "duration": 300,
      "easing": { "type": "EASE_OUT", "cubicBezier": [0, 0, 0.58, 1] }
    }]
  }
  ```
- [ ] 插件将 JSON 写入 Figma 节点描述（`description` 字段），通过 REST API 读取
- [ ] `AnimationParamSyncer`：从 REST API 读取描述中的 JSON，生成 Android 动画代码
- [ ] 生成 Compose `AnimationSpec` 或 View `ObjectAnimator` 代码

**交付**：设计师在 Figma 中配置动效参数 → build 时生成 Android 动画代码

---

### 第 8 周：Gradle 增量构建优化

**目标**：利用 Gradle 增量构建机制，提升性能

- [ ] 每个 Syncer Task 正确声明 `inputs` 和 `outputs`
- [ ] Task 间依赖关系建模：`syncFigmaColors` / `syncFigmaIcons` 等可并行
- [ ] Gradle Build Cache 兼容：确保 Task 输出可重现
- [ ] Configuration Cache 兼容
- [ ] 性能基准测试：初次构建 vs 增量构建 vs 无变更构建

**交付**：Gradle UP-TO-DATE 检查正常工作，无变更时全部跳过

---

### 第 9 周：CI/CD 集成 + 自动 PR

**目标**：打通 CI 管线，Figma 变更自动触发更新

- [ ] CI workflow（GitHub Actions / Jenkins）：定时或 webhook 触发 `syncFigmaResources`
- [ ] 变更检测：对比 sync 前后 git diff
- [ ] 有变更时自动创建 PR，包含变更摘要（哪个颜色变了、哪个图标新增）
- [ ] PR 描述模板：diff 概览 + Figma link
- [ ] 失败处理：token 过期、API 限流告警

**交付**：Figma 变更 → CI 自动检测 → 自动 PR

---

### 第 10 周：测试 + 文档

**目标**：确保质量，降低接入成本

- [ ] 单元测试：ColorConverter, SvgConverter, VersionCache, FigmaClient mock
- [ ] 集成测试：用固定 Figma 文件版本验证全链路输出
- [ ] 接入文档：`README.md` 含 DSL 配置示例、token 获取步骤
- [ ] 设计端文档：给设计师的规范（图标命名、颜色样式、Lottie 导出、动效参数填写）
- [ ] 故障排查文档：常见错误码、token 过期、API 限流

**交付**：测试覆盖率 > 70%，文档完整

---

### 第 11-12 周：打磨 + 实际项目接入

**目标**：在实际 App 中验证，解决边界问题

- [ ] 在实际 Android 项目中接入插件，替换手动切图流程
- [ ] 收集设计师反馈：命名是否匹配、工作流是否顺畅
- [ ] 性能调优：API 调用次数优化、并行下载
- [ ] 边界处理：超大图标、嵌套 COMPONENT、颜色透明度
- [ ] 插件发布：发布到 Gradle Plugin Portal 或内部 Maven

**交付**：插件在生产项目中稳定运行

---

## 里程碑汇总

| 周 | 里程碑 | 可验证产出 |
|----|--------|-----------|
| W1 | 插件骨架 + API 客户端 | `./gradlew figmaSync` 调用成功 |
| W2 | 颜色同步 | `figma_colors.xml` 自动生成 |
| W3 | 字体同步 | `res/font/` 自动填充 |
| W4 | 图标同步增强 | 差分下载 + RTL + 子集选择 |
| W5 | 拆分下载 + 版本锁定 | 单资源类型执行 / 版本固定 |
| W6 | Lottie 集成 | `res/raw/*.json` 自动下载 |
| W7 | 动效参数 | 生成 Android 动画代码 |
| W8 | 增量构建优化 | UP-TO-DATE 全部生效 |
| W9 | CI/CD + 自动 PR | 首个自动 PR 创建 |
| W10 | 测试 + 文档 | 接入文档发表 |
| W11-12 | 生产验证 | 实际 App 接入运行 |

## 风险与对策

| 风险 | 概率 | 影响 | 对策 |
|------|------|------|------|
| LottieFiles API 变更/收费 | 中 | Lottie 管线断裂 | 降级为手动导出 + 版本管理 |
| Figma API 速率限制 (30 req/min 免费) | 中 | 大批量同步失败 | 请求队列 + 退避重试 + 差分减少调用 |
| Google Fonts 匹配不到 Figma 字体 | 高 | 字体文件缺失 | 清单标注 "manual"，开发者手动补齐 |
| Figma Plugin API 未来变更 | 低 | 动效参数提取失败 | 插件版本与 Figma 版本解耦 |
| 大型 Figma 文件 API 响应超时 | 低 | depth 参数需要调高 | 按需 depth，拆分请求 |

## 验证方式

每个里程碑完成后运行：

```bash
# 插件功能验证
./gradlew figmaSync           # 全量同步
./gradlew syncFigmaColors     # 单独颜色
./gradlew syncFigmaIcons      # 单独图标
./gradlew figmaSyncCheck      # 变更检测

# 增量验证：第二次运行应全部 UP-TO-DATE
./gradlew figmaSync --info    # 检查 Task 状态

# 输出验证
ls res/values/figma_colors.xml
ls res/font/
ls res/drawable/*.xml | wc -l
ls res/drawable-ldrtl/*.xml | wc -l
ls res/raw/*.json
```
