package net.trollyloki.discit.interactions.cache;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@NullMarked
public class InteractionDataCache<K, D> {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionDataCache.class);

    private static final long EXPIRATION_TIME = Duration.ofMinutes(15).toNanos();

    private record CachedData<D>(long nanoTime, D data) {
        CachedData(D data) {
            this(System.nanoTime(), data);
        }

        boolean isExpired() {
            return (System.nanoTime() - nanoTime) > EXPIRATION_TIME;
        }
    }

    private final Map<K, CachedData<D>> map = new HashMap<>();

    public synchronized void removeExpired() {
        map.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                LOGGER.info("Cache entry {} expired", entry.getKey());
                return true;
            }
            return false;
        });
    }

    public synchronized void put(K key, D data) {
        // Remove any expired entries before adding more
        removeExpired();

        map.put(key, new CachedData<>(data));
        LOGGER.info("Cache contains {} entries after put", map.size());
    }

    public synchronized @Nullable D pop(K key) {
        CachedData<D> cachedData = map.remove(key);
        if (cachedData == null) return null;

        LOGGER.info("Cache contains {} entries after pop", map.size());
        return cachedData.data;
    }

}
