package net.trollyloki.discit;

import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.https.exception.InvalidTokenException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.trollyloki.discit.LoggingUtils.*;

@NullMarked
public class GameStateCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameStateCache.class);

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    private static long retryAfterMillis(int errorCount) {
        return switch (errorCount) {
            case 0 -> 0;
            case 1 -> 500;
            case 2 -> 1_000;
            case 3 -> 2_000;
            case 4 -> 5_000;
            default -> 10_000;
        };
    }

    private final GuildManager guildManager;
    private final Server server;

    private final HttpsApi httpsApi;
    private final ScheduledExecutorService executor;

    private int errorCount;

    private @Nullable Future<?> future;
    private @Nullable ServerGameState gameState;
    private @Nullable String message;

    public GameStateCache(GuildManager guildManager, UUID serverId, Server server) {
        this.guildManager = guildManager;
        this.server = server;

        this.httpsApi = server.httpsApi(QUERY_TIMEOUT);
        this.executor = Executors.newSingleThreadScheduledExecutor(serverThreadFactory(serverId, "Game State Query Thread"));
    }

    public void shutdownNow() {
        executor.shutdownNow();
    }

    public void refresh() {
        executor.execute(() -> {
            synchronized (this) {
                if (future != null && !future.isDone()) return;

                future = executor.submit(this::query);
            }
        });
    }

    public synchronized void reset() {
        if (future != null && !future.isDone()) {
            LOGGER.info("Cancelling game state query and clearing cached game state for {}", serverNameForLog(server.getName()));
            future.cancel(true);
        } else {
            LOGGER.info("Clearing cached game state for {}", serverNameForLog(server.getName()));
        }

        this.gameState = null;
        this.message = null;
    }

    public synchronized @Nullable ServerGameState getGameState() {
        return gameState;
    }

    public synchronized @Nullable String getMessage() {
        return message;
    }

    private synchronized void set(@Nullable ServerGameState gameState, @Nullable String message) {
        if (future == null || future.isCancelled()) return;

        this.gameState = gameState;
        this.message = message;
    }

    private void query() {
        setMDC(guildManager);
        LOGGER.info("Querying game state of {}", serverNameForLog(server.getName()));

        try {
            String token = server.getToken();
            if (token != null) {
                httpsApi.setToken(token);
            } else if (httpsApi.getPrivilegeLevel() != PrivilegeLevel.CLIENT) {
                LOGGER.info("Attempting passwordless login to {}", serverNameForLog(server.getName()));
                httpsApi.setToken(null);
                httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
            }

            ServerGameState gameState = httpsApi.queryServerState();

            set(gameState, null);

            errorCount = 0;
            return;

        } catch (ApiException e) {
            LOGGER.warn("Unable to query game state of {}: {}", serverNameForLog(server.getName()), e.getMessage());
            if (e instanceof InvalidTokenException) httpsApi.setToken(null);

            set(null, e.getMessage());

        } catch (Exception e) {
            LOGGER.warn("Failed to query game state of {}", serverNameForLog(server.getName()), e);

            set(null, null);

        }

        synchronized (this) {
            if (future == null || future.isCancelled()) return;

            long retryAfterMillis = retryAfterMillis(++errorCount);
            LOGGER.warn("Retrying query in {} milliseconds", retryAfterMillis);
            future = executor.schedule(this::query, retryAfterMillis, TimeUnit.MILLISECONDS);
        }
    }

}
