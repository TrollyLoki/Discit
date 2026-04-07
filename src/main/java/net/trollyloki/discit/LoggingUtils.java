package net.trollyloki.discit;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.Interaction;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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

    public static <T extends @Nullable Object, R extends @Nullable Object> Function<T, R> withMDC(Function<T, R> function) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return t -> {
            MDC.setContextMap(mdc);
            try {
                return function.apply(t);
            } finally {
                MDC.clear();
            }
        };
    }

    public static <T extends @Nullable Object> Consumer<T> withMDC(Consumer<T> consumer) {
        Function<T, ?> function = withMDC(t -> {
            consumer.accept(t);
            return null;
        });
        return function::apply;
    }

    public static <R extends @Nullable Object> Supplier<R> withMDC(Supplier<R> supplier) {
        Function<?, R> function = withMDC(_ -> {
            return supplier.get();
        });
        return () -> function.apply(null);
    }

    public static Runnable withMDC(Runnable runnable) {
        Function<?, ?> function = withMDC(_ -> {
            runnable.run();
            return null;
        });
        return () -> function.apply(null);
    }

    public static @Nullable String serverNameForLog(@Nullable String serverName) {
        if (serverName == null) return null;
        else return '"' + serverName + '"';
    }

    public static ThreadFactory serverThreadFactory(UUID serverId, String threadName) {
        return runnable -> new Thread(runnable, String.format("%-25s %s", threadName, serverId));
    }

}
