package net.trollyloki.discit;

import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.exception.ApiException;
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

import static net.trollyloki.discit.LoggingUtils.setMDC;

@NullMarked
public class ServerMonitor implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMonitor.class);

    private static final Duration
            POLL_INTERVAL = Duration.ofMillis(500),
            OFFLINE_TIMEOUT = Duration.ofSeconds(5),
            HTTPS_TIMEOUT = Duration.ofSeconds(3);
    private static final long
            HTTPS_RETRY_NANOS_MULTIPLIER = Duration.ofMillis(500).toNanos(),
            HTTPS_RETRY_NANOS_MAX = Duration.ofSeconds(10).toNanos();

    private final GuildManager guildManager;
    private final UUID serverId;
    private final Server server;

    private final DashboardUpdater dashboardUpdater;

    private final ScheduledExecutorService updateExecutor;
    private final ScheduledExecutorService requestServerStateExecutor;
    private final ScheduledExecutorService receiveServerStateExecutor;

    private @Nullable QueryApi queryApi;

    private @Nullable ScheduledFuture<?> timeoutFuture;

    private short cachedGameStateVersion;
    private @Nullable ServerGameState cachedGameState;

    private @Nullable Long lastHttpsQueryNanos;
    private @Nullable String httpsQueryMessage;
    private long httpsErrorCount;

    public ServerMonitor(GuildManager guildManager, UUID serverId) {
        this.guildManager = guildManager;
        this.serverId = serverId;

        Server server = guildManager.getServer(serverId);
        if (server == null) {
            throw new IllegalArgumentException("Server does not exist: " + serverId);
        }
        this.server = server;

        dashboardUpdater = new DashboardUpdater(guildManager, serverId, server.hasToken());

        updateExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduleOfflineTimeout();

        requestServerStateExecutor = Executors.newSingleThreadScheduledExecutor();
        requestServerStateExecutor.scheduleAtFixedRate(this::requestServerState, 0, POLL_INTERVAL.toNanos(), TimeUnit.NANOSECONDS);

        receiveServerStateExecutor = Executors.newSingleThreadScheduledExecutor();
        receiveServerStateExecutor.scheduleWithFixedDelay(this::receiveServerState, 0, 1, TimeUnit.NANOSECONDS);
    }

    public DashboardUpdater getDashboardUpdater() {
        return dashboardUpdater;
    }

    public void refresh() {
        updateExecutor.execute(() -> {
            cachedGameStateVersion = 0;
            cachedGameState = null;
        });
    }

    @Override
    public synchronized void close() {
        requestServerStateExecutor.shutdown();
        receiveServerStateExecutor.shutdown();
        updateExecutor.shutdown();

        if (queryApi != null) {
            LOGGER.info("Closing monitor socket for server \"{}\"", server.getName());
            queryApi.close();
        }

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
            LOGGER.warn("Unexpected exception while requesting server state for server \"{}\"", server.getName(), e);
        }
    }

    private void receiveServerState() {
        try {
            setMDC(guildManager);

            ServerStatePayload response = getQueryApi().receiveServerState();
            Duration ping = Duration.ofNanos(System.nanoTime() - response.cookie());
            ServerState state = response.state();

            // Update name if it changed
            if (!state.name().equals(server.getName())) {
                guildManager.updateServerName(serverId, state.name());
            }

            updateExecutor.submit(() -> onState(state, ping));
            if (timeoutFuture != null) timeoutFuture.cancel(true);
            scheduleOfflineTimeout();

        } catch (Exception e) {
            LOGGER.warn("Unexpected exception while receiving server state for server \"{}\"", server.getName(), e);
        }
    }

    private void scheduleOfflineTimeout() {
        timeoutFuture = updateExecutor.schedule(this::onOffline, OFFLINE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void onOffline() {
        setMDC(guildManager);

        dashboardUpdater.update(server.getName(), ServerStatus.OFFLINE, null, null, null);
    }

    private void onState(ServerState state, Duration ping) {
        setMDC(guildManager);

        String name = state.name();
        ServerStatus status = state.status();
        short gameStateVersion = state.subStateVersion(ServerSubState.SERVER_GAME_STATE);

        if (status == ServerStatus.PLAYING && (cachedGameStateVersion != gameStateVersion || shouldRetryHttps())) {
            queryGameState(gameStateVersion);
        }

        dashboardUpdater.update(name, status,
                status.isHttpsApiAvailable() ? httpsQueryMessage : null,
                status == ServerStatus.PLAYING ? cachedGameState : null,
                ping);
    }

    private void queryGameState(short gameStateVersion) {
        LOGGER.info("Querying HTTPS API of server \"{}\" for game state version {}", server.getName(), gameStateVersion);

        cachedGameStateVersion = gameStateVersion;
        cachedGameState = null;

        lastHttpsQueryNanos = System.nanoTime();
        try {

            HttpsApi httpsApi = server.httpsApi(HTTPS_TIMEOUT);
            if (httpsApi.getPrivilegeLevel() == PrivilegeLevel.NOT_AUTHENTICATED) {
                httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
            }
            cachedGameState = httpsApi.queryServerState();
            httpsQueryMessage = null;

            httpsErrorCount = 0;
            return;

        } catch (ApiException e) {
            httpsQueryMessage = "Unable to query game state: " + e.getMessage();
        } catch (Exception e) {
            httpsQueryMessage = "Failed to query game state";
            LOGGER.warn("Failed to query game state of server \"{}\"", server.getName(), e);
        }

        httpsErrorCount++;
    }

    private boolean shouldRetryHttps() {
        if (cachedGameState != null)
            return false;

        if (lastHttpsQueryNanos == null)
            return true;

        long elapsedNanos = System.nanoTime() - lastHttpsQueryNanos;
        return elapsedNanos > Math.min(httpsErrorCount * HTTPS_RETRY_NANOS_MULTIPLIER, HTTPS_RETRY_NANOS_MAX);
    }

}
