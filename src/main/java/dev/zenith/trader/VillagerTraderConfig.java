package dev.zenith.trader;

import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static dev.zenith.trader.module.VillagerTrader.VillagerProfession;

public class VillagerTraderConfig {
    public boolean enabled = false;
    public long waitForInteractTimeoutTicks = 20L;
    public boolean logTradeStatusToDiscord = false;
    public long villagerTradeRestockWaitSeconds = 120L;
    public int itemFrameScanHorizontalRangeBlocks = 32;
    public int itemFrameScanVerticalRangeBlocks = 8;
    // 背包空位 <= 该值时触发卸货（把购买到的产出物卸到 outputChest），避免背包满了卡住交易
    public int inventoryStoreEmptySlotBuffer = 3;

    public LinkedHashMap<String, Trade> trades = new LinkedHashMap<>();

    // 职业档案：key = 职业英文名小写（如 "armorer"）
    // buyItems = 该职业要购买的物品列表，仅 buyItems 内的物品会被扫描/生成交易
    public LinkedHashMap<String, TraderProfession> profession = new LinkedHashMap<>();

    // =========配套工具方法（指令/扫描共用）=========
    // 启用指定职业；首次启用创建默认档案，再次启用恢复buyItems清单
    public void enableProfession(VillagerProfession prof) {
        TraderProfession p = profession.computeIfAbsent(prof.name(), k -> new TraderProfession());
        p.enabled = true;
    }

    // 禁用指定职业：只置 enabled=false，保留 buyItems 以便再次启用恢复，不会产生交易
    public void disableProfession(VillagerProfession prof) {
        TraderProfession p = profession.get(prof.name());
        if (p != null) p.enabled = false;
    }

    // 添加购买物品（去重），并自动启用职业
    public void addBuyItem(VillagerProfession prof, String itemName) {
        TraderProfession p = profession.computeIfAbsent(prof.name(), k -> new TraderProfession());
        p.enabled = true;
        if (!p.buyItems.contains(itemName)) p.buyItems.add(itemName);
    }

    // 移除一个购买物品
    public void removeBuyItem(VillagerProfession prof, String itemName) {
        TraderProfession p = profession.get(prof.name());
        if (p != null) p.buyItems.remove(itemName);
    }

    // 清空某职业的购买物品（仍保持启用状态）
    public void clearProfItems(VillagerProfession prof) {
        TraderProfession p = profession.get(prof.name());
        if (p != null) p.buyItems.clear();
    }

    // 获取该职业的购买清单（未启用返回空集合）
    public List<String> getEnabledItems(VillagerProfession prof) {
        TraderProfession p = profession.get(prof.name());
        return (p == null || !p.enabled) ? List.of() : p.buyItems;
    }

    // 判断职业是否启用
    public boolean isProfEnabled(VillagerProfession prof) {
        TraderProfession p = profession.get(prof.name());
        return p != null && p.enabled;
    }

    // 判断该物品是否已选为购买（职业启用 且 物品在 buyItems）
    public boolean isItemEnabled(VillagerProfession prof, String itemName) {
        TraderProfession p = profession.get(prof.name());
        return p != null && p.enabled && p.buyItems.contains(itemName);
    }

    // 获取职业档案（不存在返回 null）
    public TraderProfession getProfession(VillagerProfession prof) {
        return profession.get(prof.name());
    }

    // 清空全部职业配置
    public void clearAllProfConfig() {
        profession.clear();
    }

    // 回填职业档案的预设信息（中文名/工作台）。
    // 仅在对应字段为空时写入，绝不覆盖用户已有的 enabled / buyItems。
    // buyItems 由用户通过 addItem 命令手动配置。
    public void backfillProfession(VillagerProfession prof, String displayName, String workstation, String workstationCn, List<String> defaultBuyItems) {
        TraderProfession p = profession.get(prof.name());
        if (p == null) {
            p = new TraderProfession();
            p.profession = prof.name().toLowerCase();
            profession.put(prof.name(), p);
        }
        if (p.displayName == null || p.displayName.isEmpty()) p.displayName = displayName;
        if (p.workstation == null || p.workstation.isEmpty()) p.workstation = workstation;
        if (p.workstationCn == null || p.workstationCn.isEmpty()) p.workstationCn = workstationCn;
        // buyItems 不再由 backfill 填充，完全由用户通过 addItem 管理
    }

    // 一个职业的可选购/已购买档案
    public static class TraderProfession {
        public boolean enabled = false;
        public String profession = "";
        public String displayName = "";
        public String workstation = "";
        public String workstationCn = "";
        public List<String> buyItems = new ArrayList<>();  // 已选购买物品
    }

    public static class Trade {
        public boolean enabled = true;
        public VillagerProfession villagerProfession = VillagerProfession.CLERIC;
        public String inputItem1 = ItemRegistry.AIR.name();
        public String inputItem2 = ItemRegistry.AIR.name();
        public String outputItem = ItemRegistry.AIR.name();
        public BlockPos inputItem1Chest = BlockPos.ZERO;
        public BlockPos inputItem2Chest = BlockPos.ZERO;
        public BlockPos outputChest = BlockPos.ZERO;
        public int inputItem1RestockStacks = 4;
        public int inputItem1RestockCountThreshold = 64;
        public int inputItem2RestockStacks = 4;
        public int inputItem2RestockCountThreshold = 64;
        public int outputItemStoreCountThreshold = 64;
        public int maxInput1PerTrade = 99;
        public int maxInput2PerTrade = 99;
        public PostTradeStoreMode postTradeStoreMode = PostTradeStoreMode.TO_RESTOCK;
        public enum PostTradeStoreMode {
            NONE,
            TO_RESTOCK,
            TO_OVERFLOW
        }
        public BlockPos overflowChestPos = BlockPos.ZERO;
        public Object2IntLinkedOpenHashMap<String> outputItemEnchantments = new Object2IntLinkedOpenHashMap<>();

        public boolean has2InputTrade() {
            return !Objects.equals(inputItem2, ItemRegistry.AIR.name());
        }

        public boolean hasEmeraldInputs() {
            return Objects.equals(inputItem1, ItemRegistry.EMERALD.name()) || Objects.equals(inputItem2, ItemRegistry.EMERALD.name());
        }

        public ItemData getInputItem1() {
            return ItemRegistry.REGISTRY.get(inputItem1);
        }

        public ItemData getInputItem2() {
            return ItemRegistry.REGISTRY.get(inputItem2);
        }

        public ItemData getOutputItem() {
            return ItemRegistry.REGISTRY.get(outputItem);
        }
    }
}
