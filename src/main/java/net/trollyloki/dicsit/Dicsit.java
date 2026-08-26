package net.trollyloki.dicsit;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static net.trollyloki.dicsit.interactions.AboutInteractions.ABOUT_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.AddInteractions.ADD_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.AnalyzeSaveInteractions.ANALYZE_SAVE_CONTEXT_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.BackupInteractions.BACKUP_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.DeferredActionsInteractions.DEFERRED_ACTIONS_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.DeployInteractions.*;
import static net.trollyloki.dicsit.interactions.ListInteractions.LIST_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.ReloadInteractions.RELOAD_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.ReloadInteractions.RESTART_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.SaveInteractions.SAVE_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.SettingsInteractions.SETTINGS_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.UploadInteractions.UPLOAD_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.UploadInteractions.UPLOAD_CONTEXT_COMMAND_NAME;

@NullMarked
public class Dicsit {

    private static final Logger LOGGER = LoggerFactory.getLogger(Dicsit.class);

    public static final String
            RELOAD_SAVE_NAME = "Dicsit_reload",
            RESTART_SAVE_NAME = "Dicsit_restart";

    public static final @Nullable String VERSION;

    static {
        Properties properties = new Properties();
        try (InputStream stream = Dicsit.class.getClassLoader().getResourceAsStream("project.properties")) {
            if (stream != null) properties.load(stream);
            else LOGGER.error("Could not find project.properties resource");
        } catch (IOException e) {
            LOGGER.error("Failed to load version from project.properties resource", e);
        }
        VERSION = properties.getProperty("version");
    }

    private static final @Nullable String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String DATA_DIRECTORY;
    public static final boolean ACCEPT_LOCAL_ADDRESSES;
    public static final boolean ACCEPT_DUPLICATE_SERVERS;
    public static final long POLL_INTERVAL_MILLIS;
    public static final long OFFLINE_TIMEOUT_MILLIS;
    public static final long DEAD_TIMEOUT_MILLIS;
    public static final long DEAD_POLL_INTERVAL_MILLIS;
    public static final long ACTION_ATTEMPT_INTERVAL;
    public static final int MAX_ACTION_ATTEMPTS;

    static {
        String dataDirectory = System.getenv("DATA_DIRECTORY");
        DATA_DIRECTORY = dataDirectory == null ? "data" : dataDirectory;

        ACCEPT_LOCAL_ADDRESSES = "true".equals(System.getenv("ACCEPT_LOCAL_ADDRESSES"));

        ACCEPT_DUPLICATE_SERVERS = "true".equals(System.getenv("ACCEPT_DUPLICATE_SERVERS"));

        String pollInterval = System.getenv("POLL_INTERVAL");
        POLL_INTERVAL_MILLIS = pollInterval == null ? 500 : Long.parseLong(pollInterval);

        String offlineTimeout = System.getenv("OFFLINE_TIMEOUT");
        OFFLINE_TIMEOUT_MILLIS = offlineTimeout == null ? 5_000 : Long.parseLong(offlineTimeout);

        String deadTimeout = System.getenv("DEAD_TIMEOUT");
        DEAD_TIMEOUT_MILLIS = deadTimeout == null ? 60_000 : Long.parseLong(deadTimeout);

        String deadPollInterval = System.getenv("DEAD_POLL_INTERVAL");
        DEAD_POLL_INTERVAL_MILLIS = deadPollInterval == null ? 10_000 : Long.parseLong(deadPollInterval);

        String actionAttemptInterval = System.getenv("ACTION_ATTEMPT_INTERVAL");
        ACTION_ATTEMPT_INTERVAL = actionAttemptInterval == null ? 3_000 : Long.parseLong(actionAttemptInterval);

        String maxActionAttempts = System.getenv("MAX_ACTION_ATTEMPTS");
        MAX_ACTION_ATTEMPTS = maxActionAttempts == null ? 5 : Integer.parseInt(maxActionAttempts);
    }

    private final Map<String, GuildManager> guildManagers = new ConcurrentHashMap<>();

    private final JDA jda;
    private final Map<String, Command> commands;

    public Dicsit() throws InterruptedException {
        if (BOT_TOKEN == null) {
            throw new IllegalArgumentException("Bot token must be provided via the BOT_TOKEN environment variable");
        }

        LOGGER.info("Starting Dicsit{}", VERSION == null ? "" : " v" + VERSION);

        jda = JDABuilder.createLight(BOT_TOKEN, Collections.emptyList()).build();
        jda.addEventListener(new InteractionListener());

        if (VERSION != null) {
            jda.getPresence().setPresence(Activity.customStatus("v" + VERSION), false);
        }

        commands = jda.updateCommands().addCommands(
                Commands.slash(ABOUT_COMMAND_NAME, "Display information about the app").setContexts(
                        InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL
                ).setIntegrationTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL),
                Commands.slash(SETTINGS_COMMAND_NAME, "Change settings").setContexts(InteractionContextType.GUILD),
                Commands.slash(ADD_COMMAND_NAME, "Add a server").setContexts(InteractionContextType.GUILD).addOptions(
                        new OptionData(OptionType.STRING, "host", "Server host address", true),
                        new OptionData(OptionType.INTEGER, "port", "Server port", true)
                                .setRequiredRange(0, 65535)
                ),
                Commands.slash(LIST_COMMAND_NAME, "List added servers and their settings").setContexts(InteractionContextType.GUILD),
                Commands.slash(RELOAD_COMMAND_NAME, "Save and reload the active session on one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.slash(RESTART_COMMAND_NAME, "Save and restart one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.slash(SAVE_COMMAND_NAME, "Create and download a save from a server").setContexts(InteractionContextType.GUILD),
                Commands.slash(UPLOAD_COMMAND_NAME, "Upload a save file to one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.message(UPLOAD_CONTEXT_COMMAND_NAME).setContexts(InteractionContextType.GUILD),
                Commands.slash(BACKUP_COMMAND_NAME, "Create a backup of saves from one or more servers").setContexts(InteractionContextType.GUILD),
                Commands.slash(DEFERRED_ACTIONS_COMMAND_NAME, "Manage deferred actions").setContexts(InteractionContextType.GUILD),
                Commands.slash(DEPLOY_COMMAND_NAME, "Deploy a save to or restart of all event servers").setContexts(InteractionContextType.GUILD).addSubcommands(
                        new SubcommandData(DEPLOY_SAVE_SUBCOMMAND_NAME, "Deploy a save to all event servers").addOptions(
                                new OptionData(OptionType.ATTACHMENT, "save", "Save file to deploy", true)
                        ),
                        new SubcommandData(DEPLOY_RESTART_SUBCOMMAND_NAME, "Deploy a restart of all event servers"),
                        new SubcommandData(DEPLOY_LOCK_SUBCOMMAND_NAME, "Lock all event servers behind a password").addOptions(
                                new OptionData(OptionType.STRING, "password", "Client password", true)
                        ),
                        new SubcommandData(DEPLOY_UNLOCK_SUBCOMMAND_NAME, "Unlock all event servers")
                ),
                Commands.message(DEPLOY_SAVE_CONTEXT_COMMAND_NAME).setContexts(InteractionContextType.GUILD),
                Commands.message(ANALYZE_SAVE_CONTEXT_COMMAND_NAME).setContexts(
                        InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL
                ).setIntegrationTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL)
        ).complete().stream().collect(Collectors.toUnmodifiableMap(Command::getName, it -> it));

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

    public Command getCommand(String name) {
        Command command = commands.get(name);
        if (command == null) {
            throw new IllegalArgumentException("Unknown command: " + name);
        }
        return command;
    }

    private static @Nullable Dicsit INSTANCE;

    public static Dicsit get() {
        assert INSTANCE != null;
        return INSTANCE;
    }

    static void main() throws InterruptedException {
        MessageRequest.setDefaultMentions(Collections.emptySet());
        INSTANCE = new Dicsit();

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
