package net.trollyloki.discit;

import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.trollyloki.discit.LoggingUtils.setMDC;

@NullMarked
public class GameStateCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameStateCache.class);

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    private static long retryAfterMillis(long errorCount) {
        return Math.min(errorCount * 500, 10_000);
    }

    private final GuildManager guildManager;
    private final Server server;

    private final ScheduledExecutorService executor;

    private long errorCount;

    private @Nullable Future<?> future;
    private @Nullable ServerGameState gameState;
    private @Nullable String message;

    public GameStateCache(GuildManager guildManager, Server server) {
        this.guildManager = guildManager;
        this.server = server;

        this.executor = Executors.newSingleThreadScheduledExecutor();
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
            LOGGER.info("Cancelling game state query and clearing cached game state for server \"{}\"", server.getName());
            future.cancel(true);
        } else {
            LOGGER.info("Clearing cached game state for server \"{}\"", server.getName());
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
        LOGGER.info("Querying game state of server \"{}\"", server.getName());

        try {
            HttpsApi httpsApi = server.httpsApi(QUERY_TIMEOUT);
            if (httpsApi.getPrivilegeLevel() == PrivilegeLevel.NOT_AUTHENTICATED) {
                httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
            }
            ServerGameState gameState = httpsApi.queryServerState();

            set(gameState, null);

            errorCount = 0;
            return;

        } catch (ApiException e) {
            LOGGER.warn("Unable to query game state of server \"{}\": {}", server.getName(), e.getMessage());

            set(null, "Unable to query game state: " + e.getMessage());

        } catch (Exception e) {
            LOGGER.warn("Failed to query game state of server \"{}\"", server.getName(), e);

            set(null, "Failed to query game state");

        }

        synchronized (this) {
            if (future == null || future.isCancelled()) return;

            long retryAfterMillis = retryAfterMillis(++errorCount);
            LOGGER.warn("Retrying query in {} milliseconds", retryAfterMillis);
            future = executor.schedule(this::query, retryAfterMillis, TimeUnit.MILLISECONDS);
        }
    }

}
