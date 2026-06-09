# 12 周计划逐条对照报告

> 基准文档：`figma-sync-gradle-plugin-plan.md`
> 检查日期：2026-06-09
> 检查范围：`figma-sync-plugin/src/main/java/com/figma/sync/` 全部源文件 + `app/` 集成代码 + CI/文档

---

## 第 1 周：项目骨架 + Figma API 客户端

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | 创建独立 Gradle 插件模块 `figma-sync-plugin/` | ✅ | `figma-sync-plugin/` 存在，独立 Java Gradle 模块 |
| 2 | 插件入口 `FigmaSyncPlugin.kt` | ⚠️ | 实现为 `FigmaSyncPlugin.java`（Java 非 Kotlin，功能等价） |
| 3 | DSL 配置类 `FigmaSyncExtension` | ✅ | 含 token/fileKey/pinnedVersion/icons/tokens/modules 全部 DSL |
| 4 | `FigmaClient` 封装 | ✅ | REST GET/下载，token 鉴权，403/400 错误处理，depth 降级 |
| 5 | `VersionCache` | ✅ | version + MD5 两级缓存，JSON 持久化，corrupt 容错 |
| 6 | Demo 项目集成验证 | ✅ | `app/` 模块集成 composite build，build 通过 |

> **W1 结论：6/6 完成。** Java 替代 Kotlin 属于语言选择，功能无差异。

---

## 第 2 周：颜色/设计令牌同步

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | `ColorSyncer` 类 | ⚠️ | 逻辑存在但未独立为 `ColorSyncer` 类，而是 `SyncTokensTask` + `TokenClient` |
| 2 | 调用 `/v1/files/:key/styles` | ⚠️ | **方案升级**：计划用 Styles API，实际实现了更强的 Variables API（`/v1/files/:key/variables/local`），支持变量别名链解析 |
| 3 | 批量 nodes 提取 RGBA | ✅ | `TokenClient.fetchLocalVariablesFiltered()` 批量解析 + alias chain |
| 4 | RGBA → `#AARRGGBB` hex | ✅ | `TokenClient.rgbaToHex()` |
| 5 | 输出 `figma_colors.xml` | ✅ | 29 个颜色令牌输出到 `res/values/figma_colors.xml` |
| 6 | 颜色命名规范 | ✅ | `sanitizeResName()` + 去重后缀 `_2`、`_3` |
| 7 | 增量更新 | ✅ | chain download + collection 过滤 |

> **W2 结论：7/7 完成，且方案优于计划**（Variables API > Styles API）。

---

## 第 3 周：字体检测 + Google Fonts 下载

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | `FontSyncer` | ❌ | 未实现 |
| 2 | 遍历文档树 TEXT 节点 | ❌ | 未实现 |
| 3 | 生成字体清单 `figma_fonts.json` | ❌ | 未实现 |
| 4 | `GoogleFontsClient` | ❌ | 未实现 |
| 5 | 输出 `res/font/` | ❌ | 未实现 |
| 6 | 自定义字体标记 "manual" | ❌ | 未实现 |

> **W3 结论：0/6。** `syncFigmaFonts` 仅存根 stub task，输出 "Font sync not yet implemented"。

---

## 第 4 周：图标同步增强

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | `IconSyncer` 复用流程 | ⚠️ | 实现在 `SyncIconsTask` + `FigmaClient.fetchIcons()`，非独立 Syncer 类 |
| 2 | 差分下载（version + MD5） | ✅ | L1 version 检查 + L2 MD5 hash 对比 |
| 3 | RTL 变体 → `drawable-ldrtl/` | ✅ | `isRtlComponent()` 自动识别，独立 `drawable-ldrtl/` 输出 |
| 4 | 模版节点跳过 | ✅ | `isTemplateNode()`：名称含 "模版"/"template" 自动跳过 |
| 5 | 子集选择下载 | ✅ | `declarations` 白名单机制 |
| 6 | 文件时间戳跳过 | ✅ | `outFile.lastModified() >= svgFile.lastModified()` |

> **W4 结论：6/6 完成。**

---

## 第 5 周：拆分下载 + 版本锁定

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | 多节点选择 `sources = listOf(...)` | ⚠️ | 实现了更强的 **modules DSL**，每模块独立 startNode/Task/Cache，超计划需求 |
| 2 | 拆分独立 Task | ✅ | `syncFigmaIcons` / `syncFigmaTokens` / `syncFigmaColors` / `syncFigmaIcons_<module>` |
| 3 | 版本锁定 `pinnedVersion` | ✅ | `pinnedVersion` 属性 + 快速路径跳过 API 调用 |
| 4 | `figmaSyncCheck` Task | ⚠️ | 未实现独立 check task，但 `figmaDiffVersions` 提供了版本级差异对比 |

> **W5 结论：3/4 完成，1 部分完成。** modules 方案超出原计划的 `sources` 列表。

---

## 第 6 周：Lottie 动效集成

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | LottieFiles API 调研 | ❌ | 未进行 |
| 2 | `LottieSyncer` | ❌ | 未实现 |
| 3 | 输出 `res/raw/` | ❌ | 未实现 |
| 4 | 本地回退方案 | ❌ | 未实现 |
| 5 | 差分下载 | ❌ | 未实现 |

> **W6 结论：0/5。**

---

## 第 7 周：动效参数 Figma 插件 + 代码生成

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | Figma 插件提取 reactions | ❌ | 我们的插件提取的是 token bindings，不是动画参数 |
| 2 | JSON 格式约定 | ❌ | 未定义 |
| 3 | `AnimationParamSyncer` | ❌ | 未实现 |
| 4 | 生成 Compose/View 动画代码 | ❌ | 未实现 |

> **W7 结论：0/4。**

---

## 第 8 周：Gradle 增量构建优化

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | 正确声明 inputs/outputs | ⚠️ | Task 有 `@Input/@Output/@Internal` 注解，但部分 `@Optional` 属性未完全覆盖所有路径 |
| 2 | Task 间依赖建模 | ✅ | `dependsOn` 链完整（module tasks → lifecycle task → preBuild） |
| 3 | Gradle Build Cache 兼容 | ❌ | 未验证，未声明 `@CacheableTask` |
| 4 | Configuration Cache 兼容 | ❌ | 未验证 |
| 5 | 性能基准测试 | ❌ | 未进行 |

> **W8 结论：1.5/5。** inputs/outputs 声明部分完成，缓存兼容性未验证。

---

## 第 9 周：CI/CD 集成 + 自动 PR

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | CI workflow（GitHub Actions） | ✅ | `.github/workflows/figma-sync.yml`：定时 + 手动触发 |
| 2 | 变更检测 | ✅ | pre/post 缓存状态对比 + git diff |
| 3 | 自动创建 PR | ✅ | `peter-evans/create-pull-request@v6` + 变更摘要 |
| 4 | PR 描述模板 | ✅ | `.github/PULL_REQUEST_TEMPLATE/figma_sync.md` |
| 5 | 失败处理（token 过期/API 限流） | ✅ | 自动创建 GitHub Issue 告警 |

> **W9 结论：5/5 完成。**

---

## 第 10 周：测试 + 文档

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | 单元测试 | ✅ | 4 个测试类（SvgToVectorConverter / TokenClient / VersionManager / VersionCache），48 条，全绿 |
| 2 | 集成测试 | ❌ | 未实现（需固定 Figma 版本） |
| 3 | 接入文档 | ✅ | `PLUGIN_USAGE.md` + `INTEGRATION.md` |
| 4 | 设计端文档 | ✅ | `FIGMA_PLUGIN_GUIDE.md` |
| 5 | 故障排查文档 | ✅ | `TROUBLESHOOTING.md` |

> **W10 结论：4/5 完成。** 集成测试缺失。

---

## 第 11-12 周：打磨 + 实际项目接入

| # | 计划项 | 状态 | 实现 |
|---|--------|:----:|------|
| 1 | 实际 Android 项目接入 | ❌ | 仅在 Demo 项目中验证 |
| 2 | 设计师反馈收集 | ❌ | 未进行 |
| 3 | 性能调优 | ⚠️ | depth 降级、命名去重等边界处理已有，但无系统性能优化 |
| 4 | 边界处理 | ⚠️ | depth 降级、dedup、opacity 传递已实现，其他边界未穷尽 |
| 5 | 插件发布到 Maven | ❌ | 仅 composite build 方式可用 |

> **W11-12 结论：0/5。** 未进入生产验证阶段。

---

## 总览矩阵

| 周 | 里程碑 | 完成/总数 | 完成率 | 判定 |
|----|--------|:--------:|:-----:|:----:|
| W1 | 项目骨架 + API 客户端 | 6/6 | 100% | ✅ |
| W2 | 颜色/设计令牌同步 | 7/7 | 100% | ✅ |
| W3 | 字体检测 + Google Fonts | 0/6 | 0% | ❌ |
| W4 | 图标同步增强 | 6/6 | 100% | ✅ |
| W5 | 拆分下载 + 版本锁定 | 3.5/4 | 88% | ✅ |
| W6 | Lottie 动效集成 | 0/5 | 0% | ❌ |
| W7 | 动效参数插件 + 代码生成 | 0/4 | 0% | ❌ |
| W8 | Gradle 增量构建优化 | 1.5/5 | 30% | ⚠️ |
| W9 | CI/CD + 自动 PR | 5/5 | 100% | ✅ |
| W10 | 测试 + 文档 | 4/5 | 80% | ✅ |
| W11-12 | 打磨 + 实际项目接入 | 0/5 | 0% | ❌ |
| **合计** | | **33/58** | **57%** | |

---

## 能力缺口分析

### 完全缺失（P0）

| 缺口 | 周 | 影响 |
|------|:--:|------|
| 字体同步 | W3 | 字体文件需手动管理，无法自动检测 Figma 字体变更 |
| Lottie 动效 | W6 | Lottie 资源无自动化管线，依赖设计师手动导出 |
| 集成测试 | W10 | 无端到端回归，管线变更时无法自动验证输出正确性 |

### 部分缺失（P1）

| 缺口 | 周 | 影响 |
|------|:--:|------|
| 动效参数 | W7 | 过渡动画参数无法从 Figma 同步到代码 |
| Gradle Build Cache | W8 | CI 首次构建后仍可能重复执行网络调用 |
| figmaSyncCheck Task | W5 | 无独立命令只检查差异不执行同步 |
| Maven 发布 | W11-12 | 只能 composite build 方式接入 |
| 生产验证 | W11-12 | 边界 case 未充分暴露 |

### 超出计划

| 超出项 | 说明 |
|--------|------|
| Variables API（替代 Styles API） | W2 实际使用了更强的 Variables API，支持别名链解析 + 令牌闭环 |
| modules DSL | W5 超计划实现多模块独立 Task + 独立 Cache |
| 令牌链下载（chainDownload） | W2 增加 BFS 闭包计算 |
| Figma 插件降级方案 | 完整 Figma 插件 + Gradle 侧 parser，覆盖免费版用户 |
| 颜色感知 VectorDrawable | W4 额外实现 `@color/figma_xxx` 引用替代硬编码 |
| 48 条单元测试 | W10 超计划覆盖 4 个核心类 |

---

## 建议优先级

```
P0（下一轮必做）：
  1. W3 字体同步：补齐 FontSyncer + Google Fonts 下载
  2. W10 集成测试：用固定 Figma 版本做端到端回归
  3. W8 Gradle Cache 兼容：@CacheableTask 声明 + 可重现构建

P1（有资源时做）：
  4. W6 Lottie 动效集成
  5. W11-12 Maven 发布流程
  6. W5 figmaSyncCheck Task

P2（降级/待定）：
  7. W7 动效参数插件（依赖 Figma Plugin API，投入产出比需评估）
  8. W11-12 性能调优（真实数据量下再做）
```
