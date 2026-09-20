package dev.zenith.trader.module;

import com.github.rfresh2.EventConsumer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.zenith.Proxy;
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
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture emeraldBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture interactWithVillagerFuture = PathingRequestFuture.rejected;
    private ClientboundMerchantOffersPacket offersPacket = null;
    private RequestFuture purchaseFuture = RequestFuture.rejected;
    private final IntList selectTradeQueue = new IntArrayList();
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
        preTradeInput2Count = 0;;
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
            if (offer.getOutput().getId() != ItemRegistry.REGISTRY.get(config.outputItem).id()) continue;
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
        if (Proxy.getInstance().isInQueue() || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
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
                interactedVillagersCache.invalidateAll();
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
                var output = ItemRegistry.REGISTRY.get(trade.outputItem);
                int outputCount = countItem(output.id());
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
                var nextVillagerOptional = nextVillager(trade);
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
                boolean hasV2Trade = false;
                for (int i = 0; i < trades.length; i++) {
                    var villagerTrade = trades[i];
                    if (villagerTrade.isTradeDisabled()) continue;
                    if (villagerTrade.getOutput() == null) continue;
                    if (trade == null) {
                        var matchingConfig = findMatchingConfig(villagerTrade);
                        if (matchingConfig.isEmpty()) continue;
                        tradeIterator.select(matchingConfig.get());
                        trade = tradeIterator.current();
                    } else {
                        if (villagerTrade.getOutput().getId() != ItemRegistry.REGISTRY.get(trade.outputItem).id()) continue;
                        if (villagerTrade.getFirstInput().getId() != ItemRegistry.REGISTRY.get(trade.inputItem1).id()) continue;
                        if (trade.has2InputTrade()) {
                            if (villagerTrade.getSecondInput() == null) continue;
                            if (villagerTrade.getSecondInput().getId() != ItemRegistry.REGISTRY.get(trade.inputItem2).id()) continue;
                        } else {
                            if (villagerTrade.getSecondInput() != null) continue;
                        }
                        if (!trade.outputItemEnchantments.isEmpty()) {
                            if (!enchantmentFilter(trade, villagerTrade.getOutput())) {
                                continue;
                            }
                        }
                    }

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

                    if (canShiftClickPurchaseV2(villagerTrade, trade)) {
                        hasV2Trade = true;
                        if (maxTradeCount > 0) {
                            info("shift clicking 1 time (single trade), trade count: {} inputCost1: {}", maxTradeCount, input1Cost);
                            selectTradeQueue.add(i);
                        }
                    } else if (canShiftClickPurchase(villagerTrade)) {
                        if (hasV2Trade) continue;
                        int outputsStackSize = ItemRegistry.REGISTRY.get(villagerTrade.getOutput().getId()).stackSize();
                        int maxTradesPerOutputStack = outputsStackSize / villagerTrade.getOutput().getAmount();
                        int maxTradesPerShiftClick = Math.min(maxTradesPerInputStack, maxTradesPerOutputStack);
                        int tradeCount = 0;
                        for (int j = 0; j < maxTradeCount; j+= maxTradesPerShiftClick) {
                            tradeCount++;
                            legacyActions.add(new SelectTrade(offersPacket.getContainerId(), i));
                            legacyActions.add(new ShiftClick(offersPacket.getContainerId(), 2, ShiftClickItemAction.LEFT_CLICK));
                        }
                        debug("shift clicking {} times, trade count: {} trades per shift click: {}", tradeCount, maxTradeCount, maxTradesPerShiftClick);
                    } else {
                        if (hasV2Trade) continue;
                        /**
                         * If there are multiple enchanted books available to trade
                         * and one's price is lower than our current
                         * a shift click will purchase the other books
                         *
                         * so we have to do left clicks only to buy one at a time, moving it to empty slots
                         *
                         * we could also close the inventory to have an empty slot found automatically
                         * but it doesn't play well with current logic for interacted villager and trade completion tracking
                         */
                        var emptySlots = findEmptySlots();
                        if (emptySlots.isEmpty()) {
                            info("Can't trade because we don't have any empty inventory slots");
                        }
                        maxTradeCount = Math.min(maxTradeCount, emptySlots.size());
                        for (int j = 0; j < maxTradeCount; j++) {
                            int outputSlot = emptySlots.removeFirst();
                            legacyActions.add(new SelectTrade(offersPacket.getContainerId(), i));
                            legacyActions.add(new ClickItem(offersPacket.getContainerId(), 2, ClickItemAction.LEFT_CLICK));
                            legacyActions.add(new ClickItem(offersPacket.getContainerId(), outputSlot, ClickItemAction.LEFT_CLICK));
                        }
                        debug("click trading {} times", maxTradeCount);
                    }
                }
                if (trade == null) {
                    trade = tradeIterator.current();
                }
                if (!selectTradeQueue.isEmpty()) {
                    selectTradePos = 0;
                    setState(State.TRADING_SELECT_TRADE);
                    return;
                }
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
                var tradeIndex = selectTradeQueue.getInt(selectTradePos);
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
                        error("Result slot still empty after selecting offer {}, skipping purchase", selectTradeQueue.getInt(selectTradePos));
                        finishPurchase();
                    }
                    return;
                }
                if (resultItem.getId() != trade.getOutputItem().id()) {
                    if (waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                        error("Result slot {} != expected {} after selecting offer {}, skipping purchase", resultItem.getId(), trade.getOutputItem().id(), selectTradeQueue.getInt(selectTradePos));
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
                        var input2Sold = preTradeInput2Count - countItem(trade.getInputItem2().id());;
                        info("Sold {} {}", input2Sold, trade.inputItem2);
                        input2SellCount += input2Sold;
                    }
                    var outputBought = countItem(trade.getOutputItem().id()) - preTradeOutputCount;
                    info("Bought {} {}{}", outputBought, trade.getOutputItem().name(), enchantmentSuffix(trade));
                    outputBuyCount += outputBought;
                    if (countItem(trade.getOutputItem().id()) > trade.outputItemStoreCountThreshold) {
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
                    var actions = Lists.newArrayList(
                        InventoryActionMacros.deposit(
                            openContainer.getContainerId(),
                            i -> outputItem.id() == i.getId()
                                && (trade.outputItemEnchantments.isEmpty() || enchantmentFilter(trade, i))
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
                var nextTrade = tradeIterator.next();
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
        if (storedEnchantments == null && enchantments == null)
            return false;
        var itemEnchants = (storedEnchantments != null ? storedEnchantments : enchantments).getEnchantments();

        for (var bookEnchantEntry : trade.outputItemEnchantments.object2IntEntrySet()) {
            String desiredEnchantKey = bookEnchantEntry.getKey();
            var desiredEnchant = EnchantmentRegistry.REGISTRY.get(desiredEnchantKey);
            if (desiredEnchant == null) {
                error("Enchanted book enchantment id: {} not found in registry", desiredEnchantKey);
                continue;
            }
            var enchantId = desiredEnchant.id();
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
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(entry.getKey()).append(' ').append(entry.getIntValue());
        }
        return " [" + sb + ']';
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

    private Optional<EntityLiving> nextVillager(final VillagerTraderConfig.Trade trade) {
        return CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .filter(e -> !interactedVillagersCache.asMap().containsKey(e.getUuid()))
            .map(e -> (EntityLiving) e)
            .filter(e -> trade.villagerProfession == getVillagerProfession(e))
            .min(Comparator.comparingDouble(e -> e.distanceSqTo(CACHE.getPlayerCache().getThePlayer())));
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
                    if (!configTrade.outputItemEnchantments.isEmpty() && !sameEnchantedBook(villagerTrade.getOutput(), offer.getOutput())) continue;
                    count++;
                }
            } else if (!offerHasInput2 && !villagerTradeHasInput2) {
                if (!configTrade.outputItemEnchantments.isEmpty() && !sameEnchantedBook(villagerTrade.getOutput(), offer.getOutput())) continue;
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
        if (aEA == null) return bEA == null;
        if (bEA == null) return false;
        return aEA.getEnchantments().equals(bEA.getEnchantments());
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
        if (count > 1) return false;
        return true;
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
