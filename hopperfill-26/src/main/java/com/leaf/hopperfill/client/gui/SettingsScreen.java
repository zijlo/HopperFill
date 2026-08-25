package com.leaf.hopperfill.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * 统一设置界面（框架版，26.x / Mojang 非混淆命名）。
 *
 * 一个 GUI 同时承担「添加 / 查看 / 删除」三类操作，两个 tab 用顶部按钮切换，
 * 二者共享同一个搜索框。每个 tab 内自上而下分为两区：
 *   Tab[0] = 黑名单（物品）：
 *       搜索框（两个 tab 共用一个）
 *       [可添加候选区] —— 遍历 BuiltInRegistries.ITEM，按搜索词过滤；未加入的条目点击 = 添加
 *       [已添加区]     —— 当前黑名单条目，点击 = 删除（查看与删除合一）
 *   Tab[1] = 跳过方块（方块）：
 *       结构同上，遍历 BuiltInRegistries.BLOCK，操作 skip block 集合
 *
 * 即：添加（点候选区）、查看（读已添加区）、删除（点已添加区）全部在同一界面完成，
 * 不再需要独立的只读查看界面与 /hf set view 命令。
 *
 * 搜索（两个 tab 共用一个搜索框，切换 tab 不清空搜索词，仅重算列表）同时匹配两个维度：
 *   1) ID path（如 minecraft:diamond -> "diamond")
 *   2) 客户端显示名（ItemStack.getDisplayName().getString()，已按客户端语言翻译，中文环境下即中文名）
 * 因此中文搜索直接比对显示名，无需语言文件反查。
 *
 * 数据流（点击即增量同步）：
 *   点候选区条目 → 发 C2S UpdateBlacklistC2SPayload / UpdateSkipBlockC2SPayload(add=true)
 *   点已添加区条目 → 发对应 C2S 包(add=false)
 *   → 服务端 TemplateStorage 增删 → 后续 /hf give 自动读取并剔除，填充自动跳过。
 *
 * ⚠ 跨版本渲染 API 敏感：EditBox、GuiGraphics、Button、Component。逐版本构建时按对应版本校对。
 */
public class SettingsScreen extends Screen {
    private enum Tab { BLACKLIST, SKIP_BLOCK }

    private Tab currentTab = Tab.BLACKLIST;

    private final Set<String> blacklistSet;
    private final Set<String> skipBlockSet;

    protected SettingsScreen(Set<String> blacklistSet, Set<String> skipBlockSet) {
        super(Component.literal("HopperFill 设置"));
        this.blacklistSet = blacklistSet;
        this.skipBlockSet = skipBlockSet;
    }

    // TODO(26.x): 字段：搜索框 EditBox、两个 tab 按钮、候选区/已添加区各自的行缓存与滚动偏移。

    // TODO(26.x): init()：addRenderableWidget 搜索框 + 两个 tab 按钮；
    //   根据 S2C 初始集合把「已添加区」填充为选中态（这些条目同时从候选区隐藏）。

    // TODO(26.x): 搜索框文本变化监听：重算当前 tab 的候选区过滤结果。

    // TODO(26.x): render()：上半屏「可添加候选区」（点击=添加），下半屏「已添加区」（点击=删除）。

    // TODO(26.x): onMouseClick()：候选区行 → C2S(add=true)；已添加区行 → C2S(add=false)；tab 按钮 → 切换页。

    // TODO(26.x): 关闭无特殊保存：点击已实时增量同步。
}