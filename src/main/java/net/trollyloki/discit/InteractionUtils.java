package net.trollyloki.discit;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.messages.MessageSnapshot;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.trollyloki.jicsit.save.SaveHeader;
import net.trollyloki.jicsit.save.Session;
import net.trollyloki.jicsit.server.https.CommandResult;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.https.exception.InvalidTokenException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import static net.trollyloki.discit.FormattingUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class InteractionUtils {
    private InteractionUtils() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionUtils.class);

    public static final Color
            RED_ACCENT = Color.getHSBColor(.000f, .75f, 1.00f),
            YELLOW_ACCENT = Color.getHSBColor(.125f, .75f, 1.00f),
            GREEN_ACCENT = Color.getHSBColor(.375f, .75f, 1.00f);

    public static final Emoji
            CHECKBOX_CHECKED_EMOJI = Emoji.fromUnicode("✅"),
            CHECKBOX_EMPTY_EMOJI = Emoji.fromUnicode("🔳");

    public static @Nullable List<Message.Attachment> findMessageAttachments(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = new ArrayList<>(event.getTarget().getAttachments());
        for (MessageSnapshot snapshot : event.getTarget().getMessageSnapshots()) {
            attachments.addAll(snapshot.getAttachments());
        }

        if (attachments.isEmpty()) {
            event.reply("Could not find any files attached to that message").setEphemeral(true).queue();
            return null;
        }
        return attachments;
    }

    public static GuildManager getGuildManager(Interaction interaction) {
        Guild guild = interaction.getGuild();
        if (guild == null) {
            throw new UnsupportedOperationException("Interaction must take place within a guild");
        }
        return Discit.get().getGuildManager(guild.getId());
    }

    public static boolean isDashboard(Interaction interaction) {
        return getGuildManager(interaction).isDashboard(interaction.getChannel());
    }

    public static @Nullable Member getMember(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member == null) {
            callback.reply("That command can only be used within a guild").setEphemeral(true).queue();
            return null;
        }
        return member;
    }

    public static boolean cannotManageGuild(IReplyCallback callback) {
        Member member = getMember(callback);
        if (member == null)
            return true;

        if (member.hasPermission(Permission.MANAGE_SERVER)) {
            return false;
        }

        LOGGER.info("Unauthorized user: {} does not have the Manager Server permission", callback.getUser().getAsMention());
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static boolean isNotAdmin(IReplyCallback callback) {
        Member member = getMember(callback);
        if (member == null)
            return true;

        if (member.hasPermission(Permission.MANAGE_SERVER) || getGuildManager(callback).hasAdminRole(member)) {
            return false;
        }

        LOGGER.info("Unauthorized user: {} does not have the administrator role or Manager Server permission", callback.getUser().getAsMention());
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static @Nullable Server getServerIfAdmin(IReplyCallback callback, String serverIdString) {
        if (isNotAdmin(callback))
            return null;

        Server server = getGuildManager(callback).getServer(UUID.fromString(serverIdString));
        if (server == null) {
            LOGGER.warn("Unknown server {}", serverIdString);
            callback.reply("Unknown server").setEphemeral(true).queue();
            return null;
        }
        return server;
    }

    public static @Nullable Map<UUID, Server> getAllServersIfAdmin(IReplyCallback callback, boolean ignoreServerChannels) {
        if (isNotAdmin(callback))
            return null;

        GuildManager guildManager = getGuildManager(callback);

        if (!ignoreServerChannels) {
            // If this is a server channel, return a singleton map of the server
            Map.Entry<UUID, Server> channelServer = guildManager.getChannelServer(callback.getChannelId());
            if (channelServer != null) {
                return Map.ofEntries(channelServer);
            }
        }

        // Otherwise return a map of all servers, unless there isn't any
        Map<UUID, Server> servers = guildManager.getServers();
        if (servers.isEmpty()) {
            callback.reply("No servers added").setEphemeral(true).queue();
            return null;
        }
        return servers;
    }

    public static @Nullable Map<UUID, Server> getAllServersIfAdmin(IReplyCallback callback) {
        return getAllServersIfAdmin(callback, false);
    }

    public static @Nullable List<Server> getServersIfAdmin(IReplyCallback callback, Collection<String> serverIdStrings) {
        List<Server> servers = new ArrayList<>(serverIdStrings.size());
        for (String serverIdString : serverIdStrings) {
            Server server = getServerIfAdmin(callback, serverIdString);
            if (server == null)
                return null;

            servers.add(server);
        }
        return servers;
    }

    public static void logAction(Interaction interaction, String action) {
        getGuildManager(interaction).logAction(interaction.getUser(), action);
    }

    public static void logActionWithServer(Interaction interaction, String action, @Nullable String serverName) {
        getGuildManager(interaction).logAction(interaction.getUser(), action + " " + inlineServerDisplayName(serverName));
    }

    public static TextInput.Builder serverNameInput(String customId) {
        // servers seem to truncate names that are longer than 32 characters
        return TextInput.create(customId, TextInputStyle.SHORT).setMaxLength(32);
    }

    public static StringSelectMenu.Builder serverSelectMenu(String customId, Map<?, Server> servers) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(customId);

        int count = 0;
        for (Map.Entry<?, Server> entry : servers.entrySet()) {
            if (count == StringSelectMenu.OPTIONS_MAX_AMOUNT) {
                LOGGER.warn("Truncated server select options from {} to {}", servers.size(), count);
                break;
            }
            builder.addOption(serverDisplayName(entry.getValue().getName()), entry.getKey().toString());
            count++;
        }

        return builder;
    }

    private static final List<ChannelType> GUILD_MESSAGE_CHANNEL_TYPES = ChannelType.guildTypes().stream().filter(ChannelType::isMessage).toList();

    public static EntitySelectMenu.Builder messageChannelSelect(String customId) {
        return EntitySelectMenu.create(customId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(GUILD_MESSAGE_CHANNEL_TYPES);
    }

    public static StringSelectMenu createIntSelectMenu(String customId, IntFunction<String> labelFunction, int current, int[] ascendingOptions) {
        int maxOptions = StringSelectMenu.OPTIONS_MAX_AMOUNT - 1;
        if (ascendingOptions.length > maxOptions) {
            throw new IllegalArgumentException("Too many options: " + ascendingOptions.length + " > " + maxOptions);
        }

        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        boolean currentAdded = false;
        for (int value : ascendingOptions) {

            if (!currentAdded && value >= current) {
                if (value != current) {
                    // Insert the current value before this value
                    selectMenu.addOption(labelFunction.apply(current), Integer.toString(current));
                }
                currentAdded = true;
            }

            selectMenu.addOption(labelFunction.apply(value), Integer.toString(value));
        }
        if (!currentAdded) {
            // Insert the current value at the end since it must be bigger than every other option
            selectMenu.addOption(labelFunction.apply(current), Integer.toString(current));
        }

        return selectMenu.setDefaultValues(Integer.toString(current)).build();
    }

    public static String generateToken(HttpsApi httpsApi) {
        String output = httpsApi.runCommand("server.GenerateAPIToken").outputLines()[0];
        return output.substring(output.indexOf(':') + 1).trim();
    }

    public static CommandResult invalidateTokens(HttpsApi httpsApi) {
        return httpsApi.runCommand("server.InvalidateAPITokens");
    }

    public static boolean verifyAndSetToken(ModalInteractionEvent event, String serverIdString, String token, @Nullable String serverName) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return false;

        serverName = serverName != null ? serverName : server.getName();

        // Validate token
        try {
            PrivilegeLevel privilegeLevel = PrivilegeLevel.ofToken(token);
            if (privilegeLevel != PrivilegeLevel.API_TOKEN) {
                event.getHook().sendMessage("Incorrect token type").setEphemeral(true).queue();
                return false;
            }
        } catch (IllegalArgumentException e) {
            event.getHook().sendMessage("Incorrect token format").setEphemeral(true).queue();
            return false;
        }

        LOGGER.info("Verifying token for {}", serverNameForLog(serverName));

        // Verify token
        try {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            httpsApi.setToken(token);
            httpsApi.verifyAuthenticationToken();
        } catch (InvalidTokenException e) {
            event.getHook().sendMessage("Token is invalid").setEphemeral(true).queue();
            return false;
        } catch (Exception e) {
            event.getHook().sendMessage("Failed to verify token").setEphemeral(true).queue();
            LOGGER.warn("Failed to verify authentication token for {}", serverNameForLog(serverName), e);
            return false;
        }

        // Save token
        if (!getGuildManager(event).setServerToken(UUID.fromString(serverIdString), token)) {
            event.getHook().sendMessage("Failed to save token").setEphemeral(true).queue();
            return false;
        }

        event.getHook().sendMessage("Authentication successful").setEphemeral(true).queue();
        logActionWithServer(event, "added an authentication token for", serverName);
        return true;
    }

    public static CompletableFuture<@Nullable Void> requestAsyncWithMDC(Server server, String actionString, Consumer<HttpsApi> action) {
        return requestAsyncWithMDC(server, actionString, httpsApi -> {
            action.accept(httpsApi);
            return null;
        });
    }

    public static <T extends @Nullable Object> CompletableFuture<T> requestAsyncWithMDC(Server server, String actionString, Function<HttpsApi, T> action) {
        return CompletableFuture.supplyAsync(withMDC(() -> {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            return action.apply(httpsApi);
        })).exceptionally(withMDC((Function<Throwable, T>) exception -> {
            String message = actionString + " " + inlineServerDisplayName(server.getName());
            // Thrown exceptions are always wrapped in a CompletionException
            if (exception.getCause() instanceof GameNotRunningException) {
                message = "Cannot " + message + ": No session is running";
                LOGGER.warn("Refusing to execute request on {}", serverNameForLog(server.getName()), exception.getCause());
            } else if (exception.getCause() instanceof ApiException apiException) {
                message = "Unable to " + message + ": " + apiException.getMessage();
                LOGGER.warn("Unable to execute request on {}: {} ({})", serverNameForLog(server.getName()), apiException.getMessage(), apiException.getErrorCode());
            } else {
                message = "Failed to " + message;
                LOGGER.warn("Failed to execute request on {}", serverNameForLog(server.getName()), exception.getCause());
            }
            throw new FormattedException(message, exception.getCause());
        }));
    }

    private static class FormattedException extends RuntimeException {
        private FormattedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static String exceptionMessage(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            // Unwrap CompletionExceptions
            throwable = throwable.getCause();
        }

        if (throwable instanceof FormattedException formatted) {
            return formatted.getMessage();
        } else {
            LOGGER.error("Unexpected exception while processing interaction", throwable);
            return "An unexpected error occurred";
        }
    }

    private static class GameNotRunningException extends IllegalStateException {
        private GameNotRunningException(String message) {
            super(message);
        }
    }

    private static void saveIfRunning(HttpsApi httpsApi, String saveName) {
        if (!httpsApi.queryServerState().isGameRunning()) {
            // Game is not running, save function will hang if executed
            throw new GameNotRunningException("Cannot create save because game is not running");
        }
        httpsApi.save(saveName);
    }

    public static CompletableFuture<Boolean> reloadAsyncWithMDC(Server server) {
        String saveName = Discit.RELOAD_SAVE_NAME;
        return requestAsyncWithMDC(server, "reload", httpsApi -> {

            Optional<Instant> previousTimestamp = Optional.ofNullable(httpsApi.enumerateSessions().current())
                    .map(session -> session.find(saveName))
                    .map(SaveHeader::saveTimestamp);

            saveIfRunning(httpsApi, saveName);

            // Verify that we aren't going to inadvertently load the previous save
            Session session = httpsApi.enumerateSessions().current();
            if (session == null) {
                LOGGER.warn("Reload verification failed: Current session is null");
                return false;
            }

            SaveHeader header = session.find(saveName);
            if (header == null) {
                LOGGER.warn("Reload verification failed: Could not find save header");
                return false;
            }

            if (previousTimestamp.isPresent() && !header.saveTimestamp().isAfter(previousTimestamp.get())) {
                LOGGER.warn("Reload verification failed: Save is older than expected");
                return false;
            }

            httpsApi.loadSave(saveName, false);
            return true;
        });
    }

    public static CompletableFuture<String> reloadHelper(Interaction interaction, Server server) {
        return reloadAsyncWithMDC(server).thenApplyAsync(withMDC(verified -> {
            if (!verified) {
                return "Reload verification for " + inlineServerDisplayName(server.getName()) + " failed, please try again";
            }
            logActionWithServer(interaction, "reloaded", server.getName());
            return "Successfully reloaded " + inlineServerDisplayName(server.getName());
        }));
    }

    public static CompletableFuture<SaveInfo> saveAsyncWithMDC(Server server, @Nullable String saveName) {
        return requestAsyncWithMDC(server, "save", httpsApi -> {

            String actualSaveName = saveName;
            if (actualSaveName == null || actualSaveName.isBlank()) {
                String sessionName = httpsApi.queryServerState().activeSessionName();
                actualSaveName = defaultSaveName(sessionName, LocalDateTime.now(Clock.systemUTC()));
            }

            saveIfRunning(httpsApi, actualSaveName);
            Instant fallbackTimestamp = Instant.now();

            SaveHeader saveHeader = null;
            Session session = httpsApi.enumerateSessions().current();
            if (session != null) saveHeader = session.find(actualSaveName);

            if (saveHeader != null) {
                return new SaveInfo(actualSaveName, saveHeader.sessionName(), saveHeader.saveTimestamp());
            } else {
                LOGGER.warn("Couldn't find save header for save name \"{}\"", actualSaveName);
                return new SaveInfo(actualSaveName, null, fallbackTimestamp);
            }

        });
    }

}
