package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.Server;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class ReloadInteractions {
    private ReloadInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadInteractions.class);

    public static final String
            RELOAD_COMMAND_NAME = "reload",
            RELOAD_BUTTON_ID = "reload",
            RELOAD_MODAL_ID = "reload";

    private static final String RELOAD_SAVE_NAME = "reload_continue";

    public static void onReloadCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event);
        if (servers == null)
            return;

        event.replyModal(Modal.create(RELOAD_MODAL_ID, "Reload Session").addComponents(
                Label.of("Servers", "The server(s) that should be reloaded",
                        serverSelectMenu("servers", servers)
                                .setMaxValues(10)
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

        reload(event, servers);
    }

    public static void onReloadButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        reload(event, Collections.singletonList(server));
    }

    private static void reload(IReplyCallback interaction, List<Server> servers) {
        List<String> messageLines = Collections.synchronizedList(servers.stream()
                .map(server -> "Reloading " + inlineServerDisplayName(server.getName()) + "...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        interaction.reply(String.join("\n", messageLines))
                .setEphemeral(isDashboard(interaction))
                .queue();

        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            Server server = servers.get(index);

            LOGGER.info("Reloading server \"{}\"", server.getName());

            requestAsync(server, "reload", httpsApi -> {
                httpsApi.save(RELOAD_SAVE_NAME);
                httpsApi.loadSave(RELOAD_SAVE_NAME, false);
            }).thenApplyAsync(r -> {
                logActionWithServer(interaction, "reloaded", server.getName());
                return "Successfully reloaded " + inlineServerDisplayName(server.getName());
            }).exceptionally(Throwable::getMessage).thenAcceptAsync(message -> {
                messageLines.set(index, message);
                synchronized (messageLines) {
                    interaction.getHook().editOriginal(String.join("\n", messageLines)).queue();
                }
            });
        }
    }

}
