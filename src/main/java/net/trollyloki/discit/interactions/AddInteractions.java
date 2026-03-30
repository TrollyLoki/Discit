package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.CertificateUtils;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.exception.PasswordlessLoginNotPossibleException;
import net.trollyloki.jicsit.server.query.QueryApi;
import net.trollyloki.jicsit.server.query.ServerState;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.trollyloki.discit.AddressUtils.validateHostAddress;
import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.CANCEL_BUTTON_ID;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class AddInteractions {
    private AddInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AddInteractions.class);

    public static final String
            ADD_COMMAND_NAME = "add",
            ADD_RETRY_BUTTON_ID = "add-retry",
            ADD_CONFIRM_BUTTON_ID = "add-confirm",
            CLAIM_BUTTON_ID = "claim",
            CLAIM_MODAL_ID = "claim";

    public static void onAddCommand(SlashCommandInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        String host = event.getOption("host", OptionMapping::getAsString);
        if (host == null) {
            event.reply("Please provide a host address").setEphemeral(true).queue();
            return;
        }

        Integer port = event.getOption("port", OptionMapping::getAsInt);

        event.deferReply(true).queue();
        tryAdd(event.getHook(), host, port != null ? port : 7777);
    }

    private static void tryAdd(InteractionHook hook, String host, int port) {
        InetAddress address = validateHostAddress(host);
        if (address == null) {
            hook.editOriginal("Invalid address").queue();
            return;
        }

        LOGGER.info("Connecting to {}:{}", host, port);

        CompletableFuture.runAsync(() -> {
            try (QueryApi queryApi = QueryApi.of(address, port, Duration.ofSeconds(3))) {

                ServerState serverState = queryApi.pollServerState();
                String fingerprint = CertificateUtils.getServerFingerprint(host, port);

                hook.editOriginalComponents(
                        TextDisplay.of("Is this the server you are trying to add?"),
                        Container.of(
                                TextDisplay.of("## " + serverDisplayName(serverState.name())),
                                TextDisplay.of("### Fingerprint\n```" + fingerprint + "```"),
                                TextDisplay.of("### Build Version\n```" + serverState.build() + "```")
                        ),
                        ActionRow.of(
                                Button.success(buildId(ADD_CONFIRM_BUTTON_ID, host, port, fingerprint), "Confirm"),
                                Button.secondary(CANCEL_BUTTON_ID, "Cancel")
                        )
                ).useComponentsV2().queue();

            } catch (Exception e) {
                hook.editOriginalComponents(
                        TextDisplay.of("Could not connect to that server"),
                        ActionRow.of(
                                Button.primary(buildId(ADD_RETRY_BUTTON_ID, host, port), "Retry")
                        )
                ).useComponentsV2().queue();
            }
        });
    }

    public static void onRetryButton(ButtonInteractionEvent event, String host, int port) {
        if (missingAdminRole(event))
            return;

        event.editComponents(ActionRow.of(
                Button.primary("null", "Retrying...").asDisabled()
        )).queue();
        tryAdd(event.getHook(), host, port);
    }

    public static void onAddConfirmButton(ButtonInteractionEvent event, String host, int port, String fingerprint) {
        if (missingAdminRole(event))
            return;

        Map.Entry<UUID, Server> serverEntry = getGuildManager(event).addServer(host, port, fingerprint);
        UUID serverId = serverEntry.getKey();
        Server server = serverEntry.getValue();

        String name;
        try (QueryApi queryApi = server.queryApi(Duration.ofSeconds(1))) {
            name = inlineServerDisplayName(queryApi.pollServerState().name());
        } catch (Exception e) {
            name = "a server";
        }
        event.editComponents(TextDisplay.of("Added " + name)).useComponentsV2().queue();
        logAction(event, "added " + name);

        // Offer to claim the server if possible
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        CompletableFuture.runAsync(() -> {
            try {
                server.httpsApi(Duration.ofSeconds(3)).passwordlessLogin(PrivilegeLevel.INITIAL_ADMIN);

                event.getHook().editOriginalComponents(
                        TextDisplay.of("The server you just added is currently unclaimed"),
                        TextDisplay.of("Would you like to claim it?"),
                        ActionRow.of(
                                Button.success(buildId(CLAIM_BUTTON_ID, serverId), "Yes"),
                                Button.danger(CANCEL_BUTTON_ID, "No")
                        )
                ).useComponentsV2().queue();
            } catch (PasswordlessLoginNotPossibleException e) {
                // Could not log in as initial admin so the server must be claimed already
            } catch (Exception e) {
                MDC.setContextMap(mdc);
                LOGGER.warn("Failed to check if added server is claimed", e);
            }
        });
    }

    public static void onClaimButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(Modal.create(buildId(CLAIM_MODAL_ID, serverIdString), "Claim Server").addComponents(
                Label.of("Server Name", TextInput.of("name", TextInputStyle.SHORT)),
                Label.of("Admin Password", TextInput.of("password1", TextInputStyle.SHORT)),
                Label.of("Repeat Admin Password", TextInput.of("password2", TextInputStyle.SHORT)),
                Label.of("Authenticate", "Should authentication be obtained automatically?", Checkbox.of("authenticate", true)),
                TextDisplay.of("The provided password is only used to claim the server (including generating an API token if **Authenticate** is checked). It will not be stored afterwards.")
        ).build()).queue();
    }

    public static void onClaimModal(ModalInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        ModalMapping name = event.getValue("name");
        if (name == null) {
            event.reply("Please provide a name").setEphemeral(true).queue();
            return;
        }

        ModalMapping password1 = event.getValue("password1");
        if (password1 == null) {
            event.reply("Please provide a password").setEphemeral(true).queue();
            return;
        }

        ModalMapping password2 = event.getValue("password2");
        if (password2 == null) {
            event.reply("Please repeat the password").setEphemeral(true).queue();
            return;
        }

        if (!password1.getAsString().equals(password2.getAsString())) {
            event.reply("Provided password and repeated password were not the same").setEphemeral(true).queue();
            return;
        }

        ModalMapping authenticate = event.getValue("authenticate");

        event.deferEdit().queue();

        LOGGER.info("Claiming server \"{}\"", name.getAsString());

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        requestAsync(server, "claim", httpsApi -> {

            httpsApi.setToken(null);
            httpsApi.passwordlessLogin(PrivilegeLevel.INITIAL_ADMIN);
            httpsApi.claimServer(name.getAsString(), password1.getAsString());

            if (authenticate != null && authenticate.getAsBoolean()) {
                // Start the automatic authentication process
                CompletableFuture.runAsync(() -> {
                    MDC.setContextMap(mdc);
                    try {
                        String token = generateToken(httpsApi);
                        verifyAndSetToken(event, serverIdString, token, name.getAsString());
                    } catch (Exception e) {
                        event.getHook().sendMessage("Automatic authentication failed").queue();
                        LOGGER.warn("Automatic authentication for server \"{}\" failed", name.getAsString(), e);
                    }
                });
            }

        }).thenApplyAsync(r -> {
            logActionWithServer(event, "claimed", name.getAsString());
            return "Successfully claimed " + inlineServerDisplayName(name.getAsString());
        }).exceptionally(Throwable::getMessage).thenAcceptAsync(message -> {
            event.getHook().editOriginalComponents(TextDisplay.of(message)).useComponentsV2().queue();
        });
    }

}
