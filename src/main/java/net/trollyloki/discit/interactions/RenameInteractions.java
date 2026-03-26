package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.Server;
import org.jspecify.annotations.NullMarked;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.getServerIfAdmin;
import static net.trollyloki.discit.InteractionUtils.isDashboard;
import static net.trollyloki.discit.InteractionUtils.logActionWithServer;
import static net.trollyloki.discit.InteractionUtils.requestAsync;

@NullMarked
public final class RenameInteractions {
    private RenameInteractions() {
    }

    public static final String
            RENAME_BUTTON_ID = "rename",
            RENAME_MODAL_ID = "rename";

    public static void onRenameButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(Modal.create(buildId(RENAME_MODAL_ID, serverIdString), "Rename Server").addComponents(
                Label.of("Server Name",
                        TextInput.create("name", TextInputStyle.SHORT).setValue(server.getName()).build()
                )
        ).build()).queue();
    }

    public static void onRenameModal(ModalInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        ModalMapping name = event.getValue("name");
        if (name == null) {
            event.reply("Please provide a name").setEphemeral(true).queue();
            return;
        }

        String originalName = server.getName();
        String newName = name.getAsString();

        event.deferReply(isDashboard(event)).queue();
        requestAsync(server, "rename", httpsApi -> {
            httpsApi.renameServer(newName);
        }).thenApplyAsync(r -> {
            logActionWithServer(event, "renamed " + inlineServerDisplayName(originalName) + " to", newName);
            return "Successfully renamed " + inlineServerDisplayName(newName);
        }).exceptionally(Throwable::getMessage).thenAcceptAsync(message -> {
            event.getHook().editOriginal(message).queue();
        });
    }

}
