package me.paulohenrique.echocraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class EchoMemoryMenuHolder
        implements InventoryHolder {

    private final Inventory inventory;

    private final Map<Integer, Long> snapshotIds =
            new HashMap<>();

    public EchoMemoryMenuHolder(
            EchoCraftPlugin plugin
    ) {
        this.inventory =
                plugin.getServer().createInventory(
                        this,
                        45,
                        Component.text(
                                        "Arquivo Temporal",
                                        NamedTextColor.DARK_AQUA
                                )
                                .decorate(
                                        TextDecoration.BOLD
                                )
                );
    }

    public void bindSnapshot(
            int slot,
            long snapshotId
    ) {
        snapshotIds.put(slot, snapshotId);
    }

    public Long getSnapshotId(int slot) {
        return snapshotIds.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}