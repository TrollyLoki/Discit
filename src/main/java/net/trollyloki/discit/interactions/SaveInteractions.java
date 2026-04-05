package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.trollyloki.discit.SaveInfo;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;

@NullMarked
public final class SaveInteractions {
    private SaveInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SaveInteractions.class);

    public static final String
            SAVE_COMMAND_NAME = "save",
            SAVE_BUTTON_ID = "save",
            SAVE_MODAL_ID = "save";

    public static void onSaveCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event);
        if (servers == null)
            return;

        event.replyModal(createSaveModal(SAVE_MODAL_ID,
                Label.of("Server", serverSelectMenu("server", servers)
                        .setPlaceholder("Select a server")
                        .build())
        )).queue();
    }

    public static void onSaveButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(createSaveModal(buildId(SAVE_MODAL_ID, serverIdString),
                TextDisplay.of("Creating save on " + inlineServerDisplayName(server.getName()))
        )).queue();
    }

    private static Modal createSaveModal(String customId, ModalTopLevelComponent serverComponent) {
        return Modal.create(customId, "Create Save").addComponents(
                serverComponent,
                Label.of("Save Name", "Optional", TextInput.create("name", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setPlaceholder("Session Name_DDMMYY-HHMMSS")
                        .setMaxLength(100) // arbitrary
                        .build())
        ).build();
    }

    public static void onSaveModal(ModalInteractionEvent event, @Nullable String serverIdString) {
        if (serverIdString == null) {
            ModalMapping serverIds = event.getValue("server");
            if (serverIds == null) {
                event.reply("Please select a server").setEphemeral(true).queue();
                return;
            }
            serverIdString = serverIds.getAsStringList().get(0);
        }

        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        ModalMapping name = event.getValue("name");
        String saveName = name != null ? name.getAsString() : null;

        record SaveDownload(SaveInfo info, InputStream data) {
        }

        event.deferReply(isDashboard(event)).queue();

        LOGGER.info("Saving {} as \"{}\"", serverNameForLog(server), saveName);

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        saveAsync(server, saveName).thenComposeAsync(saveInfo -> {

            event.getHook().editOriginal("Downloading `" + saveInfo.name() + SaveFileReader.EXTENSION + "` from " + inlineServerDisplayName(server.getName()) + "...").queue();

            MDC.setContextMap(mdc);
            LOGGER.info("Downloading save \"{}\" from {}", saveInfo.name(), serverNameForLog(server));

            return requestAsync(server, "download `" + saveInfo.name() + SaveFileReader.EXTENSION + "` from", httpsApi -> {
                return new SaveDownload(saveInfo, httpsApi.downloadSave(saveInfo.name()));
            });

        }).thenAcceptAsync(saveDownload -> {

            event.getHook().editOriginal(saveDownload.info.formatted(server.getName()))
                    .setFiles(FileUpload.fromData(saveDownload.data, saveDownload.info.name() + SaveFileReader.EXTENSION))
                    .queue(message -> logActionWithServer(event, "downloaded " + message.getAttachments().get(0).getUrl() + " from", server.getName()));

        }).exceptionallyAsync(throwable -> {
            event.getHook().editOriginal(throwable.getMessage()).queue();
            return null;
        });
    }

}
