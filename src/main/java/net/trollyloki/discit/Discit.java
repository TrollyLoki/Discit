package net.trollyloki.discit;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class Discit {

    private final JDA jda;
    private final Map<String, GuildManager> guildManagers = new ConcurrentHashMap<>();

    public Discit() throws InterruptedException {
        this.jda = JDABuilder.createLight(System.getenv("BOT_TOKEN"), Collections.emptyList()).build();
        this.jda.addEventListener(new InteractionListener(this));

        this.jda.awaitReady();

        for (Guild guild : this.jda.getGuilds()) {
            // Should probably have a better way of initializing the guild managers
            getGuildManager(guild.getId());
        }
    }

    public JDA getJDA() {
        return jda;
    }

    public void shutdown() throws InterruptedException {
        jda.shutdown();
        jda.awaitShutdown();
    }

    public synchronized GuildManager getGuildManager(String guildId) {
        return guildManagers.computeIfAbsent(guildId, k -> {
            try {
                return GuildManager.load(jda, k);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void main(String[] args) throws InterruptedException {
        Discit discit = new Discit();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("SHUTDOWN");
            try {
                discit.shutdown();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
    }

}
