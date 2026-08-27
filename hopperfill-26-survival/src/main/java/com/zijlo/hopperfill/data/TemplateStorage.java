package com.zijlo.hopperfill.data;

import com.zijlo.hopperfill.HopperFillMod;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 玩家数据存储。所有方法必须在服务端主线程调用。
 */
public class TemplateStorage {
    private static final Map<UUID, PlayerData> dataMap = new HashMap<>();

    /** 模板槽位数：与漏斗 5 槽一一对应，空槽表示过滤物的占位 */
    private static final int TEMPLATE_SLOTS = 5;

    private static class PlayerData {
        Set<Identifier> blockIds = new HashSet<>();
        /** 手动添加的 give 黑名单（与上面的跳过方块 blockIds 相互独立） */
        Set<Identifier> giveBlacklist = new HashSet<>();
        /** 手动添加的「满盒物品」清单（/hf give box 使用） */
        Set<Identifier> boxItems = new HashSet<>();
        List<ItemStack> template16 = new ArrayList<>();
        List<ItemStack> template64 = new ArrayList<>();
        boolean hoeEnabled = false;
    }

    private static PlayerData getData(ServerPlayer player) {
        return dataMap.computeIfAbsent(player.getUUID(), k -> new PlayerData());
    }

    public static void loadPlayer(MinecraftServer server, ServerPlayer player) {
        Path path = getPlayerDataPath(server, player.getUUID());
        if (!Files.exists(path)) return;
        try {
            CompoundTag tag = NbtIo.read(path);
            if (tag == null) return;
            PlayerData data = getData(player);
            data.blockIds.clear();

            ListTag blockList = tag.getListOrEmpty("blockIds");
            for (int i = 0; i < blockList.size(); i++) {
                String s = blockList.getString(i).orElse("");
                if (s.isEmpty()) continue;
                try {
                    data.blockIds.add(Identifier.parse(s));
                } catch (Exception e) {
                    HopperFillMod.LOGGER.warn("[HopperFill] 无法解析方块ID: {}", s);
                }
            }

            data.giveBlacklist.clear();
            ListTag blacklist = tag.getListOrEmpty("giveBlacklist");
            for (int i = 0; i < blacklist.size(); i++) {
                String s = blacklist.getString(i).orElse("");
                if (s.isEmpty()) continue;
                try {
                    data.giveBlacklist.add(Identifier.parse(s));
                } catch (Exception e) {
                    HopperFillMod.LOGGER.warn("[HopperFill] 无法解析黑名单物品ID: {}", s);
                }
            }

            data.boxItems.clear();
            ListTag boxList = tag.getListOrEmpty("boxItems");
            for (int i = 0; i < boxList.size(); i++) {
                String s = boxList.getString(i).orElse("");
                if (s.isEmpty()) continue;
                try {
                    data.boxItems.add(Identifier.parse(s));
                } catch (Exception e) {
                    HopperFillMod.LOGGER.warn("[HopperFill] 无法解析满盒物品ID: {}", s);
                }
            }

            data.template16 = readTemplate(tag.getCompoundOrEmpty("template16"));
            data.template64 = readTemplate(tag.getCompoundOrEmpty("template64"));
            data.hoeEnabled = tag.getBooleanOr("hoeEnabled", false);
        } catch (IOException e) {
            HopperFillMod.LOGGER.error("[HopperFill] 读取玩家数据失败: {}", player.getUUID(), e);
        }
    }

    public static void savePlayer(MinecraftServer server, ServerPlayer player) {
        Path path = getPlayerDataPath(server, player.getUUID());
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            HopperFillMod.LOGGER.error("[HopperFill] 无法创建玩家数据目录", e);
            return;
        }

        PlayerData data = getData(player);
        CompoundTag tag = new CompoundTag();
        ListTag blockList = new ListTag();
        for (Identifier id : data.blockIds) {
            blockList.add(StringTag.valueOf(id.toString()));
        }
        tag.put("blockIds", blockList);

        ListTag blacklist = new ListTag();
        for (Identifier id : data.giveBlacklist) {
            blacklist.add(StringTag.valueOf(id.toString()));
        }
        tag.put("giveBlacklist", blacklist);

        ListTag boxList = new ListTag();
        for (Identifier id : data.boxItems) {
            boxList.add(StringTag.valueOf(id.toString()));
        }
        tag.put("boxItems", boxList);
        tag.put("template16", writeTemplate(data.template16));
        tag.put("template64", writeTemplate(data.template64));
        tag.putBoolean("hoeEnabled", data.hoeEnabled);

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            NbtIo.write(tag, tmp);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            HopperFillMod.LOGGER.error("[HopperFill] 写入玩家数据失败: {}", player.getUUID(), e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }

    public static void removePlayer(UUID uuid) {
        dataMap.remove(uuid);
    }

    private static Path getPlayerDataPath(MinecraftServer server, UUID uuid) {
        return server.getWorldPath(LevelResource.ROOT).resolve("hopperfill/playerdata/" + uuid + ".nbt");
    }

    private static List<ItemStack> readTemplate(CompoundTag tag) {
        List<ItemStack> list = new ArrayList<>(TEMPLATE_SLOTS);
        ListTag items = tag.getListOrEmpty("items");
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i).orElse(new CompoundTag());
            DataResult<ItemStack> result = ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag);
            list.add(result.result().orElse(ItemStack.EMPTY));
        }
        return list;
    }

    private static CompoundTag writeTemplate(List<ItemStack> template) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        for (ItemStack stack : template) {
            if (stack == null || stack.isEmpty()) {
                items.add(new CompoundTag());
            } else {
                DataResult<Tag> result = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack);
                Tag nbt = result.result().orElse(new CompoundTag());
                if (nbt instanceof CompoundTag compoundTag) {
                    items.add(compoundTag);
                } else {
                    items.add(new CompoundTag());
                }
            }
        }
        tag.put("items", items);
        return tag;
    }

    public static void saveTemplate(ServerPlayer player, SimpleContainer container, int type) {
        PlayerData data = getData(player);
        List<ItemStack> template = new ArrayList<>(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            template.add(container.getItem(i).copy());
        }
        if (type == 16) {
            data.template16 = template;
        } else {
            data.template64 = template;
        }
        savePlayer(player.level().getServer(), player);
    }

    public static void loadTemplateInto(ServerPlayer player, SimpleContainer container, int type) {
        PlayerData data = getData(player);
        List<ItemStack> template = type == 16 ? data.template16 : data.template64;
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            if (i < template.size() && template.get(i) != null && !template.get(i).isEmpty()) {
                container.setItem(i, template.get(i).copy());
            } else {
                container.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    public static void addBlockId(ServerPlayer player, Identifier id) {
        getData(player).blockIds.add(id);
    }

    public static void removeBlockId(ServerPlayer player, Identifier id) {
        getData(player).blockIds.remove(id);
    }

    public static Set<Identifier> getBlockIds(ServerPlayer player) {
        return new HashSet<>(getData(player).blockIds);
    }

    public static void clearBlockIds(ServerPlayer player) {
        getData(player).blockIds.clear();
    }

    // ---------- give 黑名单 API（手动添加，区别于跳过方块） ----------

    public static void addGiveBlacklist(ServerPlayer player, Identifier id) {
        getData(player).giveBlacklist.add(id);
    }

    public static void removeGiveBlacklist(ServerPlayer player, Identifier id) {
        getData(player).giveBlacklist.remove(id);
    }

    public static boolean isGiveBlacklisted(ServerPlayer player, Identifier id) {
        return getData(player).giveBlacklist.contains(id);
    }

    public static Set<Identifier> getGiveBlacklist(ServerPlayer player) {
        return new HashSet<>(getData(player).giveBlacklist);
    }

    public static void clearGiveBlacklist(ServerPlayer player) {
        getData(player).giveBlacklist.clear();
    }

    public static void setGiveBlacklist(ServerPlayer player, Set<Identifier> ids) {
        getData(player).giveBlacklist = new HashSet<>(ids);
    }

    // ---------- 满盒物品 API（/hf give box 使用） ----------

    public static void addBoxItem(ServerPlayer player, Identifier id) {
        getData(player).boxItems.add(id);
    }

    public static void removeBoxItem(ServerPlayer player, Identifier id) {
        getData(player).boxItems.remove(id);
    }

    public static boolean isBoxItem(ServerPlayer player, Identifier id) {
        return getData(player).boxItems.contains(id);
    }

    public static Set<Identifier> getBoxItems(ServerPlayer player) {
        return new HashSet<>(getData(player).boxItems);
    }

    public static void clearBoxItems(ServerPlayer player) {
        getData(player).boxItems.clear();
    }

    public static void setBoxItems(ServerPlayer player, Set<Identifier> ids) {
        getData(player).boxItems = new HashSet<>(ids);
    }

    public static void setBlockIds(ServerPlayer player, Set<Identifier> ids) {
        getData(player).blockIds = new HashSet<>(ids);
    }

    public static void setHoeEnabled(ServerPlayer player, boolean enabled) {
        getData(player).hoeEnabled = enabled;
    }

    public static boolean isHoeEnabled(ServerPlayer player) {
        return getData(player).hoeEnabled;
    }

    /** 判断模板列表中是否存在至少一个非空物品 */
    public static boolean hasAnyItem(List<ItemStack> template) {
        if (template == null) return false;
        for (ItemStack s : template) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasTemplate16(ServerPlayer player) {
        return hasAnyItem(getData(player).template16);
    }

    public static boolean hasTemplate64(ServerPlayer player) {
        return hasAnyItem(getData(player).template64);
    }

    public static List<ItemStack> getTemplate16(ServerPlayer player) {
        return getData(player).template16;
    }

    public static List<ItemStack> getTemplate64(ServerPlayer player) {
        return getData(player).template64;
    }
}