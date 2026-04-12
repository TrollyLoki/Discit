package net.trollyloki.discit;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.trollyloki.discit.interactions.AddInteractions.ADD_COMMAND_NAME;
import static net.trollyloki.discit.interactions.AnalyzeSaveInteractions.ANALYZE_SAVE_CONTEXT_COMMAND_NAME;
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

    public static final String RELOAD_SAVE_NAME = "reload_continue";

    private static final @Nullable String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String DATA_DIRECTORY;
    public static final boolean ACCEPT_LOCAL_ADDRESSES;

    static {
        String dataDirectory = System.getenv("DATA_DIRECTORY");
        DATA_DIRECTORY = dataDirectory != null ? dataDirectory : "data";
        ACCEPT_LOCAL_ADDRESSES = "true".equals(System.getenv("ACCEPT_LOCAL_ADDRESSES"));
    }

    private final JDA jda;
    private final Map<String, GuildManager> guildManagers = new ConcurrentHashMap<>();

    public Discit() throws InterruptedException {
        if (BOT_TOKEN == null) {
            throw new IllegalArgumentException("Bot token must be provided via the BOT_TOKEN environment variable");
        }

        this.jda = JDABuilder.createLight(BOT_TOKEN, Collections.emptyList()).build();
        this.jda.addEventListener(new InteractionListener());

        this.jda.updateCommands().addCommands(
                Commands.slash(SETTINGS_COMMAND_NAME, "Change settings").setContexts(InteractionContextType.GUILD),
                Commands.slash(ADD_COMMAND_NAME, "Add a server").setContexts(InteractionContextType.GUILD).addOptions(
                        new OptionData(OptionType.STRING, "host", "Server host address", true),
                        new OptionData(OptionType.INTEGER, "port", "Server port", true)
                                .setRequiredRange(0, 65535)
                ),
                Commands.slash(LIST_COMMAND_NAME, "List added servers").setContexts(InteractionContextType.GUILD),
                Commands.slash(RELOAD_COMMAND_NAME, "Save and reload the active session on one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.slash(SAVE_COMMAND_NAME, "Create and download a save from a server").setContexts(InteractionContextType.GUILD),
                Commands.slash(UPLOAD_COMMAND_NAME, "Upload a save file to one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.message(UPLOAD_CONTEXT_COMMAND_NAME).setContexts(InteractionContextType.GUILD),
                Commands.slash(BACKUP_COMMAND_NAME, "Create and download a save from each server").setContexts(InteractionContextType.GUILD).addOptions(
                        new OptionData(OptionType.STRING, "name", "Backup file name", true)
                                .setMaxLength(100) // arbitrary
                ),
                Commands.message(ANALYZE_SAVE_CONTEXT_COMMAND_NAME).setContexts(
                        InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL
                ).setIntegrationTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL)
        ).queue();

        this.jda.awaitReady();

        for (Guild guild : this.jda.getGuilds()) {
            LOGGER.info("Loading data for guild \"{}\"", guild.getName());
            try {
                // Should probably have a better way of initializing the guild managers
                getGuildManager(guild.getId());
            } catch (Exception e) {
                LOGGER.error("Failed to load guild manager for guild \"{}\"", guild.getName(), e);
            }
        }
    }

    public void shutdown() throws InterruptedException {
        jda.shutdown();
        jda.awaitShutdown();
    }

    public synchronized GuildManager getGuildManager(String guildId) {
        return guildManagers.computeIfAbsent(guildId, k -> GuildManager.load(jda, k));
    }

    private static @Nullable Discit INSTANCE;

    public static Discit get() {
        assert INSTANCE != null;
        return INSTANCE;
    }

    static void main() throws InterruptedException {
        MessageRequest.setDefaultMentions(Collections.emptySet());
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
