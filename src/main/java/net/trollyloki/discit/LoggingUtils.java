package net.trollyloki.discit;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.Interaction;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.function.*;

@NullMarked
public final class LoggingUtils {
    private LoggingUtils() {
    }

    public static final String
            GUILD = "guild",
            USER = "user";

    public static void setMDC(Interaction interaction) {
        MDC.put(USER, interaction.getUser().getName());

        Guild guild = interaction.getGuild();
        if (guild != null && !guild.isDetached()) MDC.put(GUILD, guild.getName());
        else MDC.remove(GUILD);
    }

    public static void setMDC(GuildManager guildManager) {
        MDC.put(GUILD, guildManager.getGuild().getName());
    }

    public static <T extends @Nullable Object, U extends @Nullable Object, R extends @Nullable Object> BiFunction<T, U, R> withMDC(BiFunction<T, U, R> biFunction) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return (t, u) -> {
            MDC.setContextMap(mdc);
            try {
                return biFunction.apply(t, u);
            } finally {
                MDC.clear();
            }
        };
    }

    public static <T extends @Nullable Object, U extends @Nullable Object> BiConsumer<T, U> withMDC(BiConsumer<T, U> biConsumer) {
        BiFunction<T, U, ?> withMDC = withMDC((t, u) -> {
            biConsumer.accept(t, u);
            return null;
        });
        return withMDC::apply;
    }

    public static <T extends @Nullable Object, R extends @Nullable Object> Function<T, R> withMDC(Function<T, R> function) {
        BiFunction<T, ?, R> withMDC = withMDC((t, _) -> {
            return function.apply(t);
        });
        return t -> withMDC.apply(t, null);
    }

    public static <T extends @Nullable Object> Consumer<T> withMDC(Consumer<T> consumer) {
        BiFunction<T, ?, ?> withMDC = withMDC((t, _) -> {
            consumer.accept(t);
            return null;
        });
        return t -> withMDC.apply(t, null);
    }

    public static <R extends @Nullable Object> Supplier<R> withMDC(Supplier<R> supplier) {
        BiFunction<?, ?, R> withMDC = withMDC((_, _) -> {
            return supplier.get();
        });
        return () -> withMDC.apply(null, null);
    }

    public static Runnable withMDC(Runnable runnable) {
        BiFunction<?, ?, ?> withMDC = withMDC((_, _) -> {
            runnable.run();
            return null;
        });
        return () -> withMDC.apply(null, null);
    }

    public static @Nullable String serverNameForLog(@Nullable String serverName) {
        if (serverName == null) return null;
        else return '"' + serverName + '"';
    }

    public static ThreadFactory serverThreadFactory(UUID serverId, String threadName) {
        return runnable -> new Thread(runnable, String.format("%-25s %s", threadName, serverId));
    }

}
