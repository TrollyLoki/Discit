package net.trollyloki.discit;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.Interaction;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.ThreadFactory;

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

    public static ThreadFactory serverThreadFactory(UUID serverId, String threadName) {
        return runnable -> new Thread(runnable, threadName + " " + serverId);
    }

}
