package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.attachmentupload.AttachmentUpload;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.label.LabelChildComponent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import net.trollyloki.discit.AttachmentCache;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class UploadInteractions {
    private UploadInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadInteractions.class);

    public static final String
            UPLOAD_CONTEXT_COMMAND_NAME = "Upload save file",
            UPLOAD_COMMAND_NAME = "upload",
            UPLOAD_BUTTON_ID = "upload",
            UPLOAD_MODAL_ID = "upload";

    private static final AttachmentCache ATTACHMENT_CACHE = new AttachmentCache();

    public static void onUploadFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = findMessageAttachments(event);
        if (attachments == null)
            return;

        ATTACHMENT_CACHE.put(event.getUser(), attachments);

        onUploadHelper(event, event, customId -> {
            StringSelectMenu.Builder builder = StringSelectMenu.create(customId);
            for (int i = 0; i < attachments.size(); i++) {
                builder.addOption(attachments.get(i).getFileName(), Integer.toString(i));
            }
            return builder.setDefaultValues("0").build();
        });
    }

    public static void onUploadCommand(SlashCommandInteractionEvent event) {
        onUploadHelper(event, event, AttachmentUpload::of);
    }

    // There's no common interface combining just IReplyCallback and IModalCallback
    private static void onUploadHelper(IReplyCallback replyCallback, IModalCallback modalCallback, Function<String, LabelChildComponent> saveFileComponentCreator) {
        Map<UUID, Server> servers = getAllServersIfAdmin(replyCallback);
        if (servers == null)
            return;

        modalCallback.replyModal(createUploadModal(UPLOAD_MODAL_ID,
                Label.of("Servers", "The server(s) that the save should be uploaded to", serverSelectMenu("servers", servers)
                        .setMaxValues(10)
                        .setPlaceholder("Select one or more servers")
                        .build()),
                saveFileComponentCreator
        )).queue();
    }

    public static void onUploadButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(createUploadModal(buildId(UPLOAD_MODAL_ID, serverIdString),
                TextDisplay.of("Uploading save to " + inlineServerDisplayName(server.getName())),
                AttachmentUpload::of
        )).queue();
    }

    private static Modal createUploadModal(String customId, ModalTopLevelComponent serversComponent, Function<String, LabelChildComponent> saveFileComponentCreator) {
        return Modal.create(customId, "Upload Save").addComponents(
                serversComponent,
                Label.of("Save File", saveFileComponentCreator.apply("save")),
                Label.of("Action", "The action to perform with the uploaded save", StringSelectMenu.create("action")
                        .addOption("Nothing", "nothing", "Just upload the save")
                        .addOption("Load", "load", "Load the save")
                        .addOption("Load with Advanced Game Settings", "load-creative", "Load the save with Advanced Game Settings enabled")
                        .setDefaultValues("load")
                        .build())
        ).build();
    }

    public static void onUploadModal(ModalInteractionEvent event, @Nullable String fixedServerIdString) {
        List<Server> servers;
        if (fixedServerIdString != null) {

            Server server = getServerIfAdmin(event, fixedServerIdString);
            if (server == null)
                return;

            servers = Collections.singletonList(server);

        } else {

            ModalMapping serverIds = event.getValue("servers");
            if (serverIds == null) {
                event.reply("Please select servers").setEphemeral(true).queue();
                return;
            }

            servers = getServersIfAdmin(event, serverIds.getAsStringList());
            if (servers == null)
                return;

        }

        ModalMapping save = event.getValue("save");
        if (save == null) {
            event.reply("Please select a save file").setEphemeral(true).queue();
            return;
        }

        NamedAttachmentProxy attachment;
        switch (save.getType()) {
            case FILE_UPLOAD -> attachment = save.getAsAttachmentList().get(0).getProxy();
            case STRING_SELECT -> {
                attachment = ATTACHMENT_CACHE.pop(event.getUser(), Integer.parseInt(save.getAsStringList().get(0)));
                if (attachment == null) {
                    event.reply("Attachment context expired, please try again").queue();
                    return;
                }
            }
            default -> {
                LOGGER.error("Unexpected save file component type: {}", save.getType());
                return;
            }
        }

        ModalMapping action = event.getValue("action");
        if (action == null) {
            event.reply("Please select an action").setEphemeral(true).queue();
            return;
        }

        String actionString = action.getAsStringList().get(0);
        boolean load = actionString.startsWith("load");
        boolean loadCreative = actionString.equals("load-creative");

        String file = attachment.getUrl();
        String saveName = SaveFileReader.saveNameOf(attachment.getFileName());

        event.deferReply(isDashboard(event)).queue();

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        attachment.download().thenAcceptAsync(downloadStream -> {

            List<String> messageLines = Collections.synchronizedList(servers.stream()
                    .map(server -> "Uploading " + file + " to " + inlineServerDisplayName(server.getName()) + "...")
                    .collect(Collectors.toList())
            );
            // No need to synchronize here, the list won't be changing yet
            event.getHook().editOriginal(String.join("\n", messageLines)).queue();

            MDC.setContextMap(mdc);

            InputStream[] uploadStreams;
            try {
                uploadStreams = splitInputStream(downloadStream, servers.size(), e -> {
                    event.getHook().editOriginal("Failed to transfer data").queue();
                    MDC.setContextMap(mdc);
                    LOGGER.error("Error while streaming split save data", e);
                });
            } catch (Exception e) {
                event.getHook().editOriginal("Failed to start data transfer").queue();
                LOGGER.error("Failed to split save data", e);
                return;
            }

            for (int i = 0; i < servers.size(); i++) {
                final int index = i;
                Server server = servers.get(index);

                LOGGER.info("Uploading save \"{}\" to server \"{}\"", saveName, server.getName());

                requestAsync(server, "upload " + file + " to", httpsApi -> {
                    try (InputStream uploadStream = uploadStreams[index]) {
                        httpsApi.uploadSave(uploadStream, saveName, load, loadCreative);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).thenApplyAsync(r -> {
                    String result = load ? "loaded " + file + " on" : "uploaded " + file + " to";
                    logActionWithServer(event, result, server.getName());
                    return "Successfully " + result + " " + inlineServerDisplayName(server.getName());
                }).exceptionally(Throwable::getMessage).thenAcceptAsync(message -> {
                    messageLines.set(index, message);
                    synchronized (messageLines) {
                        event.getHook().editOriginal(String.join("\n", messageLines)).queue();
                    }
                });
            }
        }).exceptionallyAsync(throwable -> {
            event.getHook().editOriginal("Failed to retrieve attachment").queue();
            MDC.setContextMap(mdc);
            LOGGER.error("Failed to retrieve attachment", throwable);
            return null;
        });
    }

    private static InputStream[] splitInputStream(InputStream stream, int count, Consumer<Exception> errorCallback) throws IOException {
        PipedInputStream[] inputStreams = new PipedInputStream[count];
        PipedOutputStream[] outputStreams = new PipedOutputStream[count];
        try {
            for (int i = 0; i < count; i++) {
                //noinspection resource: inputStreams are returned from this method
                inputStreams[i] = new PipedInputStream();
                outputStreams[i] = new PipedOutputStream(inputStreams[i]);
            }
        } catch (Exception e) {
            for (PipedInputStream inputStream : inputStreams) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
            for (PipedOutputStream outputStream : outputStreams) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }

        new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[1024];

                int read;
                do {
                    read = stream.read(buffer);
                    if (read > 0) {
                        for (PipedOutputStream outputStream : outputStreams) {
                            outputStream.write(buffer, 0, read);
                        }
                    }
                } while (read >= 0);
            } catch (Exception e) {
                errorCallback.accept(e);
            } finally {
                for (PipedOutputStream outputStream : outputStreams) {
                    try {
                        outputStream.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();

        return inputStreams;
    }

}
