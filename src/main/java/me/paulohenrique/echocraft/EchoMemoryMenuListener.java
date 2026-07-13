package me.paulohenrique.echocraft;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class EchoMemoryMenuListener
        implements Listener {

    private final EchoCraftPlugin plugin;

    public EchoMemoryMenuListener(
            EchoCraftPlugin plugin
    ) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        Inventory topInventory =
                event.getView().getTopInventory();

        if (!(topInventory.getHolder(false)
                instanceof EchoMemoryMenuHolder holder)) {

            return;
        }

        // Não deixa retirar ou colocar itens no menu.
        event.setCancelled(true);

        if (!(event.getWhoClicked()
                instanceof Player player)) {

            return;
        }

        // Ignora cliques no inventário do jogador.
        if (event.getClickedInventory()
                != topInventory) {

            return;
        }

        Long snapshotId =
                holder.getSnapshotId(
                        event.getRawSlot()
                );

        if (snapshotId == null) {
            return;
        }

        /*
         * Fechar inventários diretamente durante
         * InventoryClickEvent pode gerar inconsistências.
         * Executamos no próximo tick.
         */
        plugin.getServer()
                .getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            player.closeInventory();

                            plugin.revealSnapshotById(
                                    player,
                                    snapshotId
                            );
                        }
                );
    }

    @EventHandler
    public void onInventoryDrag(
            InventoryDragEvent event
    ) {
        Inventory topInventory =
                event.getView().getTopInventory();

        if (topInventory.getHolder(false)
                instanceof EchoMemoryMenuHolder) {

            event.setCancelled(true);
        }
    }
}