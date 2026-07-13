package me.paulohenrique.echocraft;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class DatabaseManager {

    private static final double LEGACY_TIMELINE_LINK_RADIUS =
            12.0;

    private static final int CURRENT_SCHEMA_VERSION = 6;
    private static final int MAX_DATABASE_BACKUPS = 5;

    private final JavaPlugin plugin;
    private String jdbcUrl;
    private File databaseFile;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() throws SQLException {
        createPluginFolder();
        loadSQLiteDriver();

        databaseFile = new File(
                plugin.getDataFolder(),
                "echocraft.db"
        );

        createStartupBackup(databaseFile);

        jdbcUrl = "jdbc:sqlite:"
                + databaseFile.getAbsolutePath();

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_metadata (
                        meta_key TEXT PRIMARY KEY,
                        meta_value TEXT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        action_type TEXT NOT NULL,
                        snapshot_id INTEGER,
                        timeline_id INTEGER,
                        details TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS placed_blocks (
                        world_uuid TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,

                        PRIMARY KEY (world_uuid, x, y, z)
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS timelines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        world_uuid TEXT NOT NULL,
                        display_name TEXT,
                        anchor_x REAL NOT NULL,
                        anchor_y REAL NOT NULL,
                        anchor_z REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timeline_id INTEGER,
                        version_number INTEGER NOT NULL DEFAULT 1,
                        memory_type TEXT NOT NULL,
                        cause TEXT NOT NULL,
                        world_uuid TEXT NOT NULL,
                        center_x REAL NOT NULL,
                        center_y REAL NOT NULL,
                        center_z REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        display_name TEXT,

                        FOREIGN KEY (timeline_id)
                            REFERENCES timelines(id)
                            ON DELETE SET NULL
                    )
                    """);

            ensureSnapshotDisplayNameColumn(statement);
            ensureSnapshotTimelineColumns(statement);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS snapshot_blocks (
                        snapshot_id INTEGER NOT NULL,
                        world_uuid TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        block_data TEXT NOT NULL,

                        PRIMARY KEY (
                            snapshot_id,
                            world_uuid,
                            x,
                            y,
                            z
                        ),

                        FOREIGN KEY (snapshot_id)
                            REFERENCES snapshots(id)
                            ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS snapshot_traces (
                        snapshot_id INTEGER NOT NULL,
                        sequence_index INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        item_in_hand TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        relative_time INTEGER NOT NULL,

                        PRIMARY KEY (snapshot_id, sequence_index),

                        FOREIGN KEY (snapshot_id)
                            REFERENCES snapshots(id)
                            ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS snapshot_actions (
                        snapshot_id INTEGER NOT NULL,
                        sequence_index INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        action_type TEXT NOT NULL,
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        block_type TEXT NOT NULL,
                        item_in_hand TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        relative_time INTEGER NOT NULL,
                        amount INTEGER NOT NULL,

                        PRIMARY KEY (snapshot_id, sequence_index),

                        FOREIGN KEY (snapshot_id)
                            REFERENCES snapshots(id)
                            ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_snapshots_created_at
                    ON snapshots(created_at DESC)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_snapshots_timeline_id
                    ON snapshots(timeline_id, version_number)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_timelines_world
                    ON timelines(world_uuid)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_snapshot_traces_snapshot_id
                    ON snapshot_traces(snapshot_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_snapshot_actions_snapshot_id
                    ON snapshot_actions(snapshot_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_audit_log_created_at
                    ON audit_log(created_at DESC)
                    """);

            migrateLegacySnapshotsToTimelines(connection);
            setSchemaVersion(connection, CURRENT_SCHEMA_VERSION);
        }

        plugin.getLogger().info(
                "Banco temporal conectado em: "
                        + databaseFile.getAbsolutePath()
        );
    }

    private void ensureSnapshotDisplayNameColumn(
            Statement statement
    ) throws SQLException {
        try {
            statement.execute("""
                    ALTER TABLE snapshots
                    ADD COLUMN display_name TEXT
                    """);

        } catch (SQLException exception) {
            String message = exception.getMessage();

            if (message == null
                    || !message.toLowerCase().contains(
                    "duplicate column"
            )) {
                throw exception;
            }
        }
    }


    private void ensureSnapshotTimelineColumns(
            Statement statement
    ) throws SQLException {
        addColumnIfMissing(
                statement,
                """
                        ALTER TABLE snapshots
                        ADD COLUMN timeline_id INTEGER
                        """
        );

        addColumnIfMissing(
                statement,
                """
                        ALTER TABLE snapshots
                        ADD COLUMN version_number INTEGER
                        NOT NULL DEFAULT 1
                        """
        );
    }

    private void addColumnIfMissing(
            Statement statement,
            String sql
    ) throws SQLException {
        try {
            statement.execute(sql);

        } catch (SQLException exception) {
            String message = exception.getMessage();

            if (message == null
                    || !message.toLowerCase().contains(
                    "duplicate column"
            )) {
                throw exception;
            }
        }
    }

    private void migrateLegacySnapshotsToTimelines(
            Connection connection
    ) throws SQLException {
        String selectSql = """
                SELECT
                    id,
                    world_uuid,
                    center_x,
                    center_y,
                    center_z,
                    created_at
                FROM snapshots
                WHERE timeline_id IS NULL
                   OR NOT EXISTS (
                        SELECT 1
                        FROM timelines
                        WHERE timelines.id = snapshots.timeline_id
                   )
                ORDER BY created_at, id
                """;

        List<LegacySnapshot> legacySnapshots =
                new ArrayList<>();

        try (PreparedStatement selectStatement =
                     connection.prepareStatement(selectSql);
             ResultSet resultSet =
                     selectStatement.executeQuery()) {

            while (resultSet.next()) {
                legacySnapshots.add(
                        new LegacySnapshot(
                                resultSet.getLong("id"),
                                UUID.fromString(
                                        resultSet.getString(
                                                "world_uuid"
                                        )
                                ),
                                resultSet.getDouble(
                                        "center_x"
                                ),
                                resultSet.getDouble(
                                        "center_y"
                                ),
                                resultSet.getDouble(
                                        "center_z"
                                ),
                                resultSet.getLong(
                                        "created_at"
                                )
                        )
                );
            }
        }

        for (LegacySnapshot snapshot :
                legacySnapshots) {

            long timelineId =
                    findNearestLegacyTimeline(
                            connection,
                            snapshot.worldId(),
                            snapshot.centerX(),
                            snapshot.centerY(),
                            snapshot.centerZ()
                    );

            if (timelineId <= 0L) {
                timelineId =
                        createTimeline(
                                connection,
                                snapshot.worldId(),
                                snapshot.centerX(),
                                snapshot.centerY(),
                                snapshot.centerZ(),
                                snapshot.createdAt()
                        );
            }

            int versionNumber =
                    getNextTimelineVersion(
                            connection,
                            timelineId
                    );

            assignSnapshotToTimeline(
                    connection,
                    snapshot.id(),
                    timelineId,
                    versionNumber
            );

            updateTimelineAnchor(
                    connection,
                    timelineId,
                    snapshot.centerX(),
                    snapshot.centerY(),
                    snapshot.centerZ(),
                    snapshot.createdAt()
            );
        }

        if (!legacySnapshots.isEmpty()) {
            plugin.getLogger().info(
                    "Migracao temporal concluida: "
                            + legacySnapshots.size()
                            + " fotografias antigas receberam "
                            + "uma linha temporal."
            );
        }
    }

    private long findNearestLegacyTimeline(
            Connection connection,
            UUID worldId,
            double centerX,
            double centerY,
            double centerZ
    ) throws SQLException {
        String sql = """
                SELECT
                    id,
                    anchor_x,
                    anchor_y,
                    anchor_z
                FROM timelines
                WHERE world_uuid = ?
                """;

        double bestDistanceSquared =
                LEGACY_TIMELINE_LINK_RADIUS
                        * LEGACY_TIMELINE_LINK_RADIUS;

        long bestTimelineId = -1L;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    worldId.toString()
            );

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    double dx =
                            resultSet.getDouble("anchor_x")
                                    - centerX;

                    double dy =
                            resultSet.getDouble("anchor_y")
                                    - centerY;

                    double dz =
                            resultSet.getDouble("anchor_z")
                                    - centerZ;

                    double distanceSquared =
                            dx * dx
                                    + dy * dy
                                    + dz * dz;

                    if (distanceSquared
                            > bestDistanceSquared) {
                        continue;
                    }

                    bestDistanceSquared =
                            distanceSquared;

                    bestTimelineId =
                            resultSet.getLong("id");
                }
            }
        }

        return bestTimelineId;
    }

    private long createTimeline(
            Connection connection,
            UUID worldId,
            double anchorX,
            double anchorY,
            double anchorZ,
            long createdAt
    ) throws SQLException {
        String sql = """
                INSERT INTO timelines (
                    world_uuid,
                    anchor_x,
                    anchor_y,
                    anchor_z,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             sql,
                             Statement.RETURN_GENERATED_KEYS
                     )) {

            statement.setString(
                    1,
                    worldId.toString()
            );

            statement.setDouble(2, anchorX);
            statement.setDouble(3, anchorY);
            statement.setDouble(4, anchorZ);
            statement.setLong(5, createdAt);
            statement.setLong(6, createdAt);

            statement.executeUpdate();

            try (ResultSet resultSet =
                         statement.getGeneratedKeys()) {

                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        }

        return getLastInsertId(connection);
    }

    private int getNextTimelineVersion(
            Connection connection,
            long timelineId
    ) throws SQLException {
        String sql = """
                SELECT COALESCE(
                    MAX(version_number),
                    0
                ) + 1 AS next_version
                FROM snapshots
                WHERE timeline_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, timelineId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    return 1;
                }

                return Math.max(
                        1,
                        resultSet.getInt(
                                "next_version"
                        )
                );
            }
        }
    }

    private void assignSnapshotToTimeline(
            Connection connection,
            long snapshotId,
            long timelineId,
            int versionNumber
    ) throws SQLException {
        String sql = """
                UPDATE snapshots
                SET timeline_id = ?,
                    version_number = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, timelineId);
            statement.setInt(2, versionNumber);
            statement.setLong(3, snapshotId);

            statement.executeUpdate();
        }
    }

    private void updateTimelineAnchor(
            Connection connection,
            long timelineId,
            double anchorX,
            double anchorY,
            double anchorZ,
            long updatedAt
    ) throws SQLException {
        String sql = """
                UPDATE timelines
                SET anchor_x = ?,
                    anchor_y = ?,
                    anchor_z = ?,
                    updated_at = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setDouble(1, anchorX);
            statement.setDouble(2, anchorY);
            statement.setDouble(3, anchorZ);
            statement.setLong(4, updatedAt);
            statement.setLong(5, timelineId);

            statement.executeUpdate();
        }
    }

    private void createStartupBackup(
            File sourceDatabase
    ) throws SQLException {
        if (!sourceDatabase.exists()
                || sourceDatabase.length() <= 0L) {
            return;
        }

        File backupFolder = new File(
                plugin.getDataFolder(),
                "backups"
        );

        if (!backupFolder.exists()
                && !backupFolder.mkdirs()) {
            throw new SQLException(
                    "Nao foi possivel criar a pasta de backups."
            );
        }

        long timestamp = System.currentTimeMillis();
        String backupPrefix = "echocraft-" + timestamp;

        try {
            copyDatabasePart(
                    sourceDatabase.toPath(),
                    backupFolder.toPath()
                            .resolve(backupPrefix + ".db")
            );

            Path walSource = Path.of(
                    sourceDatabase.getAbsolutePath() + "-wal"
            );

            if (Files.exists(walSource)) {
                copyDatabasePart(
                        walSource,
                        backupFolder.toPath()
                                .resolve(backupPrefix + ".db-wal")
                );
            }

            Path shmSource = Path.of(
                    sourceDatabase.getAbsolutePath() + "-shm"
            );

            if (Files.exists(shmSource)) {
                copyDatabasePart(
                        shmSource,
                        backupFolder.toPath()
                                .resolve(backupPrefix + ".db-shm")
                );
            }

            cleanupOldBackups(backupFolder);

            plugin.getLogger().info(
                    "Backup automatico do banco criado: "
                            + backupPrefix
            );

        } catch (IOException exception) {
            throw new SQLException(
                    "Nao foi possivel criar o backup automatico.",
                    exception
            );
        }
    }

    private void copyDatabasePart(
            Path source,
            Path destination
    ) throws IOException {
        Files.copy(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );
    }

    private void cleanupOldBackups(
            File backupFolder
    ) throws IOException {
        File[] mainBackups = backupFolder.listFiles(
                (folder, name) ->
                        name.startsWith("echocraft-")
                                && name.endsWith(".db")
        );

        if (mainBackups == null
                || mainBackups.length <= MAX_DATABASE_BACKUPS) {
            return;
        }

        List<File> orderedBackups = new ArrayList<>(
                List.of(mainBackups)
        );

        orderedBackups.sort(
                Comparator.comparingLong(File::lastModified)
                        .reversed()
        );

        for (int index = MAX_DATABASE_BACKUPS;
             index < orderedBackups.size();
             index++) {

            File mainBackup = orderedBackups.get(index);
            String basePath = mainBackup.getAbsolutePath();

            Files.deleteIfExists(mainBackup.toPath());
            Files.deleteIfExists(Path.of(basePath + "-wal"));
            Files.deleteIfExists(Path.of(basePath + "-shm"));
        }
    }

    private void setSchemaVersion(
            Connection connection,
            int schemaVersion
    ) throws SQLException {
        String sql = """
                INSERT INTO schema_metadata (
                    meta_key,
                    meta_value
                ) VALUES ('schema_version', ?)
                ON CONFLICT(meta_key) DO UPDATE SET
                    meta_value = excluded.meta_value
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    Integer.toString(schemaVersion)
            );

            statement.executeUpdate();
        }
    }

    public int getSchemaVersion() throws SQLException {
        String sql = """
                SELECT meta_value
                FROM schema_metadata
                WHERE meta_key = 'schema_version'
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            if (!resultSet.next()) {
                return 0;
            }

            try {
                return Integer.parseInt(
                        resultSet.getString("meta_value")
                );

            } catch (NumberFormatException exception) {
                return 0;
            }
        }
    }

    public void checkpointWal() throws SQLException {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    public void saveAuditEvent(
            UUID actorId,
            String actorName,
            String actionType,
            Long snapshotId,
            Long timelineId,
            String details
    ) throws SQLException {
        String sql = """
                INSERT INTO audit_log (
                    actor_uuid,
                    actor_name,
                    action_type,
                    snapshot_id,
                    timeline_id,
                    details,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            if (actorId == null) {
                statement.setNull(1, java.sql.Types.VARCHAR);
            } else {
                statement.setString(1, actorId.toString());
            }

            statement.setString(2, actorName);
            statement.setString(3, actionType);

            if (snapshotId == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, snapshotId);
            }

            if (timelineId == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, timelineId);
            }

            statement.setString(6, details);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public Connection getConnection() throws SQLException {
        if (jdbcUrl == null) {
            throw new IllegalStateException(
                    "O banco ainda nao foi inicializado."
            );
        }

        Connection connection =
                DriverManager.getConnection(jdbcUrl);

        try (Statement statement =
                     connection.createStatement()) {

            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }

        return connection;
    }

    public void savePlacedBlock(
            StoredPosition position
    ) throws SQLException {

        String sql = """
                INSERT OR IGNORE INTO placed_blocks (
                    world_uuid,
                    x,
                    y,
                    z
                ) VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    position.worldId().toString()
            );

            statement.setInt(2, position.x());
            statement.setInt(3, position.y());
            statement.setInt(4, position.z());

            statement.executeUpdate();
        }
    }

    public void deletePlacedBlocks(
            Collection<StoredPosition> positions
    ) throws SQLException {

        if (positions.isEmpty()) {
            return;
        }

        String sql = """
                DELETE FROM placed_blocks
                WHERE world_uuid = ?
                  AND x = ?
                  AND y = ?
                  AND z = ?
                """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                for (StoredPosition position : positions) {
                    statement.setString(
                            1,
                            position.worldId().toString()
                    );

                    statement.setInt(2, position.x());
                    statement.setInt(3, position.y());
                    statement.setInt(4, position.z());

                    statement.addBatch();
                }

                statement.executeBatch();
                connection.commit();

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void savePlacedBlocks(
            Collection<StoredPosition> positions
    ) throws SQLException {

        if (positions.isEmpty()) {
            return;
        }

        String sql = """
            INSERT OR IGNORE INTO placed_blocks (
                world_uuid,
                x,
                y,
                z
            ) VALUES (?, ?, ?, ?)
            """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                for (StoredPosition position : positions) {
                    statement.setString(
                            1,
                            position.worldId().toString()
                    );

                    statement.setInt(2, position.x());
                    statement.setInt(3, position.y());
                    statement.setInt(4, position.z());

                    statement.addBatch();
                }

                statement.executeBatch();
                connection.commit();

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Set<StoredPosition> loadPlacedBlocks()
            throws SQLException {

        Set<StoredPosition> positions =
                new LinkedHashSet<>();

        String sql = """
                SELECT world_uuid, x, y, z
                FROM placed_blocks
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {
                positions.add(
                        new StoredPosition(
                                UUID.fromString(
                                        resultSet.getString(
                                                "world_uuid"
                                        )
                                ),
                                resultSet.getInt("x"),
                                resultSet.getInt("y"),
                                resultSet.getInt("z")
                        )
                );
            }
        }

        return positions;
    }

    public long createTimeline(
            UUID worldId,
            double anchorX,
            double anchorY,
            double anchorZ,
            long createdAt
    ) throws SQLException {
        try (Connection connection = getConnection()) {
            return createTimeline(
                    connection,
                    worldId,
                    anchorX,
                    anchorY,
                    anchorZ,
                    createdAt
            );
        }
    }

    public int getNextTimelineVersion(
            long timelineId
    ) throws SQLException {
        try (Connection connection = getConnection()) {
            return getNextTimelineVersion(
                    connection,
                    timelineId
            );
        }
    }

    public long saveSnapshot(
            long timelineId,
            int versionNumber,
            String memoryType,
            String cause,
            UUID worldId,
            double centerX,
            double centerY,
            double centerZ,
            long createdAt,
            List<StoredBlock> blocks
    ) throws SQLException {

        String snapshotSql = """
                INSERT INTO snapshots (
                    timeline_id,
                    version_number,
                    memory_type,
                    cause,
                    world_uuid,
                    center_x,
                    center_y,
                    center_z,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String blockSql = """
                INSERT INTO snapshot_blocks (
                    snapshot_id,
                    world_uuid,
                    x,
                    y,
                    z,
                    block_data
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try {
                long snapshotId;

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     snapshotSql,
                                     Statement.RETURN_GENERATED_KEYS
                             )) {

                    statement.setLong(1, timelineId);
                    statement.setInt(2, versionNumber);
                    statement.setString(3, memoryType);
                    statement.setString(4, cause);
                    statement.setString(
                            5,
                            worldId.toString()
                    );

                    statement.setDouble(6, centerX);
                    statement.setDouble(7, centerY);
                    statement.setDouble(8, centerZ);
                    statement.setLong(9, createdAt);

                    statement.executeUpdate();

                    try (ResultSet resultSet =
                                 statement.getGeneratedKeys()) {

                        if (resultSet.next()) {
                            snapshotId = resultSet.getLong(1);
                        } else {
                            snapshotId = getLastInsertId(connection);
                        }
                    }
                }

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     blockSql
                             )) {

                    for (StoredBlock block : blocks) {
                        statement.setLong(1, snapshotId);

                        statement.setString(
                                2,
                                block.worldId().toString()
                        );

                        statement.setInt(3, block.x());
                        statement.setInt(4, block.y());
                        statement.setInt(5, block.z());

                        statement.setString(
                                6,
                                block.blockData()
                        );

                        statement.addBatch();
                    }

                    statement.executeBatch();
                }

                updateTimelineAnchor(
                        connection,
                        timelineId,
                        centerX,
                        centerY,
                        centerZ,
                        createdAt
                );

                connection.commit();
                return snapshotId;

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private long getLastInsertId(
            Connection connection
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT last_insert_rowid()"
             )) {

            if (!resultSet.next()) {
                throw new SQLException(
                        "Nao foi possivel obter o ID "
                                + "da fotografia."
                );
            }

            return resultSet.getLong(1);
        }
    }

    public List<StoredSnapshot> loadSnapshots(
            long oldestAllowedTime,
            int limit
    ) throws SQLException {

        List<SnapshotHeader> headers =
                new ArrayList<>();

        String sql = """
                SELECT
                    snapshots.id,
                    snapshots.timeline_id,
                    snapshots.version_number,
                    snapshots.memory_type,
                    snapshots.cause,
                    snapshots.world_uuid,
                    snapshots.center_x,
                    snapshots.center_y,
                    snapshots.center_z,
                    snapshots.created_at,
                    snapshots.display_name,
                    timelines.display_name AS timeline_name
                FROM snapshots
                LEFT JOIN timelines
                    ON timelines.id = snapshots.timeline_id
                WHERE snapshots.created_at >= ?
                ORDER BY snapshots.created_at DESC
                LIMIT ?
                """;

        try (Connection connection = getConnection()) {
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setLong(1, oldestAllowedTime);
                statement.setInt(2, limit);

                try (ResultSet resultSet =
                             statement.executeQuery()) {

                    while (resultSet.next()) {
                        headers.add(
                                new SnapshotHeader(
                                        resultSet.getLong("id"),
                                        resultSet.getLong(
                                                "timeline_id"
                                        ),
                                        resultSet.getInt(
                                                "version_number"
                                        ),
                                        resultSet.getString(
                                                "timeline_name"
                                        ),
                                        resultSet.getString(
                                                "memory_type"
                                        ),
                                        resultSet.getString(
                                                "cause"
                                        ),
                                        UUID.fromString(
                                                resultSet.getString(
                                                        "world_uuid"
                                                )
                                        ),
                                        resultSet.getDouble(
                                                "center_x"
                                        ),
                                        resultSet.getDouble(
                                                "center_y"
                                        ),
                                        resultSet.getDouble(
                                                "center_z"
                                        ),
                                        resultSet.getLong(
                                                "created_at"
                                        ),
                                        resultSet.getString(
                                                "display_name"
                                        )
                                )
                        );
                    }
                }
            }

            List<StoredSnapshot> snapshots =
                    new ArrayList<>();

            for (SnapshotHeader header : headers) {
                List<StoredBlock> blocks =
                        loadSnapshotBlocks(
                                connection,
                                header.id()
                        );

                List<StoredTrace> traces =
                        loadSnapshotTraces(
                                connection,
                                header.id()
                        );

                List<StoredAction> actions =
                        loadSnapshotActions(
                                connection,
                                header.id()
                        );

                snapshots.add(
                        new StoredSnapshot(
                                header.id(),
                                header.timelineId(),
                                header.versionNumber(),
                                header.timelineName(),
                                header.memoryType(),
                                header.cause(),
                                header.worldId(),
                                header.centerX(),
                                header.centerY(),
                                header.centerZ(),
                                header.createdAt(),
                                header.displayName(),
                                List.copyOf(blocks),
                                List.copyOf(traces),
                                List.copyOf(actions)
                        )
                );
            }

            return snapshots;
        }
    }

    private List<StoredBlock> loadSnapshotBlocks(
            Connection connection,
            long snapshotId
    ) throws SQLException {

        List<StoredBlock> blocks =
                new ArrayList<>();

        String sql = """
                SELECT
                    world_uuid,
                    x,
                    y,
                    z,
                    block_data
                FROM snapshot_blocks
                WHERE snapshot_id = ?
                ORDER BY y, x, z
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, snapshotId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    blocks.add(
                            new StoredBlock(
                                    UUID.fromString(
                                            resultSet.getString(
                                                    "world_uuid"
                                            )
                                    ),
                                    resultSet.getInt("x"),
                                    resultSet.getInt("y"),
                                    resultSet.getInt("z"),
                                    resultSet.getString(
                                            "block_data"
                                    )
                            )
                    );
                }
            }
        }

        return blocks;
    }

    public void saveSnapshotReplayData(
            long snapshotId,
            List<StoredTrace> traces,
            List<StoredAction> actions
    ) throws SQLException {

        String deleteTracesSql = """
                DELETE FROM snapshot_traces
                WHERE snapshot_id = ?
                """;

        String deleteActionsSql = """
                DELETE FROM snapshot_actions
                WHERE snapshot_id = ?
                """;

        String traceSql = """
                INSERT INTO snapshot_traces (
                    snapshot_id,
                    sequence_index,
                    player_uuid,
                    player_name,
                    world_uuid,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    item_in_hand,
                    created_at,
                    relative_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String actionSql = """
                INSERT INTO snapshot_actions (
                    snapshot_id,
                    sequence_index,
                    player_uuid,
                    player_name,
                    action_type,
                    world_uuid,
                    x,
                    y,
                    z,
                    block_type,
                    item_in_hand,
                    created_at,
                    relative_time,
                    amount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     deleteTracesSql
                             )) {

                    statement.setLong(1, snapshotId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     deleteActionsSql
                             )) {

                    statement.setLong(1, snapshotId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     traceSql
                             )) {

                    int index = 0;

                    for (StoredTrace trace : traces) {
                        statement.setLong(1, snapshotId);
                        statement.setInt(2, index++);
                        statement.setString(
                                3,
                                trace.playerId().toString()
                        );
                        statement.setString(4, trace.playerName());
                        statement.setString(
                                5,
                                trace.worldId().toString()
                        );
                        statement.setDouble(6, trace.x());
                        statement.setDouble(7, trace.y());
                        statement.setDouble(8, trace.z());
                        statement.setFloat(9, trace.yaw());
                        statement.setFloat(10, trace.pitch());
                        statement.setString(11, trace.itemInHand());
                        statement.setLong(12, trace.createdAt());
                        statement.setLong(13, trace.relativeTime());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                }

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     actionSql
                             )) {

                    int index = 0;

                    for (StoredAction action : actions) {
                        statement.setLong(1, snapshotId);
                        statement.setInt(2, index++);
                        statement.setString(
                                3,
                                action.playerId().toString()
                        );
                        statement.setString(4, action.playerName());
                        statement.setString(5, action.actionType());
                        statement.setString(
                                6,
                                action.worldId().toString()
                        );
                        statement.setDouble(7, action.x());
                        statement.setDouble(8, action.y());
                        statement.setDouble(9, action.z());
                        statement.setString(10, action.blockType());
                        statement.setString(11, action.itemInHand());
                        statement.setLong(12, action.createdAt());
                        statement.setLong(13, action.relativeTime());
                        statement.setInt(14, action.amount());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                }

                connection.commit();

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private List<StoredTrace> loadSnapshotTraces(
            Connection connection,
            long snapshotId
    ) throws SQLException {

        List<StoredTrace> traces =
                new ArrayList<>();

        String sql = """
                SELECT
                    player_uuid,
                    player_name,
                    world_uuid,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    item_in_hand,
                    created_at,
                    relative_time
                FROM snapshot_traces
                WHERE snapshot_id = ?
                ORDER BY sequence_index
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, snapshotId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    traces.add(
                            new StoredTrace(
                                    UUID.fromString(
                                            resultSet.getString(
                                                    "player_uuid"
                                            )
                                    ),
                                    resultSet.getString(
                                            "player_name"
                                    ),
                                    UUID.fromString(
                                            resultSet.getString(
                                                    "world_uuid"
                                            )
                                    ),
                                    resultSet.getDouble("x"),
                                    resultSet.getDouble("y"),
                                    resultSet.getDouble("z"),
                                    resultSet.getFloat("yaw"),
                                    resultSet.getFloat("pitch"),
                                    resultSet.getString(
                                            "item_in_hand"
                                    ),
                                    resultSet.getLong("created_at"),
                                    resultSet.getLong("relative_time")
                            )
                    );
                }
            }
        }

        return traces;
    }

    private List<StoredAction> loadSnapshotActions(
            Connection connection,
            long snapshotId
    ) throws SQLException {

        List<StoredAction> actions =
                new ArrayList<>();

        String sql = """
                SELECT
                    player_uuid,
                    player_name,
                    action_type,
                    world_uuid,
                    x,
                    y,
                    z,
                    block_type,
                    item_in_hand,
                    created_at,
                    relative_time,
                    amount
                FROM snapshot_actions
                WHERE snapshot_id = ?
                ORDER BY sequence_index
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, snapshotId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    actions.add(
                            new StoredAction(
                                    UUID.fromString(
                                            resultSet.getString(
                                                    "player_uuid"
                                            )
                                    ),
                                    resultSet.getString(
                                            "player_name"
                                    ),
                                    resultSet.getString(
                                            "action_type"
                                    ),
                                    UUID.fromString(
                                            resultSet.getString(
                                                    "world_uuid"
                                            )
                                    ),
                                    resultSet.getDouble("x"),
                                    resultSet.getDouble("y"),
                                    resultSet.getDouble("z"),
                                    resultSet.getString(
                                            "block_type"
                                    ),
                                    resultSet.getString(
                                            "item_in_hand"
                                    ),
                                    resultSet.getLong("created_at"),
                                    resultSet.getLong("relative_time"),
                                    resultSet.getInt("amount")
                            )
                    );
                }
            }
        }

        return actions;
    }

    public void updateSnapshotDisplayName(
            long snapshotId,
            String displayName
    ) throws SQLException {

        String sql = """
                UPDATE snapshots
                SET display_name = ?
                WHERE id = ?
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, displayName);
            statement.setLong(2, snapshotId);

            statement.executeUpdate();
        }
    }


    public void updateTimelineDisplayName(
            long timelineId,
            String displayName
    ) throws SQLException {
        String sql = """
                UPDATE timelines
                SET display_name = ?,
                    updated_at = ?
                WHERE id = ?
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, displayName);
            statement.setLong(
                    2,
                    System.currentTimeMillis()
            );
            statement.setLong(3, timelineId);

            statement.executeUpdate();
        }
    }

    public boolean deleteSnapshot(
            long snapshotId
    ) throws SQLException {
        String timelineSql = """
                SELECT timeline_id
                FROM snapshots
                WHERE id = ?
                """;

        String deleteSql = """
                DELETE FROM snapshots
                WHERE id = ?
                """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try {
                Long timelineId = null;

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     timelineSql
                             )) {

                    statement.setLong(1, snapshotId);

                    try (ResultSet resultSet =
                                 statement.executeQuery()) {

                        if (resultSet.next()) {
                            long value =
                                    resultSet.getLong(
                                            "timeline_id"
                                    );

                            if (!resultSet.wasNull()) {
                                timelineId = value;
                            }
                        }
                    }
                }

                int affectedRows;

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     deleteSql
                             )) {

                    statement.setLong(1, snapshotId);
                    affectedRows =
                            statement.executeUpdate();
                }

                if (timelineId != null) {
                    deleteTimelineIfEmpty(
                            connection,
                            timelineId
                    );
                }

                connection.commit();
                return affectedRows > 0;

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void deleteExpiredSnapshots(
            long oldestAllowedTime
    ) throws SQLException {
        String deleteSnapshotsSql = """
                DELETE FROM snapshots
                WHERE created_at < ?
                """;

        String deleteEmptyTimelinesSql = """
                DELETE FROM timelines
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM snapshots
                    WHERE snapshots.timeline_id = timelines.id
                )
                """;

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     deleteSnapshotsSql
                             )) {

                    statement.setLong(
                            1,
                            oldestAllowedTime
                    );

                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     deleteEmptyTimelinesSql
                             )) {

                    statement.executeUpdate();
                }

                connection.commit();

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void deleteTimelineIfEmpty(
            Connection connection,
            long timelineId
    ) throws SQLException {
        String sql = """
                DELETE FROM timelines
                WHERE id = ?
                  AND NOT EXISTS (
                    SELECT 1
                    FROM snapshots
                    WHERE snapshots.timeline_id = timelines.id
                  )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, timelineId);
            statement.executeUpdate();
        }
    }

    private void createPluginFolder()
            throws SQLException {

        File dataFolder = plugin.getDataFolder();

        if (dataFolder.exists()) {
            return;
        }

        if (!dataFolder.mkdirs()) {
            throw new SQLException(
                    "Nao foi possivel criar "
                            + "a pasta do EchoCraft."
            );
        }
    }

    private void loadSQLiteDriver()
            throws SQLException {

        try {
            Class.forName("org.sqlite.JDBC");

        } catch (ClassNotFoundException exception) {
            throw new SQLException(
                    "O driver SQLite nao foi encontrado.",
                    exception
            );
        }
    }

    public record StoredPosition(
            UUID worldId,
            int x,
            int y,
            int z
    ) {
    }

    public record StoredBlock(
            UUID worldId,
            int x,
            int y,
            int z,
            String blockData
    ) {
    }

    public record StoredSnapshot(
            long id,
            long timelineId,
            int versionNumber,
            String timelineName,
            String memoryType,
            String cause,
            UUID worldId,
            double centerX,
            double centerY,
            double centerZ,
            long createdAt,
            String displayName,
            List<StoredBlock> blocks,
            List<StoredTrace> traces,
            List<StoredAction> actions
    ) {
    }

    public record StoredTrace(
            UUID playerId,
            String playerName,
            UUID worldId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String itemInHand,
            long createdAt,
            long relativeTime
    ) {
    }

    public record StoredAction(
            UUID playerId,
            String playerName,
            String actionType,
            UUID worldId,
            double x,
            double y,
            double z,
            String blockType,
            String itemInHand,
            long createdAt,
            long relativeTime,
            int amount
    ) {
    }

    private record LegacySnapshot(
            long id,
            UUID worldId,
            double centerX,
            double centerY,
            double centerZ,
            long createdAt
    ) {
    }

    private record SnapshotHeader(
            long id,
            long timelineId,
            int versionNumber,
            String timelineName,
            String memoryType,
            String cause,
            UUID worldId,
            double centerX,
            double centerY,
            double centerZ,
            long createdAt,
            String displayName
    ) {
    }
}
