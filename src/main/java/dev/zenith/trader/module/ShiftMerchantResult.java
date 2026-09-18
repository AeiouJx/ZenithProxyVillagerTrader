package dev.zenith.trader.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CLIENT_LOG;

/**
 * Shift-clicks the merchant result slot (slot 2), but only when the cached result
 * is the expected item. Guards against purchasing the default/previous selection
 * (e.g. a librarian's bookshelf/lantern offer) on race-conditions where the
 * ServerboundSelectTradePacket has not yet been reflected in the container cache.
 * Returns a null packet to skip the action when the result slot is empty or wrong.
 */
public class ShiftMerchantResult implements InventoryAction {
    private final int containerId;
    private final int expectedItemId;
    private static final ContainerActionType containerActionType = ContainerActionType.SHIFT_CLICK_ITEM;

    public ShiftMerchantResult(int containerId, int expectedItemId) {
        this.containerId = containerId;
        this.expectedItemId = expectedItemId;
    }

    @Override
    public MinecraftPacket packet() {
        var container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        var itemStack = container.getItemStack(2);
        if (isStackEmpty(itemStack)) {
            CLIENT_LOG.debug("ShiftMerchantResult: result slot empty, skipping (select not confirmed)");
            return null;
        }
        if (itemStack.getId() != expectedItemId) {
            CLIENT_LOG.debug("ShiftMerchantResult: result slot {} != expected {}, skipping", itemStack.getId(), expectedItemId);
            return null;
        }
        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectArrayMap<>();
        return new ServerboundContainerClickPacket(
            containerId,
            CACHE.getPlayerCache().getActionId().get() + 1,
            2,
            containerActionType,
            ShiftClickItemAction.LEFT_CLICK,
            Container.EMPTY_STACK,
            changedSlots
        );
    }

    @Override
    public int containerId() {
        return containerId;
    }
}