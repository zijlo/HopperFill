# HopperFill

**简体中文** | [English](README_EN.md)

HopperFill 是一个 Minecraft Fabric 模组，提供区域扫描、自动填充漏斗线、可视化木锄选区工具，以及 `/hf give` 一键将物品打包进潜影盒。支持创造与生存两种模式（部分命令为创造模式专属）。

## 功能特性

- **区域扫描**：圈选一块区域，自动统计其中的方块与物品（优先识别展示框中的物品），统计结果在聊天栏按真实字体宽度对齐显示。
- **一片多物品检测**：一个漏斗只能放一种物品，若同一切片内出现多种物品，扫描会直接报错并列出冲突位置，提示你把这些物品加入「跳过方块」后重试（木锄状态自动重置，可直接重新圈选）。
- **跳过方块（黑名单）**：在 `/hf set gui` 界面中添加扫描时要忽略的方块（泥土、石头等填充物）。
- **漏斗线自动填充**：按 16 堆叠 / 64 堆叠两套模板，把扫描结果自动写入一条连续的漏斗线；生存模式下会消耗背包内的对应材料。
- **木锄可视化操作**：手持木锄右键分步选区，4 步完成 scan → fill 全流程（生存模式下自动消耗材料）。
- **scan 后的快捷操作**：木锄第 2 下右键（scan）完成后，聊天栏会给出一行可点击入口——
  - `[清空漏斗]`：仅当**选区里全是容器**（箱子 / 木桶 / 漏斗 / 潜影盒 / 熔炉…）时出现，清空这些容器的内容，生存返还物品、创造直接清空；
  - `[满盒物品]`：仅**创造模式**出现，把区域内扫描到的每种物品装满一个纯净潜影盒。
  - 点击后除执行操作外还会**重置木锄状态**，下一次右键从「区域起点」重新开始，不会残留上一次的选区与流程。
- **清除漏斗 `/hf clear`**：带坐标时清空漏斗线内容；不带坐标时清空**木锄选区内所有容器**的内容（生存模式先返还物品）。
- **物品给予 `/hf give`**（仅创造模式）：
  - `all` / `stackable` / `nonstackable` / `box` 四种模式
  - 数量可选：`all`（满堆叠）、`all-1`（满堆叠 -1）、或具体数字
  - 16 色潜影盒循环染色
  - 自动排除刷怪蛋、生存无法获取的物品、潜影盒自身
- **满盒物品 `/hf givebox`**（仅创造模式）：把区域内扫描到的每种物品装满一个纯净潜影盒给予玩家。
- **统一设置界面 `/hf set gui`**：
  - 黑名单（物品）、跳过方块（方块）、满盒物品 三个标签页
  - REI 风格物品图标网格，按创造模式物品组排序
  - 支持中文名 / 英文 ID 搜索
  - 「可添加 / 已添加」双视图，已添加项置灰打勾、点击即可移除
  - **只列生存可获取的内容**：黑名单 / 满盒物品 不再出现命令方块、屏障、刷怪蛋、基岩等生存拿不到的物品；跳过方块 会滤掉命令方块、结构方块、屏障、光源等创造专用方块

## 命令

| 命令 | 说明 |
|---|---|
| `/hf` | 查看用法帮助 |
| `/hf set template` | 打开模板设置（原版漏斗界面，16/64 切换） |
| `/hf set gui` | 打开设置界面（黑名单 / 跳过方块 / 满盒物品） |
| `/hf scan <from> <to>` | 扫描区域统计 |
| `/hf fill <from> <to> <lineStart> <lineEnd>` | 填充漏斗线（生存模式消耗材料） |
| `/hf clear <lineStart> <lineEnd>` | 清除漏斗线内容（生存返还物品，创造直接清空） |
| `/hf clear` | 清除**木锄选区内所有容器**的内容（生存返还物品），无参数形式 |
| `/hf givebox <from> <to>` | 创造：把区域内每种物品装满一个纯净潜影盒 |
| `/hf hoe on/off` | 开启 / 关闭木锄工具 |
| `/hf give all [数量]` | 创造：给予可获取物品（装入潜影盒） |
| `/hf give stackable [数量]` | 创造：仅给予可堆叠物品 |
| `/hf give nonstackable [数量]` | 创造：仅给予不可堆叠物品 |
| `/hf give box` | 创造：按「满盒物品」清单给予纯净满潜影盒 |

数量参数支持：`all`（64 堆叠 = 64，16 堆叠 = 16）、`all-1`（63 / 15）、或具体数字（64 堆叠按给定数，16 堆叠按比例换算）。

## 木锄操作流程（共 4 步）

| 第几次右键 | 作用 |
|---|---|
| 第 1 下 | 设置区域起点 |
| 第 2 下 | 设置区域终点 → 自动 scan；完成后给出可点击的快捷操作 |
| 第 3 下 | 设置漏斗线起点 |
| 第 4 下 | 设置漏斗线终点 → fill（生存转为逐 tick 渐进填充，再右键木锄可手动结束） |

任一步骤出错（一片多物品 / 不可堆叠物品 / 漏斗线不连续 / 材料不足）都会报错并重置木锄，直接重新右键即可。

## 支持版本与下载

| Minecraft 系列 | 产物文件 | 需要 Java |
|---|---|---|
| 1.21.11 ～ 1.21.x | `hopperfill-4.0.1-fabric-mc1.21.x.jar` | 21 |
| 26.1 – 26.3 | `hopperfill-4.0.1-fabric-mc26.1.x.jar` | 25 |

当前版本 **4.0.1**。下载地：[Releases](https://github.com/zijlo/HopperFill/releases) 与 [Modrinth](https://modrinth.com/user/zijlo)。

> `mc1.21.x` 包的编译基准是 **1.21.11**，`fabric.mod.json` 声明 `>=1.21.11 <1.22`——因为客户端界面用到了 1.21.9+ 才提供的 API。若需要 1.21.0 ～ 1.21.10，请用 `build-121x.sh` 按小版本自行构建（每个小版本产出一个 jar）。

> `mc26.1.x` 包**一个就覆盖 26.1 ～ 26.3**。26.3 改了两处 API：渲染管线从 `com.mojang.blaze3d` 迁到了 `com.mojang.renderpearl`，`Player.drop(ItemStack, boolean)` 也换成了带 `Prediction` 参数的新签名。模组把这两处改成**按名字反射解析**，所以同一个 jar 在 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 上都能用（已做符号级校验）。若将来 26.x 再出现破坏性变更，可用 `build-26x.sh` 按小版本另出包。

## 环境要求

- Minecraft 1.21.x 或 26.x
- Fabric Loader 0.19.3+
- Fabric API（对应你所用 Minecraft 版本）
- Java 21（1.21.x）/ Java 25（26.x）

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/) 与对应版本的 [Fabric API](https://modrinth.com/mod/fabric-api)。
2. 将与你游戏版本匹配的 `hopperfill-<版本>-fabric-mc<系列>.jar` 放入 `.minecraft/mods/` 目录。
3. 进入游戏，输入 `/hf` 查看用法。

## 使用流程

1. `/hf set template` 设置 16 堆叠与 64 堆叠模板。
2. `/hf set gui` 在界面中添加需要跳过的方块（以及满盒物品）。
3. `/hf scan <from> <to>` 扫描区域，确认统计结果。
4. `/hf fill <from> <to> <lineStart> <lineEnd>` 沿漏斗线自动填充（生存模式需准备材料）。
5. 或手持木锄，按提示右键 4 步完成 scan 与 fill。

## 源码结构

两个模块（`hopperfill-1.21-survival` 用 Yarn 映射，`hopperfill-26-survival` 用 Mojang 官方映射）源码结构一致，各只有 12 个文件：

| 文件 | 职责 |
|---|---|
| `HopperFillMod` | 模组入口，注册网络 / 命令 / 事件 / tick |
| `client/HopperFillClient` | 客户端入口，扫描结果的聊天栏排版 |
| `client/gui/SettingsScreen` | 设置界面（三个标签页） |
| `client/gui/TemplateScreen` | 模板编辑界面 |
| `command/HopperFillCommand` | 全部 `/hf` 命令 + `give` 数量策略 |
| `data/TemplateStorage` | 玩家数据（模板 / 黑名单 / 跳过方块 / 满盒物品）持久化 |
| `fill/FillService` | 漏斗线几何 + 填充校验 + 一次性填充 + 生存逐 tick 会话 |
| `region/RegionScanner` | 区域切片扫描、一片多物品检测、容器判定 |
| `tool/HoeToolHandler` | 木锄 4 步状态机 + 可点击快捷操作 |
| `gui/TemplateScreenHandler` | 模板界面容器逻辑 |
| `network/SettingsNetwork` | 设置界面的全部载荷与收发 |
| `util/ItemFilter` | 生存可获取 / 排除名单 |

## 构建

```bash
# 1.21.x（Yarn 映射，需 JDK 21）
cd hopperfill-1.21-survival && ./gradlew build

# 26.x（Mojang 官方映射，需 JDK 25）
cd hopperfill-26-survival && ./gradlew build
```

产物名由 `gradle.properties` 的 `archives_base_name` + `mod_version` + `artifact_suffix` 决定，
即 `hopperfill-<mod_version>-fabric-<artifact_suffix>.jar`。多版本构建脚本会按小版本改写 `artifact_suffix`。

## 许可证

[MIT](LICENSE)
