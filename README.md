# HopperFill

HopperFill 是一个 Minecraft Fabric 模组，提供区域扫描、自动填充漏斗线、可视化木锄选区工具，以及 `/hf give` 一键将物品按分类打包进潜影盒。

> 仅限创造模式使用，所有命令与交互均在创造模式下生效。

## 功能特性

- **区域扫描**：圈选一块区域，自动统计其中的方块与物品（优先识别展示框中的物品）。
- **跳过方块（黑名单）**：在 `/hf set gui` 界面中添加扫描时要忽略的方块（泥土、石头等填充物）。
- **漏斗线自动填充**：按 16 堆叠 / 64 堆叠两套模板，把扫描结果自动写入一条连续的漏斗线。
- **木锄可视化操作**：手持木锄右键分步选区，即可完成 scan → fill 全流程。
- **物品给予 `/hf give`**：
  - `all` / `stackable` / `nonstackable` / `category` 四种模式
  - 数量可选：`all`（满堆叠）、`all-1`（满堆叠 -1）、或具体数字
  - 按分类分盒，16 色潜影盒循环染色；`category` 模式下盒子会按分类命名
  - 自动排除刷怪蛋、创造/生存无法获取的物品、潜影盒自身
- **统一设置界面 `/hf set gui`**：
  - 黑名单（物品）与跳过方块（方块）两个标签页
  - REI 风格物品图标网格，按创造模式物品组排序
  - 支持中文名 / 英文 ID 搜索
  - 「可添加 / 已添加」双视图，已添加项置灰打勾、点击即可移除
  - 配置下拉选择器：全部 / 可堆叠 / 64 堆叠 / 16 堆叠 / 不可堆叠

## 命令

| 命令 | 说明 |
|---|---|
| `/hf` | 查看用法帮助 |
| `/hf set template` | 打开模板设置（原版漏斗界面，16/64 切换） |
| `/hf set gui` | 打开黑名单 / 跳过方块设置界面（跳过方块唯一入口） |
| `/hf scan <from> <to>` | 扫描区域统计 |
| `/hf fill <from> <to> <lineStart> <lineEnd>` | 填充漏斗线 |
| `/hf hoe on/off` | 开启 / 关闭木锄工具 |
| `/hf give all [数量]` | 给予可获取物品（装入潜影盒） |
| `/hf give stackable [数量]` | 仅给予可堆叠物品 |
| `/hf give nonstackable [数量]` | 仅给予不可堆叠物品 |
| `/hf give category [数量]` | 按分类分盒给予 |

数量参数支持：`all`（64 堆叠 = 64，16 堆叠 = 16）、`all-1`（63 / 15）、或具体数字（64 堆叠按给定数，16 堆叠按比例换算）。

## 支持版本

| Minecraft 系列 | 说明 |
|---|---|
| 1.21.x | 1.21、1.21.1 … 1.21.11 全系列 |
| 26.x | 26.1、26.1.1、26.1.2、26.2 |

每个小版本对应一个独立的 `.jar` 文件，请按你的 Minecraft 版本选择对应产物。

## 环境要求

- Minecraft 1.21.x 或 26.x
- Fabric Loader 0.19.3+
- Fabric API（对应你所用 Minecraft 版本）
- Java 21（1.21.x）/ Java 25（26.x）

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/) 与对应版本的 [Fabric API](https://modrinth.com/mod/fabric-api)。
2. 将与你游戏版本匹配的 `hopperfill-<版本>.jar` 放入 `.minecraft/mods/` 目录。
3. 进入游戏（创造模式），输入 `/hf` 查看用法。

## 使用流程

1. `/hf set template` 设置 16 堆叠与 64 堆叠模板。
2. `/hf set gui` 在界面中添加需要跳过的方块。
3. `/hf scan <from> <to>` 扫描区域，确认统计结果。
4. `/hf fill <from> <to> <lineStart> <lineEnd>` 沿漏斗线自动填充。
5. 或手持木锄，按提示右键分步完成 scan 与 fill。

## 许可证

[MIT](LICENSE)
