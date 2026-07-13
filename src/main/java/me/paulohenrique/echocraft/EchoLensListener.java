package me.paulohenrique.echocraft;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class EchoLensListener implements Listener {

    private final EchoCraftPlugin plugin;

    public EchoLensListener(EchoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(
            priority = EventPriority.NORMAL,
            ignoreCancelled = false
    )
    public void onEchoLensUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();

        if (!plugin.isEchoLens(item)) {
            return;
        }

        event.setCancelled(true);

        plugin.activateEchoLens(event.getPlayer());
    }
}