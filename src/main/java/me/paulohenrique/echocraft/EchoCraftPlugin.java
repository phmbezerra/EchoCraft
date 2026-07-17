package me.paulohenrique.echocraft;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.EulerAngle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import java.sql.SQLException;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class EchoCraftPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;

    private NamespacedKey echoLensKey;

    private EchoPanelClient echoPanelClient;

    private static final double SNAPSHOT_SEARCH_RADIUS = 40.0;

    private static final long EXPLOSION_CHAIN_WINDOW_MILLIS =
            8_000L;

    private static final double EXPLOSION_CHAIN_RADIUS =
            24.0;

    private static final long SNAPSHOT_LIFETIME_MILLIS =
            365L * 24L * 60L * 60L * 1000L;

    private static final long DISPLAY_DURATION_TICKS =
            20L * 15L;

    private static final int MAX_STRUCTURE_BLOCKS = 2_000;
    private static final int MAX_SNAPSHOTS = 200;

    private static final int[][] DIRECTIONS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

    private static final long PLAYER_TRACE_INTERVAL_TICKS = 10L;

    private static final long PLAYER_TRACE_MEMORY_MILLIS =
            5L * 60L * 1000L;

    private static final long TRAIL_BEFORE_SNAPSHOT_MILLIS =
            30_000L;

    private static final long TRAIL_AFTER_SNAPSHOT_MILLIS =
            5_000L;

    private static final double TRAIL_SEARCH_RADIUS =
            35.0;

    private static final int MAX_TRAIL_POINTS =
            90;

    private static final long PLAYER_ACTION_MEMORY_MILLIS =
            5L * 60L * 1000L;

    private static final int MAX_REPLAY_ACTIONS =
            40;

    private static final long ACTION_LABEL_DURATION_TICKS =
            22L;

    private static final int TRACE_PARTICLE_STEP_THRESHOLD =
            45;

    private static final long CINEMA_REVEAL_DELAY_TICKS =
            45L;

    private static final long CINEMA_EFFECT_DURATION_TICKS =
            80L;

    private static final long DELETE_CONFIRMATION_WINDOW_MILLIS =
            30_000L;

    private static final long RESTORE_CONFIRMATION_WINDOW_MILLIS =
            30_000L;

    private static final int RESTORE_BLOCKS_PER_TICK =
            20;

    private static final int MAX_RESTORE_FAILURES_REPORTED =
            10;

    private static final double TIMELINE_FALLBACK_RADIUS =
            6.0;

    private static final int TIMELINE_MIN_EXACT_OVERLAP =
            2;

    private static final double TIMELINE_MIN_OVERLAP_RATIO =
            0.35;

    private static final int TIMELINE_ITEMS_PER_PAGE =
            7;

    private final Set<BlockKey> playerPlacedBlocks =
            new HashSet<>();

    private final Deque<TemporalSnapshot> snapshots =
            new ArrayDeque<>();

    private final List<BlockDisplay> activeDisplays =
            new ArrayList<>();

    private final List<ArmorStand> activeGhosts =
            new ArrayList<>();

    private final List<PlayerTrace> playerTraces =
            new ArrayList<>();

    private final List<PlayerAction> playerActions =
            new ArrayList<>();

    private final Map<UUID, PendingDeletion> pendingDeletions =
            new HashMap<>();

    private final Map<UUID, PendingRestore> pendingRestores =
            new HashMap<>();

    private final Set<Long> activeRestoreTimelines =
            new HashSet<>();

    private final Map<Long, BukkitTask> activeRestoreTasks =
            new HashMap<>();

    private boolean shuttingDown;

    private static final int[] MEMORY_MENU_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private long nextSnapshotId = 1L;
    private long nextTimelineId = 1L;

    @Override
    public void onEnable() {
        shuttingDown = false;

        echoLensKey = new NamespacedKey(
                this,
                "echo_lens"
        );

        databaseManager = new DatabaseManager(this);

        try {
            databaseManager.initialize();
            loadStateFromDatabase();

            getLogger().info(
                    "Schema do banco temporal: v"
                            + databaseManager.getSchemaVersion()
            );

        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel iniciar "
                            + "o banco temporal.",
                    exception
            );

            getServer().getPluginManager()
                    .disablePlugin(this);

            return;
        }

        getServer().getPluginManager().registerEvents(
                new BlockMemoryListener(this),
                this
        );

        startPlayerTraceRecorder();

        getServer().getPluginManager().registerEvents(
                new EchoMemoryMenuListener(this),
                this
        );

        getServer().getPluginManager().registerEvents(
                new EchoLensListener(this),
                this
        );

        if (getCommand("echo") != null) {
            getCommand("echo").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        revealEcho(player);
                        return true;
                    }
            );
        } else {
            getLogger().severe(
                    "O comando /echo nao foi encontrado no plugin.yml."
            );
        }

        getLogger().info("Memoria temporal iniciada!");
        getLogger().info("Sistema de fotografias temporais ativado!");

        if (getCommand("echolens") != null) {
            getCommand("echolens").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado "
                                            + "por jogadores."
                            );

                            return true;
                        }

                        ItemStack echoLens = createEchoLens();

                        var remainingItems =
                                player.getInventory().addItem(
                                        echoLens
                                );

                        for (ItemStack remaining :
                                remainingItems.values()) {

                            player.getWorld().dropItemNaturally(
                                    player.getLocation(),
                                    remaining
                            );
                        }

                        player.sendMessage(
                                "§b[EchoCraft] §fEchoLens adicionada "
                                        + "ao seu inventário."
                        );

                        player.sendMessage(
                                "§7Segure a lente e clique com "
                                        + "o botão direito."
                        );

                        return true;
                    }
            );
        }

        if (getCommand("echoname") != null) {
            getCommand("echoname").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (args.length < 2) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f/echoname <id> <nome>"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId = Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        String displayName =
                                String.join(
                                        " ",
                                        java.util.Arrays.copyOfRange(
                                                args,
                                                1,
                                                args.length
                                        )
                                ).trim();

                        renameSnapshot(
                                player,
                                snapshotId,
                                displayName
                        );

                        return true;
                    }
            );
        } else {
            getLogger().warning(
                    "O comando /echoname nao foi encontrado no plugin.yml."
            );
        }

        if (getCommand("echolist") != null) {
            getCommand("echolist").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        listEchoMemories(player);
                        return true;
                    }
            );
        }

        if (getCommand("echoinfo") != null) {
            getCommand("echoinfo").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (args.length < 1) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f/echoinfo <id>"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId = Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        showEchoInfo(player, snapshotId);
                        return true;
                    }
            );
        }

        if (getCommand("echodelete") != null) {
            getCommand("echodelete").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (args.length < 1) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f/echodelete <id>"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId = Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        boolean confirmed =
                                args.length >= 2
                                        && args[1].equalsIgnoreCase("confirm");

                        deleteEchoMemory(
                                player,
                                snapshotId,
                                confirmed
                        );
                        return true;
                    }
            );
        }
        if (getCommand("echorestore") != null) {
            getCommand("echorestore").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (!player.hasPermission("echocraft.restore")) {
                            player.sendMessage(
                                    "§c[EchoCraft] Você não tem permissão "
                                            + "para restaurar memórias."
                            );
                            return true;
                        }

                        if (args.length < 1) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f"
                                            + "/echorestore <id> [confirm]"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId =
                                    Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        boolean confirmed =
                                args.length >= 2
                                        && args[1].equalsIgnoreCase(
                                        "confirm"
                                );

                        restoreEchoMemory(
                                player,
                                snapshotId,
                                confirmed
                        );

                        return true;
                    }
            );
        }
        if (getCommand("echotimeline") != null) {
            getCommand("echotimeline").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (args.length < 1) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f"
                                            + "/echotimeline <id> [pagina]"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId =
                                    Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        int page = 1;

                        if (args.length >= 2) {
                            try {
                                page =
                                        Integer.parseInt(args[1]);

                            } catch (NumberFormatException exception) {
                                player.sendMessage(
                                        "§c[EchoCraft] Página inválida."
                                );
                                return true;
                            }
                        }

                        showEchoTimeline(
                                player,
                                snapshotId,
                                page
                        );

                        return true;
                    }
            );
        }

        if (getCommand("echotimelinename") != null) {
            getCommand("echotimelinename").setExecutor(
                    (sender, command, label, args) -> {

                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(
                                    "Este comando so pode ser usado por jogadores."
                            );
                            return true;
                        }

                        if (args.length < 2) {
                            player.sendMessage(
                                    "§c[EchoCraft] Use: §f"
                                            + "/echotimelinename <id-da-memoria> <nome>"
                            );
                            return true;
                        }

                        long snapshotId;

                        try {
                            snapshotId =
                                    Long.parseLong(args[0]);

                        } catch (NumberFormatException exception) {
                            player.sendMessage(
                                    "§c[EchoCraft] ID inválido."
                            );
                            return true;
                        }

                        String timelineName =
                                String.join(
                                        " ",
                                        java.util.Arrays.copyOfRange(
                                                args,
                                                1,
                                                args.length
                                        )
                                ).trim();

                        renameTimeline(
                                player,
                                snapshotId,
                                timelineName
                        );

                        return true;
                    }
            );
        } else {
            getLogger().warning(
                    "O comando /echotimelinename nao foi "
                            + "encontrado no plugin.yml."
            );
        }
        saveDefaultConfig();

        echoPanelClient = new EchoPanelClient(this);
        echoPanelClient.start();
    }

    @Override
    public void onDisable() {
        if (echoPanelClient != null) {
            echoPanelClient.stop();
        }

        shuttingDown = true;

        for (BukkitTask task : activeRestoreTasks.values()) {
            task.cancel();
        }

        activeRestoreTasks.clear();
        activeRestoreTimelines.clear();

        clearActiveEchoes();

        playerPlacedBlocks.clear();
        snapshots.clear();
        playerTraces.clear();
        playerActions.clear();
        pendingDeletions.clear();
        pendingRestores.clear();

        if (databaseManager != null) {
            try {
                databaseManager.checkpointWal();

            } catch (SQLException exception) {
                getLogger().log(
                        Level.WARNING,
                        "Nao foi possivel finalizar o WAL do banco.",
                        exception
                );
            }
        }

        getLogger().info("Memoria temporal encerrada!");
    }

    public void trackPlacedBlock(Block block) {
        BlockKey key = keyOf(block);

        if (!playerPlacedBlocks.add(key)) {
            return;
        }

        try {
            databaseManager.savePlacedBlock(
                    new DatabaseManager.StoredPosition(
                            key.worldId(),
                            key.x(),
                            key.y(),
                            key.z()
                    )
            );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel salvar "
                            + "o bloco colocado.",
                    exception
            );
        }
    }

    private void removeTrackedBlocks(
            Set<BlockKey> keys
    ) {
        if (keys.isEmpty()) {
            return;
        }

        List<DatabaseManager.StoredPosition> positions =
                new ArrayList<>();

        for (BlockKey key : keys) {
            if (!playerPlacedBlocks.remove(key)) {
                continue;
            }

            positions.add(
                    new DatabaseManager.StoredPosition(
                            key.worldId(),
                            key.x(),
                            key.y(),
                            key.z()
                    )
            );
        }

        try {
            databaseManager.deletePlacedBlocks(positions);

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel remover "
                            + "os blocos destruidos do banco.",
                    exception
            );
        }
    }

    public boolean captureManualBreak(Block block) {
        BlockKey brokenKey = keyOf(block);

        if (!playerPlacedBlocks.contains(brokenKey)) {
            return false;
        }

        Set<BlockKey> structure = findConnectedStructure(
                Set.of(brokenKey)
        );

        createSnapshot(
                MemoryType.MANUAL_BREAK,
                "Bloco quebrado",
                block.getLocation(),
                structure
        );

        removeTrackedBlocks(Set.of(brokenKey));
        return true;
    }

    public int captureExplosion(
            Iterable<Block> destroyedBlocks,
            Location explosionLocation,
            String cause
    ) {
        Set<BlockKey> destroyedPlayerBlocks =
                new HashSet<>();

        for (Block block : destroyedBlocks) {
            BlockKey key = keyOf(block);

            if (playerPlacedBlocks.contains(key)) {
                destroyedPlayerBlocks.add(key);
            }
        }

        if (destroyedPlayerBlocks.isEmpty()) {
            return 0;
        }

        /*
         * A lista da explosão fornece os pontos atingidos.
         * A partir deles, procuramos o restante da construção
         * conectada antes que os blocos sejam destruídos.
         */
        Set<BlockKey> completeStructure =
                findConnectedStructure(destroyedPlayerBlocks);

        TemporalSnapshot snapshot = createSnapshot(
                MemoryType.EXPLOSION,
                cause,
                explosionLocation,
                completeStructure
        );

        /*
         * Só removemos do rastreamento os blocos que realmente
         * serão destruídos. As partes sobreviventes continuam
         * sendo reconhecidas como construção de jogador.
         */
        removeTrackedBlocks(destroyedPlayerBlocks);

        if (snapshot == null) {
            return 0;
        }

        return snapshot.blocks().size();
    }

    private Set<BlockKey> findConnectedStructure(
            Set<BlockKey> startingBlocks
    ) {
        Set<BlockKey> visited = new HashSet<>();
        Deque<BlockKey> queue = new ArrayDeque<>();

        for (BlockKey startingBlock : startingBlocks) {
            if (visited.size() >= MAX_STRUCTURE_BLOCKS) {
                break;
            }

            if (!playerPlacedBlocks.contains(startingBlock)) {
                continue;
            }

            if (visited.add(startingBlock)) {
                queue.addLast(startingBlock);
            }
        }

        while (!queue.isEmpty()
                && visited.size() < MAX_STRUCTURE_BLOCKS) {

            BlockKey current = queue.removeFirst();

            for (int[] direction : DIRECTIONS) {
                BlockKey neighbor = new BlockKey(
                        current.worldId(),
                        current.x() + direction[0],
                        current.y() + direction[1],
                        current.z() + direction[2]
                );

                if (!playerPlacedBlocks.contains(neighbor)) {
                    continue;
                }

                if (!visited.add(neighbor)) {
                    continue;
                }

                queue.addLast(neighbor);

                if (visited.size() >= MAX_STRUCTURE_BLOCKS) {
                    break;
                }
            }
        }

        return visited;
    }

    private TemporalSnapshot createSnapshot(
            MemoryType type,
            String cause,
            Location center,
            Set<BlockKey> structure
    ) {
        return createSnapshot(
                type,
                cause,
                center,
                structure,
                null
        );
    }

    private TemporalSnapshot createSnapshot(
            MemoryType type,
            String cause,
            Location center,
            Set<BlockKey> structure,
            Long forcedTimelineId
    ) {
        List<BlockMemory> capturedBlocks =
                new ArrayList<>();

        for (BlockKey key : structure) {
            World world =
                    getServer().getWorld(key.worldId());

            if (world == null) {
                continue;
            }

            Block block = world.getBlockAt(
                    key.x(),
                    key.y(),
                    key.z()
            );

            if (block.getType().isAir()) {
                continue;
            }

            capturedBlocks.add(
                    new BlockMemory(
                            block.getLocation().clone(),
                            block.getBlockData().clone()
                    )
            );
        }

        if (capturedBlocks.isEmpty()) {
            return null;
        }

        capturedBlocks.sort(
                Comparator
                        .comparingInt(
                                (BlockMemory memory) ->
                                        memory.location()
                                                .getBlockY()
                        )
                        .thenComparingInt(
                                memory ->
                                        memory.location()
                                                .getBlockX()
                        )
                        .thenComparingInt(
                                memory ->
                                        memory.location()
                                                .getBlockZ()
                        )
        );

        World centerWorld = center.getWorld();

        if (centerWorld == null) {
            return null;
        }

        long createdAt = System.currentTimeMillis();

        List<PlayerTrace> snapshotTraces =
                buildSnapshotTraces(
                        center,
                        createdAt
                );

        List<PlayerAction> snapshotActions =
                buildSnapshotActions(
                        center,
                        createdAt
                );

        List<DatabaseManager.StoredBlock> storedBlocks =
                new ArrayList<>();

        for (BlockMemory memory : capturedBlocks) {
            Location location = memory.location();
            World world = location.getWorld();

            if (world == null) {
                continue;
            }

            storedBlocks.add(
                    new DatabaseManager.StoredBlock(
                            world.getUID(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ(),
                            memory.blockData().getAsString()
                    )
            );
        }

        TimelineAssignment timelineAssignment;

        try {
            timelineAssignment =
                    resolveTimelineAssignment(
                            centerWorld,
                            center,
                            capturedBlocks,
                            forcedTimelineId,
                            createdAt
                    );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.WARNING,
                    "Nao foi possivel resolver a linha temporal "
                            + "no banco. A fotografia pode ficar "
                            + "apenas na memoria.",
                    exception
            );

            timelineAssignment =
                    resolveInMemoryTimelineAssignment(
                            centerWorld,
                            center,
                            capturedBlocks,
                            forcedTimelineId
                    );
        }

        long snapshotId;
        boolean snapshotPersisted = false;

        try {
            snapshotId = databaseManager.saveSnapshot(
                    timelineAssignment.timelineId(),
                    timelineAssignment.versionNumber(),
                    type.name(),
                    cause,
                    centerWorld.getUID(),
                    center.getX(),
                    center.getY(),
                    center.getZ(),
                    createdAt,
                    storedBlocks
            );

            snapshotPersisted = true;

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "A fotografia ficou apenas "
                            + "na memoria temporaria.",
                    exception
            );

            snapshotId = nextSnapshotId++;
        }

        nextSnapshotId = Math.max(
                nextSnapshotId,
                snapshotId + 1L
        );

        if (snapshotPersisted) {
            try {
                databaseManager.saveSnapshotReplayData(
                        snapshotId,
                        toStoredTraces(
                                createdAt,
                                snapshotTraces
                        ),
                        toStoredActions(
                                createdAt,
                                snapshotActions
                        )
                );

            } catch (SQLException exception) {
                getLogger().log(
                        Level.WARNING,
                        "A fotografia foi salva, mas o replay "
                                + "temporal ficou apenas na memoria.",
                        exception
                );
            }
        }

        TemporalSnapshot snapshot =
                new TemporalSnapshot(
                        snapshotId,
                        timelineAssignment.timelineId(),
                        timelineAssignment.versionNumber(),
                        timelineAssignment.timelineName(),
                        type,
                        cause,
                        center.clone(),
                        createdAt,
                        null,
                        List.copyOf(capturedBlocks),
                        List.copyOf(snapshotTraces),
                        List.copyOf(snapshotActions)
                );

        snapshots.addFirst(snapshot);

        removeExpiredSnapshots();

        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.removeLast();
        }

        getLogger().info(
                "Fotografia temporal #"
                        + snapshot.id()
                        + " salva na linha #"
                        + snapshot.timelineId()
                        + " como versao "
                        + snapshot.versionNumber()
                        + ", com "
                        + capturedBlocks.size()
                        + " blocos, "
                        + snapshotTraces.size()
                        + " rastros e "
                        + snapshotActions.size()
                        + " acoes. Motivo: "
                        + cause
        );

        if (echoPanelClient != null) {
            echoPanelClient.sendTimelineSnapshot(
                    snapshot.center(),
                    snapshot.id(),
                    snapshot.timelineId(),
                    snapshot.versionNumber(),
                    snapshot.timelineName(),
                    getSnapshotTitle(snapshot),
                    snapshot.type().name(),
                    snapshot.cause(),
                    snapshot.blocks().size(),
                    snapshot.traces().size(),
                    snapshot.actions().size(),
                    snapshot.createdAt()
            );
        }

        return snapshot;
    }


    private TimelineAssignment resolveTimelineAssignment(
            World world,
            Location center,
            List<BlockMemory> capturedBlocks,
            Long forcedTimelineId,
            long createdAt
    ) throws SQLException {
        TemporalSnapshot matchedSnapshot =
                forcedTimelineId == null
                        ? findBestTimelineMatch(
                        world,
                        center,
                        capturedBlocks
                )
                        : findLatestSnapshotInTimeline(
                        forcedTimelineId
                );

        long timelineId;
        String timelineName;

        if (matchedSnapshot != null) {
            timelineId =
                    matchedSnapshot.timelineId();

            timelineName =
                    matchedSnapshot.timelineName();

        } else if (forcedTimelineId != null) {
            timelineId =
                    forcedTimelineId;

            timelineName =
                    findTimelineName(
                            forcedTimelineId
                    );

        } else {
            timelineId =
                    databaseManager.createTimeline(
                            world.getUID(),
                            center.getX(),
                            center.getY(),
                            center.getZ(),
                            createdAt
                    );

            timelineName = null;
        }

        int versionNumber =
                databaseManager.getNextTimelineVersion(
                        timelineId
                );

        nextTimelineId = Math.max(
                nextTimelineId,
                timelineId + 1L
        );

        return new TimelineAssignment(
                timelineId,
                versionNumber,
                timelineName
        );
    }

    private TimelineAssignment resolveInMemoryTimelineAssignment(
            World world,
            Location center,
            List<BlockMemory> capturedBlocks,
            Long forcedTimelineId
    ) {
        TemporalSnapshot matchedSnapshot =
                forcedTimelineId == null
                        ? findBestTimelineMatch(
                        world,
                        center,
                        capturedBlocks
                )
                        : findLatestSnapshotInTimeline(
                        forcedTimelineId
                );

        long timelineId;
        String timelineName;

        if (matchedSnapshot != null) {
            timelineId =
                    matchedSnapshot.timelineId();

            timelineName =
                    matchedSnapshot.timelineName();

        } else if (forcedTimelineId != null) {
            timelineId =
                    forcedTimelineId;

            timelineName =
                    findTimelineName(
                            forcedTimelineId
                    );

        } else {
            timelineId =
                    nextTimelineId++;

            timelineName = null;
        }

        int versionNumber =
                getNextTimelineVersionInMemory(
                        timelineId
                );

        return new TimelineAssignment(
                timelineId,
                versionNumber,
                timelineName
        );
    }

    private TemporalSnapshot findBestTimelineMatch(
            World world,
            Location center,
            List<BlockMemory> capturedBlocks
    ) {
        Set<BlockKey> currentKeys =
                toBlockKeys(capturedBlocks);

        if (currentKeys.isEmpty()) {
            return null;
        }

        Map<Long, TemporalSnapshot> latestByTimeline =
                new HashMap<>();

        for (TemporalSnapshot snapshot : snapshots) {
            World snapshotWorld =
                    snapshot.center().getWorld();

            if (snapshotWorld == null
                    || !snapshotWorld.getUID().equals(
                    world.getUID()
            )) {
                continue;
            }

            TemporalSnapshot currentLatest =
                    latestByTimeline.get(
                            snapshot.timelineId()
                    );

            if (currentLatest == null
                    || snapshot.createdAt()
                    > currentLatest.createdAt()) {

                latestByTimeline.put(
                        snapshot.timelineId(),
                        snapshot
                );
            }
        }

        TemporalSnapshot bestMatch = null;
        double bestScore =
                Double.NEGATIVE_INFINITY;

        double fallbackRadiusSquared =
                TIMELINE_FALLBACK_RADIUS
                        * TIMELINE_FALLBACK_RADIUS;

        for (TemporalSnapshot candidate :
                latestByTimeline.values()) {

            Set<BlockKey> candidateKeys =
                    toBlockKeys(
                            candidate.blocks()
                    );

            int minimumSize =
                    Math.max(
                            1,
                            Math.min(
                                    currentKeys.size(),
                                    candidateKeys.size()
                            )
                    );

            int exactOverlap =
                    countExactOverlap(
                            currentKeys,
                            candidateKeys
                    );

            double overlapRatio =
                    exactOverlap
                            / (double) minimumSize;

            int nearbyOverlap =
                    countNearbyOverlap(
                            currentKeys,
                            candidateKeys
                    );

            double nearbyRatio =
                    nearbyOverlap
                            / (double) minimumSize;

            double centerDistanceSquared =
                    center.distanceSquared(
                            candidate.center()
                    );

            boolean exactMatch =
                    exactOverlap
                            >= TIMELINE_MIN_EXACT_OVERLAP
                            || overlapRatio
                            >= TIMELINE_MIN_OVERLAP_RATIO;

            boolean spatialFallback =
                    centerDistanceSquared
                            <= fallbackRadiusSquared
                            && nearbyRatio >= 0.50;

            if (!exactMatch
                    && !spatialFallback) {
                continue;
            }

            double score =
                    exactOverlap * 1_000.0
                            + overlapRatio * 500.0
                            + nearbyRatio * 100.0
                            - centerDistanceSquared;

            if (score <= bestScore) {
                continue;
            }

            bestScore = score;
            bestMatch = candidate;
        }

        return bestMatch;
    }

    private Set<BlockKey> toBlockKeys(
            List<BlockMemory> blocks
    ) {
        Set<BlockKey> keys =
                new HashSet<>();

        for (BlockMemory memory : blocks) {
            Location location =
                    memory.location();

            World world =
                    location.getWorld();

            if (world == null) {
                continue;
            }

            keys.add(
                    new BlockKey(
                            world.getUID(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()
                    )
            );
        }

        return keys;
    }

    private int countExactOverlap(
            Set<BlockKey> first,
            Set<BlockKey> second
    ) {
        Set<BlockKey> smaller =
                first.size() <= second.size()
                        ? first
                        : second;

        Set<BlockKey> larger =
                first.size() <= second.size()
                        ? second
                        : first;

        int overlap = 0;

        for (BlockKey key : smaller) {
            if (larger.contains(key)) {
                overlap++;
            }
        }

        return overlap;
    }

    private int countNearbyOverlap(
            Set<BlockKey> current,
            Set<BlockKey> historical
    ) {
        int overlap = 0;

        for (BlockKey key : current) {
            boolean found = false;

            for (int dx = -1;
                 dx <= 1 && !found;
                 dx++) {

                for (int dy = -1;
                     dy <= 1 && !found;
                     dy++) {

                    for (int dz = -1;
                         dz <= 1;
                         dz++) {

                        BlockKey neighbor =
                                new BlockKey(
                                        key.worldId(),
                                        key.x() + dx,
                                        key.y() + dy,
                                        key.z() + dz
                                );

                        if (!historical.contains(
                                neighbor
                        )) {
                            continue;
                        }

                        overlap++;
                        found = true;
                        break;
                    }
                }
            }
        }

        return overlap;
    }

    private TemporalSnapshot findLatestSnapshotInTimeline(
            long timelineId
    ) {
        TemporalSnapshot latest = null;

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.timelineId()
                    != timelineId) {
                continue;
            }

            if (latest == null
                    || snapshot.createdAt()
                    > latest.createdAt()) {

                latest = snapshot;
            }
        }

        return latest;
    }

    private String findTimelineName(
            long timelineId
    ) {
        TemporalSnapshot snapshot =
                findLatestSnapshotInTimeline(
                        timelineId
                );

        return snapshot == null
                ? null
                : snapshot.timelineName();
    }

    private int getNextTimelineVersionInMemory(
            long timelineId
    ) {
        int highestVersion = 0;

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.timelineId()
                    != timelineId) {
                continue;
            }

            highestVersion = Math.max(
                    highestVersion,
                    snapshot.versionNumber()
            );
        }

        return highestVersion + 1;
    }

    private void loadStateFromDatabase()
            throws SQLException {

        long oldestAllowedTime =
                System.currentTimeMillis()
                        - SNAPSHOT_LIFETIME_MILLIS;

        databaseManager.deleteExpiredSnapshots(
                oldestAllowedTime
        );

        playerPlacedBlocks.clear();
        snapshots.clear();

        for (DatabaseManager.StoredPosition position :
                databaseManager.loadPlacedBlocks()) {

            playerPlacedBlocks.add(
                    new BlockKey(
                            position.worldId(),
                            position.x(),
                            position.y(),
                            position.z()
                    )
            );
        }

        List<DatabaseManager.StoredSnapshot>
                storedSnapshots =
                databaseManager.loadSnapshots(
                        oldestAllowedTime,
                        MAX_SNAPSHOTS
                );

        for (DatabaseManager.StoredSnapshot stored :
                storedSnapshots) {

            World centerWorld =
                    getServer().getWorld(stored.worldId());

            if (centerWorld == null) {
                getLogger().warning(
                        "Mundo da fotografia #"
                                + stored.id()
                                + " nao foi encontrado."
                );

                continue;
            }

            MemoryType type;

            try {
                type = MemoryType.valueOf(
                        stored.memoryType()
                );

            } catch (IllegalArgumentException exception) {
                getLogger().warning(
                        "Tipo invalido na fotografia #"
                                + stored.id()
                );

                continue;
            }

            List<BlockMemory> blocks =
                    new ArrayList<>();

            for (DatabaseManager.StoredBlock storedBlock :
                    stored.blocks()) {

                World blockWorld =
                        getServer().getWorld(
                                storedBlock.worldId()
                        );

                if (blockWorld == null) {
                    continue;
                }

                BlockData blockData;

                try {
                    blockData =
                            getServer().createBlockData(
                                    storedBlock.blockData()
                            );

                } catch (IllegalArgumentException exception) {
                    getLogger().warning(
                            "BlockData invalido na fotografia #"
                                    + stored.id()
                    );

                    continue;
                }

                blocks.add(
                        new BlockMemory(
                                new Location(
                                        blockWorld,
                                        storedBlock.x(),
                                        storedBlock.y(),
                                        storedBlock.z()
                                ),
                                blockData
                        )
                );
            }

            if (blocks.isEmpty()) {
                continue;
            }

            Location center = new Location(
                    centerWorld,
                    stored.centerX(),
                    stored.centerY(),
                    stored.centerZ()
            );

            snapshots.addLast(
                    new TemporalSnapshot(
                            stored.id(),
                            stored.timelineId(),
                            stored.versionNumber(),
                            stored.timelineName(),
                            type,
                            stored.cause(),
                            center,
                            stored.createdAt(),
                            stored.displayName(),
                            List.copyOf(blocks),
                            fromStoredTraces(
                                    stored.traces()
                            ),
                            fromStoredActions(
                                    stored.actions()
                            )
                    )
            );

            nextSnapshotId = Math.max(
                    nextSnapshotId,
                    stored.id() + 1L
            );

            nextTimelineId = Math.max(
                    nextTimelineId,
                    stored.timelineId() + 1L
            );
        }

        getLogger().info(
                "Estado temporal carregado: "
                        + playerPlacedBlocks.size()
                        + " blocos construidos e "
                        + snapshots.size()
                        + " fotografias."
        );
    }

    public ItemStack createEchoLens() {
        ItemStack item =
                new ItemStack(Material.SPYGLASS);

        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text(
                                "EchoLens",
                                NamedTextColor.AQUA
                        )
                        .decorate(TextDecoration.BOLD)
                        .decoration(
                                TextDecoration.ITALIC,
                                false
                        )
        );

        meta.lore(
                List.of(
                        Component.text(
                                        "Visor de memórias temporais",
                                        NamedTextColor.GRAY
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.empty(),

                        Component.text(
                                        "Clique direito para revelar",
                                        NamedTextColor.WHITE
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "uma memória próxima.",
                                        NamedTextColor.WHITE
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.empty(),

                        Component.text(
                                        "Tecnologia temporal instável",
                                        NamedTextColor.DARK_PURPLE
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                )
                )
        );

        meta.getPersistentDataContainer().set(
                echoLensKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        item.setItemMeta(meta);

        return item;
    }

    public boolean isEchoLens(ItemStack item) {
        if (item == null
                || item.getType().isAir()
                || !item.hasItemMeta()) {

            return false;
        }

        Byte marker = item.getItemMeta()
                .getPersistentDataContainer()
                .get(
                        echoLensKey,
                        PersistentDataType.BYTE
                );

        return marker != null && marker == (byte) 1;
    }

    public void activateEchoLens(Player player) {
        player.sendActionBar(
                Component.text(
                        "Acessando o arquivo temporal...",
                        NamedTextColor.AQUA
                )
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                0.8F,
                1.3F
        );

        openEchoMemoryMenu(player);
    }

    private void startPlayerTraceRecorder() {
        getServer().getScheduler().runTaskTimer(
                this,
                () -> {
                    long now = System.currentTimeMillis();

                    for (Player player : getServer().getOnlinePlayers()) {
                        Location location = player.getLocation().clone();

                        playerTraces.add(
                                new PlayerTrace(
                                        player.getUniqueId(),
                                        player.getName(),
                                        location,
                                        location.getYaw(),
                                        location.getPitch(),
                                        player.getInventory()
                                                .getItemInMainHand()
                                                .getType()
                                                .name(),
                                        now
                                )
                        );
                    }

                    playerTraces.removeIf(
                            trace ->
                                    now - trace.createdAt()
                                            > PLAYER_TRACE_MEMORY_MILLIS
                    );
                },
                20L,
                PLAYER_TRACE_INTERVAL_TICKS
        );

        getLogger().info(
                "Gravador de rastros temporais ativado."
        );
    }

    public void openEchoMemoryMenu(Player player) {
        List<TemporalSnapshot> nearbySnapshots =
                findNearbySnapshots(player);

        if (nearbySnapshots.isEmpty()) {
            player.sendMessage(
                    "§c[EchoCraft] Nenhuma memória temporal "
                            + "foi encontrada por perto."
            );

            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_BASS,
                    0.8F,
                    0.7F
            );

            return;
        }

        EchoMemoryMenuHolder holder =
                new EchoMemoryMenuHolder(this);

        Inventory inventory =
                holder.getInventory();

        ItemStack filler = createMenuFiller();

        for (int slot = 0;
             slot < inventory.getSize();
             slot++) {

            inventory.setItem(slot, filler);
        }

        int amount = Math.min(
                nearbySnapshots.size(),
                MEMORY_MENU_SLOTS.length
        );

        for (int index = 0;
             index < amount;
             index++) {

            TemporalSnapshot snapshot =
                    nearbySnapshots.get(index);

            int slot = MEMORY_MENU_SLOTS[index];

            inventory.setItem(
                    slot,
                    createSnapshotMenuItem(
                            player,
                            snapshot
                    )
            );

            holder.bindSnapshot(
                    slot,
                    snapshot.id()
            );
        }

        inventory.setItem(
                40,
                createMenuInformationItem(amount)
        );

        player.openInventory(inventory);
    }

    private List<TemporalSnapshot>
    findNearbySnapshots(Player player) {

        removeExpiredSnapshots();

        List<TemporalSnapshot> nearby =
                new ArrayList<>();

        UUID playerWorldId =
                player.getWorld().getUID();

        Location playerLocation =
                player.getLocation();

        double radiusSquared =
                SNAPSHOT_SEARCH_RADIUS
                        * SNAPSHOT_SEARCH_RADIUS;

        for (TemporalSnapshot snapshot : snapshots) {
            Location center = snapshot.center();
            World world = center.getWorld();

            if (world == null) {
                continue;
            }

            if (!world.getUID().equals(playerWorldId)) {
                continue;
            }

            if (center.distanceSquared(playerLocation)
                    > radiusSquared) {

                continue;
            }

            nearby.add(snapshot);
        }

        nearby.sort(
                Comparator.comparingLong(
                        TemporalSnapshot::createdAt
                ).reversed()
        );

        return nearby;
    }

    private String getSnapshotTitle(
            TemporalSnapshot snapshot
    ) {
        if (snapshot.displayName() != null
                && !snapshot.displayName().isBlank()) {

            return snapshot.displayName();
        }

        return "Memória #" + snapshot.id();
    }

    private String getTimelineTitle(
            TemporalSnapshot snapshot
    ) {
        if (snapshot.timelineName() != null
                && !snapshot.timelineName().isBlank()) {

            return snapshot.timelineName();
        }

        return "Linha temporal #"
                + snapshot.timelineId();
    }

    private ItemStack createSnapshotMenuItem(
            Player player,
            TemporalSnapshot snapshot
    ) {
        Material iconMaterial = switch (snapshot.type()) {
            case EXPLOSION -> Material.TNT;
            case MANUAL_BREAK -> Material.IRON_PICKAXE;
            case RESTORE -> Material.RECOVERY_COMPASS;
        };

        NamedTextColor titleColor = switch (snapshot.type()) {
            case EXPLOSION -> NamedTextColor.RED;
            case MANUAL_BREAK -> NamedTextColor.AQUA;
            case RESTORE -> NamedTextColor.LIGHT_PURPLE;
        };

        ItemStack item =
                new ItemStack(iconMaterial);

        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text(
                                getSnapshotTitle(snapshot),
                                titleColor
                        )
                        .decorate(TextDecoration.BOLD)
                        .decoration(
                                TextDecoration.ITALIC,
                                false
                        )
        );

        int changedBlocks =
                findMissingBlocks(snapshot).size();

        double distance =
                Math.sqrt(
                        snapshot.center()
                                .distanceSquared(
                                        player.getLocation()
                                )
                );

        meta.lore(
                List.of(
                        Component.text(
                                        formatMemoryCause(snapshot),
                                        NamedTextColor.GRAY
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "ID temporal: #"
                                                + snapshot.id(),
                                        NamedTextColor.DARK_GRAY
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "Linha: #"
                                                + snapshot.timelineId()
                                                + " • Versão "
                                                + snapshot.versionNumber(),
                                        NamedTextColor.DARK_PURPLE
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        getTimelineTitle(snapshot),
                                        NamedTextColor.LIGHT_PURPLE
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.empty(),

                        Component.text(
                                        "Idade: ",
                                        NamedTextColor.DARK_GRAY
                                )
                                .append(
                                        Component.text(
                                                formatAge(
                                                        snapshot.createdAt()
                                                ),
                                                NamedTextColor.WHITE
                                        )
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "Estrutura original: ",
                                        NamedTextColor.DARK_GRAY
                                )
                                .append(
                                        Component.text(
                                                snapshot.blocks().size()
                                                        + " blocos",
                                                NamedTextColor.AQUA
                                        )
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "Alterados ou destruídos: ",
                                        NamedTextColor.DARK_GRAY
                                )
                                .append(
                                        Component.text(
                                                changedBlocks,
                                                NamedTextColor.LIGHT_PURPLE
                                        )
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "Distância: ",
                                        NamedTextColor.DARK_GRAY
                                )
                                .append(
                                        Component.text(
                                                Math.round(distance)
                                                        + " blocos",
                                                NamedTextColor.WHITE
                                        )
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.empty(),

                        Component.text(
                                        "Clique para revelar",
                                        NamedTextColor.YELLOW
                                )
                                .decorate(
                                        TextDecoration.BOLD
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                )
                )
        );

        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );

        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createMenuFiller() {
        ItemStack item =
                new ItemStack(
                        Material.BLACK_STAINED_GLASS_PANE
                );

        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.empty());

        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createMenuInformationItem(
            int memoryAmount
    ) {
        ItemStack item =
                new ItemStack(Material.CLOCK);

        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text(
                                "Arquivo Temporal",
                                NamedTextColor.GOLD
                        )
                        .decorate(TextDecoration.BOLD)
                        .decoration(
                                TextDecoration.ITALIC,
                                false
                        )
        );

        meta.lore(
                List.of(
                        Component.text(
                                        "Memórias próximas: "
                                                + memoryAmount,
                                        NamedTextColor.GRAY
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                ),

                        Component.text(
                                        "Raio de análise: "
                                                + (int)
                                                SNAPSHOT_SEARCH_RADIUS
                                                + " blocos",
                                        NamedTextColor.DARK_GRAY
                                )
                                .decoration(
                                        TextDecoration.ITALIC,
                                        false
                                )
                )
        );

        item.setItemMeta(meta);

        return item;
    }

    private String formatMemoryCause(
            TemporalSnapshot snapshot
    ) {
        if (snapshot.type()
                == MemoryType.MANUAL_BREAK) {

            return "Alteração manual";
        }

        if (snapshot.type()
                == MemoryType.RESTORE) {

            return "Restauração temporal";
        }

        return switch (
                snapshot.cause()
                        .toUpperCase(Locale.ROOT)
                ) {
            case "PRIMED_TNT" ->
                    "Explosão de TNT";

            case "CREEPER" ->
                    "Explosão de Creeper";

            case "BLOCK_EXPLOSION" ->
                    "Explosão causada por bloco";

            default ->
                    "Explosão: "
                            + snapshot.cause()
                            .toLowerCase(Locale.ROOT)
                            .replace('_', ' ');
        };
    }

    private String formatAge(long createdAt) {
        long seconds = Math.max(
                0L,
                (System.currentTimeMillis()
                        - createdAt) / 1000L
        );

        if (seconds < 60L) {
            return seconds + " segundos atrás";
        }

        long minutes = seconds / 60L;

        if (minutes < 60L) {
            return minutes + " minutos atrás";
        }

        long hours = minutes / 60L;

        if (hours < 24L) {
            return hours + " horas atrás";
        }

        long days = hours / 24L;

        return days + " dias atrás";
    }

    private void renameSnapshot(
            Player player,
            long snapshotId,
            String displayName
    ) {
        if (displayName.isBlank()) {
            player.sendMessage(
                    "§c[EchoCraft] O nome não pode estar vazio."
            );
            return;
        }

        if (displayName.length() > 40) {
            player.sendMessage(
                    "§c[EchoCraft] O nome precisa ter no máximo 40 caracteres."
            );
            return;
        }

        TemporalSnapshot target = null;

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.id() == snapshotId) {
                target = snapshot;
                break;
            }
        }

        if (target == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        TemporalSnapshot renamed =
                new TemporalSnapshot(
                        target.id(),
                        target.timelineId(),
                        target.versionNumber(),
                        target.timelineName(),
                        target.type(),
                        target.cause(),
                        target.center(),
                        target.createdAt(),
                        displayName,
                        target.blocks(),
                        target.traces(),
                        target.actions()
                );

        snapshots.remove(target);
        snapshots.addFirst(renamed);

        try {
            databaseManager.updateSnapshotDisplayName(
                    snapshotId,
                    displayName
            );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel renomear "
                            + "a memoria temporal.",
                    exception
            );

            player.sendMessage(
                    "§c[EchoCraft] Não foi possível salvar o nome no banco."
            );
            return;
        }

        auditTemporalOperation(
                player,
                "SNAPSHOT_RENAMED",
                renamed,
                "Novo nome: " + displayName
        );

        player.sendMessage(
                "§b[EchoCraft] §fMemória #"
                        + snapshotId
                        + " renomeada para: §d"
                        + displayName
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.8F,
                1.7F
        );
    }


    private void renameTimeline(
            Player player,
            long snapshotId,
            String timelineName
    ) {
        if (timelineName.isBlank()) {
            player.sendMessage(
                    "§c[EchoCraft] O nome da linha temporal "
                            + "não pode estar vazio."
            );
            return;
        }

        if (timelineName.length() > 40) {
            player.sendMessage(
                    "§c[EchoCraft] O nome da linha temporal "
                            + "precisa ter no máximo 40 caracteres."
            );
            return;
        }

        TemporalSnapshot reference =
                findSnapshotById(snapshotId);

        if (reference == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        long timelineId =
                reference.timelineId();

        try {
            databaseManager.updateTimelineDisplayName(
                    timelineId,
                    timelineName
            );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel renomear "
                            + "a linha temporal.",
                    exception
            );

            player.sendMessage(
                    "§c[EchoCraft] Não foi possível salvar "
                            + "o nome da linha temporal."
            );
            return;
        }

        Deque<TemporalSnapshot> renamedSnapshots =
                new ArrayDeque<>();

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.timelineId()
                    != timelineId) {

                renamedSnapshots.addLast(snapshot);
                continue;
            }

            renamedSnapshots.addLast(
                    new TemporalSnapshot(
                            snapshot.id(),
                            snapshot.timelineId(),
                            snapshot.versionNumber(),
                            timelineName,
                            snapshot.type(),
                            snapshot.cause(),
                            snapshot.center(),
                            snapshot.createdAt(),
                            snapshot.displayName(),
                            snapshot.blocks(),
                            snapshot.traces(),
                            snapshot.actions()
                    )
            );
        }

        snapshots.clear();
        snapshots.addAll(renamedSnapshots);

        TemporalSnapshot renamedReference =
                findSnapshotById(snapshotId);

        auditTemporalOperation(
                player,
                "TIMELINE_RENAMED",
                renamedReference,
                "Novo nome: " + timelineName
        );

        player.sendMessage(
                "§d[EchoCraft] §fLinha temporal #"
                        + timelineId
                        + " renomeada para: §d"
                        + timelineName
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_BEACON_ACTIVATE,
                0.8F,
                1.55F
        );
    }

    private void listEchoMemories(
            Player player
    ) {
        removeExpiredSnapshots();

        List<TemporalSnapshot> nearbySnapshots =
                findNearbySnapshots(player);

        if (nearbySnapshots.isEmpty()) {
            player.sendMessage(
                    "§c[EchoCraft] Nenhuma memória temporal próxima."
            );
            return;
        }

        player.sendMessage(
                "§b§l[EchoCraft] §fMemórias temporais próximas:"
        );

        int limit =
                Math.min(
                        nearbySnapshots.size(),
                        8
                );

        for (int index = 0;
             index < limit;
             index++) {

            TemporalSnapshot snapshot =
                    nearbySnapshots.get(index);

            double distance =
                    Math.sqrt(
                            snapshot.center()
                                    .distanceSquared(
                                            player.getLocation()
                                    )
                    );

            player.sendMessage(
                    "§7#"
                            + snapshot.id()
                            + " §8- §d"
                            + getSnapshotTitle(snapshot)
                            + " §8| §5L#"
                            + snapshot.timelineId()
                            + " V"
                            + snapshot.versionNumber()
                            + " §8| §f"
                            + formatMemoryCause(snapshot)
                            + " §8| §b"
                            + snapshot.blocks().size()
                            + " blocos §8| §7"
                            + Math.round(distance)
                            + "m"
            );
        }

        if (nearbySnapshots.size() > limit) {
            player.sendMessage(
                    "§8Mostrando "
                            + limit
                            + " de "
                            + nearbySnapshots.size()
                            + " memórias próximas."
            );
        }
    }

    private void showEchoInfo(
            Player player,
            long snapshotId
    ) {
        TemporalSnapshot snapshot =
                findSnapshotById(snapshotId);

        if (snapshot == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        player.sendMessage(
                "§b§l[EchoCraft] §fInformações da memória:"
        );

        player.sendMessage(
                "§7Nome: §d"
                        + getSnapshotTitle(snapshot)
        );

        player.sendMessage(
                "§7ID temporal: §f#"
                        + snapshot.id()
        );

        player.sendMessage(
                "§7Linha temporal: §d"
                        + getTimelineTitle(snapshot)
                        + " §8(#"
                        + snapshot.timelineId()
                        + ")"
        );

        player.sendMessage(
                "§7Versão: §f"
                        + snapshot.versionNumber()
        );

        player.sendMessage(
                "§7Causa: §f"
                        + formatMemoryCause(snapshot)
        );

        player.sendMessage(
                "§7Idade: §f"
                        + formatAge(snapshot.createdAt())
        );

        player.sendMessage(
                "§7Blocos originais: §b"
                        + snapshot.blocks().size()
        );

        player.sendMessage(
                "§7Blocos alterados/desaparecidos agora: §d"
                        + findMissingBlocks(snapshot).size()
        );

        player.sendMessage(
                "§7Rastros salvos: §b"
                        + snapshot.traces().size()
        );

        player.sendMessage(
                "§7Ações salvas: §d"
                        + snapshot.actions().size()
        );

        Location center =
                snapshot.center();

        player.sendMessage(
                "§7Centro: §f"
                        + center.getBlockX()
                        + ", "
                        + center.getBlockY()
                        + ", "
                        + center.getBlockZ()
        );
    }

    private void deleteEchoMemory(
            Player player,
            long snapshotId,
            boolean confirmed
    ) {
        TemporalSnapshot snapshot =
                findSnapshotById(snapshotId);

        if (snapshot == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        UUID playerId =
                player.getUniqueId();

        long now =
                System.currentTimeMillis();

        if (!confirmed) {
            pendingDeletions.put(
                    playerId,
                    new PendingDeletion(
                            snapshotId,
                            now
                    )
            );

            player.sendMessage(
                    "§c[EchoCraft] Atenção: você está prestes a apagar:"
            );

            player.sendMessage(
                    "§7#"
                            + snapshot.id()
                            + " §8- §d"
                            + getSnapshotTitle(snapshot)
            );

            player.sendMessage(
                    "§eUse §f/echodelete "
                            + snapshotId
                            + " confirm §eem até 30 segundos para confirmar."
            );

            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_BASS,
                    0.8F,
                    0.8F
            );

            return;
        }

        PendingDeletion pendingDeletion =
                pendingDeletions.get(playerId);

        if (pendingDeletion == null
                || pendingDeletion.snapshotId() != snapshotId) {

            player.sendMessage(
                    "§c[EchoCraft] Primeiro use: §f/echodelete "
                            + snapshotId
            );

            player.sendMessage(
                    "§7Depois confirme com: §f/echodelete "
                            + snapshotId
                            + " confirm"
            );

            return;
        }

        if (now - pendingDeletion.requestedAt()
                > DELETE_CONFIRMATION_WINDOW_MILLIS) {

            pendingDeletions.remove(playerId);

            player.sendMessage(
                    "§c[EchoCraft] A confirmação expirou."
            );

            player.sendMessage(
                    "§7Use novamente: §f/echodelete "
                            + snapshotId
            );

            return;
        }

        try {
            boolean deleted =
                    databaseManager.deleteSnapshot(snapshotId);

            if (!deleted) {
                player.sendMessage(
                        "§c[EchoCraft] Essa memória não foi encontrada no banco."
                );
                return;
            }

        } catch (SQLException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Nao foi possivel apagar "
                            + "a memoria temporal.",
                    exception
            );

            player.sendMessage(
                    "§c[EchoCraft] Não foi possível apagar a memória no banco."
            );
            return;
        }

        pendingDeletions.remove(playerId);

        snapshots.remove(snapshot);

        clearActiveEchoes();

        auditTemporalOperation(
                player,
                "SNAPSHOT_DELETED",
                snapshot,
                "Nome: " + getSnapshotTitle(snapshot)
        );

        player.sendMessage(
                "§b[EchoCraft] §fMemória #"
                        + snapshotId
                        + " apagada permanentemente: §d"
                        + getSnapshotTitle(snapshot)
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                0.8F,
                1.2F
        );
    }

    private List<TemporalSnapshot> findTimeline(
            TemporalSnapshot reference
    ) {
        List<TemporalSnapshot> timeline =
                new ArrayList<>();

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.timelineId()
                    != reference.timelineId()) {
                continue;
            }

            timeline.add(snapshot);
        }

        timeline.sort(
                Comparator.comparingInt(
                        TemporalSnapshot::versionNumber
                )
        );

        return List.copyOf(timeline);
    }

    private void showEchoTimeline(
            Player player,
            long snapshotId,
            int requestedPage
    ) {
        removeExpiredSnapshots();

        TemporalSnapshot reference =
                findSnapshotById(snapshotId);

        if (reference == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        World referenceWorld =
                reference.center().getWorld();

        if (referenceWorld == null) {
            player.sendMessage(
                    "§c[EchoCraft] O mundo dessa memória "
                            + "não está disponível."
            );
            return;
        }

        List<TemporalSnapshot> timeline =
                findTimeline(reference);

        if (timeline.isEmpty()) {
            player.sendMessage(
                    "§c[EchoCraft] Nenhuma versão temporal encontrada."
            );
            return;
        }

        int totalPages =
                Math.max(
                        1,
                        (timeline.size()
                                + TIMELINE_ITEMS_PER_PAGE
                                - 1)
                                / TIMELINE_ITEMS_PER_PAGE
                );

        int page =
                Math.max(
                        1,
                        Math.min(
                                requestedPage,
                                totalPages
                        )
                );

        int startIndex =
                (page - 1)
                        * TIMELINE_ITEMS_PER_PAGE;

        int endIndex =
                Math.min(
                        startIndex
                                + TIMELINE_ITEMS_PER_PAGE,
                        timeline.size()
                );

        player.sendMessage(
                "§5§m--------------------------------"
        );

        player.sendMessage(
                "§d§l[EchoCraft] §fLinha do Tempo"
        );

        player.sendMessage(
                "§7Linha: §d"
                        + getTimelineTitle(reference)
                        + " §8(#"
                        + reference.timelineId()
                        + ")"
        );

        player.sendMessage(
                "§7Referência: §f"
                        + getSnapshotTitle(reference)
                        + " §8(#"
                        + reference.id()
                        + ", versão "
                        + reference.versionNumber()
                        + ")"
        );

        player.sendMessage(
                "§7Versões encontradas: §b"
                        + timeline.size()
                        + " §8| §7Página §f"
                        + page
                        + "§7/§f"
                        + totalPages
        );

        player.sendMessage(" ");

        for (int index = startIndex;
             index < endIndex;
             index++) {

            TemporalSnapshot snapshot =
                    timeline.get(index);

            int versionNumber =
                    snapshot.versionNumber();

            int changedBlocks =
                    findMissingBlocks(snapshot).size();

            String marker =
                    snapshot.id() == reference.id()
                            ? "§e▶ "
                            : "§8• ";

            String state;

            if (changedBlocks == 0) {
                state =
                        "§aestável";

            } else {
                state =
                        "§d"
                                + changedBlocks
                                + " divergências";
            }

            player.sendMessage(
                    marker
                            + "§7Versão "
                            + versionNumber
                            + " §8— §f#"
                            + snapshot.id()
                            + " §8— §d"
                            + getSnapshotTitle(snapshot)
            );

            player.sendMessage(
                    "  §8↳ §7"
                            + formatAge(snapshot.createdAt())
                            + " §8| §b"
                            + snapshot.blocks().size()
                            + " blocos §8| "
                            + state
            );
        }

        player.sendMessage(" ");

        player.sendMessage(
                "§7Detalhes: §f/echoinfo <id>"
        );

        player.sendMessage(
                "§7Revelar: §f/echo §8ou use a EchoLens"
        );

        player.sendMessage(
                "§7Materializar: §f/echorestore <id>"
        );

        player.sendMessage(
                "§7Renomear linha: §f/echotimelinename "
                        + reference.id()
                        + " <nome>"
        );

        if (totalPages > 1) {
            player.sendMessage(
                    "§7Outra página: §f/echotimeline "
                            + snapshotId
                            + " <pagina>"
            );
        }

        player.sendMessage(
                "§5§m--------------------------------"
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.8F,
                1.5F
        );
    }

    private TemporalSnapshot findSnapshotById(
            long snapshotId
    ) {
        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.id() == snapshotId) {
                return snapshot;
            }
        }

        return null;
    }

    public void handlePanelCommand(
            long commandId,
            String commandType,
            long snapshotId
    ) {
        if (!"restore_snapshot".equals(
                commandType
        )) {
            if (echoPanelClient != null) {
                echoPanelClient.completeCommand(
                        commandId,
                        false,
                        "Tipo de comando nao suportado: "
                                + commandType,
                        0,
                        0
                );
            }
            return;
        }

        restoreEchoMemoryFromPanel(
                commandId,
                snapshotId
        );
    }

    private void restoreEchoMemoryFromPanel(
            long commandId,
            long snapshotId
    ) {
        removeExpiredSnapshots();

        TemporalSnapshot snapshot =
                findSnapshotById(snapshotId);

        if (snapshot == null) {
            completePanelRestore(
                    commandId,
                    false,
                    "Memoria #"
                            + snapshotId
                            + " nao encontrada no servidor.",
                    0,
                    0
            );
            return;
        }

        if (activeRestoreTimelines.contains(
                snapshot.timelineId()
        )) {
            completePanelRestore(
                    commandId,
                    false,
                    "A linha temporal #"
                            + snapshot.timelineId()
                            + " ja esta sendo restaurada.",
                    0,
                    0
            );
            return;
        }

        World snapshotWorld =
                snapshot.center().getWorld();

        if (snapshotWorld == null) {
            completePanelRestore(
                    commandId,
                    false,
                    "O mundo da memoria #"
                            + snapshotId
                            + " nao esta disponivel.",
                    0,
                    0
            );
            return;
        }

        List<BlockMemory> changedBlocks =
                findMissingBlocks(snapshot);

        if (changedBlocks.isEmpty()) {
            completePanelRestore(
                    commandId,
                    true,
                    "A construcao ja estava igual a memoria #"
                            + snapshotId
                            + ".",
                    0,
                    0
            );
            return;
        }

        getLogger().info(
                "EchoPanel solicitou a restauracao da memoria #"
                        + snapshotId
                        + " com "
                        + changedBlocks.size()
                        + " bloco(s)."
        );

        beginTemporalRestore(
                null,
                commandId,
                snapshot,
                List.copyOf(changedBlocks)
        );
    }

    private void completePanelRestore(
            long commandId,
            boolean successful,
            String message,
            int restoredBlocks,
            int failures
    ) {
        if (echoPanelClient == null) {
            return;
        }

        echoPanelClient.completeCommand(
                commandId,
                successful,
                message,
                restoredBlocks,
                failures
        );
    }

    private void restoreEchoMemory(
            Player player,
            long snapshotId,
            boolean confirmed
    ) {
        removeExpiredSnapshots();

        TemporalSnapshot snapshot =
                findSnapshotById(snapshotId);

        if (snapshot == null) {
            player.sendMessage(
                    "§c[EchoCraft] Memória #"
                            + snapshotId
                            + " não encontrada."
            );
            return;
        }

        if (activeRestoreTimelines.contains(
                snapshot.timelineId()
        )) {
            player.sendMessage(
                    "§c[EchoCraft] Essa linha temporal já está "
                            + "sendo restaurada por outro processo."
            );
            return;
        }

        World snapshotWorld =
                snapshot.center().getWorld();

        if (snapshotWorld == null) {
            player.sendMessage(
                    "§c[EchoCraft] O mundo dessa memória "
                            + "não está disponível."
            );
            return;
        }

        if (!snapshotWorld.getUID().equals(
                player.getWorld().getUID()
        )) {
            player.sendMessage(
                    "§c[EchoCraft] Essa memória pertence "
                            + "a outro mundo."
            );
            return;
        }

        double radiusSquared =
                SNAPSHOT_SEARCH_RADIUS
                        * SNAPSHOT_SEARCH_RADIUS;

        if (snapshot.center().distanceSquared(
                player.getLocation()
        ) > radiusSquared) {
            player.sendMessage(
                    "§c[EchoCraft] Você precisa estar próximo "
                            + "da memória para restaurá-la."
            );
            return;
        }

        List<BlockMemory> changedBlocks =
                findMissingBlocks(snapshot);

        if (changedBlocks.isEmpty()) {
            player.sendMessage(
                    "§e[EchoCraft] Essa construção já está "
                            + "igual à memória selecionada."
            );
            return;
        }

        UUID playerId =
                player.getUniqueId();

        long now =
                System.currentTimeMillis();

        if (!confirmed) {
            pendingRestores.put(
                    playerId,
                    new PendingRestore(
                            snapshotId,
                            now
                    )
            );

            player.sendMessage(
                    "§d§l[EchoCraft] RESTAURAÇÃO TEMPORAL"
            );

            player.sendMessage(
                    "§7Memória: §f#"
                            + snapshot.id()
                            + " §8- §d"
                            + getSnapshotTitle(snapshot)
            );

            player.sendMessage(
                    "§7Blocos que serão reescritos: §d"
                            + changedBlocks.size()
            );

            player.sendMessage(
                    "§cAtenção: blocos atuais nessas posições "
                            + "serão substituídos."
            );

            player.sendMessage(
                    "§eUse §f/echorestore "
                            + snapshotId
                            + " confirm §eem até 30 segundos."
            );

            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_BEACON_DEACTIVATE,
                    0.8F,
                    0.75F
            );

            return;
        }

        PendingRestore pending =
                pendingRestores.get(playerId);

        if (pending == null
                || pending.snapshotId() != snapshotId) {

            player.sendMessage(
                    "§c[EchoCraft] Primeiro use: §f"
                            + "/echorestore "
                            + snapshotId
            );
            return;
        }

        if (now - pending.requestedAt()
                > RESTORE_CONFIRMATION_WINDOW_MILLIS) {

            pendingRestores.remove(playerId);

            player.sendMessage(
                    "§c[EchoCraft] A confirmação da restauração expirou."
            );

            player.sendMessage(
                    "§7Use novamente: §f/echorestore "
                            + snapshotId
            );
            return;
        }

        pendingRestores.remove(playerId);

        beginTemporalRestore(
                player,
                null,
                snapshot,
                List.copyOf(changedBlocks)
        );
    }

    private void beginTemporalRestore(
            Player player,
            Long panelCommandId,
            TemporalSnapshot snapshot,
            List<BlockMemory> changedBlocks
    ) {
        long timelineId = snapshot.timelineId();

        if (!activeRestoreTimelines.add(timelineId)) {
            if (player != null) {
                player.sendMessage(
                        "§c[EchoCraft] Essa linha temporal já está "
                                + "sendo restaurada."
                );
            }

            if (panelCommandId != null) {
                completePanelRestore(
                        panelCommandId,
                        false,
                        "A linha temporal #"
                                + timelineId
                                + " ja esta sendo restaurada.",
                        0,
                        0
                );
            }
            return;
        }

        clearActiveEchoes();

        auditTemporalOperation(
                player,
                "RESTORE_STARTED",
                snapshot,
                "Blocos solicitados: " + changedBlocks.size()
        );

        if (player != null) {
            player.sendTitle(
                    "§d§lREESCREVENDO O TEMPO",
                    "§f" + getSnapshotTitle(snapshot),
                    10,
                    40,
                    10
            );

            player.sendMessage(
                    "§5[EchoCraft] §7Restauração iniciada: §d"
                            + changedBlocks.size()
                            + " blocos§7."
            );

            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                    1.2F,
                    0.55F
            );
        }

        spawnCinemaPulse(
                snapshot.center()
                        .clone()
                        .add(0.0, 1.0, 0.0),
                45,
                0.8
        );

        BukkitRunnable restoreRunnable =
                new BukkitRunnable() {

                    private int index;
                    private int failedBlocks;

                    private final List<BlockMemory>
                            successfullyRestored =
                            new ArrayList<>();

                    @Override
                    public void run() {
                        if (shuttingDown
                                || !EchoCraftPlugin.this.isEnabled()) {

                            cancel();
                            releaseRestoreLock(timelineId);

                            if (panelCommandId != null) {
                                completePanelRestore(
                                        panelCommandId,
                                        false,
                                        "O servidor foi desligado durante "
                                                + "a restauracao.",
                                        successfullyRestored.size(),
                                        failedBlocks
                                );
                            }
                            return;
                        }

                        try {
                            int endIndex =
                                    Math.min(
                                            index
                                                    + RESTORE_BLOCKS_PER_TICK,
                                            changedBlocks.size()
                                    );

                            while (index < endIndex) {
                                BlockMemory memory =
                                        changedBlocks.get(index);

                                if (restoreBlockWithoutPhysics(memory)) {
                                    successfullyRestored.add(memory);
                                } else {
                                    failedBlocks++;
                                }

                                index++;
                            }

                            if (index < changedBlocks.size()) {
                                return;
                            }

                            cancel();
                            activeRestoreTasks.remove(timelineId);

                            getServer().getScheduler().runTaskLater(
                                    EchoCraftPlugin.this,
                                    () -> finalizeTemporalRestore(
                                            player,
                                            panelCommandId,
                                            snapshot,
                                            List.copyOf(
                                                    successfullyRestored
                                            ),
                                            failedBlocks
                                    ),
                                    1L
                            );

                        } catch (RuntimeException exception) {
                            cancel();
                            releaseRestoreLock(timelineId);

                            getLogger().log(
                                    Level.SEVERE,
                                    "A restauracao temporal da linha #"
                                            + timelineId
                                            + " foi interrompida.",
                                    exception
                            );

                            auditTemporalOperation(
                                    player,
                                    "RESTORE_FAILED",
                                    snapshot,
                                    exception.getClass().getSimpleName()
                            );

                            if (player != null
                                    && player.isOnline()) {
                                player.sendMessage(
                                        "§c[EchoCraft] A restauração foi "
                                                + "interrompida com segurança."
                                );
                            }

                            if (panelCommandId != null) {
                                completePanelRestore(
                                        panelCommandId,
                                        false,
                                        "A restauracao da memoria #"
                                                + snapshot.id()
                                                + " foi interrompida.",
                                        successfullyRestored.size(),
                                        failedBlocks
                                );
                            }
                        }
                    }
                };

        BukkitTask task = restoreRunnable.runTaskTimer(
                this,
                0L,
                1L
        );

        activeRestoreTasks.put(timelineId, task);
    }

    private boolean restoreBlockWithoutPhysics(
            BlockMemory memory
    ) {
        Location location = memory.location();
        World world = location.getWorld();

        if (world == null) {
            return false;
        }

        try {
            Block block = world.getBlockAt(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );

            block.setBlockData(
                    memory.blockData().clone(),
                    false
            );

            return true;

        } catch (RuntimeException exception) {
            getLogger().log(
                    Level.WARNING,
                    "Nao foi possivel restaurar o bloco em "
                            + formatLocation(location),
                    exception
            );

            return false;
        }
    }

    private void finalizeTemporalRestore(
            Player player,
            Long panelCommandId,
            TemporalSnapshot snapshot,
            List<BlockMemory> restoredBlocks,
            int previousFailures
    ) {
        long timelineId = snapshot.timelineId();

        try {
            List<DatabaseManager.StoredPosition> positions =
                    new ArrayList<>();

            List<BlockMemory> finalizedBlocks =
                    new ArrayList<>();

            int finalPassFailures = 0;

            for (BlockMemory memory : restoredBlocks) {
                Location location = memory.location();
                World world = location.getWorld();

                if (world == null) {
                    finalPassFailures++;
                    continue;
                }

                try {
                    Block block = world.getBlockAt(
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()
                    );

                    block.setBlockData(
                            memory.blockData().clone(),
                            false
                    );

                    BlockKey key = new BlockKey(
                            world.getUID(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()
                    );

                    playerPlacedBlocks.add(key);

                    positions.add(
                            new DatabaseManager.StoredPosition(
                                    key.worldId(),
                                    key.x(),
                                    key.y(),
                                    key.z()
                            )
                    );

                    finalizedBlocks.add(memory);

                } catch (RuntimeException exception) {
                    finalPassFailures++;

                    if (finalPassFailures
                            <= MAX_RESTORE_FAILURES_REPORTED) {

                        getLogger().log(
                                Level.WARNING,
                                "Falha na passagem final do bloco em "
                                        + formatLocation(location),
                                exception
                        );
                    }
                }
            }

            try {
                databaseManager.savePlacedBlocks(positions);

            } catch (SQLException exception) {
                getLogger().log(
                        Level.SEVERE,
                        "A estrutura foi restaurada, mas os blocos "
                                + "nao foram salvos no rastreamento.",
                        exception
                );

                if (player != null
                        && player.isOnline()) {
                    player.sendMessage(
                            "§c[EchoCraft] A estrutura voltou, mas houve "
                                    + "um erro ao atualizar o banco."
                    );
                }
            }

            Location center = snapshot.center()
                    .clone()
                    .add(0.0, 1.0, 0.0);

            World world = center.getWorld();

            if (world != null) {
                world.spawnParticle(
                        Particle.END_ROD,
                        center,
                        45,
                        0.8,
                        0.8,
                        0.8,
                        0.025
                );

                world.spawnParticle(
                        Particle.REVERSE_PORTAL,
                        center,
                        70,
                        1.0,
                        1.0,
                        1.0,
                        0.045
                );
            }

            int totalFailures =
                    previousFailures + finalPassFailures;

            if (player != null
                    && player.isOnline()) {
                player.sendTitle(
                        "§b§lLINHA TEMPORAL RESTAURADA",
                        "§f"
                                + finalizedBlocks.size()
                                + " blocos reescritos",
                        5,
                        35,
                        15
                );

                player.sendMessage(
                        "§b[EchoCraft] §f"
                                + getSnapshotTitle(snapshot)
                                + " foi materializada no presente."
                );

                if (totalFailures > 0) {
                    player.sendMessage(
                            "§e[EchoCraft] "
                                    + totalFailures
                                    + " bloco(s) não puderam ser "
                                    + "restaurados. Veja o console."
                    );
                }

                player.playSound(
                        player.getLocation(),
                        Sound.BLOCK_BEACON_ACTIVATE,
                        1.0F,
                        1.35F
                );

                player.playSound(
                        player.getLocation(),
                        Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                        1.1F,
                        1.7F
                );
            }

            Set<BlockKey> restoredStructure =
                    toBlockKeys(snapshot.blocks());

            TemporalSnapshot restoredVersion =
                    createSnapshot(
                            MemoryType.RESTORE,
                            "RESTORE_FROM_" + snapshot.id(),
                            snapshot.center(),
                            restoredStructure,
                            snapshot.timelineId()
                    );

            if (restoredVersion != null
                    && player != null
                    && player.isOnline()) {

                player.sendMessage(
                        "§d[EchoCraft] §7Nova versão registrada: §f"
                                + restoredVersion.versionNumber()
                                + " §8na linha #"
                                + restoredVersion.timelineId()
                                + "§7."
                );
            }

            auditTemporalOperation(
                    player,
                    "RESTORE_COMPLETED",
                    snapshot,
                    "Restaurados: "
                            + finalizedBlocks.size()
                            + "; falhas: "
                            + totalFailures
            );

            if (echoPanelClient != null
                    && !finalizedBlocks.isEmpty()) {

                echoPanelClient.sendBlockReconstruction(
                        player,
                        snapshot.center(),
                        finalizedBlocks.size(),
                        snapshot.id(),
                        snapshot.timelineId(),
                        snapshot.versionNumber(),
                        snapshot.timelineName(),
                        getSnapshotTitle(snapshot),
                        snapshot.type().name(),
                        snapshot.blocks().size(),
                        snapshot.traces().size(),
                        snapshot.actions().size(),
                        snapshot.cause()
                );

                sendSnapshotPlayerTracesToEchoPanel(
                        snapshot
                );
            }

            if (panelCommandId != null) {
                boolean successful =
                        !finalizedBlocks.isEmpty();

                completePanelRestore(
                        panelCommandId,
                        successful,
                        successful
                                ? "Memoria #"
                                + snapshot.id()
                                + " restaurada pelo EchoPanel."
                                : "Nenhum bloco da memoria #"
                                + snapshot.id()
                                + " pode ser restaurado.",
                        finalizedBlocks.size(),
                        totalFailures
                );
            }

        } catch (RuntimeException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Falha ao finalizar a restauracao da linha #"
                            + timelineId,
                    exception
            );

            auditTemporalOperation(
                    player,
                    "RESTORE_FAILED",
                    snapshot,
                    "Falha na finalizacao: "
                            + exception.getClass().getSimpleName()
            );

            if (player != null
                    && player.isOnline()) {
                player.sendMessage(
                        "§c[EchoCraft] A restauração terminou "
                                + "com uma falha de finalização."
                );
            }

            if (panelCommandId != null) {
                completePanelRestore(
                        panelCommandId,
                        false,
                        "Falha ao finalizar a restauracao da memoria #"
                                + snapshot.id()
                                + ": "
                                + exception.getClass().getSimpleName(),
                        0,
                        previousFailures
                );
            }

        } finally {
            releaseRestoreLock(timelineId);
        }
    }


    private void sendSnapshotPlayerTracesToEchoPanel(
            TemporalSnapshot snapshot
    ) {
        if (echoPanelClient == null
                || snapshot.traces().isEmpty()) {
            return;
        }

        Map<UUID, List<PlayerTrace>> tracesByPlayer =
                new LinkedHashMap<>();

        for (PlayerTrace trace : snapshot.traces()) {
            tracesByPlayer
                    .computeIfAbsent(
                            trace.playerId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(trace);
        }

        for (Map.Entry<UUID, List<PlayerTrace>> entry :
                tracesByPlayer.entrySet()) {

            List<PlayerTrace> traces =
                    entry.getValue();

            if (traces.isEmpty()) {
                continue;
            }

            PlayerTrace lastTrace =
                    traces.get(traces.size() - 1);

            int actionsCount = 0;

            for (PlayerAction action : snapshot.actions()) {
                if (!action.playerId().equals(
                        entry.getKey()
                )) {
                    continue;
                }

                actionsCount++;
            }

            echoPanelClient.sendPlayerTrace(
                    entry.getKey(),
                    lastTrace.playerName(),
                    lastTrace.location(),
                    traces.size(),
                    actionsCount,
                    snapshot.id(),
                    snapshot.timelineId(),
                    snapshot.versionNumber(),
                    snapshot.timelineName(),
                    getSnapshotTitle(snapshot),
                    snapshot.type().name(),
                    snapshot.blocks().size(),
                    snapshot.traces().size(),
                    snapshot.actions().size(),
                    snapshot.cause()
            );
        }
    }


    private void releaseRestoreLock(
            long timelineId
    ) {
        BukkitTask task = activeRestoreTasks.remove(timelineId);

        if (task != null
                && !task.isCancelled()) {
            task.cancel();
        }

        activeRestoreTimelines.remove(timelineId);
    }

    private String formatLocation(
            Location location
    ) {
        World world = location.getWorld();

        String worldName =
                world == null
                        ? "mundo-desconhecido"
                        : world.getName();

        return worldName
                + " "
                + location.getBlockX()
                + ","
                + location.getBlockY()
                + ","
                + location.getBlockZ();
    }

    private void auditTemporalOperation(
            Player player,
            String actionType,
            TemporalSnapshot snapshot,
            String details
    ) {
        UUID actorId =
                player == null
                        ? null
                        : player.getUniqueId();

        String actorName =
                player == null
                        ? "SYSTEM"
                        : player.getName();

        Long snapshotId =
                snapshot == null
                        ? null
                        : snapshot.id();

        Long timelineId =
                snapshot == null
                        ? null
                        : snapshot.timelineId();

        getLogger().info(
                "[AUDIT] "
                        + actorName
                        + " -> "
                        + actionType
                        + " | snapshot="
                        + snapshotId
                        + " | timeline="
                        + timelineId
                        + " | "
                        + details
        );

        try {
            databaseManager.saveAuditEvent(
                    actorId,
                    actorName,
                    actionType,
                    snapshotId,
                    timelineId,
                    details
            );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.WARNING,
                    "Nao foi possivel salvar o evento de auditoria.",
                    exception
            );
        }
    }

    public void revealSnapshotById(
            Player player,
            long snapshotId
    ) {
        removeExpiredSnapshots();

        TemporalSnapshot selectedSnapshot = null;

        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.id() == snapshotId) {
                selectedSnapshot = snapshot;
                break;
            }
        }

        if (selectedSnapshot == null) {
            player.sendMessage(
                    "§c[EchoCraft] Essa memória "
                            + "não está mais disponível."
            );

            return;
        }

        World snapshotWorld =
                selectedSnapshot.center().getWorld();

        if (snapshotWorld == null
                || !snapshotWorld.getUID().equals(
                player.getWorld().getUID()
        )) {

            player.sendMessage(
                    "§c[EchoCraft] Essa memória pertence "
                            + "a outro mundo."
            );

            return;
        }

        double radiusSquared =
                SNAPSHOT_SEARCH_RADIUS
                        * SNAPSHOT_SEARCH_RADIUS;

        if (selectedSnapshot.center()
                .distanceSquared(player.getLocation())
                > radiusSquared) {

            player.sendMessage(
                    "§c[EchoCraft] Tu se afastou demais "
                            + "da memória."
            );

            return;
        }

        startCinemaReveal(
                player,
                selectedSnapshot
        );
    }


    private void revealEcho(Player player) {
        removeExpiredSnapshots();

        TemporalSnapshot snapshot =
                findBestSnapshot(player);

        if (snapshot == null) {
            player.sendMessage(
                    "§c[EchoCraft] Nenhuma fotografia "
                            + "temporal foi encontrada por perto."
            );

            return;
        }

        revealSnapshot(player, snapshot);
    }

    private void startCinemaReveal(
            Player player,
            TemporalSnapshot snapshot
    ) {
        clearActiveEchoes();

        Location playerLocation =
                player.getLocation().clone();

        Location snapshotCenter =
                snapshot.center().clone();

        player.sendTitle(
                "§b§lECHOLENS",
                "§7Sincronizando memória temporal...",
                10,
                35,
                10
        );

        player.sendActionBar(
                Component.text(
                        "O mundo ao redor começa a lembrar...",
                        NamedTextColor.DARK_AQUA
                )
        );

        player.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.DARKNESS,
                        (int) CINEMA_EFFECT_DURATION_TICKS,
                        0
                )
        );

        player.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        (int) CINEMA_REVEAL_DELAY_TICKS,
                        4
                )
        );

        player.playSound(
                playerLocation,
                Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                1.2F,
                0.55F
        );

        player.playSound(
                playerLocation,
                Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                0.9F,
                0.7F
        );

        for (int index = 0;
             index < 7;
             index++) {

            long delay =
                    index * 5L;

            int particleAmount =
                    8 + index * 4;

            double spread =
                    0.25 + index * 0.08;

            getServer().getScheduler().runTaskLater(
                    this,
                    () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        spawnCinemaPulse(
                                player.getLocation()
                                        .clone()
                                        .add(0.0, 1.0, 0.0),
                                particleAmount,
                                spread
                        );

                        spawnCinemaPulse(
                                snapshotCenter
                                        .clone()
                                        .add(0.0, 1.0, 0.0),
                                particleAmount,
                                spread
                        );
                    },
                    delay
            );
        }

        getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    player.playSound(
                            player.getLocation(),
                            Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                            1.0F,
                            1.4F
                    );

                    playMemoryStabilizedEffect(
                            player,
                            snapshot
                    );

                    revealSnapshot(
                            player,
                            snapshot
                    );
                },
                CINEMA_REVEAL_DELAY_TICKS
        );
    }

    private void spawnCinemaPulse(
            Location location,
            int amount,
            double spread
    ) {
        World world =
                location.getWorld();

        if (world == null) {
            return;
        }

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                location,
                amount,
                spread,
                spread,
                spread,
                0.035
        );

        world.spawnParticle(
                Particle.END_ROD,
                location,
                Math.max(1, amount / 8),
                spread * 0.45,
                spread * 0.45,
                spread * 0.45,
                0.008
        );

        world.spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                location,
                Math.max(1, amount / 10),
                spread * 0.35,
                spread * 0.35,
                spread * 0.35,
                0.006
        );
    }

    private void playMemoryStabilizedEffect(
            Player player,
            TemporalSnapshot snapshot
    ) {
        Location center =
                snapshot.center().clone()
                        .add(0.0, 1.0, 0.0);

        World world =
                center.getWorld();

        player.sendTitle(
                "§d§lMEMÓRIA ESTABILIZADA",
                "§f"
                        + getSnapshotTitle(snapshot)
                        + " sincronizada",
                5,
                30,
                10
        );

        player.sendActionBar(
                Component.text(
                        "O passado foi ancorado ao presente.",
                        NamedTextColor.LIGHT_PURPLE
                )
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_BEACON_ACTIVATE,
                0.8F,
                1.6F
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                1.0F,
                1.9F
        );

        if (world == null) {
            return;
        }

        world.spawnParticle(
                Particle.END_ROD,
                center,
                28,
                0.55,
                0.55,
                0.55,
                0.02
        );

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                45,
                0.75,
                0.75,
                0.75,
                0.04
        );

        world.spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                center,
                16,
                0.35,
                0.55,
                0.35,
                0.015
        );
    }

    private void revealSnapshot(
            Player player,
            TemporalSnapshot snapshot
    ) {
        List<BlockMemory> changedBlocks =
                findMissingBlocks(snapshot);

        if (changedBlocks.isEmpty()) {
            player.sendMessage(
                    "§e[EchoCraft] A estrutura dessa memória "
                            + "ainda está inteira no presente."
            );

            return;
        }

        auditTemporalOperation(
                player,
                "SNAPSHOT_REVEALED",
                snapshot,
                "Blocos divergentes: " + changedBlocks.size()
        );

        clearActiveEchoes();

        player.sendMessage(
                "§b[EchoCraft] §f"
                        + getSnapshotTitle(snapshot)
                        + " selecionada."
        );

        player.sendMessage(
                "§7Causa: §f"
                        + formatMemoryCause(snapshot)
        );

        player.sendMessage(
                "§7Estrutura original: §b"
                        + snapshot.blocks().size()
                        + " blocos§7."
        );

        player.sendMessage(
                "§7Reconstruindo: §d"
                        + changedBlocks.size()
                        + " blocos alterados ou desaparecidos§7."
        );

        player.playSound(
                player.getLocation(),
                Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                1.3F,
                0.65F
        );

        revealPlayerTrail(player, snapshot);

        for (int index = 0;
             index < changedBlocks.size();
             index++) {

            BlockMemory memory =
                    changedBlocks.get(index);

            long delay = index / 10L;

            getServer()
                    .getScheduler()
                    .runTaskLater(
                            this,
                            () -> spawnEcho(
                                    player,
                                    memory
                            ),
                            delay
                    );
        }

        long finishDelay =
                ((changedBlocks.size() - 1)
                        / 10L) + 8L;

        getServer()
                .getScheduler()
                .runTaskLater(
                        this,
                        () -> {
                            if (!player.isOnline()) {
                                return;
                            }

                            player.playSound(
                                    player.getLocation(),
                                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                                    1.4F,
                                    0.75F
                            );

                            player.sendMessage(
                                    "§d[EchoCraft] §fO estado anterior "
                                            + "da construção foi revelado."
                            );
                        },
                        finishDelay
                );
    }

    public void recordPlayerAction(
            Player player,
            PlayerActionType type,
            Block block
    ) {
        if (block == null) {
            return;
        }

        Location location =
                block.getLocation().clone();

        long now =
                System.currentTimeMillis();

        playerActions.add(
                new PlayerAction(
                        player.getUniqueId(),
                        player.getName(),
                        type,
                        location,
                        block.getType().name(),
                        player.getInventory()
                                .getItemInMainHand()
                                .getType()
                                .name(),
                        now,
                        1
                )
        );

        playerActions.removeIf(
                action ->
                        now - action.createdAt()
                                > PLAYER_ACTION_MEMORY_MILLIS
        );
    }



    private List<PlayerTrace> buildSnapshotTraces(
            Location center,
            long snapshotCreatedAt
    ) {
        World centerWorld = center.getWorld();

        if (centerWorld == null) {
            return List.of();
        }

        UUID worldId = centerWorld.getUID();
        double radiusSquared =
                TRAIL_SEARCH_RADIUS
                        * TRAIL_SEARCH_RADIUS;

        long startTime =
                snapshotCreatedAt
                        - TRAIL_BEFORE_SNAPSHOT_MILLIS;

        long endTime =
                snapshotCreatedAt
                        + TRAIL_AFTER_SNAPSHOT_MILLIS;

        List<PlayerTrace> selectedTraces =
                new ArrayList<>();

        for (PlayerTrace trace : playerTraces) {
            Location traceLocation = trace.location();
            World traceWorld = traceLocation.getWorld();

            if (traceWorld == null) {
                continue;
            }

            if (!traceWorld.getUID().equals(worldId)) {
                continue;
            }

            if (trace.createdAt() < startTime
                    || trace.createdAt() > endTime) {
                continue;
            }

            if (traceLocation.distanceSquared(center)
                    > radiusSquared) {
                continue;
            }

            selectedTraces.add(trace);
        }

        selectedTraces.sort(
                Comparator.comparingLong(
                        PlayerTrace::createdAt
                )
        );

        if (selectedTraces.size() > MAX_TRAIL_POINTS) {
            selectedTraces =
                    selectedTraces.subList(
                            selectedTraces.size()
                                    - MAX_TRAIL_POINTS,
                            selectedTraces.size()
                    );
        }

        return List.copyOf(selectedTraces);
    }

    private List<PlayerAction> buildSnapshotActions(
            Location center,
            long snapshotCreatedAt
    ) {
        World centerWorld = center.getWorld();

        if (centerWorld == null) {
            return List.of();
        }

        UUID worldId = centerWorld.getUID();
        double radiusSquared =
                TRAIL_SEARCH_RADIUS
                        * TRAIL_SEARCH_RADIUS;

        long startTime =
                snapshotCreatedAt
                        - TRAIL_BEFORE_SNAPSHOT_MILLIS;

        long endTime =
                snapshotCreatedAt
                        + TRAIL_AFTER_SNAPSHOT_MILLIS;

        List<PlayerAction> selectedActions =
                new ArrayList<>();

        for (PlayerAction action : playerActions) {
            Location actionLocation = action.location();
            World actionWorld = actionLocation.getWorld();

            if (actionWorld == null) {
                continue;
            }

            if (!actionWorld.getUID().equals(worldId)) {
                continue;
            }

            if (action.createdAt() < startTime
                    || action.createdAt() > endTime) {
                continue;
            }

            if (actionLocation.distanceSquared(center)
                    > radiusSquared) {
                continue;
            }

            selectedActions.add(action);
        }

        selectedActions.sort(
                Comparator.comparingLong(
                        PlayerAction::createdAt
                )
        );

        selectedActions =
                compactPlayerActions(selectedActions);

        if (selectedActions.size() > MAX_REPLAY_ACTIONS) {
            selectedActions =
                    selectedActions.subList(
                            selectedActions.size()
                                    - MAX_REPLAY_ACTIONS,
                            selectedActions.size()
                    );
        }

        return List.copyOf(selectedActions);
    }

    private List<DatabaseManager.StoredTrace> toStoredTraces(
            long snapshotCreatedAt,
            List<PlayerTrace> traces
    ) {
        List<DatabaseManager.StoredTrace> storedTraces =
                new ArrayList<>();

        for (PlayerTrace trace : traces) {
            Location location = trace.location();
            World world = location.getWorld();

            if (world == null) {
                continue;
            }

            storedTraces.add(
                    new DatabaseManager.StoredTrace(
                            trace.playerId(),
                            trace.playerName(),
                            world.getUID(),
                            location.getX(),
                            location.getY(),
                            location.getZ(),
                            trace.yaw(),
                            trace.pitch(),
                            trace.itemInHand(),
                            trace.createdAt(),
                            trace.createdAt()
                                    - snapshotCreatedAt
                    )
            );
        }

        return storedTraces;
    }

    private List<DatabaseManager.StoredAction> toStoredActions(
            long snapshotCreatedAt,
            List<PlayerAction> actions
    ) {
        List<DatabaseManager.StoredAction> storedActions =
                new ArrayList<>();

        for (PlayerAction action : actions) {
            Location location = action.location();
            World world = location.getWorld();

            if (world == null) {
                continue;
            }

            storedActions.add(
                    new DatabaseManager.StoredAction(
                            action.playerId(),
                            action.playerName(),
                            action.type().name(),
                            world.getUID(),
                            location.getX(),
                            location.getY(),
                            location.getZ(),
                            action.blockType(),
                            action.itemInHand(),
                            action.createdAt(),
                            action.createdAt()
                                    - snapshotCreatedAt,
                            action.amount()
                    )
            );
        }

        return storedActions;
    }

    private List<PlayerTrace> fromStoredTraces(
            List<DatabaseManager.StoredTrace> storedTraces
    ) {
        List<PlayerTrace> traces =
                new ArrayList<>();

        for (DatabaseManager.StoredTrace stored : storedTraces) {
            World world =
                    getServer().getWorld(
                            stored.worldId()
                    );

            if (world == null) {
                continue;
            }

            traces.add(
                    new PlayerTrace(
                            stored.playerId(),
                            stored.playerName(),
                            new Location(
                                    world,
                                    stored.x(),
                                    stored.y(),
                                    stored.z()
                            ),
                            stored.yaw(),
                            stored.pitch(),
                            stored.itemInHand(),
                            stored.createdAt()
                    )
            );
        }

        return List.copyOf(traces);
    }

    private List<PlayerAction> fromStoredActions(
            List<DatabaseManager.StoredAction> storedActions
    ) {
        List<PlayerAction> actions =
                new ArrayList<>();

        for (DatabaseManager.StoredAction stored : storedActions) {
            World world =
                    getServer().getWorld(
                            stored.worldId()
                    );

            if (world == null) {
                continue;
            }

            PlayerActionType type;

            try {
                type = PlayerActionType.valueOf(
                        stored.actionType()
                );

            } catch (IllegalArgumentException exception) {
                continue;
            }

            actions.add(
                    new PlayerAction(
                            stored.playerId(),
                            stored.playerName(),
                            type,
                            new Location(
                                    world,
                                    stored.x(),
                                    stored.y(),
                                    stored.z()
                            ),
                            stored.blockType(),
                            stored.itemInHand(),
                            stored.createdAt(),
                            stored.amount()
                    )
            );
        }

        return List.copyOf(actions);
    }

    private TemporalSnapshot findBestSnapshot(
            Player player
    ) {
        Location playerLocation = player.getLocation();
        UUID playerWorldId = player.getWorld().getUID();

        double searchRadiusSquared =
                SNAPSHOT_SEARCH_RADIUS
                        * SNAPSHOT_SEARCH_RADIUS;

        TemporalSnapshot newestExplosion = null;
        TemporalSnapshot newestManualSnapshot = null;

        /*
         * Primeiro encontra a explosão mais recente
         * que aconteceu perto do jogador.
         */
        for (TemporalSnapshot snapshot : snapshots) {
            Location center = snapshot.center();
            World centerWorld = center.getWorld();

            if (centerWorld == null) {
                continue;
            }

            if (!centerWorld.getUID().equals(playerWorldId)) {
                continue;
            }

            if (center.distanceSquared(playerLocation)
                    > searchRadiusSquared) {
                continue;
            }

            if (snapshot.type() == MemoryType.EXPLOSION) {
                newestExplosion = snapshot;
                break;
            }

            if (newestManualSnapshot == null) {
                newestManualSnapshot = snapshot;
            }
        }

        /*
         * Se não houve explosão próxima,
         * utiliza a quebra manual mais recente.
         */
        if (newestExplosion == null) {
            return newestManualSnapshot;
        }

        long chainEndTime = newestExplosion.createdAt();

        double chainRadiusSquared =
                EXPLOSION_CHAIN_RADIUS
                        * EXPLOSION_CHAIN_RADIUS;

        TemporalSnapshot bestSnapshot = newestExplosion;

        /*
         * Verifica todas as explosões ocorridas poucos
         * segundos antes da última.
         *
         * Entre elas, seleciona a fotografia que contém
         * a maior quantidade de blocos, normalmente a
         * primeira fotografia feita antes da destruição.
         */
        for (TemporalSnapshot snapshot : snapshots) {
            if (snapshot.type() != MemoryType.EXPLOSION) {
                continue;
            }

            long timeDifference =
                    chainEndTime - snapshot.createdAt();

            if (timeDifference < 0L) {
                continue;
            }

            if (timeDifference
                    > EXPLOSION_CHAIN_WINDOW_MILLIS) {
                continue;
            }

            Location snapshotCenter = snapshot.center();
            World snapshotWorld = snapshotCenter.getWorld();

            if (snapshotWorld == null) {
                continue;
            }

            if (!snapshotWorld.getUID().equals(playerWorldId)) {
                continue;
            }

            if (snapshotCenter.distanceSquared(
                    newestExplosion.center()
            ) > chainRadiusSquared) {
                continue;
            }

            int snapshotSize = snapshot.blocks().size();
            int bestSize = bestSnapshot.blocks().size();

            if (snapshotSize > bestSize) {
                bestSnapshot = snapshot;
                continue;
            }

            /*
             * Se as duas têm o mesmo tamanho,
             * preferimos a fotografia mais antiga.
             */
            if (snapshotSize == bestSize
                    && snapshot.createdAt()
                    < bestSnapshot.createdAt()) {

                bestSnapshot = snapshot;
            }
        }

        getLogger().info(
                "Cadeia de explosoes detectada. "
                        + "Fotografia selecionada: #"
                        + bestSnapshot.id()
                        + " com "
                        + bestSnapshot.blocks().size()
                        + " blocos."
        );

        return bestSnapshot;
    }

    private List<BlockMemory> findMissingBlocks(
            TemporalSnapshot snapshot
    ) {
        List<BlockMemory> changedBlocks =
                new ArrayList<>();

        for (BlockMemory memory : snapshot.blocks()) {
            Location location = memory.location();
            World world = location.getWorld();

            if (world == null) {
                continue;
            }

            Block currentBlock = world.getBlockAt(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );

            String currentState =
                    currentBlock.getBlockData().getAsString();

            String historicalState =
                    memory.blockData().getAsString();

            /*
             * Não verificamos apenas se virou ar.
             * Também reconstruímos blocos cujo estado mudou,
             * como escadas, portas, cercas e muros.
             */
            if (currentState.equals(historicalState)) {
                continue;
            }

            changedBlocks.add(memory);
        }

        return changedBlocks;
    }

    private void revealPlayerTrail(
            Player viewer,
            TemporalSnapshot snapshot
    ) {
        long startTime =
                snapshot.createdAt()
                        - TRAIL_BEFORE_SNAPSHOT_MILLIS;

        long endTime =
                snapshot.createdAt()
                        + TRAIL_AFTER_SNAPSHOT_MILLIS;

        Location snapshotCenter =
                snapshot.center();

        World snapshotWorld =
                snapshotCenter.getWorld();

        if (snapshotWorld == null) {
            return;
        }

        UUID worldId =
                snapshotWorld.getUID();

        double radiusSquared =
                TRAIL_SEARCH_RADIUS
                        * TRAIL_SEARCH_RADIUS;

        List<PlayerTrace> selectedTraces =
                new ArrayList<>(snapshot.traces());

        if (selectedTraces.isEmpty()) {
            selectedTraces =
                    buildSnapshotTraces(
                            snapshotCenter,
                            snapshot.createdAt()
                    );
        }

        if (selectedTraces.isEmpty()) {
            selectedTraces = new ArrayList<>();

            for (PlayerTrace trace : playerTraces) {
                Location traceLocation =
                        trace.location();

                World traceWorld =
                        traceLocation.getWorld();

                if (traceWorld == null) {
                    continue;
                }

                if (!traceWorld.getUID().equals(worldId)) {
                    continue;
                }

                if (trace.createdAt() < startTime
                        || trace.createdAt() > endTime) {
                    continue;
                }

                if (traceLocation.distanceSquared(snapshotCenter)
                        > radiusSquared) {
                    continue;
                }

                selectedTraces.add(trace);
            }

            selectedTraces.sort(
                    Comparator.comparingLong(
                            PlayerTrace::createdAt
                    )
            );

            if (selectedTraces.size() > MAX_TRAIL_POINTS) {
                selectedTraces =
                        selectedTraces.subList(
                                selectedTraces.size()
                                        - MAX_TRAIL_POINTS,
                                selectedTraces.size()
                        );
            }
        }

        if (selectedTraces.isEmpty()) {
            return;
        }

        long replayStartTime =
                selectedTraces.get(0).createdAt();

        List<PlayerAction> selectedActions =
                new ArrayList<>(snapshot.actions());

        if (selectedActions.isEmpty()) {
            selectedActions =
                    findPlayerActions(
                            snapshot,
                            startTime,
                            endTime,
                            worldId,
                            radiusSquared
                    );
        } else {
            selectedActions =
                    compactPlayerActions(selectedActions);
        }

        viewer.sendMessage(
                "§5[EchoCraft] §7Rastro temporal detectado: §d"
                        + selectedTraces.size()
                        + " fragmentos§7."
        );

        if (!selectedActions.isEmpty()) {
            viewer.sendMessage(
                    "§5[EchoCraft] §7Ações sincronizadas: §d"
                            + selectedActions.size()
                            + " eventos§7."
            );
        }

        viewer.playSound(
                viewer.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.8F,
                1.6F
        );

        spawnTemporalGhost(
                selectedTraces,
                selectedActions,
                replayStartTime
        );

        int traceStep =
                selectedTraces.size()
                        > TRACE_PARTICLE_STEP_THRESHOLD
                        ? 2
                        : 1;

        for (int index = 0;
             index < selectedTraces.size();
             index += traceStep) {

            PlayerTrace trace =
                    selectedTraces.get(index);

            long delay = index * 2L;

            getServer().getScheduler().runTaskLater(
                    this,
                    () -> spawnTraceFragment(trace),
                    delay
            );
        }
    }

    private List<PlayerAction> findPlayerActions(
            TemporalSnapshot snapshot,
            long startTime,
            long endTime,
            UUID worldId,
            double radiusSquared
    ) {
        List<PlayerAction> selectedActions =
                new ArrayList<>();

        Location snapshotCenter =
                snapshot.center();

        for (PlayerAction action : playerActions) {
            Location actionLocation =
                    action.location();

            World actionWorld =
                    actionLocation.getWorld();

            if (actionWorld == null) {
                continue;
            }

            if (!actionWorld.getUID().equals(worldId)) {
                continue;
            }

            if (action.createdAt() < startTime
                    || action.createdAt() > endTime) {
                continue;
            }

            if (actionLocation.distanceSquared(snapshotCenter)
                    > radiusSquared) {
                continue;
            }

            selectedActions.add(action);
        }

        selectedActions.sort(
                Comparator.comparingLong(
                        PlayerAction::createdAt
                )
        );

        selectedActions =
                compactPlayerActions(selectedActions);

        if (selectedActions.size() > MAX_REPLAY_ACTIONS) {
            selectedActions =
                    selectedActions.subList(
                            selectedActions.size()
                                    - MAX_REPLAY_ACTIONS,
                            selectedActions.size()
                    );
        }

        return selectedActions;
    }

    private List<PlayerAction> compactPlayerActions(
            List<PlayerAction> actions
    ) {
        if (actions.isEmpty()) {
            return actions;
        }

        List<PlayerAction> compacted =
                new ArrayList<>();

        PlayerAction current =
                actions.get(0);

        for (int index = 1;
             index < actions.size();
             index++) {

            PlayerAction next =
                    actions.get(index);

            if (canMergeActions(current, next)) {
                current =
                        mergeActions(current, next);
                continue;
            }

            compacted.add(current);
            current = next;
        }

        compacted.add(current);

        return compacted;
    }

    private boolean canMergeActions(
            PlayerAction first,
            PlayerAction second
    ) {
        if (first.type() != second.type()) {
            return false;
        }

        if (!first.playerId().equals(second.playerId())) {
            return false;
        }

        if (!first.blockType().equals(second.blockType())) {
            return false;
        }

        long timeDifference =
                second.createdAt() - first.createdAt();

        if (timeDifference < 0L
                || timeDifference > 1_200L) {
            return false;
        }

        World firstWorld =
                first.location().getWorld();

        World secondWorld =
                second.location().getWorld();

        if (firstWorld == null
                || secondWorld == null) {
            return false;
        }

        if (!firstWorld.getUID().equals(
                secondWorld.getUID()
        )) {
            return false;
        }

        return first.location()
                .distanceSquared(second.location())
                <= 4.0;
    }

    private PlayerAction mergeActions(
            PlayerAction first,
            PlayerAction second
    ) {
        return new PlayerAction(
                first.playerId(),
                first.playerName(),
                first.type(),
                second.location(),
                first.blockType(),
                second.itemInHand(),
                second.createdAt(),
                first.amount() + second.amount()
        );
    }

    private void replayPlayerActions(
            Player viewer,
            List<PlayerAction> actions,
            long replayStartTime
    ) {
        if (actions.isEmpty()) {
            return;
        }

        viewer.sendMessage(
                "§5[EchoCraft] §7Ações do passado detectadas: §d"
                        + actions.size()
                        + " eventos§7."
        );

        for (PlayerAction action : actions) {
            long timeDifference =
                    Math.max(
                            0L,
                            action.createdAt()
                                    - replayStartTime
                    );

            /*
             * O replay é acelerado.
             * 500 ms do passado viram 2 ticks no presente.
             */
            long delay =
                    timeDifference / 250L;

            getServer()
                    .getScheduler()
                    .runTaskLater(
                            this,
                            () -> spawnActionEcho(action),
                            delay
                    );
        }
    }

    private void spawnActionEcho(
            PlayerAction action
    ) {
        Location location =
                action.location().clone()
                        .add(0.5, 0.5, 0.5);

        World world =
                location.getWorld();

        if (world == null) {
            return;
        }

        float pitch;

        switch (action.type()) {
            case PLACE_BLOCK -> {
                pitch = 1.45F;

                world.spawnParticle(
                        Particle.END_ROD,
                        location,
                        10,
                        0.18,
                        0.18,
                        0.18,
                        0.018
                );

                world.spawnParticle(
                        Particle.REVERSE_PORTAL,
                        location,
                        6,
                        0.25,
                        0.25,
                        0.25,
                        0.015
                );
            }

            case BREAK_BLOCK -> {
                pitch = 0.75F;

                world.spawnParticle(
                        Particle.SOUL_FIRE_FLAME,
                        location,
                        7,
                        0.18,
                        0.18,
                        0.18,
                        0.018
                );

                world.spawnParticle(
                        Particle.REVERSE_PORTAL,
                        location,
                        10,
                        0.25,
                        0.25,
                        0.25,
                        0.018
                );
            }

            case IGNITE_BLOCK -> {
                pitch = 1.95F;

                world.spawnParticle(
                        Particle.FLAME,
                        location,
                        10,
                        0.18,
                        0.18,
                        0.18,
                        0.012
                );

                world.spawnParticle(
                        Particle.REVERSE_PORTAL,
                        location,
                        7,
                        0.25,
                        0.25,
                        0.25,
                        0.015
                );
            }

            default -> {
                pitch = 1.0F;

                world.spawnParticle(
                        Particle.END_ROD,
                        location,
                        8,
                        0.2,
                        0.2,
                        0.2,
                        0.01
                );
            }
        }

        world.playSound(
                location,
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.8F,
                pitch
        );
    }

    private void spawnTemporalGhost(
            List<PlayerTrace> traces,
            List<PlayerAction> actions,
            long replayStartTime
    ) {
        if (traces.isEmpty()) {
            return;
        }

        PlayerTrace firstTrace = traces.getFirst();

        Location startLocation =
                firstTrace.location().clone();

        World world =
                startLocation.getWorld();

        if (world == null) {
            return;
        }

        startLocation.setYaw(firstTrace.yaw());
        startLocation.setPitch(firstTrace.pitch());

        world.playSound(
                startLocation,
                Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                0.9F,
                1.8F
        );

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                startLocation.clone().add(0.0, 1.0, 0.0),
                35,
                0.45,
                0.8,
                0.45,
                0.04
        );

        ArmorStand ghost = world.spawn(
                startLocation,
                ArmorStand.class,
                entity -> {
                    entity.customName(
                            Component.text(
                                    "Eco de "
                                            + firstTrace.playerName(),
                                    NamedTextColor.AQUA
                            )
                    );

                    entity.setCustomNameVisible(true);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.setCollidable(false);
                    entity.setBasePlate(false);
                    entity.setArms(true);
                    entity.setSmall(false);
                    entity.setGlowing(true);

                    entity.setHeadPose(
                            new EulerAngle(
                                    Math.toRadians(
                                            firstTrace.pitch()
                                    ),
                                    0.0,
                                    0.0
                            )
                    );

                    entity.setRightArmPose(
                            new EulerAngle(
                                    Math.toRadians(-25),
                                    0.0,
                                    Math.toRadians(8)
                            )
                    );

                    entity.getEquipment().setHelmet(
                            createGhostArmor(
                                    Material.LEATHER_HELMET
                            )
                    );

                    entity.getEquipment().setChestplate(
                            createGhostArmor(
                                    Material.LEATHER_CHESTPLATE
                            )
                    );

                    entity.getEquipment().setLeggings(
                            createGhostArmor(
                                    Material.LEATHER_LEGGINGS
                            )
                    );

                    entity.getEquipment().setBoots(
                            createGhostArmor(
                                    Material.LEATHER_BOOTS
                            )
                    );

                    entity.getEquipment().setItemInMainHand(
                            createGhostHeldItem(
                                    firstTrace.itemInHand()
                            )
                    );
                }
        );

        activeGhosts.add(ghost);

        for (int index = 0;
             index < traces.size();
             index++) {

            PlayerTrace trace = traces.get(index);
            long delay = index * 2L;

            int animationIndex = index;

            getServer().getScheduler().runTaskLater(
                    this,
                    () -> {
                        if (!ghost.isValid()) {
                            return;
                        }

                        Location location =
                                trace.location().clone();

                        location.setYaw(trace.yaw());
                        location.setPitch(trace.pitch());

                        ghost.teleport(location);

                        ghost.setHeadPose(
                                new EulerAngle(
                                        Math.toRadians(
                                                trace.pitch()
                                        ),
                                        0.0,
                                        0.0
                                )
                        );

                        double armSwing =
                                Math.sin(animationIndex * 0.55)
                                        * 18.0;

                        ghost.setRightArmPose(
                                new EulerAngle(
                                        Math.toRadians(
                                                -25.0 + armSwing
                                        ),
                                        0.0,
                                        Math.toRadians(8)
                                )
                        );

                        ghost.setLeftArmPose(
                                new EulerAngle(
                                        Math.toRadians(
                                                25.0 - armSwing
                                        ),
                                        0.0,
                                        Math.toRadians(-8)
                                )
                        );

                        ghost.getEquipment().setItemInMainHand(
                                createGhostHeldItem(
                                        trace.itemInHand()
                                )
                        );

                        World traceWorld =
                                location.getWorld();

                        if (traceWorld == null) {
                            return;
                        }

                        Location chest =
                                location.clone()
                                        .add(0.0, 1.0, 0.0);

                        Location head =
                                location.clone()
                                        .add(0.0, 1.75, 0.0);

                        if (animationIndex % 2 == 0) {
                            traceWorld.spawnParticle(
                                    Particle.REVERSE_PORTAL,
                                    chest,
                                    4,
                                    0.18,
                                    0.32,
                                    0.18,
                                    0.015
                            );
                        }

                        if (animationIndex % 4 == 0) {
                            traceWorld.spawnParticle(
                                    Particle.END_ROD,
                                    head,
                                    1,
                                    0.04,
                                    0.06,
                                    0.04,
                                    0.004
                            );
                        }

                        if (animationIndex % 8 == 0) {
                            traceWorld.playSound(
                                    location,
                                    Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                                    0.25F,
                                    1.8F
                            );
                        }
                    },
                    delay
            );
        }

        for (PlayerAction action : actions) {
            long timeDifference =
                    Math.max(
                            0L,
                            action.createdAt()
                                    - replayStartTime
                    );

            long delay =
                    timeDifference / 250L;

            getServer().getScheduler().runTaskLater(
                    this,
                    () -> performGhostAction(
                            ghost,
                            action
                    ),
                    delay
            );
        }

        long removeDelay =
                traces.size() * 2L + 60L;

        getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    if (ghost.isValid()) {
                        Location location =
                                ghost.getLocation();

                        World ghostWorld =
                                location.getWorld();

                        if (ghostWorld != null) {
                            ghostWorld.playSound(
                                    location,
                                    Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                                    0.9F,
                                    1.6F
                            );

                            ghostWorld.spawnParticle(
                                    Particle.REVERSE_PORTAL,
                                    location.clone()
                                            .add(0.0, 1.0, 0.0),
                                    45,
                                    0.45,
                                    0.8,
                                    0.45,
                                    0.04
                            );

                            ghostWorld.spawnParticle(
                                    Particle.SOUL_FIRE_FLAME,
                                    location.clone()
                                            .add(0.0, 1.0, 0.0),
                                    18,
                                    0.25,
                                    0.55,
                                    0.25,
                                    0.02
                            );
                        }

                        ghost.remove();
                    }

                    activeGhosts.remove(ghost);
                },
                removeDelay
        );
    }

    private void performGhostAction(
            ArmorStand ghost,
            PlayerAction action
    ) {
        if (!ghost.isValid()) {
            return;
        }

        Location actionLocation =
                action.location().clone();

        World world =
                actionLocation.getWorld();

        if (world == null) {
            return;
        }

        Location ghostLocation =
                ghost.getLocation();

        double dx =
                actionLocation.getX()
                        - ghostLocation.getX();

        double dz =
                actionLocation.getZ()
                        - ghostLocation.getZ();

        float yaw =
                (float) Math.toDegrees(
                        Math.atan2(-dx, dz)
                );

        ghostLocation.setYaw(yaw);
        ghost.teleport(ghostLocation);

        ghost.getEquipment().setItemInMainHand(
                createGhostHeldItem(
                        action.itemInHand()
                )
        );

        ghost.setRightArmPose(
                new EulerAngle(
                        Math.toRadians(-80),
                        0.0,
                        Math.toRadians(12)
                )
        );

        spawnActionEcho(action);
        spawnActionLabel(action);

        playActionSound(
                world,
                actionLocation,
                action
        );

        getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    if (!ghost.isValid()) {
                        return;
                    }

                    ghost.setRightArmPose(
                            new EulerAngle(
                                    Math.toRadians(-25),
                                    0.0,
                                    Math.toRadians(8)
                            )
                    );
                },
                8L
        );
    }

    private void playActionSound(
            World world,
            Location location,
            PlayerAction action
    ) {
        switch (action.type()) {
            case PLACE_BLOCK -> world.playSound(
                    location,
                    Sound.BLOCK_AMETHYST_BLOCK_PLACE,
                    0.45F,
                    1.5F
            );

            case BREAK_BLOCK -> world.playSound(
                    location,
                    Sound.BLOCK_AMETHYST_BLOCK_BREAK,
                    0.45F,
                    0.8F
            );

            case IGNITE_BLOCK -> world.playSound(
                    location,
                    Sound.ITEM_FLINTANDSTEEL_USE,
                    0.55F,
                    1.35F
            );
        }
    }

    private void spawnActionLabel(
            PlayerAction action
    ) {
        double height =
                getActionLabelHeight(action);

        Location location =
                action.location().clone()
                        .add(0.5, height, 0.5);

        World world =
                location.getWorld();

        if (world == null) {
            return;
        }

        ArmorStand label = world.spawn(
                location,
                ArmorStand.class,
                entity -> {
                    entity.customName(
                            Component.text(
                                            formatActionLabel(action),
                                            getActionLabelColor(action)
                                    )
                                    .decorate(TextDecoration.BOLD)
                                    .decoration(
                                            TextDecoration.ITALIC,
                                            false
                                    )
                    );

                    entity.setCustomNameVisible(true);
                    entity.setInvisible(true);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.setCollidable(false);
                    entity.setBasePlate(false);
                    entity.setMarker(true);
                }
        );

        activeGhosts.add(label);

        world.spawnParticle(
                Particle.END_ROD,
                location,
                5,
                0.12,
                0.12,
                0.12,
                0.008
        );

        getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    if (label.isValid()) {
                        label.remove();
                    }

                    activeGhosts.remove(label);
                },
                ACTION_LABEL_DURATION_TICKS
        );
    }

    private String formatActionLabel(
            PlayerAction action
    ) {
        String blockName =
                action.blockType()
                        .toLowerCase(Locale.ROOT)
                        .replace('_', ' ');

        String suffix =
                action.amount() > 1
                        ? " x" + action.amount()
                        : "";

        String text =
                switch (action.type()) {
                    case PLACE_BLOCK ->
                            "COLOCOU " + blockName + suffix;

                    case BREAK_BLOCK ->
                            "QUEBROU " + blockName + suffix;

                    case IGNITE_BLOCK ->
                            "ACENDEU " + blockName + suffix;
                };

        return text.toUpperCase(Locale.ROOT);
    }

    private double getActionLabelHeight(
            PlayerAction action
    ) {
        return switch (action.type()) {
            case PLACE_BLOCK -> 1.20;
            case BREAK_BLOCK -> 1.50;
            case IGNITE_BLOCK -> 1.80;
        };
    }

    private NamedTextColor getActionLabelColor(
            PlayerAction action
    ) {
        return switch (action.type()) {
            case PLACE_BLOCK ->
                    NamedTextColor.AQUA;

            case BREAK_BLOCK ->
                    NamedTextColor.RED;

            case IGNITE_BLOCK ->
                    NamedTextColor.GOLD;
        };
    }

    private ItemStack createGhostArmor(
            Material material
    ) {
        ItemStack item =
                new ItemStack(material);

        LeatherArmorMeta meta =
                (LeatherArmorMeta) item.getItemMeta();

        meta.setColor(
                Color.fromRGB(
                        70,
                        210,
                        255
                )
        );

        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createGhostHeldItem(
            String materialName
    ) {
        try {
            Material material =
                    Material.valueOf(materialName);

            if (material.isAir()) {
                return new ItemStack(Material.AIR);
            }

            return new ItemStack(material);

        } catch (IllegalArgumentException exception) {
            return new ItemStack(Material.AIR);
        }
    }

    private void spawnTraceFragment(
            PlayerTrace trace
    ) {
        Location location =
                trace.location().clone();

        World world =
                location.getWorld();

        if (world == null) {
            return;
        }

        Location feet =
                location.clone().add(0.0, 0.15, 0.0);

        Location head =
                location.clone().add(0.0, 1.55, 0.0);

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                feet,
                4,
                0.14,
                0.05,
                0.14,
                0.015
        );

        world.spawnParticle(
                Particle.END_ROD,
                head,
                1,
                0.05,
                0.08,
                0.05,
                0.005
        );

        world.spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                location.clone().add(0.0, 0.9, 0.0),
                1,
                0.03,
                0.14,
                0.03,
                0.003
        );
    }

    private void spawnEcho(
            Player player,
            BlockMemory memory
    ) {
        Location location = memory.location().clone();
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        Block realBlock = world.getBlockAt(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );

        boolean wasDestroyed = realBlock.getType().isAir();

        /*
         * Caso exista um bloco real alterado nessa posição,
         * escondemos ele apenas para o jogador que usou /echo.
         *
         * O mundo não é modificado de verdade.
         */
        if (!realBlock.getType().isAir()
                && player.isOnline()) {

            player.sendBlockChange(
                    location,
                    Material.AIR.createBlockData()
            );
        }

        BlockDisplay display = world.spawn(
                location,
                BlockDisplay.class,
                entity -> {
                    entity.setBlock(memory.blockData());
                    entity.setGlowing(wasDestroyed);

                    if (wasDestroyed) {
                        entity.setGlowColorOverride(Color.AQUA);
                    }
                    if (wasDestroyed) {
                        entity.setBrightness(
                                new Display.Brightness(15, 15)
                        );
                    } else {
                        entity.setBrightness(null);
                    }
                    entity.setViewRange(64.0F);
                    entity.setShadowRadius(0.0F);
                    entity.setShadowStrength(0.0F);
                    entity.setPersistent(false);
                }
        );

        activeDisplays.add(display);

        if (wasDestroyed) {
            getServer().getScheduler().runTaskLater(
                    this,
                    () -> {
                        if (display.isValid()) {
                            display.setGlowing(false);
                        }
                    },
                    50L
            );
        }

        Location center =
                location.clone().add(0.5, 0.5, 0.5);

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                6,
                0.3,
                0.3,
                0.3,
                0.025
        );

        world.spawnParticle(
                Particle.END_ROD,
                center,
                2,
                0.2,
                0.2,
                0.2,
                0.01
        );

        getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    if (display.isValid()) {
                        world.spawnParticle(
                                Particle.REVERSE_PORTAL,
                                center,
                                4,
                                0.25,
                                0.25,
                                0.25,
                                0.02
                        );

                        display.remove();
                    }

                    activeDisplays.remove(display);

                    /*
                     * Quando o eco termina, mostramos novamente
                     * o estado verdadeiro e atual do bloco.
                     */
                    if (player != null
                    && player.isOnline()) {
                        Block currentBlock = world.getBlockAt(
                                location.getBlockX(),
                                location.getBlockY(),
                                location.getBlockZ()
                        );

                        player.sendBlockChange(
                                location,
                                currentBlock.getBlockData()
                        );
                    }
                },
                DISPLAY_DURATION_TICKS
        );
    }

    private void clearActiveEchoes() {
        for (BlockDisplay display :
                new ArrayList<>(activeDisplays)) {

            if (display.isValid()) {
                display.remove();
            }
        }

        activeDisplays.clear();

        for (ArmorStand ghost :
                new ArrayList<>(activeGhosts)) {

            if (ghost.isValid()) {
                ghost.remove();
            }
        }

        activeGhosts.clear();
    }

    private void removeExpiredSnapshots() {
        long oldestAllowedTime =
                System.currentTimeMillis()
                        - SNAPSHOT_LIFETIME_MILLIS;

        snapshots.removeIf(
                snapshot ->
                        snapshot.createdAt()
                                < oldestAllowedTime
        );

        try {
            databaseManager.deleteExpiredSnapshots(
                    oldestAllowedTime
            );

        } catch (SQLException exception) {
            getLogger().log(
                    Level.WARNING,
                    "Nao foi possivel limpar "
                            + "fotografias expiradas.",
                    exception
            );
        }
    }

    private BlockKey keyOf(Block block) {
        Location location = block.getLocation();

        return new BlockKey(
                block.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private enum MemoryType {
        EXPLOSION,
        MANUAL_BREAK,
        RESTORE
    }

    private record BlockKey(
            UUID worldId,
            int x,
            int y,
            int z
    ) {
    }

    private record BlockMemory(
            Location location,
            BlockData blockData
    ) {
    }

    private record TemporalSnapshot(
            long id,
            long timelineId,
            int versionNumber,
            String timelineName,
            MemoryType type,
            String cause,
            Location center,
            long createdAt,
            String displayName,
            List<BlockMemory> blocks,
            List<PlayerTrace> traces,
            List<PlayerAction> actions
    ) {
    }

    private record TimelineAssignment(
            long timelineId,
            int versionNumber,
            String timelineName
    ) {
    }

    public enum PlayerActionType {
        PLACE_BLOCK,
        BREAK_BLOCK,
        IGNITE_BLOCK
    }

    private record PlayerAction(
            UUID playerId,
            String playerName,
            PlayerActionType type,
            Location location,
            String blockType,
            String itemInHand,
            long createdAt,
            int amount
    ) {
    }

    private record PlayerTrace(
            UUID playerId,
            String playerName,
            Location location,
            float yaw,
            float pitch,
            String itemInHand,
            long createdAt
    ) {
    }

    private record PendingDeletion(
            long snapshotId,
            long requestedAt
    ) {
    }

    private record PendingRestore(
            long snapshotId,
            long requestedAt
    ) {
    }
}