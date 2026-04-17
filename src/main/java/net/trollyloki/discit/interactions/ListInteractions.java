package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.trollyloki.discit.FormattingUtils.escapedServerName;
import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class ListInteractions {
    private ListInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ListInteractions.class);

    public static final String
            LIST_COMMAND_NAME = "list",
            LIST_SELECT_ID = "list-select",
            AUTHENTICATE_BUTTON_ID = "authenticate",
            LIST_AUTHENTICATE_BUTTON_ID = "list-authenticate",
            AUTHENTICATE_MODAL_ID = "authenticate",
            LIST_DEAUTHENTICATE_BUTTON_ID = "list-deauthenticate",
            LIST_REMOVE_BUTTON_ID = "list-remove",
            SERVER_CHANNEL_SELECT_ID = "server-channel",
            UNSET_SERVER_CHANNEL_BUTTON_ID = "unset-server-channel",
            ALLOW_RELOADING_BUTTON_ID = "allow-reloading";

    private static Container serverDetailsContainer(Interaction interaction, String serverIdString, Server server) {
        List<Button> buttons = new ArrayList<>(3);
        buttons.add(Button.primary(buildId(LIST_AUTHENTICATE_BUTTON_ID, serverIdString), server.hasToken() ? "Reauthenticate" : "Authenticate"));
        if (server.hasToken()) {
            buttons.add(Button.secondary(buildId(LIST_DEAUTHENTICATE_BUTTON_ID, serverIdString), "Deauthenticate"));
        }
        buttons.add(Button.danger(buildId(LIST_REMOVE_BUTTON_ID, serverIdString), "Remove"));

        EntitySelectMenu.Builder serverChannelSelect = messageChannelSelect(buildId(SERVER_CHANNEL_SELECT_ID, serverIdString));
        GuildMessageChannel currentServerChannel = getGuildManager(interaction).getServerChannel(UUID.fromString(serverIdString));
        if (currentServerChannel != null) {
            serverChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentServerChannel));
        }

        return Container.of(
                TextDisplay.of("## " + escapedServerName(server.getName())),
                TextDisplay.of("### Host\n||```" + server.getHost() + "```||"),
                TextDisplay.of("### Port\n```" + server.getPort() + "```"),
                TextDisplay.of("### Fingerprint\n```" + server.getFingerprint() + "```"),
                ActionRow.of(buttons),
                Separator.createDivider(Separator.Spacing.LARGE),
                TextDisplay.of("### Server Channel"),
                TextDisplay.of("Slash commands sent in this channel will select this server automatically"),
                ActionRow.of(serverChannelSelect.setPlaceholder("Select a channel").build()),
                ActionRow.of(Button.secondary(buildId(UNSET_SERVER_CHANNEL_BUTTON_ID, serverIdString), "Unset Server Channel")
                        .withDisabled(currentServerChannel == null)),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Allow Reloading"),
                TextDisplay.of("If enabled, anyone can reload this server from its server channel"),
                ActionRow.of(Button.secondary(
                        buildId(ALLOW_RELOADING_BUTTON_ID, serverIdString, !server.isAllowReloading()),
                        "Allow Reloading"
                ).withEmoji(server.isAllowReloading() ? CHECKBOX_CHECKED_EMOJI : CHECKBOX_EMPTY_EMOJI))
        );
    }

    private static ActionRow serverSelectForDetails(Interaction interaction) {
        Map<UUID, Server> servers = getGuildManager(interaction).getServers();
        return ActionRow.of((servers.isEmpty()
                ? StringSelectMenu.create("null").addOption("null", "null").setPlaceholder("No servers added").setDisabled(true)
                : serverSelectMenu(LIST_SELECT_ID, servers).setPlaceholder("Select a server to view details")
        ).build());
    }

    private static MessageTopLevelComponent[] detailsComponents(Interaction interaction, String serverIdString, Server server) {
        return new MessageTopLevelComponent[]{
                serverDetailsContainer(interaction, serverIdString, server),
                serverSelectForDetails(interaction)
        };
    }

    public static void onListCommand(SlashCommandInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        int serverCount = getGuildManager(event).getServers().size();

        Guild guild = event.getGuild();
        String message = guild != null ? guild.getName() + " has " : "You have ";
        if (serverCount > 1) message += serverCount + " servers";
        else if (serverCount == 1) message += "1 server";
        else message += "no servers";

        event.replyComponents(
                TextDisplay.of(message),
                serverSelectForDetails(event)
        ).useComponentsV2().setEphemeral(true).queue();
    }

    public static void onListSelect(StringSelectInteractionEvent event) {
        String serverIdString = event.getValues().getFirst();
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.editComponents(detailsComponents(event, serverIdString, server)).useComponentsV2().queue();
    }

    public static void onListRemoveButton(ButtonInteractionEvent event, String serverIdString) {
        if (isNotAdmin(event))
            return;

        Server server = getGuildManager(event).removeServer(UUID.fromString(serverIdString));
        if (server == null) {
            event.reply("Server already removed").setEphemeral(true).queue();
            return;
        }

        event.editComponents(
                TextDisplay.of("Removed " + inlineServerDisplayName(server.getName())),
                serverSelectForDetails(event)
        ).useComponentsV2().queue();
        logActionWithServer(event, "removed", server.getName());
    }

    public static void onAuthenticateButton(ButtonInteractionEvent event, String serverIdString, boolean isOnList) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(Modal.create(buildId(AUTHENTICATE_MODAL_ID, serverIdString, isOnList), "Authenticate").addComponents(
                TextDisplay.of("Provide authentication for " + inlineServerDisplayName(server.getName())),
                Label.of("Method", "The method of authentication you wish to use", StringSelectMenu.create("type")
                        .addOption("API Token", "token", "Use an API Token generated by the `server.GenerateAPIToken` console command")
                        .addOption("Admin Password", "password", "Generate an API Token automatically by logging in with the server's admin password")
                        .setDefaultValues("token")
                        .build()),
                Label.of("Authentication", "The token or password", TextInput.of("authentication", TextInputStyle.SHORT)),
                TextDisplay.of("When **Admin Password** is selected the provided password is only used to generate an API token. It will not be stored afterwards.")
        ).build()).queue();
    }

    public static void onAuthenticateModal(ModalInteractionEvent event, String serverIdString, boolean isOnList) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        ModalMapping type = event.getValue("type");
        if (type == null) {
            event.reply("Please select an authentication type").setEphemeral(true).queue();
            return;
        }

        ModalMapping authentication = event.getValue("authentication");
        if (authentication == null) {
            event.reply("Please provide authentication").setEphemeral(true).queue();
            return;
        }

        event.deferEdit().queue();

        CompletableFuture<String> tokenFuture;
        if (type.getAsStringList().getFirst().equals("password")) {
            LOGGER.info("Generating API token for {}", serverNameForLog(server.getName()));

            tokenFuture = requestAsyncWithMDC(server, "generate token for", httpsApi -> {
                // Convert password into token
                httpsApi.setToken(null);
                httpsApi.passwordLogin(PrivilegeLevel.ADMIN, authentication.getAsString());
                return generateToken(httpsApi);
            });
            tokenFuture.exceptionallyAsync(withMDC(throwable -> {
                event.getHook().sendMessage(InteractionUtils.exceptionMessage(throwable)).setEphemeral(true).queue();
                //noinspection DataFlowIssue: not actually returned to anything
                return null;
            }));
        } else {
            tokenFuture = CompletableFuture.completedFuture(authentication.getAsString());
        }

        tokenFuture.thenAcceptAsync(withMDC(token -> {
            if (!verifyAndSetToken(event, serverIdString, token, null))
                return;

            if (isOnList) {
                event.getHook().editOriginalComponents(detailsComponents(event, serverIdString, server))
                        .useComponentsV2().queue();
            }
        }));
    }

    public static void onDeauthenticateButtonOnList(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        getGuildManager(event).setServerToken(UUID.fromString(serverIdString), null);

        event.editComponents(detailsComponents(event, serverIdString, server)).useComponentsV2().queue();

        event.getHook().sendMessage("Authentication removed").setEphemeral(true).queue();
        logActionWithServer(event, "removed the authentication token for", server.getName());
    }

    public static void onServerChannelSelect(EntitySelectInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        IMentionable selection = event.getValues().getFirst();

        Server channelServer = getGuildManager(event).setServerChannel(UUID.fromString(serverIdString), selection.getId());
        if (channelServer == null) {
            event.reply("Failed to set server channel").setEphemeral(true).queue();
            return;
        }

        if (channelServer != server) {
            event.reply(selection.getAsMention() + " is already associated with " + inlineServerDisplayName(channelServer.getName()))
                    .setEphemeral(true).queue();
            return;
        }

        event.editComponents(detailsComponents(event, serverIdString, server)).useComponentsV2().queue();

        event.getHook().sendMessage("Server channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the server channel for " + inlineServerDisplayName(server.getName()) + " to " + selection.getAsMention());
    }

    public static void onUnsetServerChannelButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        Server channelServer = getGuildManager(event).setServerChannel(UUID.fromString(serverIdString), null);
        if (channelServer != null) {
            event.reply("Failed to unset server channel").setEphemeral(true).queue();
            return;
        }

        event.editComponents(detailsComponents(event, serverIdString, server)).useComponentsV2().queue();

        event.getHook().sendMessage("Server channel unset").setEphemeral(true).queue();
        logActionWithServer(event, "unset the server channel for", server.getName());
    }

    public static void onAllowReloadingButton(ButtonInteractionEvent event, String serverIdString, boolean value) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        if (!getGuildManager(event).setAllowReloading(UUID.fromString(serverIdString), value)) {
            event.reply("Failed to set allow reloading value").setEphemeral(true).queue();
            return;
        }

        event.editComponents(detailsComponents(event, serverIdString, server)).useComponentsV2().queue();

        logActionWithServer(event, (value ? "allowed" : "disallowed") + " reloading", server.getName());
    }

}
