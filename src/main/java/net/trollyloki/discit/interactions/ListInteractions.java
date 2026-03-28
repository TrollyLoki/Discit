package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;

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
            LIST_REMOVE_BUTTON_ID = "list-remove";

    private static Container serverDetailsContainer(String serverIdString, Server server) {
        List<Button> buttons = new ArrayList<>(3);
        buttons.add(Button.primary(buildId(LIST_AUTHENTICATE_BUTTON_ID, serverIdString), server.hasToken() ? "Reauthenticate" : "Authenticate"));
        if (server.hasToken()) {
            buttons.add(Button.secondary(buildId(LIST_DEAUTHENTICATE_BUTTON_ID, serverIdString), "Deauthenticate"));
        }
        buttons.add(Button.danger(buildId(LIST_REMOVE_BUTTON_ID, serverIdString), "Remove"));

        return Container.of(
                TextDisplay.of("## " + serverDisplayName(server.getName())),
                TextDisplay.of("### Host\n||```" + server.getHost() + "```||"),
                TextDisplay.of("### Port\n```" + server.getPort() + "```"),
                TextDisplay.of("### Fingerprint\n```" + server.getFingerprint() + "```"),
                ActionRow.of(buttons)
        );
    }

    private static ActionRow serverSelectForDetails(Interaction interaction) {
        Map<UUID, Server> servers = getGuildManager(interaction).getServers();
        return ActionRow.of((servers.isEmpty()
                ? StringSelectMenu.create("null").addOption("null", "null").setPlaceholder("No servers added").setDisabled(true)
                : serverSelectMenu(LIST_SELECT_ID, servers).setPlaceholder("Select a server to view details")
        ).build());
    }

    public static void onListCommand(SlashCommandInteractionEvent event) {
        if (missingAdminRole(event))
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
        String serverIdString = event.getValues().get(0);
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.editComponents(
                serverDetailsContainer(serverIdString, server),
                serverSelectForDetails(event)
        ).useComponentsV2().queue();
    }

    public static void onListRemoveButton(ButtonInteractionEvent event, String serverIdString) {
        if (missingAdminRole(event))
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
                        .addOption("API Token", "token", "Directly enter an API Token generated using the `server.GenerateAPIToken` command")
                        .addOption("Admin Password", "password", "Generate an API Token automatically using the server's admin password")
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
        if (type.getAsStringList().get(0).equals("password")) {
            LOGGER.info("Generating API token for server \"{}\"", server.getName());

            tokenFuture = requestAsync(server, "generate token for", httpsApi -> {

                // Convert password into token
                httpsApi.setToken(null);
                httpsApi.passwordLogin(PrivilegeLevel.ADMIN, authentication.getAsString());
                return generateToken(httpsApi);

            });
            tokenFuture.exceptionallyAsync(throwable -> {
                event.getHook().sendMessage(throwable.getMessage()).setEphemeral(true).queue();
                //noinspection DataFlowIssue: not actually returned to anything
                return null;
            });
        } else {
            tokenFuture = CompletableFuture.completedFuture(authentication.getAsString());
        }

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        tokenFuture.thenAcceptAsync(token -> {
            MDC.setContextMap(mdc);
            if (!verifyAndSetToken(event, serverIdString, token, null))
                return;

            if (isOnList) {
                event.getHook().editOriginalComponents(
                        serverDetailsContainer(serverIdString, server),
                        serverSelectForDetails(event)
                ).useComponentsV2().queue();
            }
        });
    }

    public static void onDeauthenticateButtonOnList(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        getGuildManager(event).setServerToken(UUID.fromString(serverIdString), null);

        event.editComponents(
                serverDetailsContainer(serverIdString, server),
                serverSelectForDetails(event)
        ).useComponentsV2().queue();

        event.getHook().sendMessage("Authentication removed").setEphemeral(true).queue();
        logActionWithServer(event, "removed the authentication token for", server.getName());
    }

}
