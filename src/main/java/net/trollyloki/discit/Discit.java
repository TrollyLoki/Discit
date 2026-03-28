package net.trollyloki.discit;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.trollyloki.discit.interactions.AddInteractions.ADD_COMMAND_NAME;
import static net.trollyloki.discit.interactions.BackupInteractions.BACKUP_COMMAND_NAME;
import static net.trollyloki.discit.interactions.ListInteractions.LIST_COMMAND_NAME;
import static net.trollyloki.discit.interactions.ReloadInteractions.RELOAD_COMMAND_NAME;
import static net.trollyloki.discit.interactions.SaveInteractions.SAVE_COMMAND_NAME;
import static net.trollyloki.discit.interactions.SettingsInteractions.SETTINGS_COMMAND_NAME;
import static net.trollyloki.discit.interactions.UploadInteractions.UPLOAD_COMMAND_NAME;
import static net.trollyloki.discit.interactions.UploadInteractions.UPLOAD_CONTEXT_COMMAND_NAME;

@NullMarked
public class Discit {

    private static final Logger LOGGER = LoggerFactory.getLogger(Discit.class);

    private final JDA jda;
    private final Map<String, GuildManager> guildManagers = new ConcurrentHashMap<>();

    public Discit() throws InterruptedException {
        this.jda = JDABuilder.createLight(System.getenv("BOT_TOKEN"), Collections.emptyList()).build();
        this.jda.addEventListener(new InteractionListener());

        this.jda.updateCommands().addCommands(
                Commands.slash(SETTINGS_COMMAND_NAME, "Change settings"),
                Commands.slash(ADD_COMMAND_NAME, "Add a server").addOptions(
                        new OptionData(OptionType.STRING, "host", "Server host address", true),
                        new OptionData(OptionType.INTEGER, "port", "Server port", true)
                                .setRequiredRange(0, 65535)
                ),
                Commands.slash(LIST_COMMAND_NAME, "List added servers"),
                Commands.slash(RELOAD_COMMAND_NAME, "Save and reload the active session on one or more servers"),
                Commands.slash(SAVE_COMMAND_NAME, "Create and download a save from a server"),
                Commands.slash(UPLOAD_COMMAND_NAME, "Upload a save file to one or more servers"),
                Commands.message(UPLOAD_CONTEXT_COMMAND_NAME),
                Commands.slash(BACKUP_COMMAND_NAME, "Create and download a save from each server").addOptions(
                        new OptionData(OptionType.STRING, "name", "Backup file name", true)
                )
        ).queue();

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
                LOGGER.error("Failed to initialize guild manager for guild {}", guildId, e);
                throw new RuntimeException(e);
            }
        });
    }

    private static @Nullable Discit INSTANCE;

    public static Discit get() {
        assert INSTANCE != null;
        return INSTANCE;
    }

    public static void main(String[] args) throws InterruptedException {
        INSTANCE = new Discit();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down");
            try {
                INSTANCE.shutdown();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
    }

}
