package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.HttpsApi;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class ChangePasswordInteractions {
    private ChangePasswordInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangePasswordInteractions.class);

    public static final String
            CHANGE_PASSWORD_BUTTON_ID = "change-password",
            CHANGE_PASSWORD_MODAL_ID = "change-password";

    public enum PasswordType {
        CLIENT("Client", false) {
            @Override
            public void set(HttpsApi httpsApi, String password) {
                httpsApi.setClientPassword(password);
            }
        },
        ADMIN("Admin", true) {
            @Override
            public void set(HttpsApi httpsApi, String password) {
                httpsApi.setAdminPassword(password);
            }
        };

        private final String name;
        private final boolean required;

        PasswordType(String name, boolean required) {
            this.name = name;
            this.required = required;
        }

        @Override
        public String toString() {
            return name + " Password";
        }

        public boolean isRequired() {
            return required;
        }

        public abstract void set(HttpsApi httpsApi, String password);

    }

    public static void onChangePasswordButton(ButtonInteractionEvent event, String serverIdString, String typeName) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        PasswordType type = PasswordType.valueOf(typeName.toUpperCase(Locale.ROOT));

        TextInput.Builder passwordInput = TextInput.create("password", TextInputStyle.SHORT)
                .setRequired(type.isRequired());
        if (!type.isRequired()) passwordInput.setPlaceholder("No password");

        event.replyModal(Modal.create(buildId(CHANGE_PASSWORD_MODAL_ID, serverIdString, typeName), "Change " + type).addComponents(
                TextDisplay.of("Changing " + type.toString().toLowerCase() + " for " + inlineServerDisplayName(server.getName())),
                Label.of("New " + type, passwordInput.build())
        ).build()).queue();
    }

    public static void onChangePasswordModal(ModalInteractionEvent event, String serverIdString, String typeName) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        PasswordType type = PasswordType.valueOf(typeName.toUpperCase(Locale.ROOT));
        String typeLower = type.toString().toLowerCase();

        ModalMapping passwordMapping = event.getValue("password");
        String password = passwordMapping == null ? "" : passwordMapping.getAsString();

        event.deferReply(true).queue();

        LOGGER.info("{} {} for {}", password.isEmpty() ? "Removing" : "Changing", typeLower, serverNameForLog(server.getName()));

        String action = password.isEmpty() ? "remove" : "change";
        requestAsyncWithMDC(server, action + " " + typeLower + " for", httpsApi -> {
            type.set(httpsApi, password);
        }).thenApplyAsync(withMDC(_ -> {
            logActionWithServer(event, action + "d the " + typeLower + " for", server.getName());
            return "Successfully " + action + "d the " + typeLower + " for " + inlineServerDisplayName(server.getName());
        })).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
            event.getHook().editOriginal(message).queue();
        }));
    }

}
