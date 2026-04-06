package net.trollyloki.discit;

import net.trollyloki.jicsit.server.query.QueryApi;
import net.trollyloki.jicsit.server.query.ServerState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import net.trollyloki.jicsit.server.query.ServerSubState;
import net.trollyloki.jicsit.server.query.protocol.payload.ServerStatePayload;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.SocketException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.serverThreadFactory;
import static net.trollyloki.discit.LoggingUtils.setMDC;

@NullMarked
public class ServerMonitor implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMonitor.class);

    private static final long
            POLL_INTERVAL_MILLIS = Duration.ofMillis(500).toMillis(),
            OFFLINE_TIMEOUT_MILLIS = Duration.ofSeconds(5).toMillis(),
            DEAD_TIMEOUT_NANOS = Duration.ofMinutes(1).toNanos(),
            DEAD_POLL_INTERVAL_MILLIS = Duration.ofSeconds(10).toMillis();

    private final GuildManager guildManager;
    private final UUID serverId;
    private final Server server;

    private final GameStateCache gameStateCache;
    private final DashboardUpdater dashboardUpdater;

    private final ScheduledExecutorService requestServerStateExecutor;
    private final ScheduledExecutorService receiveServerStateExecutor;
    private final ScheduledExecutorService updateExecutor;

    private @Nullable QueryApi queryApi;

    private @Nullable ScheduledFuture<?> offlineFuture;
    private @Nullable ScheduledFuture<?> offlineAlertFuture;

    private volatile long lastResponseNanos = System.nanoTime();
    private @Nullable ServerStatus lastStatus;
    private short lastGameStateVersion;

    public ServerMonitor(GuildManager guildManager, UUID serverId) {
        this.guildManager = guildManager;
        this.serverId = serverId;

        Server server = guildManager.getServer(serverId);
        if (server == null) {
            throw new IllegalArgumentException("Server does not exist: " + serverId);
        }
        this.server = server;

        gameStateCache = new GameStateCache(guildManager, serverId, server);
        dashboardUpdater = new DashboardUpdater(guildManager, serverId, server.hasToken());

        requestServerStateExecutor = Executors.newSingleThreadScheduledExecutor(serverThreadFactory(serverId, "State Request Thread"));
        requestServerStateExecutor.submit(this::requestServerState);

        receiveServerStateExecutor = Executors.newSingleThreadScheduledExecutor(serverThreadFactory(serverId, "State Receive Thread"));
        receiveServerStateExecutor.scheduleWithFixedDelay(this::receiveServerState, 0, 1, TimeUnit.NANOSECONDS);

        updateExecutor = Executors.newSingleThreadScheduledExecutor(serverThreadFactory(serverId, "Server Update Thread"));
        scheduleOfflineFuture();
    }

    public GameStateCache getGameStateCache() {
        return gameStateCache;
    }

    public DashboardUpdater getDashboardUpdater() {
        return dashboardUpdater;
    }

    @Override
    public synchronized void close() {
        requestServerStateExecutor.shutdownNow();
        receiveServerStateExecutor.shutdownNow();
        updateExecutor.shutdownNow();

        if (queryApi != null) {
            LOGGER.info("Closing monitor socket for {}", serverNameForLog(server.getName()));
            queryApi.close();
        }

        gameStateCache.shutdownNow();
        dashboardUpdater.shutdown();
    }

    private synchronized QueryApi getQueryApi() throws SocketException {
        if (queryApi == null || queryApi.isClosed()) {
            queryApi = server.queryApi(null);
        }
        return queryApi;
    }

    private void requestServerState() {
        try {
            setMDC(guildManager);

            getQueryApi().requestServerState(System.nanoTime());

        } catch (Exception e) {
            LOGGER.warn("Unexpected exception while requesting server state for {}", serverNameForLog(server.getName()), e);
        }

        requestServerStateExecutor.schedule(this::requestServerState,
                (System.nanoTime() - lastResponseNanos) > DEAD_TIMEOUT_NANOS
                        ? DEAD_POLL_INTERVAL_MILLIS
                        : POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private void receiveServerState() {
        try {
            setMDC(guildManager);

            ServerStatePayload response = getQueryApi().receiveServerState();
            lastResponseNanos = System.nanoTime();
            Duration ping = Duration.ofNanos(lastResponseNanos - response.cookie());
            ServerState state = response.state();

            String name = state.name();
            ServerStatus status = state.status();
            short gameStateVersion = state.subStateVersion(ServerSubState.SERVER_GAME_STATE);

            // Reschedule offline update
            if (offlineFuture != null) offlineFuture.cancel(true);
            scheduleOfflineFuture();

            // Reschedule offline alert
            if (offlineAlertFuture != null) offlineAlertFuture.cancel(true);
            Duration alertDelay = guildManager.getOfflineAlertDelay();
            if (alertDelay != null) {
                offlineAlertFuture = updateExecutor.schedule(
                        () -> guildManager.logAlert(inlineServerDisplayName(server.getName()) + " went down"),
                        alertDelay.toNanos(), TimeUnit.NANOSECONDS
                );
            }

            updateExecutor.submit(() -> {
                setMDC(guildManager);

                // Update name if it changed
                if (!name.equals(server.getName())) {
                    guildManager.updateServerName(serverId, name);
                }

                if (status != lastStatus || gameStateVersion != lastGameStateVersion) {
                    LOGGER.info("New state response from {}: \"{}\" {}", serverNameForLog(server.getName()), status, gameStateVersion);

                    if (status != lastStatus && lastStatus == ServerStatus.PLAYING) {
                        gameStateCache.reset();
                    } else if (status == ServerStatus.PLAYING) {
                        gameStateCache.refresh();
                    }

                    lastStatus = status;
                    lastGameStateVersion = gameStateVersion;
                }

                updateDashboardInfo(status, ping);

            });

        } catch (Exception e) {
            LOGGER.warn("Unexpected exception while receiving server state for {}", serverNameForLog(server.getName()), e);
        }
    }

    private void scheduleOfflineFuture() {
        offlineFuture = updateExecutor.schedule(() -> {
            setMDC(guildManager);

            long millis = (System.nanoTime() - lastResponseNanos) / 1_000_000;
            LOGGER.info("No state responses from {} in the last {} milliseconds", serverNameForLog(server.getName()), millis);

            if (lastStatus == ServerStatus.PLAYING) {
                gameStateCache.reset();
            }

            lastStatus = null;
            lastGameStateVersion = 0;

            updateDashboardInfo(ServerStatus.OFFLINE, null);

        }, OFFLINE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void updateDashboardInfo(ServerStatus status, @Nullable Duration ping) {
        dashboardUpdater.setInfo(server.getName(), status, gameStateCache.getMessage(), gameStateCache.getGameState(), ping);
    }

}
