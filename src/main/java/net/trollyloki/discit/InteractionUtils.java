package net.trollyloki.discit;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.trollyloki.jicsit.save.SaveHeader;
import net.trollyloki.jicsit.save.Session;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.https.exception.InvalidTokenException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.awt.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.trollyloki.discit.FormattingUtils.defaultSaveName;
import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;

@NullMarked
public final class InteractionUtils {
    private InteractionUtils() {
    }

    public static final Color
            RED_ACCENT = Color.getHSBColor(.000f, .75f, 1.00f),
            YELLOW_ACCENT = Color.getHSBColor(.125f, .75f, 1.00f),
            GREEN_ACCENT = Color.getHSBColor(.375f, .75f, 1.00f);

    public static GuildManager getGuildManager(Interaction interaction) {
        Guild guild = interaction.getGuild();
        if (guild == null) //FIXME: Is this going to cause problems?
            throw new UnsupportedOperationException("This operation can only be done from within a guild");
        return Discit.get().getGuildManager(guild.getId());
    }

    public static boolean isDashboard(Interaction interaction) {
        return getGuildManager(interaction).isDashboard(interaction.getChannel());
    }

    public static boolean cannotManageGuild(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member != null && member.hasPermission(Permission.MANAGE_SERVER)) {
            return false;
        }
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static boolean missingAdminRole(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member != null && getGuildManager(callback).hasAdminRole(member)) {
            return false;
        }
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static @Nullable Server getServerIfAdmin(IReplyCallback callback, String serverIdString) {
        if (missingAdminRole(callback))
            return null;

        Server server = getGuildManager(callback).getServer(UUID.fromString(serverIdString));
        if (server == null) {
            callback.reply("Unknown server").setEphemeral(true).queue();
            return null;
        }
        return server;
    }

    public static @Nullable Map<UUID, Server> getAllServersIfAdmin(IReplyCallback callback) {
        if (missingAdminRole(callback))
            return null;

        Map<UUID, Server> servers = getGuildManager(callback).getServers();
        if (servers.isEmpty()) {
            callback.reply("No servers added").setEphemeral(true).queue();
            return null;
        }
        return servers;
    }

    public static @Nullable List<Server> getServersIfAdmin(IReplyCallback callback, Collection<String> serverIdStrings) {
        if (missingAdminRole(callback))
            return null;

        GuildManager guildManager = getGuildManager(callback);

        List<Server> servers = new ArrayList<>(serverIdStrings.size());
        for (String serverIdString : serverIdStrings) {
            Server server = guildManager.getServer(UUID.fromString(serverIdString));
            if (server == null) {
                callback.reply("Unknown server selected").setEphemeral(true).queue();
                return null;
            }
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

    public static StringSelectMenu.Builder serverSelectMenu(String customId, Map<UUID, Server> servers) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(customId);
        servers.forEach((id, server) -> builder.addOption(serverDisplayName(server.getName()), id.toString()));
        return builder;
    }

    public static String generateToken(HttpsApi httpsApi) {
        String output = httpsApi.runCommand("server.GenerateAPIToken").outputLines()[0];
        return output.substring(output.indexOf(':') + 1).trim();
    }

    public static boolean verifyAndSetToken(ModalInteractionEvent event, String serverIdString, String token, @Nullable String serverName) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return false;

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
            e.printStackTrace();
            return false;
        }

        // Save token
        getGuildManager(event).setServerToken(UUID.fromString(serverIdString), token);

        serverName = serverName != null ? serverName : server.getName();
        event.getHook().sendMessage("Authentication successful").setEphemeral(true).queue();
        logActionWithServer(event, "added an authentication token for", serverName);
        return true;
    }

    public static CompletableFuture<@Nullable Void> requestAsync(Server server, String actionString, Consumer<HttpsApi> action) {
        return requestAsync(server, actionString, httpsApi -> {
            action.accept(httpsApi);
            return null;
        });
    }

    public static <T extends @Nullable Object> CompletableFuture<T> requestAsync(Server server, String actionString, Function<HttpsApi, T> action) {
        return CompletableFuture.supplyAsync(() -> {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            return action.apply(httpsApi);
        }).exceptionally(exception -> {
            String message = " to " + actionString + " " + inlineServerDisplayName(server.getName());
            // Thrown exceptions are always wrapped in a CompletionException
            if (exception.getCause() instanceof ApiException apiException) {
                message = "Unable" + message + ": " + apiException.getMessage();
            } else {
                message = "Failed" + message;
                exception.printStackTrace();
            }
            // Rethrowing specifically a CompletionException here prevents it from being doubly wrapped
            throw new CompletionException(message, exception.getCause());
        });
    }

    public static CompletableFuture<SaveInfo> saveAsync(Server server, @Nullable String saveName) {
        return requestAsync(server, "save", httpsApi -> {

            String actualSaveName = saveName;
            if (actualSaveName == null || actualSaveName.isBlank()) {
                String sessionName = httpsApi.queryServerState().activeSessionName();
                actualSaveName = defaultSaveName(sessionName, LocalDateTime.now(Clock.systemUTC()));
            }

            httpsApi.save(actualSaveName);
            Instant fallbackTimestamp = Instant.now();

            SaveHeader saveHeader = null;
            Session session = httpsApi.enumerateSessions().current();
            if (session != null) saveHeader = session.find(actualSaveName);

            if (saveHeader != null) {
                return new SaveInfo(actualSaveName, saveHeader.sessionName(), saveHeader.saveTimestamp());
            } else {
                return new SaveInfo(actualSaveName, null, fallbackTimestamp);
            }

        });
    }

}
