# Figma Sync Plugin — 应用侧接入指南

> 适用对象：Android 应用开发者
> 前置条件：已从设计师获取 Figma 文件的 fileKey 和起始节点 ID

---

## 1. 添加插件

### 方式一：本地 composite build（当前阶段）

将插件源码放到项目根目录下：

```
YourApp/
├── figma-sync-plugin/          ← 从 https://github.com/lixintyw/figma-sync-gradle-plugin clone
├── app/
│   ├── build.gradle.kts
│   └── src/main/res/...
├── settings.gradle.kts
└── build.gradle.kts
```

`settings.gradle.kts` 添加：
```kotlin
pluginManagement {
    includeBuild("figma-sync-plugin")
}
```

`app/build.gradle.kts` 添加：
```kotlin
plugins {
    id("com.android.application")
    id("com.figma.sync")
}
```

### 方式二：Maven 发布（待插件发布后）

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://your-maven-repo/releases") }
    }
}

// app/build.gradle.kts
plugins {
    id("com.figma.sync") version "1.0.0"
}
```

---

## 2. 配置 Figma Token

在项目根目录 `local.properties` 中添加（该文件已在 `.gitignore` 中）：

```properties
figma.token=figd_xxxxxxxxxxxxxxxxxxxx
```

Token 生成地址：https://www.figma.com/settings → Personal Access Tokens → 勾选 `file_content:read`

---

## 3. 最小化配置

在 `app/build.gradle.kts` 中：

```kotlin
figmaSync {
    fileKey = "G4GyegR4f1uHsW5ZdBuOaY"  // Figma 文件 URL 中的 key

    icons {
        enabled = true
        startNode = "14832:59978"       // 图标所在 Section/Frame 的节点 ID
    }
}
```

**如何获取 startNode：** 在 Figma 中右键目标 Section → Copy link → URL 中 `node-id=14832-59978` → 将 `-` 替换为 `:` → `14832:59978`

---

## 4. 钩入构建流程

在 `app/build.gradle.kts` 末尾添加，使每次构建前自动同步：

```kotlin
// preBuild 之前自动同步图标
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("syncFigmaIcons")
}
```

---

## 5. 首次同步

```bash
./gradlew syncFigmaIcons
```

输出示例：
```
[Figma] Fetching file structure (depth=4) ...
[Figma] Version: 2361395147552737305
[Figma] Found 82 components to render
[Figma] Downloaded 80 LTR + 2 RTL SVG assets
[Figma] Converted 80 LTR → app/src/main/res/drawable
[Figma] Converted 2 RTL → app/src/main/res/drawable-ldrtl
```

同步后，资源自动出现在：
```
app/src/main/res/
├── drawable/           ← LTR 图标 VectorDrawable（icon_xxx.xml）
└── drawable-ldrtl/    ← RTL 图标 VectorDrawable
```

---

## 6. 在代码中使用

```kotlin
// Kotlin
imageView.setImageResource(R.drawable.icon_car_door)
```

```xml
<!-- XML -->
<ImageView
    android:layout_width="24dp"
    android:layout_height="24dp"
    android:src="@drawable/icon_car_door" />
```

---

## 7. 常用操作

```bash
# 同步图标
./gradlew syncFigmaIcons

# 同步颜色令牌（需 Enterprise plan）
./gradlew syncFigmaTokens

# 查看已冻结的版本
./gradlew figmaListVersions

# 锁定某个版本（CI 出包时用）
# 在 build.gradle.kts 中设置: pinnedVersion = "2361395147552737305"
```

---

## 8. 进阶配置

### 只下载需要的图标（白名单）

```kotlin
icons {
    enabled = true
    startNode = "14832:59978"
    declarations = listOf(            // 只下载列表中的图标
        "icon_car_door",
        "icon_window",
        "icon_low_beam"
    )
}
```

### 同步颜色令牌

```kotlin
tokens {
    enabled = true
    chainDownload = true              // 自动解析令牌别名链
}
```

执行后生成 `res/values/figma_colors.xml`，图标 VectorDrawable 会自动引用 `@color/figma_xxx`：

```xml
<vector ...>
    <path
        android:fillColor="@color/figma_text_color_icon_1primary_primary_02_80"
        android:pathData="..." />
</vector>
```

### 多模块拆分

```kotlin
modules {
    module("vehicle_control") {
        startNode = "14832:59978"
    }
    module("lighting") {
        startNode = "14832:60000"
    }
}
```

```bash
./gradlew syncFigmaIcons_vehicleControl   # 只同步车辆控制模块
./gradlew syncFigmaIcons_lighting         # 只同步灯光模块
./gradlew syncFigmaIcons                  # 同步全部模块
```

### 锁定版本（CI 出包）

```kotlin
figmaSync {
    fileKey = "G4Gyeg..."
    pinnedVersion = "2361395147552737305"  // 锁定版本，跳过 API 调用
}
```

---

## 9. CI 环境配置

在 GitHub Actions Secrets 中添加 `FIGMA_TOKEN`，然后在 workflow 中：

```yaml
- name: Configure Figma token
  env:
    FIGMA_TOKEN: ${{ secrets.FIGMA_TOKEN }}
  run: echo "figma.token=$FIGMA_TOKEN" >> local.properties

- name: Sync Figma assets
  run: ./gradlew syncFigmaIcons syncFigmaTokens
```

---

## 10. 同步机制说明

| 场景 | 行为 | 耗时 |
|------|------|------|
| Figma 无变更 | 跳过全部下载（version 命中缓存） | < 1s |
| 图标未修改 | 跳过单个文件写入（MD5 hash 命中） | 毫秒级 |
| 有新增/修改 | 仅下载变更的图标 | 正常网络耗时 |
| 首次同步 | 下载全部图标 | 取决于图标数量 |

增量同步依赖 `build/figma/.figma-cache.json`，不要加入 `.gitignore`（多人协作时各开发者独立缓存）。

---

## 11. 故障排查

| 现象 | 处理 |
|------|------|
| `403 Forbidden` | Token 过期，重新生成 |
| `Task not found` | 检查 `id("com.figma.sync")` 是否在 plugins 块中 |
| 图标不更新 | 删除 `build/figma/.figma-cache.json` 后重试 |
| RTL 图标未分离 | Figma 组件名需包含 "RTL"（大小写不敏感） |
| 颜色令牌为空 | 免费版 Figma 不支持 Variables API，使用插件模式 |

---

## 12. 接入检查清单

- [ ] `settings.gradle.kts` 中添加 `includeBuild("figma-sync-plugin")`
- [ ] `app/build.gradle.kts` 中添加 `id("com.figma.sync")`
- [ ] `local.properties` 中配置 `figma.token=...`
- [ ] `figmaSync { fileKey = "..." }` DSL 配置完成
- [ ] `icons { enabled = true; startNode = "..." }` 配置完成
- [ ] `preBuild` 钩子添加完成
- [ ] `./gradlew syncFigmaIcons` 执行成功
- [ ] `res/drawable/` 下有生成的 `icon_*.xml` 文件
- [ ] CI `FIGMA_TOKEN` secret 已配置
