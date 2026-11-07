package dev.zenith.trader.module;

import com.github.rfresh2.EventConsumer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.BlockTags;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.api.BlockStateProperties;
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
    private final Cache<UUID, Boolean> interactedVillagersCache = CacheBuilder.newBuilder()
        .build();
    private final TradeIterator tradeIterator = new TradeIterator();
    private @Nullable UUID activeVillagerUuid;
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture emeraldBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture interactWithVillagerFuture = PathingRequestFuture.rejected;
    private ClientboundMerchantOffersPacket offersPacket = null;
    private RequestFuture purchaseFuture = RequestFuture.rejected;
    private final List<QueuedTrade> selectTradeQueue = new ArrayList<>();
    private int selectTradePos = 0;
    private RequestFuture selectTradeFuture = RequestFuture.rejected;
    private RequestFuture shiftResultFuture = RequestFuture.rejected;
    private PathingRequestFuture storePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture storeDepositFuture = RequestFuture.rejected;
    private PathingRequestFuture postTradePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture postTradeDepositFuture = RequestFuture.rejected;
    private final Timer waitForRestockTimer = Timers.tickTimer();
    private final Timer waitForInteractTimer = Timers.tickTimer();
    private int preTradeOutputCount = 0;
    private int preTradeInput1Count = 0;
    private int preTradeInput2Count = 0;
    private int outputBuyCount = 0;
    private int input1SellCount = 0;
    private int input2SellCount = 0;
    private long tradeStartTime = System.nanoTime();

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
        interactedVillagersCache.invalidateAll();
        activeVillagerUuid = null;
        offersPacket = null;
        waitForInteractTimer.reset();
        waitForRestockTimer.reset();
        selectTradeQueue.clear();
        selectTradePos = 0;
        tradeIterator.reset();
        resetTradeCounter();
    }

    private void resetTradeCounter() {
        preTradeOutputCount = 0;
        preTradeInput1Count = 0;
        preTradeInput2Count = 0;
        outputBuyCount = 0;
        input1SellCount = 0;
        input2SellCount = 0;
        tradeStartTime = System.nanoTime();
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
        debug("Offers: {}", packet);
        return packet;
    }

    private Optional<VillagerTraderConfig.Trade> findMatchingConfig(VillagerTrade offer) {
        for (var config : tradeIterator.backingArray) {
            if (offer.getOutput() != null && offer.getOutput().getId() != ItemRegistry.REGISTRY.get(config.outputItem).id())
                continue;
            if (offer.getFirstInput().getId() != ItemRegistry.REGISTRY.get(config.inputItem1).id()) continue;
            if (config.has2InputTrade()) {
                if (offer.getSecondInput() == null) continue;
                if (offer.getSecondInput().getId() != ItemRegistry.REGISTRY.get(config.inputItem2).id()) continue;
            } else {
                if (offer.getSecondInput() != null) continue;
            }
            if (!config.outputItemEnchantments.isEmpty()) {
                if (!enchantmentFilter(config, offer.getOutput())) continue;
            }
            return Optional.of(config);
        }
        return Optional.empty();
    }

    private void onTick(ClientBotTick event) {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (Proxy.getInstance().isInQueue() || player == null || !player.isAlive()) {
            state = State.ENTRYPOINT;
            return;
        }
        if (BOT.isSneaking()) {
            INPUTS.submit(InputRequest.builder()
                .owner(this)
                .priority(getPriority() - 1)
                .input(Input.builder().sneaking(false).build())
                .build());
        }
        switch (state) {
            case ENTRYPOINT -> {
                if (!tradeIterator.hasNext())
                    return;
                resetTradeCounter();
                setState(State.EVAL_RESTOCK);
            }
            case EVAL_RESTOCK -> {
                var trade = tradeIterator.current();
                var storeTrade = findStorableOutputTrade();
                if (storeTrade.isPresent()) {
                    tradeIterator.select(storeTrade.get());
                    setState(State.STORE_GO_TO_CHEST);
                    return;
                }
                var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                int input1Count = countItem(input1.id());
                if (trade.inputItem1RestockCountThreshold > input1Count) {
                    setState(State.RESTOCK_INPUT_1_GO_TO_CHEST);
                    return;
                }
                if (trade.has2InputTrade()) {
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    int input2Count = countItem(input2.id());
                    if (trade.inputItem2RestockCountThreshold > input2Count) {
                        setState(State.RESTOCK_INPUT_2_GO_TO_CHEST);
                        return;
                    }
                }
                int outputCount = countOutputItem(trade);
                if (outputCount > trade.outputItemStoreCountThreshold) {
                    setState(State.STORE_GO_TO_CHEST);
                    return;
                }
                setState(State.CRAFT_EMERALD_BLOCKS);
            }
            case RESTOCK_INPUT_1_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem1Chest.x(), trade.inputItem1Chest.y(), trade.inputItem1Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_1_WITHDRAW);
            }
            case RESTOCK_INPUT_1_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
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
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            error("Timed out waiting for input 1 container to open");
                            sneakForContainerOpen();
                            setState(State.RESTOCK_INPUT_1_AWAIT_WITHDRAW);
                        }
                    }
                }
            }
            case RESTOCK_INPUT_1_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var input1 = ItemRegistry.REGISTRY.get(trade.inputItem1);
                    int input1Count = countItem(input1.id());
                    if (trade.inputItem1RestockCountThreshold > input1Count) {
                        error("Failed restocking sufficient {} for trade: {}", input1.name(), trade.outputItem);
                    }
                    setState(State.CRAFT_EMERALD_BLOCKS);
                }
            }
            case RESTOCK_INPUT_2_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                restockPathingFuture = BARITONE.rightClickBlock(trade.inputItem2Chest.x(), trade.inputItem2Chest.y(), trade.inputItem2Chest.z());
                restockPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.RESTOCK_INPUT_2_WITHDRAW);
            }
            case RESTOCK_INPUT_2_WITHDRAW -> {
                if (restockPathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
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
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            error("Timed out waiting for input 2 container to open");
                            sneakForContainerOpen();
                            setState(State.RESTOCK_INPUT_2_AWAIT_WITHDRAW);
                        }
                    }
                }
            }
            case RESTOCK_INPUT_2_AWAIT_WITHDRAW -> {
                if (restockWithdrawFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var input2 = ItemRegistry.REGISTRY.get(trade.inputItem2);
                    int input2Count = countItem(input2.id());
                    if (trade.inputItem2RestockCountThreshold > input2Count) {
                        error("Failed restocking sufficient {} for trade: {}", input2.name(), trade.outputItem);
                    }
                    setState(State.CRAFT_EMERALD_BLOCKS);
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
                var trade = tradeIterator.current();
                var nextVillagerOptional = nextVillager();
                if (nextVillagerOptional.isEmpty()) {
                    if (interactedVillagersCache.asMap().isEmpty()) {
                        warn("No villagers found to trade with");
                        setState(State.READY_NEXT_TRADE);
                    } else {
                        if (countItem(trade.getOutputItem().id()) > 0) {
                            setState(State.STORE_GO_TO_CHEST);
                        } else {
                            setState(State.READY_NEXT_TRADE);
                        }
                    }
                    return;
                }
                var nextVillager = nextVillagerOptional.get();
                activeVillagerUuid = nextVillager.getUuid();
                offersPacket = null;
                interactWithVillagerFuture = BARITONE.rightClickEntity(nextVillager);
                interactWithVillagerFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                interactedVillagersCache.put(nextVillager.getUuid(), true);
                setState(State.TRADING_AWAIT_INTERACT_WITH_VILLAGER);
            }
            case TRADING_AWAIT_INTERACT_WITH_VILLAGER -> {
                if (interactWithVillagerFuture.isCompleted()) {
                    if (offersPacket == null) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.TRADING_INTERACT_WITH_VILLAGER);
                            
                        }
                        return;
                    }
                    if (offersPacket.getContainerId() != CACHE.getPlayerCache().getInventoryCache().getOpenContainerId()) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            setState(State.TRADING_INTERACT_WITH_VILLAGER);
                        }
                        return;
                    }
                    setState(State.TRADING_TRY_START_PURCHASE);
                }
            }
            case TRADING_TRY_START_PURCHASE -> {
                VillagerTraderConfig.Trade trade = null;
                var trades = offersPacket.getTrades();
                selectTradeQueue.clear();
                List<InventoryAction> legacyActions = Lists.newArrayList();
                for (int i = 0; i < trades.length; i++) {
                    var villagerTrade = trades[i];
                    if (villagerTrade.isTradeDisabled()) continue;
                    if (villagerTrade.getOutput() == null) continue;
                    var matchingConfig = findMatchingConfig(villagerTrade);
                    if (matchingConfig.isEmpty()) continue;
                    var candidate = matchingConfig.get();
                    tradeIterator.select(candidate);
                    trade = candidate;

                    int availableTradeCount = villagerTrade.getMaxUses() - villagerTrade.getNumUses(); // each shift click can consume many trades

                    int input1StackSize = ItemRegistry.REGISTRY.get(trade.inputItem1).stackSize();

                    int baseCostInput1 = villagerTrade.getFirstInput().getAmount();
                    int input1DemandCost = Math.max(0, MathHelper.floorI((villagerTrade.getFirstInput().getAmount() * villagerTrade.getDemand() * villagerTrade.getPriceMultiplier())));
                    int input1Cost = MathHelper.clamp(baseCostInput1 + input1DemandCost + villagerTrade.getSpecialPrice(), 1, input1StackSize);
                    if (input1Cost > trade.maxInput1PerTrade) continue;
                    int maxTradesPerInputStack = input1StackSize / input1Cost;
                    int input1Count = countItem(trade.getInputItem1().id());
                    int maxTradesForInput1 = input1Count / input1Cost;
                    int maxTradeCount = Math.min(availableTradeCount, maxTradesForInput1);

                    if (maxTradeCount == 0) {
                        info("Can't trade because not enough {} (have {}, need at least {})", ItemRegistry.REGISTRY.get(trade.inputItem1).name(), input1Count, input1Cost);
                    }

                    if (trade.has2InputTrade()) {
                        int input2StackSize = trade.has2InputTrade() ? 64 : ItemRegistry.REGISTRY.get(trade.inputItem2).stackSize();
                        int baseCostInput2 = villagerTrade.getSecondInput().getAmount();
                        int input2DemandCost = Math.max(0, MathHelper.floorI((villagerTrade.getSecondInput().getAmount() * villagerTrade.getDemand() * villagerTrade.getPriceMultiplier())));
                        int input2Cost = MathHelper.clamp(baseCostInput2 + input2DemandCost + villagerTrade.getSpecialPrice(), 1, input2StackSize);
                        if (input2Cost > trade.maxInput2PerTrade) continue;
                        int tradersPerInput2Stack = villagerTrade.getSecondInput().getAmount() / input2Cost;
                        maxTradesPerInputStack = Math.min(maxTradesPerInputStack, tradersPerInput2Stack);
                        int input2Count = countItem(trade.getInputItem2().id());
                        int maxTradesForInput2 = input2Count / input2Cost;
                        boolean hadEnoughInput1ToTrade = maxTradeCount > 0;
                        maxTradeCount = Math.min(maxTradeCount, maxTradesForInput2);
                        if (maxTradeCount == 0 && hadEnoughInput1ToTrade) {
                            info("Can't trade because not enough {} (have {}, need {})", ItemRegistry.REGISTRY.get(trade.inputItem2).name(), input2Count, input2Cost);
                        }
                    }

                    if (maxTradeCount > 0) selectTradeQueue.add(new QueuedTrade(i, candidate));
                }
                if (trade == null) {
                    trade = tradeIterator.current();
                }
                if (!selectTradeQueue.isEmpty()) {
                    selectTradePos = 0;
                    setState(State.TRADING_SELECT_TRADE);
                    return;
                }
                if (activeVillagerUuid != null) interactedVillagersCache.put(activeVillagerUuid, true);
                activeVillagerUuid = null;
                legacyActions.add(new CloseContainer(offersPacket.getContainerId()));
                purchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(getPriority())
                    .actions(legacyActions)
                    .build());
                preTradeInput1Count = countItem(trade.getInputItem1().id());
                preTradeInput2Count = trade.has2InputTrade() ? countItem(trade.getInputItem2().id()) : 0;
                preTradeOutputCount = countItem(trade.getOutputItem().id());
                setState(State.TRADING_AWAIT_PURCHASE);
            }
            case TRADING_SELECT_TRADE -> {
                var queuedTrade = selectTradeQueue.get(selectTradePos);
                tradeIterator.select(queuedTrade.config());
                var tradeIndex = queuedTrade.offerIndex();
                selectTradeFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(getPriority())
                    .actions(List.of(new SelectTrade(offersPacket.getContainerId(), tradeIndex)))
                    .build());
                waitForInteractTimer.reset();
                setState(State.TRADING_AWAIT_RESULT_CONFIRM);
            }
            case TRADING_AWAIT_RESULT_CONFIRM -> {
                if (!selectTradeFuture.isCompleted()) return;
                var trade = tradeIterator.current();
                var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                var resultItem = openContainer.getItemStack(2);
                if (resultItem == Container.EMPTY_STACK) {
                    if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                        error("Result slot still empty after selecting offer {}, skipping purchase", selectTradeQueue.get(selectTradePos).offerIndex());
                        finishPurchase();
                    }
                    return;
                }
                if (resultItem.getId() != trade.getOutputItem().id()) {
                    if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                        error("Result slot {} != expected {} after selecting offer {}, skipping purchase", resultItem.getId(), trade.getOutputItem().id(), selectTradeQueue.get(selectTradePos).offerIndex());
                        finishPurchase();
                    }
                    return;
                }
                setState(State.TRADING_SHIFT_RESULT);
            }
            case TRADING_SHIFT_RESULT -> {
                var trade = tradeIterator.current();
                info("shift clicking 1 time (single trade)");
                shiftResultFuture = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(getPriority())
                    .actions(List.of(new ShiftMerchantResult(offersPacket.getContainerId(), trade.getOutputItem().id())))
                    .build());
                setState(State.TRADING_AWAIT_SHIFT_RESULT);
            }
            case TRADING_AWAIT_SHIFT_RESULT -> {
                if (!shiftResultFuture.isCompleted()) return;
                selectTradePos++;
                if (selectTradePos < selectTradeQueue.size()) {
                    setState(State.TRADING_SELECT_TRADE);
                } else {
                    finishPurchase();
                }
            }
            case TRADING_AWAIT_PURCHASE -> {
                if (purchaseFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var input1Sold = preTradeInput1Count - countItem(trade.getInputItem1().id());
                    info("Sold {} {}", input1Sold, trade.inputItem1);
                    input1SellCount += input1Sold;
                    if (trade.has2InputTrade()) {
                        var input2Sold = preTradeInput2Count - countItem(trade.getInputItem2().id());
                        info("Sold {} {}", input2Sold, trade.inputItem2);
                        input2SellCount += input2Sold;
                    }
                    var outputBought = countItem(trade.getOutputItem().id()) - preTradeOutputCount;
                    info("Bought {} {}{}", outputBought, trade.getOutputItem().name(), enchantmentSuffix(trade));
                    outputBuyCount += outputBought;
                    if (countOutputItem(trade) > trade.outputItemStoreCountThreshold) {
                        setState(State.STORE_GO_TO_CHEST);
                    } else {
                        setState(State.EVAL_RESTOCK);
                    }
                }
            }
            case STORE_GO_TO_CHEST -> {
                var trade = tradeIterator.current();
                var storeChest = trade.outputChest;
                storePathingFuture = BARITONE.rightClickBlock(storeChest.x(), storeChest.y(), storeChest.z());
                storePathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
                setState(State.STORE_DEPOSIT);
            }
            case STORE_DEPOSIT -> {
                if (storePathingFuture.isCompleted()) {
                    var trade = tradeIterator.current();
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() == 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            sneakForContainerOpen();
                            setState(State.STORE_GO_TO_CHEST);
                        }
                        return;
                    }
                    var outputItem = trade.getOutputItem();
                    var actions = Lists.<InventoryAction>newArrayList();
                    var containerInfo = ContainerTypeInfoRegistry.REGISTRY.get(openContainer.getType());
                    for (int slot = containerInfo.topSlots(); slot < containerInfo.totalSlots(); slot++) {
                        var stack = openContainer.getItemStack(slot);
                        if (stack == Container.EMPTY_STACK || stack.getId() != outputItem.id()) continue;
                        if (!trade.outputItemEnchantments.isEmpty() && !enchantmentFilter(trade, stack)) continue;
                        actions.add(new ShiftClick(openContainer.getContainerId(), slot, ShiftClickItemAction.LEFT_CLICK));
                    }
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
                    var trade = tradeIterator.current();
                    int buyItemCount = countOutputItem(trade);
                    if (buyItemCount > 0) {
                        if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            warn("Unable to fully deposit buy items, trying to continue anyway");
                            setState(State.EVAL_RESTOCK);
                        }
                        return;
                    }
                    var nextStoreTrade = findAnyStorableOutputTrade(trade);
                    if (nextStoreTrade.isPresent()) {
                        tradeIterator.select(nextStoreTrade.get());
                        setState(State.STORE_GO_TO_CHEST);
                    } else {
                        setState(State.EVAL_RESTOCK);
                    }
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
                            sneakForContainerOpen();
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
                            sneakForContainerOpen();
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
                            sneakForContainerOpen();
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
                var trade = tradeIterator.current();
                int previousIndex = tradeIterator.index;
                var nextTrade = tradeIterator.next();
                if (tradeIterator.index <= previousIndex) {
                    interactedVillagersCache.invalidateAll();
                    activeVillagerUuid = null;
                }
                var tradeDuration = Duration.ofNanos(System.nanoTime() - tradeStartTime);
                var tradeResult = Embed.builder()
                    .title("Trade Completed")
                    .addField("Trade ID", Objects.requireNonNullElse(getTradeId(trade), "?"))
                    .addField("Duration", MathHelper.formatDuration(tradeDuration))
                    .addField("Input 1", trade.inputItem1)
                    .addField("Input 1 Sell Count", input1SellCount);
                if (trade.has2InputTrade()) {
                    tradeResult
                        .addField("Input 2", trade.inputItem2)
                        .addField("Input 2 Sell Count", input2SellCount);
                }
                tradeResult
                    .addField("Output", trade.outputItem)
                    .addField("Output Buy Count", outputBuyCount)
                    .addField("Next Trade", Objects.requireNonNullElse(getTradeId(nextTrade), "?"));
                if (PLUGIN_CONFIG.logTradeStatusToDiscord) {
                    discordNotification(tradeResult);
                } else {
                    info(EmbedSerializer.serialize(tradeResult));
                }
                setState(State.ENTRYPOINT);
            }
        }
    }

    private boolean enchantmentFilter(VillagerTraderConfig.Trade trade, @NonNull ItemStack output) {
        var dataComponents = output.getDataComponents();
        if (dataComponents == null)
            return false;
        var storedEnchantments = dataComponents.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var enchantments = dataComponents.get(DataComponentTypes.ENCHANTMENTS);
        var itemEnchants = storedEnchantments != null ? storedEnchantments.getEnchantments()
            : enchantments != null ? enchantments.getEnchantments() : null;

        for (var bookEnchantEntry : trade.outputItemEnchantments.object2IntEntrySet()) {
            String desiredEnchantKey = bookEnchantEntry.getKey();
            if (desiredEnchantKey.startsWith("lore:")) {
                var loreEnchant = loreEnchantment(dataComponents.toString());
                if (loreEnchant != null
                    && loreEnchant.name().equals(desiredEnchantKey.substring("lore:".length()))
                    && loreEnchant.level() >= bookEnchantEntry.getIntValue()) return true;
                continue;
            }
            if (itemEnchants == null) continue;
            var desiredEnchant = EnchantmentRegistry.REGISTRY.get(desiredEnchantKey);
            int enchantId;
            if (desiredEnchant != null) {
                enchantId = desiredEnchant.id();
            } else if (desiredEnchantKey.startsWith("registry:")) {
                try {
                    enchantId = Integer.parseInt(desiredEnchantKey.substring("registry:".length()));
                } catch (NumberFormatException e) {
                    error("Invalid numeric enchantment registry key: {}", desiredEnchantKey);
                    continue;
                }
            } else {
                error("Enchanted book enchantment id: {} not found in registry", desiredEnchantKey);
                continue;
            }
            int desiredLevel = bookEnchantEntry.getIntValue();
            if (!itemEnchants.containsKey(enchantId))
                continue;
            int actualLevel = itemEnchants.get(enchantId);
            if (actualLevel >= desiredLevel) {
                return true;
            }
        }
        return false;
    }

    private String enchantmentSuffix(VillagerTraderConfig.Trade trade) {
        if (trade.outputItemEnchantments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : trade.outputItemEnchantments.object2IntEntrySet()) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(entry.getKey()).append(' ').append(entry.getIntValue());
        }
        return " [" + sb + ']';
    }

    /**
     * Trades whose extra input is not a book (maps, tipped arrows, cooked fish,
     * banner patterns) are intentionally absent: the two input restock mechanism
     * only supports emerald + book.
     */
    private static final Map<VillagerProfession, Set<String>> VILLAGER_SELLABLE_OUTPUTS = Map.ofEntries(
            Map.entry(VillagerProfession.ARMORER, Set.of(  // 盔甲匠
                    "iron_helmet",          // 铁头盔
                    "iron_chestplate",      // 铁胸甲
                    "iron_leggings",        // 铁护腿
                    "iron_boots",           // 铁靴子
                    "bell",                 // 钟
                    "chainmail_helmet",     // 锁链头盔
                    "chainmail_chestplate", // 锁链胸甲
                    "chainmail_leggings",   // 锁链护腿
                    "chainmail_boots",      // 锁链靴子
                    "shield",               // 盾牌
                    "diamond_helmet",       // 钻石头盔（附魔）
                    "diamond_chestplate",   // 钻石胸甲（附魔）
                    "diamond_leggings",     // 钻石护腿（附魔）
                    "diamond_boots"         // 钻石靴子（附魔）
            )),

            Map.entry(VillagerProfession.BUTCHER, Set.of(  // 屠夫
                    "rabbit_stew",          // 兔肉煲
                    "cooked_chicken",       // 熟鸡肉
                    "cooked_porkchop",      // 熟猪排
                    "dried_kelp_block",     // 干海带块
                    "sweet_berries"         // 甜浆果
            )),

            Map.entry(VillagerProfession.CARTOGRAPHER, Set.of(  // 制图师
                    "empty_map",                    // 空地图
                    "village_map",                  // 村庄地图
                    "ocean_monument_map",           // 海洋神殿地图
                    "buried_trial_chambers_map",    // 埋藏的试炼密室地图
                    "item_frame",                   // 物品展示框
                    "banner",                       // 旗帜（颜色随生物群系）
                    "globe_banner_pattern",         // 地球仪旗帜图案
                    "woodland_mansion_map"          // 林地府邸地图
            )),

            Map.entry(VillagerProfession.CLERIC, Set.of(  // 牧师
                    "redstone",             // 红石
                    "lapis_lazuli",         // 青金石
                    "glowstone",            // 萤石
                    "ender_pearl",          // 末影珍珠
                    "experience_bottle"   // 附魔之瓶
            )),

            Map.entry(VillagerProfession.FARMER, Set.of(  // 农民
                    "bread",                // 面包
                    "pumpkin_pie",          // 南瓜派
                    "apple",                // 苹果
                    "cookie",               // 曲奇
                    "suspicious_stew",      // 迷之炖菜
                    "cake",                 // 蛋糕
                    "golden_carrot",        // 金胡萝卜
                    "glistering_melon_slice" // 闪烁的西瓜片
            )),

            Map.entry(VillagerProfession.FISHERMAN, Set.of(  // 渔夫
                    "bucket_of_cod",        // 鳕鱼桶
                    "cooked_cod",           // 熟鳕鱼
                    "campfire",             // 营火
                    "cooked_salmon",        // 熟鲑鱼
                    "fishing_rod",          // 钓鱼竿（附魔）
                    "boat"                  // 船（材质随生物群系）
            )),

            Map.entry(VillagerProfession.FLETCHER, Set.of(  // 制箭师
                    "arrow",                // 箭
                    "flint",                // 燧石
                    "bow",                  // 弓
                    "crossbow",             // 弩
                    "enchanted_bow",        // 附魔弓
                    "enchanted_crossbow",   // 附魔弩
                    "tipped_arrow"          // 药箭
            )),

            Map.entry(VillagerProfession.LEATHERWORKER, Set.of(  // 皮匠
                    "leather_pants",        // 皮革裤子
                    "leather_tunic",        // 皮革外套
                    "leather_cap",          // 皮革帽子
                    "leather_boots",        // 皮革靴子
                    "leather_horse_armor",  // 皮革马铠
                    "saddle"                // 鞍
            )),

            Map.entry(VillagerProfession.LIBRARIAN, Set.of(  // 图书管理员
                    "bookshelf",            // 书架
                    "enchanted_book",       // 附魔书
                    "lantern",              // 灯笼
                    "glass",                // 玻璃
                    "compass",              // 指南针
                    "clock",                // 时钟
                    "name_tag",             // 命名牌（1.21.4）
                    "red_candle",           // 红色蜡烛（26.1）
                    "yellow_candle"         // 黄色蜡烛（26.1）
            )),

            Map.entry(VillagerProfession.MASON, Set.of(  // 石匠
                    "brick",                // 红砖
                    "chiseled_stone_bricks", // 錾制石砖
                    "dripstone_block",      // 滴水石块
                    "polished_andesite",    // 磨制安山岩
                    "polished_diorite",     // 磨制闪长岩
                    "polished_granite",     // 磨制花岗岩
                    "stained_terracotta",   // 陶瓦（任意颜色）
                    "glazed_terracotta",    // 带釉陶瓦（任意颜色）
                    "quartz_pillar",        // 石英柱
                    "quartz_block"          // 石英块
            )),

            Map.entry(VillagerProfession.SHEPHERD, Set.of(  // 牧羊人
                    "shears",               // 剪刀
                    "white_wool",           // 白色羊毛
                    "orange_wool",          // 橙色羊毛
                    "magenta_wool",         // 品红色羊毛
                    "light_blue_wool",      // 淡蓝色羊毛
                    "yellow_wool",          // 黄色羊毛
                    "lime_wool",            // 黄绿色羊毛
                    "pink_wool",            // 粉红色羊毛
                    "gray_wool",            // 灰色羊毛
                    "light_gray_wool",      // 淡灰色羊毛
                    "cyan_wool",            // 青色羊毛
                    "purple_wool",          // 紫色羊毛
                    "blue_wool",            // 蓝色羊毛
                    "brown_wool",           // 棕色羊毛
                    "green_wool",           // 绿色羊毛
                    "red_wool",             // 红色羊毛
                    "black_wool",           // 黑色羊毛
                    "white_carpet",         // 白色地毯
                    "orange_carpet",        // 橙色地毯
                    "magenta_carpet",       // 品红色地毯
                    "light_blue_carpet",    // 淡蓝色地毯
                    "yellow_carpet",        // 黄色地毯
                    "lime_carpet",          // 黄绿色地毯
                    "pink_carpet",          // 粉红色地毯
                    "gray_carpet",          // 灰色地毯
                    "light_gray_carpet",    // 淡灰色地毯
                    "cyan_carpet",          // 青色地毯
                    "purple_carpet",        // 紫色地毯
                    "blue_carpet",          // 蓝色地毯
                    "brown_carpet",         // 棕色地毯
                    "green_carpet",         // 绿色地毯
                    "red_carpet",           // 红色地毯
                    "black_carpet",         // 黑色地毯
                    "white_bed",            // 白色床
                    "orange_bed",           // 橙色床
                    "magenta_bed",          // 品红色床
                    "light_blue_bed",       // 淡蓝色床
                    "yellow_bed",           // 黄色床
                    "lime_bed",             // 黄绿色床
                    "pink_bed",             // 粉红色床
                    "gray_bed",             // 灰色床
                    "light_gray_bed",       // 淡灰色床
                    "cyan_bed",             // 青色床
                    "purple_bed",           // 紫色床
                    "blue_bed",             // 蓝色床
                    "brown_bed",            // 棕色床
                    "green_bed",            // 绿色床
                    "red_bed",              // 红色床
                    "black_bed",            // 黑色床
                    "white_banner",         // 白色旗帜
                    "orange_banner",        // 橙色旗帜
                    "magenta_banner",       // 品红色旗帜
                    "light_blue_banner",    // 淡蓝色旗帜
                    "yellow_banner",        // 黄色旗帜
                    "lime_banner",          // 黄绿色旗帜
                    "pink_banner",          // 粉红色旗帜
                    "gray_banner",          // 灰色旗帜
                    "light_gray_banner",    // 淡灰色旗帜
                    "cyan_banner",          // 青色旗帜
                    "purple_banner",        // 紫色旗帜
                    "blue_banner",          // 蓝色旗帜
                    "brown_banner",         // 棕色旗帜
                    "green_banner",         // 绿色旗帜
                    "red_banner",           // 红色旗帜
                    "black_banner",         // 黑色旗帜
                    "painting"              // 画
            )),

            Map.entry(VillagerProfession.TOOLSMITH, Set.of(  // 工具匠
                    "stone_axe",            // 石斧
                    "stone_shovel",         // 石锹
                    "stone_pickaxe",        // 石镐
                    "stone_hoe",            // 石锄
                    "bell",                 // 钟
                    "iron_axe",             // 铁斧（附魔）
                    "iron_shovel",          // 铁锹（附魔）
                    "iron_pickaxe",         // 铁镐（附魔）
                    "diamond_hoe",          // 钻石锄
                    "diamond_axe",          // 钻石斧（附魔）
                    "diamond_shovel",       // 钻石锹（附魔）
                    "diamond_pickaxe"       // 钻石镐（附魔）
            )),

            Map.entry(VillagerProfession.WEAPONSMITH, Set.of(  // 武器匠
                    "iron_axe",             // 铁斧
                    "iron_sword",           // 铁剑（附魔）
                    "bell",                 // 钟
                    "diamond_axe",          // 钻石斧（附魔）
                    "diamond_sword"         // 钻石剑（附魔）
            ))
    );



    /**
     * Scans the item frames around the player and creates or updates trade entries:
     * <ul>
     *   <li>emerald frame attached to a container -&gt; input 1 chest</li>
     *   <li>book frame attached to a container -&gt; input 2 chest</li>
     *   <li>any other whitelisted item -&gt; output item: the first container scanned
     *       straight below the frame becomes its output chest, the first redstone lamp
     *       scanned straight above it toggles the trade (lit = enabled)</li>
     * </ul>
     * Enchanted books are matched against existing trades by enchantment and level.
     */
    public ScanResult scanItemFrames() {
        int ignored = 0;
        int skipped = 0;
        int noLampFrames = 0;
        int generated = 0;
        int updated = 0;
        String problem = null;
        BlockPos input1Chest = null;
        BlockPos input2Chest = null;
        var outputFrames = new ArrayList<OutputScan>();
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
            var framePos = entity.blockPos();
            var itemName = itemData.name();
            if (itemName.equals(ItemRegistry.EMERALD.name()) || itemName.equals(ItemRegistry.EMERALD_BLOCK.name())) {
                var container = attachedContainer(entity, framePos);
                if (container == null) {
                    ignored++;
                    problem = firstProblem(problem, "Emerald item frame at " + framePos + " is not attached to a container");
                } else if (input1Chest == null) {
                    input1Chest = container;
                }
                continue;
            }
            if (itemName.equals(ItemRegistry.BOOK.name())) {
                var container = attachedContainer(entity, framePos);
                if (container == null) {
                    ignored++;
                } else if (input2Chest == null) {
                    input2Chest = container;
                }
                continue;
            }
            if (VILLAGER_SELLABLE_OUTPUTS.values().stream().noneMatch(items -> items.contains(itemName))) {
                ignored++;
                continue;
            }
            var isEnchantedBook = itemName.equals(ItemRegistry.ENCHANTED_BOOK.name());
            var enchantment = isEnchantedBook ? displayedBookEnchantment(item) : null;
            if (isEnchantedBook && enchantment == null) {
                ignored++;
                problem = firstProblem(problem, "Enchanted book item frame at " + framePos + " has no readable enchantment. Full ItemStack: " + item + "; data components: " + item.getDataComponents() + "; enchantments: " + storedEnchantmentEntries(item));
                continue;
            }
            var outputChest = findContainerBelow(framePos);
            if (outputChest == null) {
                skipped++;
                problem = firstProblem(problem, "No container below item frame at " + framePos + ", skipped " + itemName);
                continue;
            }
            var lampLit = findRedstoneLampAbove(framePos);
            if (lampLit == null) noLampFrames++;
            var professions = isEnchantedBook
                ? Set.of(VillagerProfession.LIBRARIAN)
                : VILLAGER_SELLABLE_OUTPUTS.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(itemName))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (professions.isEmpty()) {
                ignored++;
                continue;
            }
            for (var profession : professions) {
                outputFrames.add(new OutputScan(itemName, profession, enchantment, outputChest, lampLit));
            }
        }
        if (input1Chest == null) {
            return new ScanResult(outputFrames.size(), 0, 0, ignored, skipped, noLampFrames,
                firstProblem(problem, "No emerald item frame attached to a container was found"));
        }
        var previousTrades = new LinkedHashMap<>(PLUGIN_CONFIG.trades);
        var reconciledTrades = new LinkedHashMap<String, VillagerTraderConfig.Trade>();
        for (var output : outputFrames) {
            if (output.enchantment() != null && input2Chest == null) {
                skipped++;
                problem = firstProblem(problem, "Enchanted book item frame found, but no book item frame attached to a container was found");
                continue;
            }
            var input2 = output.enchantment() == null ? null : input2Chest;
            var existingEntry = previousTrades.entrySet().stream()
                .filter(entry -> entry.getValue().villagerProfession == output.profession())
                .filter(entry -> sameScannedOutput(entry.getValue(), output))
                .findFirst()
                .orElse(null);
            if (existingEntry == null) {
                var legacyCandidates = previousTrades.entrySet().stream()
                    .filter(entry -> sameScannedOutput(entry.getValue(), output))
                    .toList();
                if (legacyCandidates.size() == 1) existingEntry = legacyCandidates.getFirst();
            }
            var trade = existingEntry == null ? new VillagerTraderConfig.Trade() : existingEntry.getValue();
            applyScannedTrade(trade, output, input1Chest, input2);
            var id = uniqueScannedTradeId(output, reconciledTrades);
            reconciledTrades.put(id, trade);
            if (existingEntry == null) generated++;
            else {
                updated++;
                previousTrades.remove(existingEntry.getKey());
            }
        }
        // The scan owns the full trade list. Entries not represented by a current frame are stale.
        PLUGIN_CONFIG.trades.clear();
        PLUGIN_CONFIG.trades.putAll(reconciledTrades);
        if (generated > 0 || updated > 0) {
            tradeIterator.refresh();
        }
        return new ScanResult(outputFrames.size(), generated, updated, ignored, skipped, noLampFrames, problem);
    }

    private void applyScannedTrade(VillagerTraderConfig.Trade trade, OutputScan output, BlockPos input1Chest, @Nullable BlockPos input2Chest) {
        var enchantment = output.enchantment();
        trade.villagerProfession = output.profession();
        trade.outputItem = output.itemName();
        trade.outputChest = output.containerPos();
        trade.inputItem1 = ItemRegistry.EMERALD.name();
        trade.inputItem1Chest = input1Chest;
        if (enchantment != null) {
            trade.inputItem2 = ItemRegistry.BOOK.name();
            if (input2Chest != null) {
                trade.inputItem2Chest = input2Chest;
            }
            trade.outputItemEnchantments.clear();
            trade.outputItemEnchantments.put(enchantment.key(), enchantment.level());
            trade.maxInput1PerTrade = PLUGIN_CONFIG.maxEmeraldCostForEnchantedBook;
        }
        if (output.lampLit() != null) {
            trade.enabled = output.lampLit();
        }
    }

    private boolean sameScannedOutput(VillagerTraderConfig.Trade trade, OutputScan output) {
        if (!trade.outputItem.equals(output.itemName())) return false;
        var enchantment = output.enchantment();
        if (enchantment == null) return trade.outputItemEnchantments.isEmpty();
        return trade.outputItemEnchantments.size() == 1
            && trade.outputItemEnchantments.getInt(enchantment.key()) == enchantment.level();
    }

    private String uniqueScannedTradeId(OutputScan output, Map<String, VillagerTraderConfig.Trade> currentScan) {
        var enchantment = output.enchantment();
        String lowerCase = output.profession().name().toLowerCase(Locale.ROOT);
        String id = output.itemName() + "_" + lowerCase;
        if (enchantment != null) {
            var enchantmentName = enchantment.name().startsWith("lore:") ? enchantment.name().substring("lore:".length()) : enchantment.name();
            id = enchantmentName + "_" + enchantment.level();
            for (var trade : currentScan.values()) {
                if (trade.outputItem.equals(output.itemName()) && trade.outputItemEnchantments.containsKey(enchantment.key())) {
                    id = output.itemName() + "_" + lowerCase + "_" + enchantmentName + "_" + enchantment.level();
                    break;
                }
            }
        }
        var base = id;
        int suffix = 1;
        while (currentScan.containsKey(id)) {
            id = base + "_" + (++suffix);
        }
        return id;
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

    private @Nullable BlockPos findContainerBelow(BlockPos framePos) {
        int range = PLUGIN_CONFIG.itemFrameScanVerticalRangeBlocks;
        for (int i = 1; i <= range; i++) {
            var pos = framePos.relative(Direction.DOWN, i);
            if (isStorageContainer(pos)) return pos;
        }
        return null;
    }

    private @Nullable Boolean findRedstoneLampAbove(BlockPos framePos) {
        int range = PLUGIN_CONFIG.itemFrameScanVerticalRangeBlocks;
        for (int i = 1; i <= range; i++) {
            var pos = framePos.relative(Direction.UP, i);
            if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return null;
            if (World.getBlock(pos) != BlockRegistry.REDSTONE_LAMP) continue;
            return World.getBlockState(pos).getProperty(BlockStateProperties.LIT);
        }
        return null;
    }

    private boolean isInItemFrameScanArea(Entity entity) {
        int horizontalRange = PLUGIN_CONFIG.itemFrameScanHorizontalRangeBlocks;
        int verticalRange = PLUGIN_CONFIG.itemFrameScanVerticalRangeBlocks;
        var player = CACHE.getPlayerCache().getThePlayer();
        return Math.abs(entity.getX() - player.getX()) <= horizontalRange
            && Math.abs(entity.getZ() - player.getZ()) <= horizontalRange
            && Math.abs(entity.getY() - player.getY()) <= verticalRange;
    }

    private @Nullable DisplayedEnchantment displayedBookEnchantment(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) return null;
        var stored = components.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var direct = components.get(DataComponentTypes.ENCHANTMENTS);
        var enchantmentComponent = stored != null && !stored.getEnchantments().isEmpty() ? stored : direct;
        if (enchantmentComponent == null || enchantmentComponent.getEnchantments().isEmpty()) {
            var loreEnchant = loreEnchantment(components.toString());
            return loreEnchant == null ? null : new DisplayedEnchantment("lore:" + loreEnchant.name(), loreEnchant.level(), -1);
        }
        var entries = enchantmentComponent.getEnchantments().int2IntEntrySet();
        // Cross-version servers can deliver extra/unmapped entries alongside a newer enchantment.
        // Prefer an entry the 1.21.4 client does not know, which is the likely custom/new enchantment.
        var entry = entries.stream()
            .filter(candidate -> EnchantmentRegistry.REGISTRY.get(candidate.getIntKey()) == null)
            .findFirst()
            .orElseGet(() -> entries.size() == 1 ? entries.iterator().next() : null);
        if (entry == null) return null;
        var enchantment = EnchantmentRegistry.REGISTRY.get(entry.getIntKey());
        var name = enchantment == null ? "registry:" + entry.getIntKey() : enchantment.name();
        return new DisplayedEnchantment(name, entry.getIntValue(), entry.getIntKey());
    }

    private @Nullable LoreEnchantment loreEnchantment(String componentDump) {
        var nameMatcher = java.util.regex.Pattern.compile("key=enchantment\\.minecraft\\.([a-z0-9_.-]+)").matcher(componentDump);
        var customNameMatcher = java.util.regex.Pattern.compile("key=([A-Z][a-zA-Z0-9_]+)").matcher(componentDump);
        var levelMatcher = java.util.regex.Pattern.compile("key=enchantment\\.level\\.(\\d+)").matcher(componentDump);
        String name = nameMatcher.find() ? nameMatcher.group(1) : customNameMatcher.find() ? customNameMatcher.group(1).toLowerCase(Locale.ROOT) : null;
        if (name == null || !levelMatcher.find()) return null;
        return new LoreEnchantment(name, Integer.parseInt(levelMatcher.group(1)));
    }

    private String storedEnchantmentEntries(ItemStack item) {
        var components = item.getDataComponents();
        if (components == null) return "<no data components>";
        var stored = components.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var direct = components.get(DataComponentTypes.ENCHANTMENTS);
        var storedEntries = stored == null ? "<missing>" : stored.getEnchantments().int2IntEntrySet().stream()
            .map(entry -> entry.getIntKey() + "=" + entry.getIntValue())
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        var directEntries = direct == null ? "<missing>" : direct.getEnchantments().int2IntEntrySet().stream()
            .map(entry -> entry.getIntKey() + "=" + entry.getIntValue())
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        return "stored_enchantments=" + storedEntries + ", enchantments=" + directEntries;
    }

    private static String firstProblem(String current, String message) {
        return current == null ? message : current;
    }

    private record DisplayedEnchantment(String name, int level, int registryId) {
        private String key() { return name.startsWith("registry:") ? name : name; }
    }
    private record LoreEnchantment(String name, int level) {}
    private record OutputScan(String itemName, VillagerProfession profession, @Nullable DisplayedEnchantment enchantment, BlockPos containerPos, @Nullable Boolean lampLit) {}
    private record QueuedTrade(int offerIndex, VillagerTraderConfig.Trade config) {}
    public record ScanResult(int outputFrames, int generated, int updated, int ignored, int skipped, int noLampFrames, @Nullable String problem) {}

    private void stop() {
        PLUGIN_CONFIG.enabled = false;
        syncEnabledFromConfig();
        saveConfigAsync();
    }

    private void setState(State newState) {
        debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    private void finishPurchase() {
        selectTradeQueue.clear();
        var trade = tradeIterator.current();
        purchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .priority(getPriority())
            .actions(List.of(new CloseContainer(offersPacket.getContainerId())))
            .build());
        preTradeInput1Count = countItem(trade.getInputItem1().id());
        preTradeInput2Count = trade.has2InputTrade() ? countItem(trade.getInputItem2().id()) : 0;
        preTradeOutputCount = countItem(trade.getOutputItem().id());
        setState(State.TRADING_AWAIT_PURCHASE);
    }

    private void sneakForContainerOpen() {
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .priority(getPriority())
            .input(Input.builder().sneaking(true).build())
            .build());
    }

    private Optional<EntityLiving> nextVillager() {
        var entities = CACHE.getEntityCache().getEntities().values();
        var enabledProfessions = EnumSet.noneOf(VillagerProfession.class);
        for (var enabledTrade : tradeIterator.backingArray) {
            enabledProfessions.add(enabledTrade.villagerProfession);
        }
        if (enabledProfessions.isEmpty()) return Optional.empty();
        if (activeVillagerUuid != null) {
            var active = entities.stream()
                .filter(e -> activeVillagerUuid.equals(e.getUuid()) && e.getEntityType() == EntityType.VILLAGER)
                .map(e -> (EntityLiving) e)
                .filter(e -> enabledProfessions.contains(getVillagerProfession(e)) && hasAdjacentWorkstation(e))
                .findFirst();
            if (active.isPresent()) return active;
            activeVillagerUuid = null;
        }
        return entities.stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .filter(e -> e.getUuid() != null)
            .filter(e -> !interactedVillagersCache.asMap().containsKey(e.getUuid()))
            .map(e -> (EntityLiving) e)
            .filter(e -> enabledProfessions.contains(getVillagerProfession(e)))
            .filter(this::hasAdjacentWorkstation)
            .min(Comparator.comparingDouble(e -> e.distanceSqTo(CACHE.getPlayerCache().getThePlayer())));
    }

    private boolean hasAdjacentWorkstation(EntityLiving villager) {
        String workstation = switch (getVillagerProfession(villager)) {
            case ARMORER -> "blast_furnace";
            case BUTCHER -> "smoker";
            case CARTOGRAPHER -> "cartography_table";
            case CLERIC -> "brewing_stand";
            case FARMER -> "composter";
            case FISHERMAN -> "barrel";
            case FLETCHER -> "fletching_table";
            case LEATHERWORKER -> "cauldron";
            case LIBRARIAN -> "lectern";
            case MASON -> "stonecutter";
            case SHEPHERD -> "loom";
            case TOOLSMITH -> "smithing_table";
            case WEAPONSMITH -> "grindstone";
            default -> null;
        };
        if (workstation == null) return false;
        var pos = villager.blockPos();
        return List.of(
            new BlockPos(pos.x() + 1, pos.y(), pos.z()), new BlockPos(pos.x() - 1, pos.y(), pos.z()),
            new BlockPos(pos.x(), pos.y(), pos.z() + 1), new BlockPos(pos.x(), pos.y(), pos.z() - 1)
        ).stream().anyMatch(blockPos -> World.isChunkLoadedBlockPos(blockPos.x(), blockPos.z())
            && World.getBlock(blockPos) != null
            && World.getBlock(blockPos).name().equals(workstation));
    }

    private VillagerProfession getVillagerProfession(EntityLiving villager) {
        var data = villager.getMetadataValue(18, MetadataTypes.VILLAGER_DATA, VillagerData.class);
        if (data == null) {
            return VillagerProfession.NONE;
        }
        return VillagerProfession.from(data.getProfession());
    }

    private int countOutputItem(VillagerTraderConfig.Trade trade) {
        int id = ItemRegistry.REGISTRY.get(trade.outputItem).id();
        int count = 0;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            var item = inv.get(i);
            if (item == Container.EMPTY_STACK) continue;
            if (item.getId() != id) continue;
            if (!trade.outputItemEnchantments.isEmpty() && !enchantmentFilter(trade, item)) continue;
            count += item.getAmount();
        }
        return count;
    }

    private Optional<VillagerTraderConfig.Trade> findStorableOutputTrade() {
        for (var trade : tradeIterator.backingArray) {
            if (countOutputItem(trade) > trade.outputItemStoreCountThreshold) {
                return Optional.of(trade);
            }
        }
        for (var trade : tradeIterator.backingArray) {
            if (countOutputItem(trade) == 0) continue;
            int emptySlotsThreshold = trade.outputItemEnchantments.isEmpty() ? 1 : 12;
            if (countInvEmptySlots() < emptySlotsThreshold) {
                return Optional.of(trade);
            }
        }
        return Optional.empty();
    }

    private Optional<VillagerTraderConfig.Trade> findAnyStorableOutputTrade(VillagerTraderConfig.Trade exclude) {
        for (var trade : tradeIterator.backingArray) {
            if (trade == exclude) continue;
            if (countOutputItem(trade) > 0) {
                return Optional.of(trade);
            }
        }
        return Optional.empty();
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

    private boolean canShiftClickPurchaseV2(VillagerTrade villagerTrade, VillagerTraderConfig.Trade configTrade) {
        int count = 0;
        for (var offer : offersPacket.getTrades()) {
            if (villagerTrade.getOutput().getId() != offer.getOutput().getId()) continue;
            if (villagerTrade.getFirstInput().getId() != offer.getFirstInput().getId()) continue;
            boolean offerHasInput2 = offer.getSecondInput() != Container.EMPTY_STACK;
            boolean villagerTradeHasInput2 = villagerTrade.getSecondInput() != Container.EMPTY_STACK;
            if (offerHasInput2 && villagerTradeHasInput2) {
                if (villagerTrade.getSecondInput().getId() == offer.getSecondInput().getId()) {
                    if (!configTrade.outputItemEnchantments.isEmpty() && sameEnchantedBook(villagerTrade.getOutput(), offer.getOutput())) continue;
                    count++;
                }
            } else if (!offerHasInput2 && !villagerTradeHasInput2) {
                if (!configTrade.outputItemEnchantments.isEmpty() && sameEnchantedBook(villagerTrade.getOutput(), offer.getOutput())) continue;
                count++;
            }
        }
        return count <= 1;
    }

    private boolean sameEnchantedBook(@NonNull ItemStack a, @NonNull ItemStack b) {
        var aData = a.getDataComponents();
        var bData = b.getDataComponents();
        var aEA = aData != null ? aData.get(DataComponentTypes.STORED_ENCHANTMENTS) : null;
        var bEA = bData != null ? bData.get(DataComponentTypes.STORED_ENCHANTMENTS) : null;
        if (aEA == null) return bEA != null;
        if (bEA == null) return true;
        return !aEA.getEnchantments().equals(bEA.getEnchantments());
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
        TRADING_SELECT_TRADE,
        TRADING_AWAIT_RESULT_CONFIRM,
        TRADING_SHIFT_RESULT,
        TRADING_AWAIT_SHIFT_RESULT,
        TRADING_AWAIT_PURCHASE,
        // todo: states to compact stackable items in inventory with shift clicks
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

        public void select(VillagerTraderConfig.Trade trade) {
            for (int i = 0; i < backingArray.length; i++) {
                if (backingArray[i] == trade) {
                    index = i;
                    return;
                }
            }
        }

        public void reset() {
            index = 0;
            refresh();
        }
    }
}
