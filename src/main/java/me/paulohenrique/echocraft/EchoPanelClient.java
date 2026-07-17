package me.paulohenrique.echocraft;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EchoPanelClient {

    private final EchoCraftPlugin plugin;

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private BukkitTask heartbeatTask;
    private BukkitTask commandPollTask;

    private String apiUrl;
    private String apiToken;
    private String activitiesUrl;
    private String commandClaimUrl;
    private String commandBaseUrl;

    private final AtomicBoolean commandPollInFlight =
            new AtomicBoolean(false);

    private final AtomicBoolean commandBusy =
            new AtomicBoolean(false);

    public EchoPanelClient(EchoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        boolean enabled = plugin.getConfig()
                .getBoolean("echopanel.enabled", false);

        if (! enabled) {
            plugin.getLogger().info(
                    "Integracao com o EchoPanel desativada."
            );
            return;
        }

        apiUrl = plugin.getConfig()
                .getString("echopanel.url", "")
                .trim();

        apiToken = plugin.getConfig()
                .getString("echopanel.token", "")
                .trim();

        int intervalSeconds = Math.max(
                10,
                plugin.getConfig().getInt(
                        "echopanel.interval-seconds",
                        30
                )
        );

        if (apiUrl.isBlank() || apiToken.isBlank()) {
            plugin.getLogger().warning(
                    "EchoPanel ativado, mas a URL ou o token "
                            + "nao foram configurados."
            );
            return;
        }

        activitiesUrl = apiUrl.replaceFirst(
                "/heartbeat/?$",
                "/activities"
        );

        commandClaimUrl = apiUrl.replaceFirst(
                "/heartbeat/?$",
                "/commands/claim"
        );

        commandBaseUrl = apiUrl.replaceFirst(
                "/heartbeat/?$",
                "/commands"
        );

        if (activitiesUrl.equals(apiUrl)
                || commandClaimUrl.equals(apiUrl)
                || commandBaseUrl.equals(apiUrl)) {
            plugin.getLogger().warning(
                    "A URL do EchoPanel nao termina em /heartbeat. "
                            + "Atividades e comandos podem falhar."
            );
        }

        long intervalTicks = intervalSeconds * 20L;

        heartbeatTask = Bukkit.getScheduler()
                .runTaskTimer(
                        plugin,
                        this::sendHeartbeat,
                        20L,
                        intervalTicks
                );

        commandPollTask = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(
                        plugin,
                        this::pollCommand,
                        40L,
                        60L
                );

        plugin.getLogger().info(
                "Integracao com o EchoPanel iniciada. "
                        + "Heartbeat a cada "
                        + intervalSeconds
                        + " segundo(s). Comandos consultados "
                        + "a cada 3 segundo(s)."
        );
    }

    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        if (commandPollTask != null) {
            commandPollTask.cancel();
            commandPollTask = null;
        }

        commandPollInFlight.set(false);
        commandBusy.set(false);
    }

    public void sendBlockReconstruction(
            Player player,
            Location center,
            int blocksCount,
            long snapshotId,
            long timelineId,
            int versionNumber,
            String timelineName,
            String snapshotName,
            String memoryType,
            int snapshotBlocks,
            int traceCount,
            int actionCount,
            String cause
    ) {
        if (activitiesUrl == null
                || activitiesUrl.isBlank()
                || apiToken == null
                || apiToken.isBlank()) {
            return;
        }

        World world = center.getWorld();

        if (world == null) {
            return;
        }

        String safeCause =
                cause == null || cause.isBlank()
                        ? "Desconhecida"
                        : cause;

        String safeMemoryType =
                memoryType == null || memoryType.isBlank()
                        ? "UNKNOWN"
                        : memoryType;

        String body = String.format(
                Locale.ROOT,
                """
                {
                    "event_uuid": "%s",
                    "type": "block_reconstruction",
                    "player_uuid": %s,
                    "player_name": %s,
                    "world": "%s",
                    "x": %.3f,
                    "y": %.3f,
                    "z": %.3f,
                    "block_type": null,
                    "metadata": {
                        "activity_kind": "reconstruction",
                        "blocks_count": %d,
                        "snapshot_id": %d,
                        "timeline_id": %d,
                        "version_number": %d,
                        "timeline_name": %s,
                        "snapshot_name": %s,
                        "memory_type": "%s",
                        "snapshot_blocks": %d,
                        "trace_count": %d,
                        "action_count": %d,
                        "cause": "%s"
                    },
                    "occurred_at": "%s"
                }
                """,
                UUID.randomUUID(),
                toNullableJsonString(
                        player == null
                                ? null
                                : player.getUniqueId().toString()
                ),
                toNullableJsonString(
                        player == null
                                ? "EchoPanel"
                                : player.getName()
                ),
                escapeJson(world.getName()),
                center.getX(),
                center.getY(),
                center.getZ(),
                Math.max(0, blocksCount),
                snapshotId,
                timelineId,
                versionNumber,
                toNullableJsonString(timelineName),
                toNullableJsonString(snapshotName),
                escapeJson(safeMemoryType),
                Math.max(0, snapshotBlocks),
                Math.max(0, traceCount),
                Math.max(0, actionCount),
                escapeJson(safeCause),
                Instant.now()
        );

        sendJsonRequest(
                activitiesUrl,
                body,
                "atividade de restauracao"
        ).whenComplete((response, exception) -> {
            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel enviar a restauracao "
                                + "ao EchoPanel.",
                        exception
                );
                return;
            }

            if (! isSuccessful(response)) {
                logRejectedResponse(
                        "atividade",
                        response
                );
                return;
            }

            plugin.getLogger().info(
                    "Restauracao enviada ao EchoPanel: "
                            + blocksCount
                            + " bloco(s)."
            );
        });
    }


    public void sendPlayerTrace(
            UUID playerId,
            String playerName,
            Location location,
            int tracePoints,
            int actionsCount,
            long snapshotId,
            long timelineId,
            int versionNumber,
            String timelineName,
            String snapshotName,
            String memoryType,
            int snapshotBlocks,
            int totalTraceCount,
            int totalActionCount,
            String cause
    ) {
        if (activitiesUrl == null
                || activitiesUrl.isBlank()
                || apiToken == null
                || apiToken.isBlank()
                || playerId == null
                || location == null) {
            return;
        }

        World world = location.getWorld();

        if (world == null) {
            return;
        }

        String safePlayerName =
                playerName == null || playerName.isBlank()
                        ? "Desconhecido"
                        : playerName;

        String safeCause =
                cause == null || cause.isBlank()
                        ? "Desconhecida"
                        : cause;

        String safeMemoryType =
                memoryType == null || memoryType.isBlank()
                        ? "UNKNOWN"
                        : memoryType;

        int safeTracePoints = Math.max(0, tracePoints);
        int safeActionsCount = Math.max(0, actionsCount);

        String body = String.format(
                Locale.ROOT,
                """
                {
                    "event_uuid": "%s",
                    "type": "player_trace",
                    "player_uuid": "%s",
                    "player_name": "%s",
                    "world": "%s",
                    "x": %.3f,
                    "y": %.3f,
                    "z": %.3f,
                    "block_type": null,
                    "metadata": {
                        "activity_kind": "player_trace",
                        "trace_points": %d,
                        "actions_count": %d,
                        "snapshot_id": %d,
                        "timeline_id": %d,
                        "version_number": %d,
                        "timeline_name": %s,
                        "snapshot_name": %s,
                        "memory_type": "%s",
                        "snapshot_blocks": %d,
                        "trace_count": %d,
                        "action_count": %d,
                        "cause": "%s"
                    },
                    "occurred_at": "%s"
                }
                """,
                UUID.randomUUID(),
                playerId,
                escapeJson(safePlayerName),
                escapeJson(world.getName()),
                location.getX(),
                location.getY(),
                location.getZ(),
                safeTracePoints,
                safeActionsCount,
                snapshotId,
                timelineId,
                versionNumber,
                toNullableJsonString(timelineName),
                toNullableJsonString(snapshotName),
                escapeJson(safeMemoryType),
                Math.max(0, snapshotBlocks),
                Math.max(0, totalTraceCount),
                Math.max(0, totalActionCount),
                escapeJson(safeCause),
                Instant.now()
        );

        sendJsonRequest(
                activitiesUrl,
                body,
                "atividade de rastro"
        ).whenComplete((response, exception) -> {
            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel enviar o rastro "
                                + "ao EchoPanel.",
                        exception
                );
                return;
            }

            if (! isSuccessful(response)) {
                logRejectedResponse(
                        "rastro",
                        response
                );
                return;
            }

            plugin.getLogger().info(
                    "Rastro enviado ao EchoPanel: "
                            + safePlayerName
                            + " com "
                            + safeTracePoints
                            + " ponto(s) e "
                            + safeActionsCount
                            + " acao(oes)."
            );
        });
    }


    public void sendTimelineSnapshot(
            Location center,
            long snapshotId,
            long timelineId,
            int versionNumber,
            String timelineName,
            String snapshotName,
            String memoryType,
            String cause,
            int blocksCount,
            int traceCount,
            int actionCount,
            long occurredAtMillis
    ) {
        if (activitiesUrl == null
                || activitiesUrl.isBlank()
                || apiToken == null
                || apiToken.isBlank()
                || center == null) {
            return;
        }

        World world = center.getWorld();

        if (world == null) {
            return;
        }

        String safeCause =
                cause == null || cause.isBlank()
                        ? "Desconhecida"
                        : cause;

        String safeMemoryType =
                memoryType == null || memoryType.isBlank()
                        ? "UNKNOWN"
                        : memoryType;

        String body = String.format(
                Locale.ROOT,
                """
                {
                    "event_uuid": "%s",
                    "type": "block_reconstruction",
                    "player_uuid": null,
                    "player_name": null,
                    "world": "%s",
                    "x": %.3f,
                    "y": %.3f,
                    "z": %.3f,
                    "block_type": null,
                    "metadata": {
                        "activity_kind": "timeline_snapshot",
                        "snapshot_id": %d,
                        "timeline_id": %d,
                        "version_number": %d,
                        "timeline_name": %s,
                        "snapshot_name": %s,
                        "memory_type": "%s",
                        "snapshot_blocks": %d,
                        "trace_count": %d,
                        "action_count": %d,
                        "cause": "%s"
                    },
                    "occurred_at": "%s"
                }
                """,
                UUID.randomUUID(),
                escapeJson(world.getName()),
                center.getX(),
                center.getY(),
                center.getZ(),
                snapshotId,
                timelineId,
                versionNumber,
                toNullableJsonString(timelineName),
                toNullableJsonString(snapshotName),
                escapeJson(safeMemoryType),
                Math.max(0, blocksCount),
                Math.max(0, traceCount),
                Math.max(0, actionCount),
                escapeJson(safeCause),
                Instant.ofEpochMilli(occurredAtMillis)
        );

        sendJsonRequest(
                activitiesUrl,
                body,
                "versao da linha temporal"
        ).whenComplete((response, exception) -> {
            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel enviar a versao temporal "
                                + "ao EchoPanel.",
                        exception
                );
                return;
            }

            if (! isSuccessful(response)) {
                logRejectedResponse(
                        "versao temporal",
                        response
                );
                return;
            }

            plugin.getLogger().info(
                    "Versao temporal enviada ao EchoPanel: linha #"
                            + timelineId
                            + ", versao "
                            + versionNumber
                            + ", memoria #"
                            + snapshotId
                            + "."
            );
        });
    }


    private void pollCommand() {
        if (commandClaimUrl == null
                || commandClaimUrl.isBlank()
                || commandBusy.get()
                || !commandPollInFlight.compareAndSet(
                        false,
                        true
                )) {
            return;
        }

        sendJsonRequest(
                commandClaimUrl,
                "{}",
                "consulta de comandos"
        ).whenComplete((response, exception) -> {
            commandPollInFlight.set(false);

            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel consultar comandos "
                                + "do EchoPanel.",
                        exception
                );
                return;
            }

            if (response.statusCode() == 204) {
                return;
            }

            if (!isSuccessful(response)) {
                logRejectedResponse(
                        "consulta de comandos",
                        response
                );
                return;
            }

            Long commandId = extractJsonLong(
                    response.body(),
                    "id"
            );

            Long snapshotId = extractJsonLong(
                    response.body(),
                    "snapshot_id"
            );

            String commandType = extractJsonString(
                    response.body(),
                    "type"
            );

            if (commandId == null
                    || snapshotId == null
                    || commandType == null) {

                plugin.getLogger().warning(
                        "EchoPanel retornou um comando invalido: "
                                + response.body()
                );
                return;
            }

            if (!commandBusy.compareAndSet(
                    false,
                    true
            )) {
                return;
            }

            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {
                        try {
                            plugin.handlePanelCommand(
                                    commandId,
                                    commandType,
                                    snapshotId
                            );

                        } catch (RuntimeException commandException) {
                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Falha ao executar o comando #"
                                            + commandId
                                            + " recebido do EchoPanel.",
                                    commandException
                            );

                            completeCommand(
                                    commandId,
                                    false,
                                    "Falha interna ao executar o comando: "
                                            + commandException
                                            .getClass()
                                            .getSimpleName(),
                                    0,
                                    0
                            );
                        }
                    }
            );
        });
    }

    public void completeCommand(
            long commandId,
            boolean successful,
            String message,
            int restoredBlocks,
            int failures
    ) {
        if (commandBaseUrl == null
                || commandBaseUrl.isBlank()) {
            commandBusy.set(false);
            return;
        }

        String safeMessage =
                message == null || message.isBlank()
                        ? (successful
                        ? "Comando concluido."
                        : "Comando falhou.")
                        : message;

        String body = String.format(
                Locale.ROOT,
                """
                {
                    "status": "%s",
                    "message": "%s",
                    "result": {
                        "restored_blocks": %d,
                        "failures": %d
                    }
                }
                """,
                successful ? "completed" : "failed",
                escapeJson(safeMessage),
                Math.max(0, restoredBlocks),
                Math.max(0, failures)
        );

        sendJsonRequest(
                commandBaseUrl
                        + "/"
                        + commandId
                        + "/complete",
                body,
                "conclusao de comando"
        ).whenComplete((response, exception) -> {
            commandBusy.set(false);

            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel confirmar o comando #"
                                + commandId
                                + " no EchoPanel.",
                        exception
                );
                return;
            }

            if (!isSuccessful(response)) {
                logRejectedResponse(
                        "conclusao do comando #"
                                + commandId,
                        response
                );
                return;
            }

            plugin.getLogger().info(
                    "Comando do EchoPanel #"
                            + commandId
                            + " finalizado como "
                            + (successful
                            ? "concluido"
                            : "falhou")
                            + "."
            );
        });
    }

    private Long extractJsonLong(
            String json,
            String field
    ) {
        Pattern pattern = Pattern.compile(
                "\\\""
                        + Pattern.quote(field)
                        + "\\\"\\s*:\\s*(\\d+)"
        );

        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Long.parseLong(
                    matcher.group(1)
            );

        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String extractJsonString(
            String json,
            String field
    ) {
        Pattern pattern = Pattern.compile(
                "\\\""
                        + Pattern.quote(field)
                        + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
        );

        Matcher matcher = pattern.matcher(json);

        return matcher.find()
                ? matcher.group(1)
                : null;
    }

    private void sendHeartbeat() {
        String minecraftVersion = Bukkit.getMinecraftVersion();
        int playersOnline = Bukkit.getOnlinePlayers().size();
        int playersMax = Bukkit.getMaxPlayers();

        String body = """
                {
                    "minecraft_version": "%s",
                    "players_online": %d,
                    "players_max": %d
                }
                """.formatted(
                escapeJson(minecraftVersion),
                playersOnline,
                playersMax
        );

        sendJsonRequest(
                apiUrl,
                body,
                "heartbeat"
        ).whenComplete((response, exception) -> {
            if (exception != null) {
                logRequestFailure(
                        "Nao foi possivel enviar o heartbeat "
                                + "ao EchoPanel.",
                        exception
                );
                return;
            }

            if (! isSuccessful(response)) {
                logRejectedResponse(
                        "heartbeat",
                        response
                );
            }
        });
    }

    private java.util.concurrent.CompletableFuture<
            HttpResponse<String>
            > sendJsonRequest(
            String url,
            String body,
            String operation
    ) {
        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header(
                            "Authorization",
                            "Bearer " + apiToken
                    )
                    .header(
                            "Accept",
                            "application/json"
                    )
                    .header(
                            "Content-Type",
                            "application/json"
                    )
                    .POST(
                            HttpRequest.BodyPublishers.ofString(body)
                    )
                    .build();

        } catch (IllegalArgumentException exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "URL invalida para " + operation + ": " + url,
                            exception
                    )
            );
        }

        return httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private boolean isSuccessful(
            HttpResponse<String> response
    ) {
        return response.statusCode() >= 200
                && response.statusCode() < 300;
    }

    private void logRejectedResponse(
            String operation,
            HttpResponse<String> response
    ) {
        if (response.statusCode() == 401) {
            plugin.getLogger().warning(
                    "EchoPanel recusou o "
                            + operation
                            + " com HTTP 401: token invalido. "
                            + "Copie novamente a chave exibida no "
                            + "EchoPanel para plugins/EchoCraft/config.yml."
            );
            return;
        }

        plugin.getLogger().warning(
                "EchoPanel recusou o "
                        + operation
                        + " com HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body()
        );
    }

    private void logRequestFailure(
            String message,
            Throwable exception
    ) {
        plugin.getLogger().log(
                Level.WARNING,
                message,
                exception
        );
    }

    private String toNullableJsonString(String value) {
        if (value == null || value.isBlank()) {
            return "null";
        }

        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
