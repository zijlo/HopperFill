package com.leaf.hopperfill.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leaf.hopperfill.HopperFillMod;
import com.mojang.serialization.JsonOps;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家数据存储。所有方法必须在服务端主线程调用。
 * 模板为 5 槽完整布局，空槽表示“过滤物占位”。
 */
public class TemplateStorage {
    /** 模板槽位数：与漏斗 5 槽一一对应 */
    private static final int TEMPLATE_SLOTS = 5;

    private static final Map<UUID, PlayerData> dataMap = new HashMap<>();
    // 防止单人游戏 DISCONNECT 重复触发导致空数据覆盖文件
    private static final Set<UUID> savedPlayers = new HashSet<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class PlayerData {
        Set<Identifier> blockIds = new LinkedHashSet<>();
        /** 手动添加的 give 黑名单（与上面的跳过方块 blockIds 相互独立） */
        Set<Identifier> giveBlacklist = new LinkedHashSet<>();
        List<ItemStack> template16 = new ArrayList<>();
        List<ItemStack> template64 = new ArrayList<>();
        boolean hoeEnabled = false;
    }

    private static PlayerData getData(PlayerEntity player) {
        return dataMap.computeIfAbsent(player.getUuid(), k -> new PlayerData());
    }

    private static Path getPlayerFile(MinecraftServer server, UUID uuid) {
        return server.getSavePath(WorldSavePath.ROOT)
                .resolve("hopperfill").resolve("playerdata").resolve(uuid + ".json");
    }

    public static void loadPlayer(MinecraftServer server, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        savedPlayers.remove(uuid);
        removePlayer(uuid); // 加载前清内存，防止脏数据

        Path file = getPlayerFile(server, uuid);
        if (!Files.exists(file)) return;
        try {
            fromJson(uuid, Files.readString(file));
            HopperFillMod.LOGGER.info("[HopperFill] 玩家数据已加载: {}", file);
        } catch (Exception e) {
            HopperFillMod.LOGGER.error("[HopperFill] 加载玩家数据失败: {}", file, e);
        }
    }

    public static void savePlayer(MinecraftServer server, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (savedPlayers.contains(uuid)) return;
        savedPlayers.add(uuid);

        Path file = getPlayerFile(server, uuid);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(uuid));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            HopperFillMod.LOGGER.info("[HopperFill] 玩家数据已保存: {}", file);
        } catch (Exception e) {
            HopperFillMod.LOGGER.error("[HopperFill] 保存玩家数据失败: {}", file, e);
        }
    }

    public static void removePlayer(UUID uuid) {
        dataMap.remove(uuid);
        savedPlayers.remove(uuid);
    }

    // ---------- JSON 序列化 ----------

    private static String toJson(UUID uuid) {
        PlayerData data = dataMap.getOrDefault(uuid, new PlayerData());

        JsonObject root = new JsonObject();
        root.addProperty("hoeEnabled", data.hoeEnabled);

        JsonArray blockArray = new JsonArray();
        for (Identifier id : data.blockIds) {
            blockArray.add(id.toString());
        }
        root.add("blockIds", blockArray);

        JsonArray blacklistArray = new JsonArray();
        for (Identifier id : data.giveBlacklist) {
            blacklistArray.add(id.toString());
        }
        root.add("giveBlacklist", blacklistArray);

        root.add("template16", templateToJson(data.template16));
        root.add("template64", templateToJson(data.template64));

        return GSON.toJson(root);
    }

    private static void fromJson(UUID uuid, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        PlayerData data = getDataByUuid(uuid);

        if (root.has("hoeEnabled") && root.get("hoeEnabled").isJsonPrimitive()) {
            data.hoeEnabled = root.get("hoeEnabled").getAsBoolean();
        }

        data.blockIds = new LinkedHashSet<>();
        if (root.has("blockIds") && root.get("blockIds").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("blockIds")) {
                if (e.isJsonPrimitive()) {
                    try {
                        data.blockIds.add(Identifier.of(e.getAsString()));
                    } catch (Exception ignored) {}
                }
            }
        }

        data.giveBlacklist = new LinkedHashSet<>();
        if (root.has("giveBlacklist") && root.get("giveBlacklist").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("giveBlacklist")) {
                if (e.isJsonPrimitive()) {
                    try {
                        data.giveBlacklist.add(Identifier.of(e.getAsString()));
                    } catch (Exception ignored) {}
                }
            }
        }

        if (root.has("template16")) {
            data.template16 = jsonToTemplate(root.get("template16"));
        }
        if (root.has("template64")) {
            data.template64 = jsonToTemplate(root.get("template64"));
        }
    }

    private static PlayerData getDataByUuid(UUID uuid) {
        return dataMap.computeIfAbsent(uuid, k -> new PlayerData());
    }

    private static JsonArray templateToJson(List<ItemStack> template) {
        JsonArray arr = new JsonArray();
        int n = template == null ? 0 : template.size();
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            ItemStack s = (i < n) ? template.get(i) : null;
            if (s == null || s.isEmpty()) {
                // 空槽使用 null 占位，保留槽位位置
                arr.add(JsonNull.INSTANCE);
                continue;
            }
            ItemStack.OPTIONAL_CODEC.encodeStart(JsonOps.INSTANCE, s)
                    .result()
                    .ifPresentOrElse(arr::add, () -> arr.add(JsonNull.INSTANCE));
        }
        return arr;
    }

    private static List<ItemStack> jsonToTemplate(JsonElement element) {
        List<ItemStack> list = new ArrayList<>(TEMPLATE_SLOTS);
        if (element.isJsonArray()) {
            for (JsonElement e : element.getAsJsonArray()) {
                if (e.isJsonNull()) {
                    list.add(ItemStack.EMPTY);
                } else {
                    ItemStack.OPTIONAL_CODEC.parse(JsonOps.INSTANCE, e)
                            .result()
                            .ifPresentOrElse(list::add, () -> list.add(ItemStack.EMPTY));
                }
                if (list.size() >= TEMPLATE_SLOTS) break;
            }
        }
        while (list.size() < TEMPLATE_SLOTS) {
            list.add(ItemStack.EMPTY);
        }
        return list;
    }

    // ---------- 模板 API ----------

    public static void saveTemplate(ServerPlayerEntity player, SimpleInventory container, int type) {
        PlayerData data = getData(player);
        List<ItemStack> template = new ArrayList<>(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            template.add(container.getStack(i).copy());
        }
        if (type == 16) {
            data.template16 = template;
        } else {
            data.template64 = template;
        }
    }

    public static void loadTemplateInto(ServerPlayerEntity player, SimpleInventory container, int type) {
        List<ItemStack> template = type == 16 ? getTemplate16(player) : getTemplate64(player);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            ItemStack s = (template != null && i < template.size()) ? template.get(i) : null;
            container.setStack(i, (s != null && !s.isEmpty()) ? s.copy() : ItemStack.EMPTY);
        }
    }

    public static List<ItemStack> getTemplate16(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        return data == null ? null : data.template16;
    }

    public static List<ItemStack> getTemplate64(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        return data == null ? null : data.template64;
    }

    public static boolean hasTemplate16(PlayerEntity player) {
        return hasAnyItem(getTemplate16(player));
    }

    public static boolean hasTemplate64(PlayerEntity player) {
        return hasAnyItem(getTemplate64(player));
    }

    /** 判断模板列表中是否存在至少一个非空物品 */
    public static boolean hasAnyItem(List<ItemStack> template) {
        if (template == null) return false;
        for (ItemStack s : template) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    // ---------- 跳过方块 API ----------

    public static void addBlockId(PlayerEntity player, Identifier id) {
        getData(player).blockIds.add(id);
    }

    public static void removeBlockId(PlayerEntity player, Identifier id) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data != null) data.blockIds.remove(id);
    }

    public static Set<Identifier> getBlockIds(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data == null) return Collections.emptySet();
        return Collections.unmodifiableSet(data.blockIds);
    }

    public static void clearBlockIds(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data != null) data.blockIds.clear();
    }

    // ---------- give 黑名单 API（手动添加，区别于跳过方块） ----------

    public static void addGiveBlacklist(PlayerEntity player, Identifier id) {
        getData(player).giveBlacklist.add(id);
    }

    public static void removeGiveBlacklist(PlayerEntity player, Identifier id) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data != null) data.giveBlacklist.remove(id);
    }

    public static boolean isGiveBlacklisted(PlayerEntity player, Identifier id) {
        PlayerData data = dataMap.get(player.getUuid());
        return data != null && data.giveBlacklist.contains(id);
    }

    public static Set<Identifier> getGiveBlacklist(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data == null) return Collections.emptySet();
        return Collections.unmodifiableSet(data.giveBlacklist);
    }

    public static void clearGiveBlacklist(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        if (data != null) data.giveBlacklist.clear();
    }

    public static void setGiveBlacklist(PlayerEntity player, Set<Identifier> ids) {
        PlayerData data = getData(player);
        data.giveBlacklist = new LinkedHashSet<>(ids);
    }

    public static void setBlockIds(PlayerEntity player, Set<Identifier> ids) {
        PlayerData data = getData(player);
        data.blockIds = new LinkedHashSet<>(ids);
    }

    // ---------- 木锄 API ----------

    public static void setHoeEnabled(PlayerEntity player, boolean enabled) {
        getData(player).hoeEnabled = enabled;
    }

    public static boolean isHoeEnabled(PlayerEntity player) {
        PlayerData data = dataMap.get(player.getUuid());
        return data != null && data.hoeEnabled;
    }
}