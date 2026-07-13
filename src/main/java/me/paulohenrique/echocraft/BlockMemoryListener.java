package me.paulohenrique.echocraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class BlockMemoryListener implements Listener {

    private final EchoCraftPlugin plugin;

    public BlockMemoryListener(EchoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent) {
            return;
        }

        Block placedBlock =
                event.getBlockPlaced();

        plugin.trackPlacedBlock(placedBlock);

        plugin.recordPlayerAction(
                event.getPlayer(),
                EchoCraftPlugin.PlayerActionType.PLACE_BLOCK,
                placedBlock
        );

        event.getPlayer().sendActionBar(
                Component.text(
                        "Bloco adicionado à linha temporal",
                        NamedTextColor.DARK_AQUA
                )
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        for (BlockState state : event.getReplacedBlockStates()) {
            Block placedBlock =
                    state.getLocation().getBlock();

            plugin.trackPlacedBlock(placedBlock);

            plugin.recordPlayerAction(
                    event.getPlayer(),
                    EchoCraftPlugin.PlayerActionType.PLACE_BLOCK,
                    placedBlock
            );
        }

        event.getPlayer().sendActionBar(
                Component.text(
                        "Blocos adicionados à linha temporal",
                        NamedTextColor.DARK_AQUA
                )
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBlockBreak(BlockBreakEvent event) {
        /*
         * Registra a ação antes de verificar se o bloco fazia parte
         * de uma construção rastreada. Assim o replay também lembra
         * que o jogador quebrou blocos comuns perto da memória.
         */
        plugin.recordPlayerAction(
                event.getPlayer(),
                EchoCraftPlugin.PlayerActionType.BREAK_BLOCK,
                event.getBlock()
        );

        boolean captured =
                plugin.captureManualBreak(
                        event.getBlock()
                );

        if (!captured) {
            return;
        }

        event.getPlayer().sendActionBar(
                Component.text(
                        "Fotografia temporal capturada",
                        NamedTextColor.AQUA
                )
        );

        plugin.getLogger().info(
                event.getPlayer().getName()
                        + " alterou uma construção em "
                        + event.getBlock().getLocation()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onEntityExplode(
            EntityExplodeEvent event
    ) {
        int structureSize =
                plugin.captureExplosion(
                        event.blockList(),
                        event.getLocation(),
                        event.getEntityType().name()
                );

        if (structureSize <= 0) {
            return;
        }

        plugin.getLogger().info(
                "Explosao de "
                        + event.getEntityType().name()
                        + " fotografou uma estrutura com "
                        + structureSize
                        + " blocos."
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBlockExplode(
            BlockExplodeEvent event
    ) {
        int structureSize =
                plugin.captureExplosion(
                        event.blockList(),
                        event.getBlock().getLocation(),
                        "BLOCK_EXPLOSION"
                );

        if (structureSize <= 0) {
            return;
        }

        plugin.getLogger().info(
                "Explosao de bloco fotografou "
                        + "uma estrutura com "
                        + structureSize
                        + " blocos."
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerInteract(
            PlayerInteractEvent event
    ) {
        if (event.getHand()
                != EquipmentSlot.HAND) {
            return;
        }

        if (event.getAction()
                != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock =
                event.getClickedBlock();

        if (clickedBlock == null) {
            return;
        }

        ItemStack item =
                event.getItem();

        if (item == null) {
            return;
        }

        Material material =
                item.getType();

        if (material != Material.FLINT_AND_STEEL
                && material != Material.FIRE_CHARGE) {
            return;
        }

        plugin.recordPlayerAction(
                event.getPlayer(),
                EchoCraftPlugin.PlayerActionType.IGNITE_BLOCK,
                clickedBlock
        );
    }
}
