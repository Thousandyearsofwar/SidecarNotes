# Sidecar Notes（侧挂笔记）

给代码区间挂「侧挂笔记」的 JetBrains Rider 插件：**一个字节都不写进源文件**，
笔记存在项目里的独立 JSON 分片中，可以跟仓库一起提交、团队共享。

![编辑器与面板](docs/screenshots/editor-and-panel.png)

- 目标宿主：JetBrains Rider 2026.2（`since-build=262`）
- 当前版本：`0.98.0`（变更记录见 [CHANGELOG.md](CHANGELOG.md)）
- 详细操作手册：[docs/usage.md](docs/usage.md)
- 设计/实现笔记：[docs/ridenotes-design-v1.md](docs/ridenotes-design-v1.md)

> 仓库目录名仍为 `RiderNotes`；插件在 IDE 与 Marketplace 上显示为 **Sidecar Notes**
> （JetBrains 自 2024-03 起禁止插件名包含 "Rider"）。

---

## 能做什么

| 能力 | 说明 |
|---|---|
| 代码区间锚点 | 选中一段代码 → `Ctrl+Alt+N` 建笔记；区间用颜色带标出 |
| 一条锚点多条笔记 | 追加/编辑/删除单条笔记，最新在前 |
| 编辑器内摘要块 | 锚点下方一个块，显示标题 + 每条笔记（首行右侧右对齐时间） |
| 就地编辑 | 双击摘要块，浮层盖在块上编辑，`Ctrl+Enter` 保存 / `Esc` 取消 |
| 分组树面板 | 分组可嵌套、拖拽排序、缩进 + 引导线显示层级 |
| **克隆组** | 分组的只读镜像，实时跟随源组增删改；可展开、可拖到其他组下 |
| 三个开关 | 显示 / 锁定 / 简洁，分组上的设置会被子项**继承** |
| 颜色 | 8 色，可覆盖、可继承 |
| 标签库 | 标签分组管理、颜色、勾选式打标签；分组标签会被子项**继承**（折叠为 `+N`） |
| **筛选** | 文本匹配标题/正文/文件/标签；标签多选；筛选不改变你的折叠状态 |
| **撤销** | 笔记增删改、颜色、开关、重命名、标签、删除都支持 `Ctrl+Z` |
| 锁定 | 锁定后编辑器与面板都禁止编辑/删除 |
| 过期提示 | 代码变了只标 `⚠已变更`，不移动、不消失 |
| 笔记内链接 | `[文字](href)` 或 `<a href>`：跳锚点 / 打开本地 md、html / 打开网页 |
| 导入导出 | 从 IDE 书签导入；导出 Markdown / PR 评论 |
| 本地 HTTP 接口 | 脚本/AI 批量建笔记、改笔记（默认关闭） |
| 多语言 | 英文（默认）/ 简体中文，跟随 IDE 语言 |

---

## 安装

### 从 Marketplace

`Settings → Plugins → Marketplace` 搜索 **Sidecar Notes** → Install → 重启 Rider。

### 从 zip 手动安装

```powershell
# 1. 构建（产物：build\distributions\RiderNotes-<version>.zip）
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat buildPlugin --console=plain

# 2. 装进日常 Rider（先删旧版本）
$plugins = "$env:APPDATA\JetBrains\Rider2026.2\plugins"
Get-ChildItem $plugins -Directory | Where-Object { $_.Name -like 'RiderNotes*' } | Remove-Item -Recurse -Force
Expand-Archive build\distributions\RiderNotes-0.98.0.zip -DestinationPath $plugins -Force
```

重启 Rider 生效。或用 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选 zip。

- 日志：`%LOCALAPPDATA%\JetBrains\Rider2026.2\log\idea.log`，插件日志前缀 `[RN]`
- 卸载：删掉插件目录，或 `Settings → Plugins` 里 Uninstall
- `gradle.properties` 的 `riderHome` 指向本机 Rider 安装目录，换机器改这一行

---

## 快速上手

1. 在编辑器里**选中一段代码**（纯空白选区会被拒绝）
2. 按 `Ctrl+Alt+N`，输入笔记，`Ctrl+Enter` 保存
3. 代码下方出现摘要块与左侧颜色带；右侧工具窗口 **Sidecar Notes** 里出现该笔记
4. 在面板里右键 → **新建分组**，把笔记拖进去；再右键分组 → **创建克隆组** 体验镜像
5. 给分组打标签：右键分组 → **添加标签…**；组内笔记会自动带上（显示为 `+N`）

完整操作手册见 **[docs/usage.md](docs/usage.md)**。

---

## 编辑器内操作

| 操作 | 效果 |
|---|---|
| 单击摘要块 | 光标移到锚点起始行 |
| 双击摘要块 | 就地编辑该锚点最新一条笔记 |
| 点 gutter 图标 | 直接进入就地编辑 |
| 点笔记里的链接 | 见下方「笔记里的链接」 |
| 摘要块左侧 3px 竖条 | 该锚点的颜色 |
| 每条笔记首行右侧 | `MM-dd HH:mm`，右对齐 |

## 面板操作（右侧工具窗口）

| 操作 | 效果 |
|---|---|
| 双击分组行 | 展开 / 折叠 |
| 双击笔记行 | 跳到代码并展开该锚点的子笔记（多条时） |
| 点 `▾/▸` | 只展开/折叠，不跳转 |
| `F2` / `Shift+F6` | 重命名 |
| `Delete` | 删除选中项（支持多选批量） |
| `Ctrl+Z` | 撤销（笔记、颜色、开关、重命名、标签、删除） |
| 拖拽 | 拖进分组（中间）/ 插到前面（上 1/4）/ 插到后面（下 1/4）；拖到列表底部空白处落到末尾 |
| 右键 | 颜色、追加笔记、展开/收起笔记、添加标签、创建克隆组、复制锚点链接、打开引用的文件、重新定位、重新绑定、删除 |
| 顶部过滤框 | 输入即过滤：标题 / 正文 / 文件路径 / 标签 |
| 「标签筛选」 | 按标签库多选筛选，命中任一即保留（包含继承标签） |
| 「标签库」 | 打开标签管理窗口 |
| 「更多 ▾」 | 刷新、清理归属、从 IDE 书签导入、导出 Markdown / PR 评论 |

三列开关：**显示**（不渲染）、**锁定**（禁止改）、**简洁**（每条笔记压成一行）。
分组上的开关会被子项继承，带 `（继承）` 标记；克隆组是不可编辑的只读镜像。

## 笔记里写代码

用 ``` 围栏包起来的代码会在摘要块里**按当前文件的语法着色**（颜色取自 IDE 配色方案，跟随深/浅主题）：

````markdown
```cpp
for (FRDGPassHandle Pass : Passes)
{
    if (Pass->IsCulled()) continue;
    State->Flags |= TransitionFlags;
}
```
````

- ` ``` ` 围栏本身不显示，里面的行用宿主文件的 `Language` 做词法着色（关键字/字符串/数字/注释/类型名）
- 不做语义分析；跨行注释、字符串能正确续接
- 语言以**当前文件**为准（`.cpp` 里写 C# 会按 C++ 高亮），暂不支持 ` ```csharp ` 指定语言
- 代码行里的链接不做点击命中
- 颜色来自 `Settings → Editor → Color Scheme`，换主题会跟着变
## 笔记里的链接

```markdown
[热路径说明](anchor:a_1a2b3c4d)                  ← 跳到另一个锚点
[设计文档](docs/design.md)                        ← 用系统默认程序打开
[UE 笔记](diagrams/walkthrough.html)              ← 用浏览器打开
[UE 笔记](E:/UnrealProjects/UEProj/docs/x.html)   ← 绝对路径也支持
<a href="https://dev.epicgames.com/documentation/">官方文档</a>
```

- 只有**链接**会被渲染：显示成文字 + 蓝色下划线 + 可点击
- Markdown/HTML 的其它标记（`**粗**`、`#`、`` `code` ``、`<b>` 等）**按原样显示**，不做样式渲染
- `.md` / `.html` / `.txt` 等本地文件在点击时交给**系统默认程序**打开（Windows 上 html 即默认浏览器）
- 本地文件不存在时提示「引用文件不存在」

## 标签继承

- 一条笔记的生效标签 = **自己的标签** ∪ **所属分组链上的标签**
- 面板里自己的标签逐个彩色显示，继承来的折叠成灰色 `+N`
- 标签筛选会匹配继承标签：勾选父组的标签，子组里的笔记也会被筛出来

---

## 数据放在哪

```
<项目根>/.ridenotes/
  ├── _project.json                         # 分组树 + 标签库 + 面板状态
  └── Source__Foo__Bar.cpp.notes.json       # 每个源文件一个分片
```

- 源文件路径转义为 `__`
- 写入走临时文件 + 原子替换
- **提交到 git 即可团队共享**

`_project.json`（schema 5）：

```jsonc
{
  "schema": 5,
  "groups": [{ "id", "title", "parentId", "orderValue", "color", "visible",
                "locked", "compact", "tags": [], "mirrorOf": null }],
  "tags": [{ "id", "name", "color", "groupId" }],
  "tagGroups": [{ "id", "name", "order" }],
  "collapsedGroups": [], "expandedAnchors": [],
  "defaultGroupId": null
}
```

分片（schema 5，锚点直接携带归属）：

```jsonc
{
  "schema": 5,
  "file": "相对路径",
  "anchors": [{
    "id", "startLine", "endLine", "anchorText", "anchorSha1",
    "contextBefore", "contextAfter", "title", "color",
    "groupId", "orderValue", "mirrorOf", "createdAt", "updatedAt",
    "notes": [{ "id", "body", "author", "createdAt", "updatedAt", "tags": [] }]
  }]
}
```

---

## 锚定模型（重要）

**按行号锚定，书签语义**：

| 时机 | 行为 |
|---|---|
| 会话内 | `RangeMarker` 跟随编辑，行号实时更新 |
| 跨会话打开 | 认存储的 `startLine/endLine`，夹到文件范围内 |
| 原文对不上 | 只标 `⚠已变更`，笔记留在原位，不搜索、不移动 |
| 想找回来 | 右键 →「尝试重新定位」（搜一次，弹确认）或「重新绑定到当前选区」 |

代价：在锚点上方插入/删除行，跨会话会整体偏移（与 Rider 自带书签一致），会话内不会。

---

## 设置

入口：**Sidecar Notes 面板工具栏的「设置」按钮**（插件自己的对话框）。

- 字体 / 字号（留空跟随 IDE 标签字体）
- 摘要最大宽度：`0` = 跟随窗口；`> 0` 作为上限
- 在编辑器中显示摘要块（关掉后只保留色带和 gutter 图标）
- 本地 HTTP 接口：开关、端口、令牌
- 笔记库目录（项目级）

---

## 本地 HTTP 接口（自动化 / 压测 / AI）

默认**关闭**。开启后项目里生成能力文件 `.ridenotes/api.json`（同目录 `.gitignore` 会排除）：

```jsonc
{ "port": 47800, "token": "…", "version": "0.98.0", "project": "UEIntroProject" }
```

除 `/ping` 外所有请求需带 `X-Sidecar-Token` 请求头。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/status` | 项目 / 库路径 / 版本 / 锚点数 / 笔记数 |
| GET | `/anchors?file=&q=` | 列出或按文本搜索锚点 |
| POST | `/anchors` | 建一条 |
| POST | `/anchors/batch` | 批量建 |
| POST | `/anchors/update` | 改标题 / 颜色 / 显示 / 锁定 / 简洁 / 归属 |
| POST | `/anchors/move` | 移动到分组 |
| POST | `/anchors/delete` | 按 id 删除 |
| POST | `/notes` | 给已有锚点追加笔记 |
| POST | `/notes/update` | 改笔记正文 / 标签 |
| POST | `/notes/delete` | 删除单条笔记 |
| GET | `/groups` · POST `/groups` `/groups/update` `/groups/delete` | 分组读写 |
| GET | `/tags` · POST `/tags` `/tags/update` `/tags/delete` | 标签库读写 |
| POST | `/batch` | 混合批量操作（一次写盘、一次重绘） |
| GET | `/changes?since=` | 增量比对 |
| GET | `/export?format=md\|pr` | 导出 Markdown / PR 评论 |
| POST | `/clear` | 清空文件或整库 |

完整协议与示例见 [docs/ai-cli.md](docs/ai-cli.md)。调用方可带 `X-Sidecar-Client: my-agent/1.0` 标识来源。

---

## 快捷键

| 快捷键 | 作用 | 作用域 |
|---|---|---|
| `Ctrl+Alt+N` | 用当前选区新建锚点笔记 | 编辑器 |
| `Ctrl+Shift+Alt+N` | 同上（备用） | 编辑器 |
| `F2` / `Shift+F6` | 重命名选中项 | 面板 |
| `Delete` | 删除选中项 | 面板 |
| `Ctrl+Z` | 撤销 | 面板 |
| `Ctrl+Enter` / `Esc` | 保存 / 取消就地编辑 | 浮层 |

编辑器右键菜单 → **Sidecar Notes** 子菜单：新建、删除、显示/锁定/简洁切换、刷新。

---

## 已知限制

- **Diff 视图内不显示**锚点
- 摘要块是 inlay：编辑用盖在上面的浮层（视觉上等同就地编辑）
- 折叠区间内的锚点不可见
- 拖拽排序、标签库对话框内的改动暂不支持 `Ctrl+Z`
- HTTP 接口需令牌；可关闭接口或重新生成令牌
- 「清理归属」是手动操作，不会自动执行
- 从 IDE 书签恢复时，列表结构可能与原书签不一致（见 [docs/known-issues.md](docs/known-issues.md)）

---

## 开发

| 项 | 版本 |
|---|---|
| Rider | 2026.2.3.1（`262.10968.170`） |
| JDK | 25（`C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot`） |
| Gradle | 9.9.0（wrapper 已带） |
| Kotlin | 2.4.21 |
| IntelliJ Platform Gradle Plugin | 2.20.0 |

```bash
./gradlew buildPlugin   # 产物 build/distributions/RiderNotes-<version>.zip
./gradlew runIde        # 沙箱实例
./gradlew verifyPlugin  # 兼容性检查（Marketplace 前必跑）
```

### 结构

```
model/      Anchor / Note / GroupNode / TagDef、Anchoring、AnchorStore、NoteLinks、NoteEmbeds
service/    NoteAnchorService（缓存、持久化、分组、克隆组、标签、快照/书签导入）
editor/     AnchorRenderService（色带、gutter、摘要块、链接命中、就地编辑）
ui/         NotesPanel、TagLibraryDialog、NotesSettingsDialog、EmbedOpener、NotePopups
actions/    CreateAnchor / DeleteAnchor / Refresh / Toggle*、registerUndo
settings/   NotesSettings（全局持久化）
api/        LocalApiServer（本地 HTTP 接口）+ NotesStartup
```

`docs/ridenotes-design-v1.md` 记录了实现时踩到的平台坑（贪婪标志、inlay 不跟随、
JTree/JTable 绘制、字体回退等），改渲染相关代码前先看。

---

## 许可

MIT，见 [LICENSE](LICENSE)。
