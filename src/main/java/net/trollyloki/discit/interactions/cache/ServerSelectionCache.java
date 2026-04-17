package net.trollyloki.discit.interactions.cache;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

@NullMarked
public class ServerSelectionCache {

    private final InteractionDataCache<UUID, List<String>> cache = new InteractionDataCache<>();

    public UUID put(List<String> serverIdStrings) {
        UUID key = UUID.randomUUID();
        cache.put(key, serverIdStrings);
        return key;
    }

    public @Nullable List<String> pop(UUID key) {
        return cache.pop(key);
    }

}
