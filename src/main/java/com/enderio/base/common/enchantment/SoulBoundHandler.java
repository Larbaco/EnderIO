package com.enderio.base.common.enchantment;

import com.enderio.base.common.init.EIOEnchantments;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

//TODO testing against graves and other mods
@EventBusSubscriber
public class SoulBoundHandler {

    private static final String SYNC_START_EVENT_CLASS = "net.pawjwp.sync.api.event.PlayerSyncEvents$StartSyncing";
    private static final Map<UUID, List<ItemStack>> PENDING_RESTORE = new HashMap<>();
    private static boolean syncStartHandlerRegistered = false;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void deathHandler(LivingDropsEvent event) {
        if (event.getEntity() == null || event.getEntity() instanceof FakePlayer || event.isCanceled()) {
            return;
        }
        if (event.getEntity().level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        tryRegisterSyncStartHandler();

        List<ItemStack> soulItems = new ArrayList<>();
        event.getDrops().removeIf(drop -> {
            ItemStack item = drop.getItem();
            if (isSoulBound(item)) {
                soulItems.add(item.copy());
                return true;
            }
            return false;
        });

        if (!soulItems.isEmpty()) {
            PENDING_RESTORE.computeIfAbsent(player.getUUID(), uuid -> new ArrayList<>()).addAll(soulItems);
        }
    }

    @SubscribeEvent
    public static void reviveHandler(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }
        if (event.getEntity().level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        //TODO More detailed and better item recovery.
        List<ItemStack> pending = PENDING_RESTORE.remove(event.getEntity().getUUID());
        if (pending != null) {
            for (ItemStack stack : pending) {
                if (!event.getEntity().addItem(stack)) {
                    event.getEntity().drop(stack, false, false);
                }
            }
        }
    }

    private static void syncStartHandler(Event event) {
        Player player = ((PlayerEvent) event).getEntity();
        if (player.level().isClientSide) {
            return;
        }

        List<ItemStack> pending = PENDING_RESTORE.remove(player.getUUID());
        if (pending == null) {
            return;
        }

        try {
            Object targetState = event.getClass().getMethod("getTargetState").invoke(event);
            if (targetState != null && targetState.getClass().getMethod("getInventory").invoke(targetState) instanceof Container container) {
                nextStack:
                for (ItemStack stack : pending) {
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        if (container.getItem(slot).isEmpty()) {
                            container.setItem(slot, stack);
                            continue nextStack;
                        }
                    }
                    player.drop(stack, false, false);
                }
                container.setChanged();
                return;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        for (ItemStack stack : pending) {
            player.drop(stack, false, false);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tryRegisterSyncStartHandler() {
        if (syncStartHandlerRegistered) {
            return;
        }
        syncStartHandlerRegistered = true;

        try {
            Class<? extends Event> syncStartEvent = Class.forName(SYNC_START_EVENT_CLASS).asSubclass(Event.class);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) syncStartEvent,
                (java.util.function.Consumer<Event>) SoulBoundHandler::syncStartHandler);
        } catch (ClassNotFoundException ignored) {
        }
    }

    public static boolean isSoulBound(ItemStack item) {
        return item.getEnchantmentLevel(EIOEnchantments.SOULBOUND.get()) > 0;
    }

}
