package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import net.trollyloki.discit.ServerSelectionCache;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class ReloadInteractions {
    private ReloadInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadInteractions.class);

    public static final String
            RELOAD_COMMAND_NAME = "reload",
            RELOAD_MODAL_ID = "reload",
            RELOAD_CANCEL_BUTTON_ID = "reload-cancel",
            RELOAD_CONFIRM_BUTTON_ID = "reload-confirm",
            RELOAD_BUTTON_ID = "reload";

    private static final ServerSelectionCache SERVER_SELECTION_CACHE = new ServerSelectionCache();

    public static void onReloadCommand(SlashCommandInteractionEvent event) {
        Map.Entry<UUID, Server> channelServer = getGuildManager(event).getChannelServer(event.getChannelId());
        if (channelServer != null) {
            // Skip modal
            if (!channelServer.getValue().isAllowReloading() && isNotAdmin(event))
                return;

            confirmReload(event, Collections.singletonList(channelServer.getKey().toString()), Collections.singletonList(channelServer.getValue()));
            return;
        }

        Map<UUID, Server> servers = getAllServersIfAdmin(event);
        if (servers == null)
            return;

        event.replyModal(Modal.create(RELOAD_MODAL_ID, "Reload Session").addComponents(
                Label.of("Servers", "The server(s) that should be reloaded",
                        serverSelectMenu("servers", servers)
                                .setMaxValues(SelectMenu.OPTIONS_MAX_AMOUNT)
                                .setPlaceholder("Select one or more servers")
                                .build())
        ).build()).queue();
    }

    public static void onReloadModal(ModalInteractionEvent event) {
        ModalMapping serverIds = event.getValue("servers");
        if (serverIds == null) {
            event.reply("Please select servers").setEphemeral(true).queue();
            return;
        }

        List<Server> servers = getServersIfAdmin(event, serverIds.getAsStringList());
        if (servers == null)
            return;

        confirmReload(event, serverIds.getAsStringList(), servers);
    }

    private static void confirmReload(IReplyCallback callback, List<String> serverIdStrings, List<Server> servers) {
        callback.deferReply(isDashboard(callback)).queue();

        List<CompletableFuture<Integer>> playerCountFutures = servers.stream().map(server -> {
            LOGGER.info("Checking if players are connected to {} before reloading", serverNameForLog(server.getName()));

            return requestAsyncWithMDC(server, "check if players are connected to", httpsApi -> {
                return httpsApi.queryServerState().connectedPlayerCount();
            });
        }).toList();

        CompletableFuture.allOf(playerCountFutures.toArray(CompletableFuture[]::new)).whenCompleteAsync(withMDC((_, throwable) -> {
            int totalPlayerCount = -1;
            String message;
            if (throwable != null) {
                message = "Failed to check if players are connected";
            } else {
                totalPlayerCount = playerCountFutures.stream().map(CompletableFuture::join).reduce(Integer::sum).orElse(0);
                if (totalPlayerCount == 1) message = "There is currently 1 player";
                else message = "There are currently " + totalPlayerCount + " players";

                message += " connected to ";

                if (servers.size() == 1) message += inlineServerDisplayName(servers.getFirst().getName());
                else message += "those " + servers.size() + " servers";
            }

            if (totalPlayerCount == 0) {
                // Skip confirmation if no players are connected
                reload(callback, servers, false);
                return;
            }

            UUID key = SERVER_SELECTION_CACHE.put(serverIdStrings);
            callback.getHook().editOriginal(message).setComponents(ActionRow.of(
                    Button.primary(buildId(RELOAD_CONFIRM_BUTTON_ID, callback.getUser().getId(), key), "Reload Anyway"),
                    Button.secondary(buildId(RELOAD_CANCEL_BUTTON_ID, callback.getUser().getId(), key), "Cancel")
            )).queue();
        }));
    }

    public static void onReloadCancelButton(ButtonInteractionEvent event, String userId, String keyString) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        SERVER_SELECTION_CACHE.pop(UUID.fromString(keyString));

        event.deferEdit().queue();
        event.getHook().deleteOriginal().queue();
    }

    public static void onReloadConfirmButton(ButtonInteractionEvent event, String userId, String keyString) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        List<String> serverIdStrings = SERVER_SELECTION_CACHE.pop(UUID.fromString(keyString));
        if (serverIdStrings == null) {
            event.deferEdit().queue();
            event.getHook().deleteOriginal().queue();
            event.getHook().sendMessage("Context expired, please try again").setEphemeral(true).queue();
            return;
        }

        // Special case of button in channel server and reloading is allowed
        if (serverIdStrings.size() == 1) {
            Map.Entry<UUID, Server> channelServer = getGuildManager(event).getChannelServer(event.getChannelId());
            if (channelServer != null && channelServer.getValue().isAllowReloading() && channelServer.getKey().toString().equals(serverIdStrings.getFirst())) {
                event.deferEdit().queue();
                reload(event, Collections.singletonList(channelServer.getValue()), false);
                return;
            }
        }

        List<Server> servers = getServersIfAdmin(event, serverIdStrings);
        if (servers == null)
            return;

        event.deferEdit().queue();
        reload(event, servers, false);
    }

    public static void onReloadButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        reload(event, Collections.singletonList(server), true);
    }

    private static void reload(IReplyCallback callback, List<Server> servers, boolean reply) {
        List<String> messageLines = Collections.synchronizedList(servers.stream()
                .map(server -> "Reloading " + inlineServerDisplayName(server.getName()) + "...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        String initialMessage = String.join("\n", messageLines);
        if (reply) {
            callback.reply(initialMessage).setEphemeral(isDashboard(callback)).queue();
        } else {
            callback.getHook().editOriginal(initialMessage).setComponents(Collections.emptySet()).queue();
        }

        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            Server server = servers.get(index);

            LOGGER.info("Reloading {}", serverNameForLog(server.getName()));

            reloadHelper(callback, server).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                messageLines.set(index, message);
                synchronized (messageLines) {
                    callback.getHook().editOriginal(String.join("\n", messageLines)).queue();
                }
            }));
        }
    }

}
