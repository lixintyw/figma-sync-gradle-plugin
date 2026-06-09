# Figma 差分下载技术方案

## 目标

每次执行 `fetchFigmaResources` 时，跳过未变更的 Figma 资源，只下载有变化的 SVG，减少不必要的网络请求和文件 I/O。

## 现状分析

当前流程每次全量下载 94 个 SVG (~97 秒)：

```
GET /v1/files/:key?depth=4  →  遍历树收集 COMPONENT ids
GET /v1/images/:key?ids=...  →  获取 CDN 下载 URL
下载每个 SVG  →  svgDir / svgRtlDir
SvgToVectorConverter  →  res/drawable/ + res/drawable-ldrtl/
```

## Figma API 可用的变更检测手段

### 已有数据

通过验证 `GET /v1/files/:key` 返回结构：

| 字段 | 值示例 | 用途 |
|------|--------|------|
| `version` | `"2358827276804074006"` | 文件级别版本号，任何编辑都会递增 |
| `lastModified` | `"2026-05-29T02:59:46Z"` | 文件级别最后修改时间 |

### 不支持的数据

- **没有 per-node `updatedAt`** — 文档树中节点只包含 `id`/`name`/`type`/`children`，没有节点级别的修改时间戳
- **CDN URL 是临时的** — `/v1/images/` 返回的下载 URL 有时效性（约 14 天），且内容不变时 URL 也会变，不能用来做变更判断
- **没有 ETag / If-Modified-Since** — Figma CDN 不支持 HTTP 条件请求

## 方案设计：两级缓存

```
┌──────────────────────────────────────────────────────────┐
│                     fetchFigmaResources                   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  1. GET /v1/files/:key?depth=4                           │
│     ↓                                                    │
│  2. 提取 version 字段                                     │
│     ↓                                                    │
│  3. version == 缓存中的 version ?                          │
│     ├── YES → 跳过全部下载 (快速路径, ~2s)                   │
│     └── NO  → 进入差分下载流程                              │
│              ↓                                           │
│  4. 遍历树收集 COMPONENT ids (跳过模版)                     │
│     ↓                                                    │
│  5. GET /v1/images/:key?ids=... (批量获取 CDN URL)         │
│     ↓                                                    │
│  6. 下载每个 SVG 到内存                                    │
│     ↓                                                    │
│  7. 计算 MD5 hash                                         │
│     ↓                                                    │
│  8. hash == 缓存中该 nodeId 的 hash ?                       │
│     ├── YES → 跳过写入 (内容未变)                            │
│     └── NO  → 写入文件 (新增/变更)                           │
│     ↓                                                    │
│  9. 保存新缓存 (.figma-cache.json)                         │
│     ↓                                                    │
│  10. 清理过期文件（nodeId 已不在树中的旧 SVG）                │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 缓存文件 `.figma-cache.json`

存放位置：`app/src/main/assets/figma/.figma-cache.json`

```json
{
  "version": "2358827276804074006",
  "ltrCount": 82,
  "rtlCount": 12,
  "assets": {
    "6:642": {
      "h": "d41d8cd98f00b204e9800998ecf8427e",
      "n": "icon_quick_nor",
      "r": false
    },
    "12267:711": {
      "h": "098f6bcd4621d373cade4e832627b4f6",
      "n": "icon_quick_nor",
      "r": true
    }
  }
}
```

字段说明：
- `version`: Figma 文件版本号，用于快速路径判断
- `ltrCount` / `rtlCount`: 缓存时的资源数量，version 匹配时直接返回
- `assets.{nodeId}.h`: SVG 内容的 MD5 哈希值
- `assets.{nodeId}.n`: 清理后的文件名（不含扩展名）
- `assets.{nodeId}.r`: 是否为 RTL 资源

### 为什么用 MD5

- 计算速度快（比 SHA-256 快 2-3 倍）
- 碰撞概率在此场景下可忽略（94 个文件，每天改几个）
- JDK 内置 `java.security.MessageDigest`，无需额外依赖

## 改动范围

### 1. FigmaFetcher.java（主要改动）

```
新增方法:
  loadCache(File cacheFile) → Map<String, Map<String, String>>
  saveCache(File cacheFile, String version, int ltr, int rtl, assets)
  md5Hex(byte[] data) → String
  downloadBytes(String url) → byte[]    // 替换 downloadFile
  cleanStaleFiles(File dir, ...)        // 清理已删除节点的旧文件

修改方法:
  fetch(...) 新增参数 File cacheFile
    - 在获取 fileJson 后立即检查 version
    - 下载改为内存 → hash 比较 → 按需写入
    - 结束时保存缓存 + 清理过期文件

不涉及:
  collectAssets, isTemplateNode, isRtlComponent 等树遍历逻辑不变
```

### 2. SvgToVectorConverter.java（小改动）

```
修改方法:
  convertAll(...) 
    - 转换前检查: 目标 XML 是否存在 && 修改时间 >= 源 SVG 修改时间
    - 如果 XML 比 SVG 新 → 跳过转换
```

### 3. app/build.gradle.kts（一行改动）

```kotlin
// 新增缓存文件路径
val cacheFile = file("src/main/assets/figma/.figma-cache.json")

// fetch 调用增加参数
val counts = FigmaFetcher.fetch(
    token, fileKey, startNode, svgDir, svgRtlDir, cacheFile, scale, format, logFn
)
```

## 性能预期

| 场景 | 当前耗时 | 优化后耗时 | 说明 |
|------|---------|-----------|------|
| Figma 无任何变更 | ~97s | ~2s | 只走快速路径，一次 API 调用 |
| Figma 有变更，但当前节点未变更 | ~97s | ~2s | 同上 |
| Figma 有变更，部分资产变更 | ~97s | ~97s | 仍需全量下载，但跳过未变文件写入 |
| Figma 有变更，全量变更 | ~97s | ~97s | 无节省，但缓存更新为下次准备 |

> 注意：Figma API 不提供 per-node 时间戳，所以 version 变更后仍需下载全部 SVG 才能做 hash 对比。真正的带宽节省依赖快速路径（version 不变完全跳过）。

## 边界情况

| 场景 | 处理方式 |
|------|---------|
| 首次运行（无缓存文件） | 全量下载，创建缓存 |
| 缓存文件损坏/格式错误 | 忽略缓存，全量下载，重新创建 |
| Figma 中删除了某个资源 | `cleanStaleFiles` 删除对应的本地 SVG |
| Figma 中新增了资源 | hash 不在缓存中 → 写入文件 |
| 多个 COMPONENT 有相同文件名 | 现有的 `_1`, `_2` 后缀逻辑保持不变 |
| Figma API 返回错误 | 保持现有异常处理，不影响缓存 |

## 不做的事情

- **增量删除 drawable XML**：drawable 目录的文件以 SVG 目录为准，由 SvgToVectorConverter 覆盖生成。不需要单独清理。
- **并行下载**：当前批量下载已经满足需求，并行化是独立优化项。
- **CDN URL 缓存**：Figma CDN URL 是临时的，缓存无意义。

## 风险评估

- **风险低**：缓存逻辑是纯增量功能，不影响现有下载流程
- **回滚简单**：删除 `.figma-cache.json` 即回退到全量下载
- **数据一致性**：version 由 Figma 官方保证递增，hash 使用标准 MD5
