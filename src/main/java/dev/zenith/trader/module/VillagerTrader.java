package dev.zenith.trader.module;

import com.github.rfresh2.EventConsumer;
import com.google.common.collect.Lists;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.inventory.Container;
import com.zenith.discord.Embed;
import com.zenith.discord.EmbedSerializer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.*;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.BlockTags;
import com.zenith.mc.block.Direction;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import dev.zenith.trader.VillagerTraderConfig;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.*;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;

public class VillagerTrader extends Module {
    private State state = State.ENTRYPOINT;
    private final TradeIterator tradeIterator = new TradeIterator();
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture emeraldBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture interactWithVillagerFuture = PathingRequestFuture.rejected;
    private ClientboundMerchantOffersPacket offersPacket = null;
    private RequestFuture purchaseFuture = RequestFuture.rejected;
    private PathingRequestFuture storePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture storeDepositFuture = RequestFuture.rejected;
    private PathingRequestFuture postTradePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture postTradeDepositFuture = RequestFuture.rejected;
    private final Timer waitForRestockTimer = Timers.tickTimer();
    private final Timer waitForInteractTimer = Timers.tickTimer();
    private long tradeStartTime = System.nanoTime();
    // 预交易阶段（EVAL_RESTOCK 循环）当前处理的购买项下标
    private int preTradeIndex = 0;
    // 补货失败（箱子不足）的购买项下标：跳过重新补货，但仍会用现有库存尝试交易
    private final Set<Integer> restockSkippedIndices = new HashSet<>();
    // 本轮批量交易前各购买项的数量，用于交易结果统计
    private final List<TradePreCounts> preTradeCounts = new ArrayList<>();
    // 购买完成后进入卸货：卸货结束应继续评估同一村民的其他购买项（而非换村民）
    private boolean postPurchaseStore = false;
    // 本轮是否实际完成了交易（用于 NEXT_TRADE 判断是否需要标记村民已交易）
    private boolean tradeMadeThisRound = false;
    // villager uuid -> 该村民上次完成最后一笔交易时的世界时间（mcTime），用于补货窗口判定
    private final Map<UUID, Long> villagerLastTradeTime = new HashMap<>();
    // villager uuid -> 上次交互失败时间，用于跳过该村民，避免反复找同一个村民
    private final Map<UUID, Long> villagerFailTime = new HashMap<>();
    // villager uuid -> 连续无可购买交互次数，超过阈值则标记失败跳过
    private final Map<UUID, Integer> villagerNoTradeCount = new HashMap<>();
    // 永久跳过的村民（多次重试后仍无可购买交易）
    private final Set<UUID> permanentlyFailedVillagers = new HashSet<>();
    private static final int NO_TRADE_FAIL_THRESHOLD = 5;
    private static final int PERMANENT_FAIL_THRESHOLD = 15;
    // 当前正在交易的村民，及该村民尚未处理完的交易列表
    private UUID currentVillagerUuid = null;
    private List<VillagerTraderConfig.Trade> currentVillagerTrades = List.of();
    // 当前正在处理的交易下标（单次交易模式：每次只处理一个交易）
    private int currentTradeIndex = 0;
    private long restockWaitStartTime = -1;
    // 补货容器打开重试计数（连续打不开多次后放弃，避免死循环）
    private int restockOpenRetries = 0;
    // 当前卸货目标（购买到的产出物/目标箱子），STORE 相关状态复用
    private String storeItemId = null;
    private String storeEnchantInfo = null;
    private BlockPos storeChestPos = BlockPos.ZERO;
    // 交易界面未购买就直接转移时，先提交一次“仅关闭商人界面”请求再进入的状态
    private State stateAfterTradeClose = null;
    // 交互（右键打开商人界面）失败重试计数：与参照模块一致，右键打开界面不成功不立即换村民，
    // 而是先重试打开同一个村民；连续多次仍打不开才按失败处理并跳到下一个村民
    private int interactRetryCount = 0;
    private static final int INTERACT_RETRY_LIMIT = 30;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset())
        );
    }

    @Override
    public void onDisable() {
        reset();
    }

    private int getPriority() {
        return Baritone.getPriority() + 100;
    }

    private void reset() {
        state = State.ENTRYPOINT;
        offersPacket = null;
        waitForInteractTimer.reset();
        waitForRestockTimer.reset();
        tradeIterator.reset();
        tradeStartTime = System.nanoTime();
        currentVillagerUuid = null;
        currentVillagerTrades = List.of();
        currentTradeIndex = 0;
        villagerLastTradeTime.clear();
        villagerFailTime.clear();
        villagerNoTradeCount.clear();
        permanentlyFailedVillagers.clear();
        restockWaitStartTime = -1;
        stateAfterTradeClose = null;
        preTradeIndex = 0;
        restockSkippedIndices.clear();
        preTradeCounts.clear();
        postPurchaseStore = false;
        tradeMadeThisRound = false;
        interactRetryCount = 0;
    }

    // 当前世界时间（mcTime = day * 24000 + timeOfDay），与参照模块一致。昼夜循环关闭时退回 gameTime。
    private long mcTime() {
        var worldData = CACHE.getChunkCache().getWorldTimeData();
        if (worldData == null) return -1;
        long dayTime = worldData.getDayTime();
        return dayTime >= 0 ? dayTime : worldData.getGameTime();
    }

    // 是否开启了昼夜循环（dayTime 为负数表示 gamerule 关闭了 doDaylightCycle）
    private boolean isDaylightCycleEnabled() {
        var worldData = CACHE.getChunkCache().getWorldTimeData();
        return worldData != null && worldData.getDayTime() >= 0;
    }

    private Optional<EntityLiving> currentVillagerEntity() {
        if (currentVillagerUuid == null) return Optional.empty();
        return CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .map(e -> (EntityLiving) e)
            .filter(e -> e.getUuid().equals(currentVillagerUuid))
            .findFirst();
    }

    // 参照模块的 next() 过滤逻辑：村民只有在到达其下一次补货窗口后才可再次交易。
    // 每天两次补货：~2000（村民上工）与 ~9000（下午）。记录上次完成交易的时间，
    // 在对应的下一次补货窗口前不会重复选择同一个村民。
    // 额外：连续无可用交易的村民（villagerFailTime）按指数退避跳过，避免无限重试。
    private boolean isVillagerEligible(EntityLiving villager) {
        long time = mcTime();
        if (time < 0) return false;
        long day = time / 24000L;
        long timeOfDay = time % 24000L;
        long last = villagerLastTradeTime.getOrDefault(villager.getUuid(), 0L);
        long lastDay = last / 24000L;
        long lastTimeOfDay = last % 24000L;
        boolean eligible;
        if (timeOfDay > 9000L) {
            eligible = lastDay < day || lastTimeOfDay < 8900L;
        } else if (timeOfDay > 2020L) {
            eligible = lastDay < day || lastTimeOfDay < 2000L;
        } else {
            eligible = lastDay < day && lastTimeOfDay < 8900L;
        }
        if (!eligible) return false;
        // 永久跳过的村民
        if (permanentlyFailedVillagers.contains(villager.getUuid())) return false;
        // 指数退避：连续失败次数越多，等待时间越长（500, 1000, 2000, 4000...）
        long fail = villagerFailTime.getOrDefault(villager.getUuid(), -1L);
        if (fail >= 0) {
            int failCount = villagerNoTradeCount.getOrDefault(villager.getUuid(), 1);
            long backoff = Math.min(500L * (1L << Math.min(failCount - 1, 4)), 8000L);
            if (fail > time - backoff) return false;
        }
        return true;
    }

    // 参照模块 next() 的职业交易优先级：同一补货轮次内按此顺序遍历职业，
    // 每个职业内选最近且合格的村民（一个村民扫完才轮到同职业下一个，职业内清空后才进入下一职业）
    private static final List<VillagerProfession> PROFESSION_PRIORITY = List.of(
        VillagerProfession.CLERIC,
        VillagerProfession.FARMER,
        VillagerProfession.LIBRARIAN,
        VillagerProfession.ARMORER,
        VillagerProfession.WEAPONSMITH,
        VillagerProfession.TOOLSMITH,
        VillagerProfession.MASON,
        VillagerProfession.CARTOGRAPHER,
        VillagerProfession.FLETCHER,
        VillagerProfession.FISHERMAN,
        VillagerProfession.BUTCHER,
        VillagerProfession.SHEPHERD,
        VillagerProfession.LEATHERWORKER
    );

    // 选择下一个处于补货窗口内、尚未处理完的村民。
    // 与参照模块一致：按职业优先级依次，先取仍存有合格村民的最高优先级职业，再在该职业内选最近者。
    private Optional<EntityLiving> selectNextVillager() {
        for (VillagerProfession prof : PROFESSION_PRIORITY) {
            if (!PLUGIN_CONFIG.isProfEnabled(prof)) continue;
            var found = CACHE.getEntityCache().getEntities().values().stream()
                .filter(e -> e.getEntityType() == EntityType.VILLAGER)
                .map(e -> (EntityLiving) e)
                .filter(e -> getVillagerProfession(e) == prof)
                .filter(this::isVillagerEligible)
                .min(Comparator.comparingDouble(e -> e.distanceSqTo(CACHE.getPlayerCache().getThePlayer())));
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    // 该村民的全部购买项对应的 Trade 列表（保持职业 buy 清单顺序）
    // 对附魔书：匹配任意 enchanted_book trade entry，由 queueTradeActions 的 enchantmentFilter
    // 在实际交易时检查村民卖的附魔是否是用户需要的（每个村民卖不同附魔）
    private List<VillagerTraderConfig.Trade> tradesForVillager(EntityLiving villager) {
        VillagerProfession profession = getVillagerProfession(villager);
        var result = new ArrayList<VillagerTraderConfig.Trade>();
        boolean enchantedBookAdded = false;
        for (String itemName : PLUGIN_CONFIG.getEnabledItems(profession)) {
            if (itemName.equals(ItemRegistry.ENCHANTED_BOOK.name())) {
                // 附魔书：只需找到一个同职业的 enchanted_book trade entry 即可
                // queueTradeActions 会在实际交易时用 enchantmentFilter 检查具体附魔
                if (enchantedBookAdded) continue;
                PLUGIN_CONFIG.trades.values().stream()
                    .filter(t -> t.enabled)
                    .filter(t -> t.villagerProfession == profession)
                    .filter(t -> t.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name()))
                    .findFirst()
                    .ifPresent(trade -> {
                        // 检查是否有书或书补货箱
                        boolean hasBooks = countItem(ItemRegistry.BOOK.id()) > 0;
                        boolean hasBookChest = trade.has2InputTrade() && !BlockPos.ZERO.equals(trade.inputItem2Chest);
                        if (!hasBooks && !hasBookChest) {
                            debug("Skipping enchanted_book trade: no books and no book restock chest");
                            return;
                        }
                        result.add(trade);
                    });
                enchantedBookAdded = true;
            } else {
                // 普通物品：精确匹配 outputItem
                PLUGIN_CONFIG.trades.values().stream()
                    .filter(t -> t.enabled)
                    .filter(t -> t.villagerProfession == profession)
                    .filter(t -> t.outputItem.equals(itemName))
                    .findFirst()
                    .ifPresent(result::add);
            }
        }
        return result;
    }

    // 村民本轮（当前补货窗口）交易完毕：记录最后交易时间，进入下一村民选择。
    // offer 售罄（uses >= maxUses）后标记，直到下一次补货窗口才会重新被选中。
    private void markVillagerTraded(UUID villagerUuid) {
        villagerLastTradeTime.put(villagerUuid, mcTime());
        currentVillagerUuid = null;
        currentVillagerTrades = List.of();
        currentTradeIndex = 0;
        preTradeIndex = 0;
        restockSkippedIndices.clear();
        postPurchaseStore = false;
        tradeMadeThisRound = false;
        interactRetryCount = 0;
        villagerNoTradeCount.remove(villagerUuid);
    }

    // 提交一个仅关闭商人窗口的请求（用于“打开了商人但未购买就转移”的情况）
    private RequestFuture closeMerchantOnly() {
        return INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .priority(getPriority())
            .actions(Lists.newArrayList(new CloseContainer(offersPacket.getContainerId())))
            .build());
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("villager-trader")
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundMerchantOffersPacket.class, this::onMerchantOffers)
                .build())
            .build();
    }

    private ClientboundMerchantOffersPacket onMerchantOffers(ClientboundMerchantOffersPacket packet, ClientSession session) {
        this.offersPacket = packet;
        var trades = packet.getTrades();
        info("Villager offers ({} trades):", trades.length);
        for (int i = 0; i < trades.length; i++) {
            var t = trades[i];
            var output = t.getOutput();
            var input1 = t.getFirstInput();
            var input2 = t.getSecondInput();
            String outputName = output != null ? ItemRegistry.REGISTRY.get(output.getId()).name() + " x" + output.getAmount() : "?";
            if (output != null && outputName.startsWith("enchanted_book")) {
                var enchantInfo = displayedBookEnchantment(output);
                if (enchantInfo != null) outputName = "enchanted_book(" + enchantInfo.name + " " + enchantInfo.level + ")";
            }
            String input1Name = input1 != null ? ItemRegistry.REGISTRY.get(input1.getId()).name() + " x" + input1.getAmount() : "?";
            String input2Str = "";
            if (input2 != null && input2.getId() != 0) {
                input2Str = " + " + ItemRegistry.REGISTRY.get(input2.getId()).name() + " x" + input2.getAmount();
            }
            String disabled = t.isTradeDisabled() ? " [DISABLED]" : "";
            String uses = " (" + t.getNumUses() + "/" + t.getMaxUses() + ")";
            info("  [{}] {}{} -> {}{}", i, input1Name, input2Str, outputName, uses + disabled);
        }
        return packet;
    }

    // 打开商人界面的交互超时处理：与参照模块一致，界面打不开先重试打开同一个村民，
    // 而不是立刻换下一个。只有连续 INTERACT_RETRY_LIMIT 次仍打不开，才记为失败并跳去下一个。
    private void interactTimeout() {
        if (currentVillagerUuid == null) {
            interactRetryCount = 0;
            setState(State.TRADING_INTERACT_WITH_VILLAGER);
            return;
        }
        if (interactRetryCount < INTERACT_RETRY_LIMIT) {
            interactRetryCount++;
            waitForInteractTimer.reset();
            // 复用当前村民重新右键打开界面（TRADING_INTERACT 的复用分支）
            setState(State.TRADING_INTERACT_WITH_VILLAGER);
            return;
        }
        // 连续多次仍打不开：记录失败时间（该村民在 fail+窗口外 100 tick 内不再被选中），跳到下一个
        villagerFailTime.put(currentVillagerUuid, mcTime());
        currentVillagerUuid = null;
        currentVillagerTrades = List.of();
        currentTradeIndex = 0;
        preTradeIndex = 0;
        restockSkippedIndices.clear();
        tradeMadeThisRound = false;
        interactRetryCount = 0;
        setState(State.TRADING_INTERACT_WITH_VILLAGER);
    }

    private void onTick(ClientBotTick event) {
        if (Proxy.getInstance().isInQueue() || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            state = State.ENTRYPOINT;
            return;
        }
        switch (state) {
            case ENTRYPOINT -> {
                if (!tradeIterator.hasNext()) {
                    // 没有手动配置的 trades，尝试从职业 buy 清单动态生成
                    if (generateTradesFromConfig()) {
                        tradeIterator.refresh();
                    }
                    if (!tradeIterator.hasNext())
                        return;
                }
                tradeStartTime = System.nanoTime();
        currentVillagerUuid = null;
        currentVillagerTrades = List.of();
        currentTradeIndex = 0;
                preTradeIndex = 0;
                restockSkippedIndices.clear();
                postPurchaseStore = false;
                tradeMadeThisRound = false;
                storeItemId = null;
                storeEnchantInfo = null;
                storeChestPos = BlockPos.ZERO;
                restockOpenRetries = 0;
                restockWaitStartTime = -1;
                tradeIterator.refresh();
                // 先选择村民，再用该村民当前交易做补货/卸货评估，避免用错交易
                setState(State.TRADING_INTERACT_WITH_VILLAGER);
            }
            case EVAL_RESTOCK -> {
                // 与参照模块 gotoPutIfNeed() 一致：仅在背包空位不足时才卸货
                if (countInvEmptySlots() <= PLUGIN_CONFIG.inventoryStoreEmptySlotBuffer) {
                    var toUnload = nextItemToUnload();
                    if (toUnload != null) {
                        storeItemId = toUnload.outputItem;
                        storeEnchantInfo = getTradeEnchantInfo(toUnload);
                        storeChestPos = toUnload.outputChest;
                        setState(State.STORE_GO_TO_CHEST);
                        return;
                    }
                }
                // 2) 遍历该村民全部购买项：输入低于补货阈值 -> 补货（缺货跳过的项不再重试补货）
                for (; preTradeIndex < currentVillagerTrades.size(); preTradeIndex++) {
                    if (restockSkippedIndices.contains(preTradeIndex)) continue;
                    var trade = currentVillagerTrades.get(preTradeIndex);
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    if (countItem(input1.id()) < trade.inputItem1RestockCountThreshold) {
                        info("Need restock: {} (have {}, need {})", input1.name(), countItem(input1.id()), trade.inputItem1RestockCountThreshold);
                        setState(State.RESTOCK_INPUT_1_GO_TO_CHEST);
                        return;
                    }
                    if (trade.has2InputTrade()) {
                        var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                        if (countItem(input2.id()) < trade.inputItem2RestockCountThreshold) {
                            info("Need restock: {} (have {}, need {})", input2.name(), countItem(input2.id()), trade.inputItem2RestockCountThreshold);
                            setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                            return;
                        }
                    }
                }
                preTradeIndex = 0;
                // 3) 输入均已满足：任一购买项产出超过卸货阈值 -> 卸货
                for (var trade : currentVillagerTrades) {
                    var output = ItemRegistry.REGISTRY.get(trade.outputItem);
                    if (output == null || output == ItemRegistry.AIR) continue;
                    if (countItem(output.id()) > trade.outputItemStoreCountThreshold) {
                        info("Need store: {} (have {}, threshold {})", output.name(), countItem(output.id()), trade.outputItemStoreCountThreshold);
                        storeItemId = trade.outputItem;
                        storeEnchantInfo = getTradeEnchantInfo(trade);
                        storeChestPos = trade.outputChest;
                        setState(State.STORE_GO_TO_CHEST);
                        return;
                    }
                }
                // 4) 全部就绪：绿宝石块转绿宝石，然后打开商人界面交易
                setState(State.CRAFT_EMERALD_BLOCKS);
            }
            case RESTOCK_INPUT_1_GO_TO_CHEST -> {
                var trade = currentVillagerTrades.get(preTradeIndex);
                var input1Item = ItemRegistry.REGISTRY.get(trade.inputItem1);
                info("Restocking {} from chest at [{}, {}, {}]", input1Item.name(), trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_1_WITHDRAW);
            }
            case RESTOCK_INPUT_1_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    var trade = currentVillagerTrades.get(preTradeIndex);
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        restockOpenRetries = 0;
                        var actions = Lists.newArrayList(
                            InventoryActionMacros.withdraw(
                                openContainer.getContainerId(),
                                i -> {
                                    if (input1 == ItemRegistry.EMERALD) {
                                        return i.getId() == input1.id() || i.getId() == ItemRegistry.EMERALD_BLOCK.id();
                                    }
                                    return i.getId() == input1.id();
                                },
                                trade.inputItem1RestockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        var request = InventoryActionRequest.builder()
                            .owner(this)
                            .actions(actions)
                            .priority(getPriority())
                            .build();
                        restockWithdrawFuture = INVENTORY.submit(request);
                        setState(State.RESTOCK_INPUT_1_AWAIT_WITHDRAW);
                    } else {
                        if (waitForInteractTimer.tick(Math.max(PLUGIN_CONFIG.waitForInteractTimeoutTicks, 40L))) {
                            if (++restockOpenRetries < 3) {
                                warn("输入1补货箱({})未打开，重试 {}/3", trade.inputItem1, restockOpenRetries);
                                setState(State.RESTOCK_INPUT_1_GO_TO_CHEST);
                            } else {
                                restockOpenRetries = 0;
                                error("Timed out waiting for input 1 container to open");
                                setState(State.RESTOCK_INPUT_1_AWAIT_WITHDRAW);
                            }
                        }
                    }
                }
            }
            case RESTOCK_INPUT_1_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    var trade = currentVillagerTrades.get(preTradeIndex);
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    int input1Count = countItem(input1.id());
                    if (trade.inputItem1RestockCountThreshold > input1Count) {
                        // 补货不足（箱子空）：对该购买项跳过再次补货，但仍用现有库存尝试交易
                        error("Failed restocking sufficient {} for trade: {}, continuing with other items", input1.name(), trade.outputItem);
                        restockSkippedIndices.add(preTradeIndex);
                        preTradeIndex++;
                        setState(State.EVAL_RESTOCK);
                        return;
                    }
                    // 输入1已满足；若该购买项还需要输入2且输入2不足，接着补输入2
                    if (trade.has2InputTrade()) {
                        var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                        if (countItem(input2.id()) < trade.inputItem2RestockCountThreshold) {
                            setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                            return;
                        }
                    }
                    preTradeIndex++;
                    setState(State.EVAL_RESTOCK);
                }
            }
            case RESTOCK_INPUT_2_GO_TO_CHEST -> {
                var trade = currentVillagerTrades.get(preTradeIndex);
                var input2Item = ItemRegistry.REGISTRY.get(trade.inputItem2);
                info("Restocking {} from chest at [{}, {}, {}]", input2Item.name(), trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_2_WITHDRAW);
            }
            case RESTOCK_INPUT_2_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    var trade = currentVillagerTrades.get(preTradeIndex);
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        restockOpenRetries = 0;
                        var actions = Lists.newArrayList(
                            InventoryActionMacros.withdraw(
                                openContainer.getContainerId(),
                                i -> {
                                    if (input2 == ItemRegistry.EMERALD) {
                                        return i.getId() == input2.id() || i.getId() == ItemRegistry.EMERALD_BLOCK.id();
                                    }
                                    return i.getId() == input2.id();
                                },
                                trade.inputItem2RestockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        var request = InventoryActionRequest.builder()
                            .owner(this)
                            .actions(actions)
                            .priority(getPriority())
                            .build();
                        restockWithdrawFuture = INVENTORY.submit(request);
                        setState(State.RESTOCK_INPUT_2_AWAIT_WITHDRAW);
                    } else {
                        if (waitForInteractTimer.tick(Math.max(PLUGIN_CONFIG.waitForInteractTimeoutTicks, 40L))) {
                            if (++restockOpenRetries < 3) {
                                warn("输入2补货箱({})未打开，重试 {}/3", trade.inputItem2, restockOpenRetries);
                                setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                            } else {
                                restockOpenRetries = 0;
                                error("Timed out waiting for input 2 container to open");
                                setState(State.RESTOCK_INPUT_2_AWAIT_WITHDRAW);
                            }
                        }
                    }
                }
            }
            case RESTOCK_INPUT_2_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    var trade = currentVillagerTrades.get(preTradeIndex);
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    int input2Count = countItem(input2.id());
                    if (trade.inputItem2RestockCountThreshold > input2Count) {
                        error("Failed restocking sufficient {} for trade: {}, continuing with other items", input2.name(), trade.outputItem);
                        restockSkippedIndices.add(preTradeIndex);
                    }
                    preTradeIndex++;
                    setState(State.EVAL_RESTOCK);
                }
            }
            case CRAFT_EMERALD_BLOCKS -> {
                int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockCount == 0) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                if (countInvEmptySlots() < 4) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                int emeraldBlockSlot = InventoryUtil.searchPlayerInventory(i -> i.getId() == ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockSlot == -1) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                List<InventoryAction> actions = Lists.newArrayList();
                actions.add(new PlaceRecipe(0, "minecraft:emerald", true));
                actions.add(new ShiftClick(0, ShiftClickItemAction.LEFT_CLICK));
                actions.add(new CloseContainer(0));
                emeraldBlockCraftFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(actions)
                    .priority(getPriority())
                    .build());
                setState(State.AWAIT_CRAFT_EMERALD_BLOCKS);
            }
            case AWAIT_CRAFT_EMERALD_BLOCKS -> {
                if (emeraldBlockCraftFuture.isCompleted()) {
                    int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                    if (emeraldBlockCount > 0) {
                        setState(State.CRAFT_EMERALD_BLOCKS);
                    } else {
                        setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    }
                }
            }
            case TRADING_INTERACT_WITH_VILLAGER -> {
                // 优先复用当前村民（其后续购买项还未处理完）
                var villagerOptional = currentVillagerUuid != null ? currentVillagerEntity() : Optional.<EntityLiving>empty();
                if (villagerOptional.isPresent()) {
                    var currentVillager = villagerOptional.get();
                    offersPacket = null;
                    interactWithVillagerFuture = BARITONE.rightClickEntity(currentVillager);
                    interactWithVillagerFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.TRADING_AWAIT_INTERACT_WITH_VILLAGER);
                    return;
                }
                // 当前村民不可用，选择下一个处于补货窗口内的村民
                var nextVillagerOptional = selectNextVillager();
                if (nextVillagerOptional.isEmpty()) {
                    warn("No eligible villagers right now, waiting for next restock window");
                    restockWaitStartTime = -1;
                    waitForRestockTimer.reset();
                    setState(State.AWAIT_RESTOCK);
                    return;
                }
                var nextVillager = nextVillagerOptional.get();
                currentVillagerUuid = nextVillager.getUuid();
                var villagerTrades = tradesForVillager(nextVillager);
                if (villagerTrades.isEmpty()) {
                    markVillagerTraded(nextVillager.getUuid());
                    return;
                }
                currentVillagerTrades = villagerTrades;
                preTradeIndex = 0;
                restockSkippedIndices.clear();
                postPurchaseStore = false;
                // 先做补货/卸货预评估（全部购买项输入补满、产出卸货），全部就绪后再打开商人界面批量交易
                setState(State.EVAL_RESTOCK);
            }
            case TRADING_AWAIT_INTERACT_WITH_VILLAGER -> {
                if (interactWithVillagerFuture.isCompleted()) {
if (offersPacket == null) {
                            if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                                // 界面未打开：先重试打开同一个村民，而不是立刻换下一个
                                interactTimeout();
                            }
                            return;
                        }
                    if (offersPacket.getContainerId() != CACHE.getPlayerCache().getInventoryCache().getOpenContainerId()) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            interactTimeout();
                        }
                        return;
                    }
                    setState(State.TRADING_TRY_START_PURCHASE);
                }
            }
            case TRADING_TRY_START_PURCHASE -> {
                // 安全检查：offersPacket 必须存在
                if (offersPacket == null) {
                    setState(State.TRADING_INTERACT_WITH_VILLAGER);
                    return;
                }
                // 与参照模块 executeTrade 一致：先检查是否需要卸货
                var toStore = nextTradeToStore();
                if (toStore != null) {
                    purchaseFuture = closeMerchantOnly();
                    stateAfterTradeClose = State.STORE_GO_TO_CHEST;
                    setState(State.TRADING_AWAIT_PURCHASE);
                    return;
                }
                // 参照模块：一次打开商人界面，遍历所有匹配的 offer 构建全部购买动作
                var trades = offersPacket.getTrades();
                List<InventoryAction> actions = Lists.newArrayList();
                preTradeCounts.clear();
                int totalExpectedInput1 = 0;
                int totalExpectedInput2 = 0;
                int totalExpectedOutput = 0;
                for (var trade : currentVillagerTrades) {
                    var queueResult = queueTradeActions(trade, trades, actions);
                    if (queueResult.pending() > 0) {
                        preTradeCounts.add(new TradePreCounts(trade,
                            countItem(trade.getInputItem1().id()),
                            trade.has2InputTrade() ? countItem(trade.getInputItem2().id()) : 0,
                            countItem(trade.getOutputItem().id()),
                            queueResult.expectedInput1Sold(),
                            queueResult.expectedInput2Sold(),
                            queueResult.expectedOutputBought()));
                        totalExpectedInput1 += queueResult.expectedInput1Sold();
                        totalExpectedInput2 += queueResult.expectedInput2Sold();
                        totalExpectedOutput += queueResult.expectedOutputBought();
                    }
                }
                if (actions.isEmpty()) {
                    // 所有购买项无可购买：关闭界面，进入下一村民
                    if (currentVillagerUuid != null && tradeMadeThisRound) {
                        markVillagerTraded(currentVillagerUuid);
                    } else if (currentVillagerUuid != null) {
                        villagerFailTime.put(currentVillagerUuid, mcTime());
                        currentVillagerUuid = null;
                        currentVillagerTrades = List.of();
                    }
                    tradeMadeThisRound = false;
                    stateAfterTradeClose = State.NEXT_TRADE;
                    purchaseFuture = closeMerchantOnly();
                    setState(State.TRADING_AWAIT_PURCHASE);
                    return;
                }
                tradeMadeThisRound = true;
                if (currentVillagerUuid != null) {
                    villagerNoTradeCount.remove(currentVillagerUuid);
                    permanentlyFailedVillagers.remove(currentVillagerUuid);
                }
                purchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(getPriority())
                    .actions(actions)
                    .build());
                stateAfterTradeClose = null;
                setState(State.TRADING_AWAIT_PURCHASE);
            }
            case TRADING_AWAIT_PURCHASE -> {
                if (purchaseFuture.isCompleted()) {
                    // 本轮只提交了"关闭商人界面"的请求，未执行购买，直接转移到目标状态
                    if (stateAfterTradeClose != null) {
                        var target = stateAfterTradeClose;
                        stateAfterTradeClose = null;
                        setState(target);
                        return;
                    }
                    // 交易完成：输出结果（使用预期值，不依赖背包缓存更新）
                    for (var pc : preTradeCounts) {
                        var trade = pc.trade;
                        info("Sold {} {}", pc.expectedInput1Sold, trade.inputItem1);
                        if (trade.has2InputTrade()) {
                            info("Sold {} {}", pc.expectedInput2Sold, trade.inputItem2);
                        }
                        String enchantInfo = "";
                        if (trade.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name()) && pc.expectedOutputBought > 0) {
                            enchantInfo = getEnchantInfoFromInventory(trade);
                            info("Bought {} enchanted_book [{}]", pc.expectedOutputBought, enchantInfo);
                        } else {
                            info("Bought {} {}", pc.expectedOutputBought, trade.outputItem);
                        }
                        var tradeResult = Embed.builder()
                            .title("Trade Completed")
                            .addField("Trade ID", Objects.requireNonNullElse(getTradeId(trade), "?"))
                            .addField("Duration", MathHelper.formatDuration(Duration.ofNanos(System.nanoTime() - tradeStartTime)))
                            .addField("Input 1", trade.inputItem1)
                            .addField("Input 1 Sold", pc.expectedInput1Sold);
                        if (trade.has2InputTrade()) {
                            tradeResult
                                .addField("Input 2", trade.inputItem2)
                                .addField("Input 2 Sold", pc.expectedInput2Sold);
                        }
                        tradeResult
                            .addField("Output", enchantInfo.isEmpty() ? trade.outputItem : trade.outputItem + " [" + enchantInfo + "]")
                            .addField("Output Bought", pc.expectedOutputBought);
                        if (PLUGIN_CONFIG.logTradeStatusToDiscord) {
                            discordNotification(tradeResult);
                        } else {
                            info(EmbedSerializer.serialize(tradeResult));
                        }
                    }
                    preTradeCounts.clear();
                    // 本轮所有交易已完成：关闭商人界面，检查是否需要卸货，然后进入下一村民
                    var toStore = nextTradeToStore();
                    if (toStore != null) {
                        storeItemId = toStore.outputItem;
                        storeEnchantInfo = getTradeEnchantInfo(toStore);
                        storeChestPos = toStore.outputChest;
                        stateAfterTradeClose = State.STORE_GO_TO_CHEST;
                    } else {
                        stateAfterTradeClose = State.NEXT_TRADE;
                    }
                    purchaseFuture = closeMerchantOnly();
                    setState(State.TRADING_AWAIT_PURCHASE);
                }
            }
            case STORE_GO_TO_CHEST -> {
                var storeItem = ItemRegistry.REGISTRY.get(storeItemId);
                var storeName = storeEnchantInfo != null && !storeEnchantInfo.equals("unknown")
                    ? storeItem.name() + "(" + storeEnchantInfo + ")"
                    : storeItem.name();
                info("Storing {} -> chest at [{}, {}, {}]", storeName, storeChestPos.x(), storeChestPos.y(), storeChestPos.z());
                storePathingFuture = BARITONE.rightClickBlock(storeChestPos.x(), storeChestPos.y(), storeChestPos.z());
                storePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.STORE_DEPOSIT);
            }
            case STORE_DEPOSIT -> {
                if (storePathingFuture.isCompleted()) {
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.STORE_GO_TO_CHEST);
                        }
                        return;
                    }
                    var storeItem = ItemRegistry.REGISTRY.get(storeItemId);
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> storeItem.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    storeDepositFuture = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build());
                    storePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.STORE_AWAIT_DEPOSIT);
                }
            }
            case STORE_AWAIT_DEPOSIT -> {
                if (storeDepositFuture.isCompleted()) {
                    var storeItem = ItemRegistry.REGISTRY.get(storeItemId);
                    int buyItemCount = countItem(storeItem.id());
                    if (buyItemCount > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit buy items, trying to continue anyway");
                            finishStore();
                        }
                        return;
                    }
                    // 卸完一类后，检查是否有其他产出物（含其他附魔类型）需要卸到不同箱子
                    if (countInvEmptySlots() <= PLUGIN_CONFIG.inventoryStoreEmptySlotBuffer) {
                        var next = nextTradeToStore();
                        if (next != null) {
                            storeItemId = next.outputItem;
                            storeEnchantInfo = getTradeEnchantInfo(next);
                            storeChestPos = next.outputChest;
                            waitForInteractTimer.reset();
                            setState(State.STORE_GO_TO_CHEST);
                            return;
                        }
                    }
                    finishStore();
                }
            }
            case READY_NEXT_TRADE -> {
                var trade = tradeIterator.current();
                switch (trade.postTradeStoreMode) {
                    case NONE -> {
                        setState(State.NEXT_TRADE);
                    }
                    case TO_RESTOCK -> {
                        setState(State.POST_TRADE_INPUT_1_GO_TO);
                    }
                    case TO_OVERFLOW -> {
                        setState(State.POST_TRADE_OVERFLOW_GO_TO);
                    }
                }
            }
            case POST_TRADE_INPUT_1_GO_TO -> {
                var trade = tradeIterator.current();
                postTradePathingFuture = BARITONE.rightClickBlock(trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_INPUT_1_DEPOSIT);
            }
            case POST_TRADE_INPUT_1_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem1();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_INPUT_1_GO_TO);
                        }
                        return;
                    }
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem1.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_INPUT_1_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_INPUT_1_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem1();
                    if (countItem(inputItem1.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit post trade input item 1, trying to continue anyway");
                        } else {
                            return;
                        }
                    }
                    if (trade.has2InputTrade()) {
                        setState(State.POST_TRADE_INPUT_2_GO_TO);
                    } else {
                        setState(State.NEXT_TRADE);
                    }
                }
            }
            case POST_TRADE_INPUT_2_GO_TO -> {
                var trade = tradeIterator.current();
                postTradePathingFuture = BARITONE.rightClickBlock(trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_INPUT_2_DEPOSIT);
            }
            case POST_TRADE_INPUT_2_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem2 = trade.getInputItem2();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_INPUT_2_GO_TO);
                        }
                        return;
                    }
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem2.id() == i.getId()
                        ));
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_INPUT_2_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_INPUT_2_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem2 = trade.getInputItem2();
                    if (countItem(inputItem2.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit post trade input item 2, trying to continue anyway");
                        } else {
                            return;
                        }
                    }
                    setState(State.NEXT_TRADE);
                }
            }
            case POST_TRADE_OVERFLOW_GO_TO -> {
                var trade = tradeIterator.current();
                postTradePathingFuture = BARITONE.rightClickBlock(trade.overflowChestPos.x(), trade.overflowChestPos.y(), trade.overflowChestPos.z());
                postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.POST_TRADE_OVERFLOW_DEPOSIT);
            }
            case POST_TRADE_OVERFLOW_DEPOSIT -> {
                if (postTradePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.POST_TRADE_OVERFLOW_GO_TO);
                        }
                        return;
                    }
                    var inputItem1 = trade.getInputItem1();
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> inputItem1.id() == i.getId()
                        ));
                    if (trade.has2InputTrade()) {
                        var inputItem2 = trade.getInputItem2();
                        actions.addAll(
                            InventoryActionMacros.deposit(
                                openContainer.getContainerId(),
                                i -> inputItem2.id() == i.getId()
                            ));
                    }
                    actions.add(new CloseContainer(openContainer.getContainerId()));
                    var request = InventoryActionRequest.builder()
                        .owner(this)
                        .priority(getPriority())
                        .actions(actions)
                        .build();
                    postTradeDepositFuture = INVENTORY.submit(request);
                    postTradePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                    setState(State.POST_TRADE_OVERFLOW_AWAIT_DEPOSIT);
                }
            }
            case POST_TRADE_OVERFLOW_AWAIT_DEPOSIT -> {
                if (postTradeDepositFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var inputItem1 = trade.getInputItem2();
                    if (countItem(inputItem1.id()) > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit post trade input item 1, trying to continue anyway");
                        } else {
                            return;
                        }
                    }
                    if (trade.has2InputTrade()) {
                        var inputItem2 = trade.getInputItem2();
                        if (countItem(inputItem2.id()) > 0) {
                            if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                                warn("Unable to fully deposit post trade input item 2, trying to continue anyway");
                            } else {
                                return;
                            }
                        }
                    }
                    setState(State.NEXT_TRADE);
                }
            }
            case NEXT_TRADE -> {
                if (currentVillagerUuid != null && tradeMadeThisRound) {
                    markVillagerTraded(currentVillagerUuid);
                } else if (currentVillagerUuid != null) {
                    // 本轮无可购买交易：记录失败时间，避免反复找同一个村民
                    villagerFailTime.put(currentVillagerUuid, mcTime());
                    currentVillagerUuid = null;
                    currentVillagerTrades = List.of();
                    preTradeIndex = 0;
                    restockSkippedIndices.clear();
                }
                tradeMadeThisRound = false;
                setState(State.TRADING_INTERACT_WITH_VILLAGER);
            }
            case AWAIT_RESTOCK -> {
                if (isDaylightCycleEnabled()) {
                    // 参照模块 waitTrade()：按上一次交易时间推算下一次补货窗口
                    // 早上 ~2000 村民上工补货，下午 ~9000 二次补货
                    if (restockWaitStartTime < 0) {
                        restockWaitStartTime = mcTime();
                    }
                    long waitStartDay = restockWaitStartTime / 24000L;
                    long waitStartTimeOfDay = restockWaitStartTime % 24000L;
                    long time = mcTime();
                    long day = time / 24000L;
                    long timeOfDay = time % 24000L;
                    boolean ok;
                    if (waitStartTimeOfDay > 9000L) {
                        ok = day > waitStartDay && timeOfDay > 2020L;
                    } else if (waitStartTimeOfDay > 2000L) {
                        ok = day > waitStartDay || timeOfDay > 9000L;
                    } else {
                        ok = day > waitStartDay || timeOfDay > 2020L;
                    }
                    if (ok) {
                        info("Restock wait complete, resuming trading");
                        restockWaitStartTime = -1;
                        setState(State.ENTRYPOINT);
                    }
                } else if (waitForRestockTimer.tick(20L * PLUGIN_CONFIG.villagerTradeRestockWaitSeconds)) {
                    // 昼夜循环被禁用时退回定时等待
                    setState(State.ENTRYPOINT);
                }
            }
        }
    }

    // 在同一个商人界面内为单个购买项匹配所有可购买 offer，并加入待执行动作队列。
    // 返回排队的交易次数（0 表示该购买项本轮无货可买）。
    // 参照模块 executeTrade：只匹配输出物品，不强制匹配输入（服务端会校验输入是否足够）
    // 附魔书场景：收集该职业所有 enchanted_book trade 的附魔需求，匹配村民实际售卖的附魔
    private record TradeQueueResult(int pending, int expectedInput1Sold, int expectedInput2Sold, int expectedOutputBought) {}

    // 在同一个商人界面内为单个购买项匹配所有可购买 offer，并加入待执行动作队列。
    // 返回排队的交易次数（0 表示该购买项本轮无货可买）。
    // 参照模块 executeTrade：只匹配输出物品，不强制匹配输入（服务端会校验输入是否足够）
    // 附魔书场景：收集该职业所有 enchanted_book trade 的附魔需求，匹配村民实际售卖的附魔
    private TradeQueueResult queueTradeActions(VillagerTraderConfig.Trade trade, VillagerTrade[] offers, List<InventoryAction> actions) {
        int pending = 0;
        int expectedInput1Sold = 0;
        int expectedInput2Sold = 0;
        int expectedOutputBought = 0;
        // 附魔书：收集该职业所有附魔书 trade 的附魔需求
        Object2IntLinkedOpenHashMap<String> allWantedEnchants = null;
        if (trade.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) {
            allWantedEnchants = new Object2IntLinkedOpenHashMap<>();
            for (var t : PLUGIN_CONFIG.trades.values()) {
                if (!t.enabled) continue;
                if (t.villagerProfession != trade.villagerProfession) continue;
                if (!t.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue;
                allWantedEnchants.putAll(t.outputItemEnchantments);
            }
        }
        for (int i = 0; i < offers.length; i++) {
            var villagerTrade = offers[i];
            if (villagerTrade.isTradeDisabled()) continue;
            if (villagerTrade.getOutput() == null) continue;
            // 只匹配输出物品（村民实际出售的物品）
            if (villagerTrade.getOutput().getId() != ItemRegistry.REGISTRY.get(trade.outputItem).id()) continue;
            // 附魔书场景：检查村民售卖的附魔是否在所有需求列表中
            if (allWantedEnchants != null) {
                if (!allWantedEnchants.isEmpty()) {
                    if (!enchantmentFilter(allWantedEnchants, villagerTrade.getOutput())) {
                        continue;
                    }
                }
                // allWantedEnchants 为空表示没有配置具体附魔需求，跳过附魔检查
            }

            // 该村民此物品本窗口已售罄（uses >= maxUses），跳过，等待下次补货
            int availableTradeCount = villagerTrade.getMaxUses() - villagerTrade.getNumUses();
            if (availableTradeCount <= 0) continue;

            // 计算实际价格（含需求加成和特殊价格）
            int baseCost = villagerTrade.getFirstInput().getAmount();
            int demandCost = Math.max(0, MathHelper.floorI((villagerTrade.getFirstInput().getAmount() * villagerTrade.getDemand() * villagerTrade.getPriceMultiplier())));
            int actualCost = MathHelper.clamp(baseCost + demandCost + villagerTrade.getSpecialPrice(), 1, 64);
            if (actualCost > trade.maxInput1PerTrade) continue;

            // 检查输入物品是否足够
            int input1Count = countItem(villagerTrade.getFirstInput().getId());
            int maxTradesForInput1 = input1Count / actualCost;
            int maxTradeCount = Math.min(availableTradeCount, maxTradesForInput1);

            if (trade.has2InputTrade() && villagerTrade.getSecondInput() != null) {
                int input2Cost = MathHelper.clamp(villagerTrade.getSecondInput().getAmount(), 1, 64);
                if (input2Cost > trade.maxInput2PerTrade) continue;
                int input2Count = countItem(villagerTrade.getSecondInput().getId());
                maxTradeCount = Math.min(maxTradeCount, input2Count / input2Cost);
            } else if (villagerTrade.getSecondInput() != null) {
                // 配置不需要第二输入，但村民交易需要 -> 跳过
                continue;
            }

            if (maxTradeCount <= 0) continue;

            if (canShiftClickPurchase(villagerTrade)) {
                int input1StackSize = ItemRegistry.REGISTRY.get(villagerTrade.getFirstInput().getId()).stackSize();
                int maxTradesPerInputStack = input1StackSize / actualCost;
                int outputsStackSize = ItemRegistry.REGISTRY.get(villagerTrade.getOutput().getId()).stackSize();
                int maxTradesPerOutputStack = outputsStackSize / villagerTrade.getOutput().getAmount();
                int maxTradesPerShiftClick = Math.min(maxTradesPerInputStack, maxTradesPerOutputStack);
                int tradeCount = 0;
                for (int j = 0; j < maxTradeCount; j += maxTradesPerShiftClick) {
                    tradeCount++;
                    actions.add(new SelectTrade(offersPacket.getContainerId(), i));
                    actions.add(new ShiftClick(offersPacket.getContainerId(), 2, ShiftClickItemAction.LEFT_CLICK));
                }
                pending += maxTradeCount;
                expectedInput1Sold += actualCost * maxTradeCount;
                expectedOutputBought += villagerTrade.getOutput().getAmount() * maxTradeCount;
                debug("shift clicking {} times, trade count: {} trades per shift click: {}", tradeCount, maxTradeCount, maxTradesPerShiftClick);
            } else {
                // 附魔书场景：shift 点击可能买到其他附魔，需逐本左键点到空位
                var emptySlots = findEmptySlots();
                if (emptySlots.isEmpty()) {
                    info("Can't trade because we don't have any empty inventory slots");
                }
                maxTradeCount = Math.min(maxTradeCount, emptySlots.size());
                for (int j = 0; j < maxTradeCount; j++) {
                    int outputSlot = emptySlots.removeFirst();
                    actions.add(new SelectTrade(offersPacket.getContainerId(), i));
                    actions.add(new ClickItem(offersPacket.getContainerId(), 2, ClickItemAction.LEFT_CLICK));
                    actions.add(new ClickItem(offersPacket.getContainerId(), outputSlot, ClickItemAction.LEFT_CLICK));
                }
                pending += maxTradeCount;
                expectedInput1Sold += actualCost * maxTradeCount;
                expectedOutputBought += villagerTrade.getOutput().getAmount() * maxTradeCount;
                debug("click trading {} times", maxTradeCount);
            }
            if (trade.has2InputTrade() && villagerTrade.getSecondInput() != null) {
                int input2Cost = MathHelper.clamp(villagerTrade.getSecondInput().getAmount(), 1, 64);
                expectedInput2Sold += input2Cost * maxTradeCount;
            }
        }
        return new TradeQueueResult(pending, expectedInput1Sold, expectedInput2Sold, expectedOutputBought);
    }

    // 与参照模块 needClean/tryPut 一致：仅在背包空位不足时才需要卸货
    // 不是每次有产出物就卸，而是背包快满时才卸
    // 与参照模块 needClean/tryPut 一致：仅在背包空位不足时才需要卸货
    // 附魔书：根据实际附魔类型匹配对应的 trade entry（每个附魔有自己的卸货箱）
    private VillagerTraderConfig.Trade nextTradeToStore() {
        if (countInvEmptySlots() > PLUGIN_CONFIG.inventoryStoreEmptySlotBuffer) {
            return null;
        }
        // 背包空位不足：先检查普通物品（非附魔书）
        for (var trade : currentVillagerTrades) {
            if (trade.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue;
            var output = ItemRegistry.REGISTRY.get(trade.outputItem);
            if (output == null || output == ItemRegistry.AIR) continue;
            if (countItem(output.id()) > 0) return trade;
        }
        // 再检查附魔书：根据实际附魔匹配对应的 trade entry
        var enchantedBookId = ItemRegistry.ENCHANTED_BOOK.id();
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() != enchantedBookId) continue;
            var enchantName = getEnchantmentName(item);
            if (enchantName == null) continue;
            for (var trade : currentVillagerTrades) {
                if (!trade.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue;
                if (trade.outputItemEnchantments.containsKey(enchantName)) {
                    return trade;
                }
            }
        }
        // 当前村民的产出物都检查完，再检查全局
        return nextItemToUnload();
    }

    // 获取附魔书的附魔名称（用于匹配 trade entry）
    private @Nullable String getEnchantmentName(ItemStack item) {
        var dataComponents = item.getDataComponents();
        if (dataComponents == null) return null;
        var storedEnchantments = dataComponents.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (storedEnchantments == null) return null;
        var enchants = storedEnchantments.getEnchantments();
        if (enchants.size() != 1) return null;
        var entry = enchants.int2IntEntrySet().iterator().next();
        var enchant = EnchantmentRegistry.REGISTRY.get(entry.getIntKey());
        return enchant != null ? enchant.name() : null;
    }

    // 卸货流程结束：进入下一个村民
    private void finishStore() {
        postPurchaseStore = false;
        setState(State.TRADING_INTERACT_WITH_VILLAGER);
    }

    private boolean enchantmentFilter(Object2IntLinkedOpenHashMap<String> wantedEnchants, @NonNull ItemStack output) {
        var dataComponents = output.getDataComponents();
        if (dataComponents == null) {
            debug("enchantmentFilter: no data components on output item");
            return false;
        }
        var storedEnchantments = dataComponents.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var enchantments = dataComponents.get(DataComponentTypes.ENCHANTMENTS);
        if (storedEnchantments == null && enchantments == null) {
            debug("enchantmentFilter: no stored or regular enchantments found");
            return false;
        }
        var itemEnchants = (storedEnchantments != null ? storedEnchantments : enchantments).getEnchantments();

        // 与参考模块 matchesDesiredEnchantments 一致：村民出售的附魔书只含一种附魔，否则不买
        if (itemEnchants.size() != 1) {
            debug("enchantmentFilter: output has {} enchantments, expected 1", itemEnchants.size());
            return false;
        }

        debug("enchantmentFilter: wanted={}, output={}", wantedEnchants, itemEnchants);

        for (var wantedEntry : wantedEnchants.object2IntEntrySet()) {
            String desiredEnchantKey = wantedEntry.getKey();
            var desiredEnchant = EnchantmentRegistry.REGISTRY.get(desiredEnchantKey);
            if (desiredEnchant == null) {
                error("Enchanted book enchantment id: {} not found in registry", desiredEnchantKey);
                continue;
            }
            var enchantId = desiredEnchant.id();
            int desiredLevel = wantedEntry.getIntValue();
            debug("enchantmentFilter: looking for enchant id={}, level>= {}, item has={}", enchantId, desiredLevel, itemEnchants.containsKey(enchantId) ? itemEnchants.get(enchantId) : "none");
            if (!itemEnchants.containsKey(enchantId))
                continue;
            int actualLevel = itemEnchants.get(enchantId);
            if (actualLevel >= desiredLevel) {
                return true;
            }
        }
        return false;
    }

    private void stop() {
        PLUGIN_CONFIG.enabled = false;
        syncEnabledFromConfig();
        saveConfigAsync();
    }

    private void setState(State newState) {
        debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    /**
     * Reads loaded item-frame entities and creates trades from the display-frame layout.
     * Input frames must be attached to a container. Output frames must have a container two
     * blocks below the frame. It writes regular Trade entries compatible with the
     * original plugin configuration format.
     */
    public ScanResult scanItemFrames() {
        var inputItemFrames = new ArrayList<ItemFrameMapping>();
        var outputItemFrames = new ArrayList<OutputItemFrame>();
        int ignored = 0;
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM_FRAME && entity.getEntityType() != EntityType.GLOW_ITEM_FRAME) continue;
            if (!isInItemFrameScanArea(entity)) continue;

            var item = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            if (item == null || item == Container.EMPTY_STACK || item.getId() == ItemRegistry.AIR.id()) {
                ignored++;
                continue;
            }
            var itemData = ItemRegistry.REGISTRY.get(item.getId());
            if (itemData == null) {
                ignored++;
                continue;
            }

            var itemFramePos = entity.blockPos();
            var outputPos = new BlockPos(itemFramePos.x(), itemFramePos.y() - 2, itemFramePos.z());
            var outputRule = outputRule(itemData.name(), item);
            if (outputRule != null && isStorageContainer(outputPos)) {
                // 附魔书：自动添加 "enchanted_book" 到该职业的 buyItems
                if (outputRule.requiresBook()) {
                    PLUGIN_CONFIG.addBuyItem(outputRule.profession(), ItemRegistry.ENCHANTED_BOOK.name());
                }
                // 是否购买该物品：需该职业已启用且该物品在其购买清单
                if (!PLUGIN_CONFIG.isItemEnabled(outputRule.profession(), itemData.name())) {
                    ignored++;
                    continue;
                }
                outputItemFrames.add(new OutputItemFrame(outputRule, outputPos));
                continue;
            }
            var inputChest = attachedContainer(entity, itemFramePos);
            if (inputChest != null) {
                inputItemFrames.add(new ItemFrameMapping(itemData.name(), inputChest));
            } else {
                ignored++;
            }
        }

        var emerald = inputItemFrames.stream().filter(f -> f.itemName.equals(ItemRegistry.EMERALD.name()) || f.itemName.equals(ItemRegistry.EMERALD_BLOCK.name())).findFirst();
        if (emerald.isEmpty()) {
            return new ScanResult(inputItemFrames.size(), outputItemFrames.size(), 0, ignored, "No emerald input itemFrame attached to a container was found");
        }
        var book = inputItemFrames.stream().filter(f -> f.itemName.equals(ItemRegistry.BOOK.name())).findFirst();
        int generated = 0;
        for (var output : outputItemFrames) {
            if (output.rule().requiresBook && book.isEmpty()) continue;
            addScannedTrade(output, emerald.get(), book.orElse(null));
            generated++;
        }
        tradeIterator.refresh();
        var problem = outputItemFrames.stream().anyMatch(output -> output.rule().requiresBook) && book.isEmpty()
            ? "Enchanted-book output frames found, but no book input frame attached to a container was found"
            : null;
        return new ScanResult(inputItemFrames.size(), outputItemFrames.size(), generated, ignored, problem);
    }

    private void addScannedTrade(OutputItemFrame output, ItemFrameMapping input1, @Nullable ItemFrameMapping input2) {
        var rule = output.rule();
        var trade = PLUGIN_CONFIG.trades.computeIfAbsent(rule.id(), key -> new VillagerTraderConfig.Trade());
        trade.villagerProfession = rule.profession();
        trade.inputItem1 = input1.itemName;
        trade.inputItem1Chest = input1.containerPos;
        trade.inputItem2 = rule.requiresBook() ? ItemRegistry.BOOK.name() : ItemRegistry.AIR.name();
        trade.inputItem2Chest = rule.requiresBook() && input2 != null ? input2.containerPos : BlockPos.ZERO;
        trade.outputItem = rule.outputItem();
        trade.outputChest = output.containerPos();
        trade.outputItemEnchantments.clear();
        if (rule.enchantment() != null) {
            // 与参考模块一致：附魔书购买该附魔的最高等级。扫描生成时直接记录该附魔的 maxLevel，
            // 使 enchantmentFilter 只接受达到最高等级的书（除非注册表无该附魔，才退回展示框等级）
            var enchantData = EnchantmentRegistry.REGISTRY.get(rule.enchantment());
            int desiredLevel = enchantData != null ? enchantData.maxLevel() : rule.enchantmentLevel();
            trade.outputItemEnchantments.put(rule.enchantment(), desiredLevel);
        }
    }

    private OutputRule outputRule(String itemName, ItemStack item) {
        if (itemName.equals(ItemRegistry.ENCHANTED_BOOK.name())) {
            var enchantment = displayedBookEnchantment(item);
            return enchantment == null ? null : new OutputRule(enchantment.name, VillagerProfession.LIBRARIAN, itemName, true, enchantment.name, enchantment.level);
        }
        // 遍历所有职业档案，匹配 buyItems 购买清单中的物品
        for (var entry : PLUGIN_CONFIG.profession.entrySet()) {
            var profession = VillagerProfession.valueOf(entry.getKey().toUpperCase());
            if (entry.getValue().buyItems.contains(itemName)) {
                return new OutputRule(itemName, profession, itemName, false, null, 0);
            }
        }
        // 不在任何职业预设目录内，不识别
        return null;
    }

    private @Nullable DisplayedEnchantment displayedBookEnchantment(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) return null;
        var stored = components.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored == null || stored.getEnchantments().size() != 1) return null;
        var entry = stored.getEnchantments().int2IntEntrySet().iterator().next();
        var enchantment = EnchantmentRegistry.REGISTRY.get(entry.getIntKey());
        return enchantment == null ? null : new DisplayedEnchantment(enchantment.name(), entry.getIntValue());
    }

    private @Nullable BlockPos attachedContainer(Entity frame, BlockPos framePos) {
        if (!(frame.getObjectData() instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction direction)) return null;
        var supportingBlock = framePos.relative(Direction.valueOf(direction.name()).invert());
        return isStorageContainer(supportingBlock) ? supportingBlock : null;
    }

    private boolean isStorageContainer(BlockPos pos) {
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        Block block = World.getBlock(pos);
        return block == BlockRegistry.CHEST || block == BlockRegistry.TRAPPED_CHEST || block == BlockRegistry.BARREL
            || block.blockTags().contains(BlockTags.SHULKER_BOXES);
    }

    private boolean isInItemFrameScanArea(Entity entity) {
        int horizontalRange = PLUGIN_CONFIG.itemFrameScanHorizontalRangeBlocks;
        int verticalRange = PLUGIN_CONFIG.itemFrameScanVerticalRangeBlocks;
        var player = CACHE.getPlayerCache().getThePlayer();
        return Math.abs(entity.getX() - player.getX()) <= horizontalRange
            && Math.abs(entity.getZ() - player.getZ()) <= horizontalRange
            && Math.abs(entity.getY() - player.getY()) <= verticalRange;
    }

    private record ItemFrameMapping(String itemName, BlockPos containerPos) {}
    private record OutputItemFrame(OutputRule rule, BlockPos containerPos) {}
    private record OutputRule(String id, VillagerProfession profession, String outputItem, boolean requiresBook, @Nullable String enchantment, int enchantmentLevel) {}
    private record DisplayedEnchantment(String name, int level) {}
    public record ScanResult(int inputFrames, int outputFrames, int generatedTrades, int ignoredFrames, @Nullable String problem) {}
    private record TradePreCounts(VillagerTraderConfig.Trade trade, int input1, int input2, int output,
                                   int expectedInput1Sold, int expectedInput2Sold, int expectedOutputBought) {}

    private VillagerProfession getVillagerProfession(EntityLiving villager) {
        var data = villager.getMetadataValue(18, MetadataTypes.VILLAGER_DATA, VillagerData.class);
        if (data == null) {
            return VillagerProfession.NONE;
        }
        return VillagerProfession.from(data.getProfession());
    }

    private int countItem(int id) {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() == id) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countInvEmptySlots() {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            if (inv.get(i) == Container.EMPTY_STACK) {
                count++;
            }
        }
        return count;
    }

    // 获取 trade 配置的附魔信息（用于卸货日志）
    private String getTradeEnchantInfo(VillagerTraderConfig.Trade trade) {
        return getEnchantInfoFromInventory(trade);
    }

    // 获取背包中附魔书的具体附魔信息（用于调试输出）
    private String getEnchantInfoFromInventory(VillagerTraderConfig.Trade trade) {
        // 直接从 trade 配置的附魔需求中获取附魔信息（不依赖背包缓存）
        if (trade.outputItemEnchantments != null && !trade.outputItemEnchantments.isEmpty()) {
            var entry = trade.outputItemEnchantments.object2IntEntrySet().iterator().next();
            return entry.getKey() + " " + entry.getIntValue();
        }
        return "unknown";
    }

    // 找背包里第一个仍持有产出物且配置了卸货箱的 Trade，用于背包满时的连锁卸货
    // 附魔书：根据实际附魔类型匹配对应的 trade entry
    private VillagerTraderConfig.Trade nextItemToUnload() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        var enchantedBookId = ItemRegistry.ENCHANTED_BOOK.id();
        // 先检查普通物品
        for (var entry : PLUGIN_CONFIG.trades.entrySet()) {
            var t = entry.getValue();
            if (!t.enabled) continue;
            if (t.outputChest == null || BlockPos.ZERO.equals(t.outputChest)) continue;
            if (t.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue;
            var item = ItemRegistry.REGISTRY.get(t.outputItem);
            if (item == null || item == ItemRegistry.AIR) continue;
            if (countItem(item.id()) > 0) return t;
        }
        // 再检查附魔书：根据实际附魔匹配
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() != enchantedBookId) continue;
            var enchantName = getEnchantmentName(item);
            if (enchantName == null) continue;
            for (var entry : PLUGIN_CONFIG.trades.entrySet()) {
                var t = entry.getValue();
                if (!t.enabled) continue;
                if (t.outputChest == null || BlockPos.ZERO.equals(t.outputChest)) continue;
                if (!t.outputItem.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue;
                if (t.outputItemEnchantments.containsKey(enchantName)) return t;
            }
        }
        return null;
    }

    private int countSlotUsages(int id) {
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() == id) {
                count++;
            }
        }
        return count;
    }

    private boolean canShiftClickPurchase(VillagerTrade villagerTrade) {
        int count = 0;
        for (var offer : offersPacket.getTrades()) {
            if (villagerTrade.getOutput().getId() != offer.getOutput().getId()) continue;
            if (villagerTrade.getFirstInput().getId() != offer.getFirstInput().getId()) continue;
            boolean offerHasInput2 = offer.getSecondInput() != Container.EMPTY_STACK;
            boolean villagerTradeHasInput2 = villagerTrade.getSecondInput() != Container.EMPTY_STACK;
            if (offerHasInput2 && villagerTradeHasInput2) {
                if (villagerTrade.getSecondInput().getId() == offer.getSecondInput().getId()) {
                    count++;
                }
            } else if (!offerHasInput2 && !villagerTradeHasInput2) {
                count++;
            }
        }
        return count <= 1;
    }

    private IntList findEmptySlots() {
        var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (openContainer.getType() != ContainerType.MERCHANT) return IntList.of();
        var output = new IntArrayList();
        var containerInfo = ContainerTypeInfoRegistry.REGISTRY.get(openContainer.getType());
        for (int i = containerInfo.topSlots(); i < containerInfo.totalSlots(); i++) {
            if (openContainer.getItemStack(i) == Container.EMPTY_STACK) {
                output.add(i);
            }
        }
        return output;
    }

    public void onTradeListChange() {
        reset();
    }

    /**
     * 当没有手动配置的 trades 时，根据 professionBuyItems 配置动态生成交易任务。
     * 寻找有效的村民，按职业匹配可购买的物品，生成 Trade 条目。
     * 返回 true 表示生成了至少一个交易。
     */
    private boolean generateTradesFromConfig() {
        if (PLUGIN_CONFIG.profession.isEmpty()) return false;

        for (var entry : PLUGIN_CONFIG.profession.entrySet()) {
            var profession = VillagerProfession.valueOf(entry.getKey().toUpperCase());
            if (!PLUGIN_CONFIG.isProfEnabled(profession)) continue;
            var buyItems = PLUGIN_CONFIG.getEnabledItems(profession);
            if (buyItems.isEmpty()) continue;

            // 找一个该职业的村民来获取交易信息（只查找，不交互）
            var found = CACHE.getEntityCache().getEntities().values().stream()
                .filter(e -> e.getEntityType() == EntityType.VILLAGER)
                .map(e -> (EntityLiving) e)
                .filter(e -> profession == getVillagerProfession(e))
                .findFirst();
            if (found.isEmpty()) continue;

            // 为每个要购买的产出物品创建一个 Trade
            // 注：这里只创建占位 Trade，补货/卸货箱坐标需要用户通过 scan 或手动设置
            for (String itemName : buyItems) {
                if (itemName.equals(ItemRegistry.ENCHANTED_BOOK.name())) continue; // 附魔书按具体附魔通过 scan 动态生成
                var tradeId = profession.name().toLowerCase() + "_" + itemName;
                if (PLUGIN_CONFIG.trades.containsKey(tradeId)) continue;

                var trade = new VillagerTraderConfig.Trade();
                trade.villagerProfession = profession;
                trade.outputItem = itemName;
                trade.inputItem1 = ItemRegistry.EMERALD.name(); // 默认输入绿宝石
                PLUGIN_CONFIG.trades.put(tradeId, trade);
            }
        }
        return !PLUGIN_CONFIG.trades.isEmpty();
    }

    @Nullable String getTradeId(VillagerTraderConfig.Trade trade) {
        for (var entry : PLUGIN_CONFIG.trades.entrySet()) {
            if (entry.getValue() == trade) {
                return entry.getKey();
            }
        }
        return null;
    }

    public enum State {
        ENTRYPOINT,
        EVAL_RESTOCK,
        RESTOCK_INPUT_1_GO_TO_CHEST,
        RESTOCK_INPUT_1_WITHDRAW,
        RESTOCK_INPUT_1_AWAIT_WITHDRAW,
        RESTOCK_INPUT_2_GO_TO_CHEST,
        RESTOCK_INPUT_2_WITHDRAW,
        RESTOCK_INPUT_2_AWAIT_WITHDRAW,
        CRAFT_EMERALD_BLOCKS,
        AWAIT_CRAFT_EMERALD_BLOCKS,
        TRADING_INTERACT_WITH_VILLAGER,
        TRADING_AWAIT_INTERACT_WITH_VILLAGER,
        TRADING_TRY_START_PURCHASE,
        TRADING_AWAIT_PURCHASE,
        STORE_GO_TO_CHEST,
        STORE_DEPOSIT,
        STORE_AWAIT_DEPOSIT,
        READY_NEXT_TRADE,
        POST_TRADE,
        POST_TRADE_INPUT_1_GO_TO,
        POST_TRADE_INPUT_1_DEPOSIT,
        POST_TRADE_INPUT_1_AWAIT_DEPOSIT,
        POST_TRADE_INPUT_2_GO_TO,
        POST_TRADE_INPUT_2_DEPOSIT,
        POST_TRADE_INPUT_2_AWAIT_DEPOSIT,
        POST_TRADE_OVERFLOW_GO_TO,
        POST_TRADE_OVERFLOW_DEPOSIT,
        POST_TRADE_OVERFLOW_AWAIT_DEPOSIT,
        NEXT_TRADE,
        AWAIT_RESTOCK
    }

    public enum VillagerProfession {
        NONE,
        ARMORER,
        BUTCHER,
        CARTOGRAPHER,
        CLERIC,
        FARMER,
        FISHERMAN,
        FLETCHER,
        LEATHERWORKER,
        LIBRARIAN,
        MASON,
        NITWIT,
        SHEPHERD,
        TOOLSMITH,
        WEAPONSMITH;

        private static final VillagerProfession[] VALUES = values();

        public static VillagerProfession from(int id) {
            return VALUES[id];
        }
    }

    public static class TradeIterator implements Iterator<VillagerTraderConfig.Trade> {
        int index = 0;
        VillagerTraderConfig.Trade[] backingArray = PLUGIN_CONFIG.trades.values()
            .stream()
            .filter(trade -> trade.enabled)
            .toArray(VillagerTraderConfig.Trade[]::new);

        @Override
        public boolean hasNext() {
            return backingArray.length > 0;
        }

        public VillagerTraderConfig.Trade current() {
            return backingArray[index];
        }

        @Override
        public VillagerTraderConfig.Trade next() {
            if (++index >= backingArray.length) {
                index = 0;
            }
            return backingArray[index];
        }

        public void refresh() {
            backingArray = PLUGIN_CONFIG.trades.values()
                .stream()
                .filter(trade -> trade.enabled)
                .toArray(VillagerTraderConfig.Trade[]::new);
            if (index >= backingArray.length) {
                index = 0;
            }
        }

        public void reset() {
            index = 0;
            refresh();
        }
    }
}