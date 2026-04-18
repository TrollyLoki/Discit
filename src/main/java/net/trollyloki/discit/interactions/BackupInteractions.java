package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.MessageLinesUpdater;
import net.trollyloki.discit.SaveInfo;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class BackupInteractions {
    private BackupInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupInteractions.class);

    public static final String
            BACKUP_COMMAND_NAME = "backup",
            BACKUP_MODAL_ID = "backup";

    public static void onBackupCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event, true);
        if (servers == null)
            return;

        event.replyModal(Modal.create(BACKUP_MODAL_ID, "Create Backup").addComponents(
                Label.of("Servers", "The server(s) that should be backed up",
                        serverSelectMenu("servers", servers)
                                .setMaxValues(SelectMenu.OPTIONS_MAX_AMOUNT)
                                .setPlaceholder("Select one or more servers")
                                .setDefaultValues(servers.keySet().stream().limit(SelectMenu.OPTIONS_MAX_AMOUNT).map(UUID::toString).toList())
                                .build()),
                Label.of("Backup Name", "The name of the backup file (individual save names will include both the server name and this name)",
                        TextInput.create("name", TextInputStyle.SHORT)
                                .setMaxLength(100) // arbitrary
                                .build())
        ).build()).queue();
    }

    public static void onBackupModal(ModalInteractionEvent event) {
        ModalMapping serverIds = event.getValue("servers");
        if (serverIds == null) {
            event.reply("Please select servers").setEphemeral(true).queue();
            return;
        }

        List<Server> servers = getServersIfAdmin(event, serverIds.getAsStringList());
        if (servers == null)
            return;

        ModalMapping name = event.getValue("name");
        if (name == null) {
            event.reply("Please provide a name for the backup").setEphemeral(true).queue();
            return;
        }

        List<String> messageLines = Collections.synchronizedList(servers.stream()
                .map(server -> "Saving " + inlineServerDisplayName(server.getName()) + "...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        event.reply(String.join("\n", messageLines))
                .setEphemeral(isDashboard(event))
                .queue();

        MessageLinesUpdater messageLinesUpdater = new MessageLinesUpdater(event.getHook(), messageLines);

        LOGGER.info("Backing up {} servers", servers.size());

        CompletableFuture.runAsync(withMDC(() -> {
            PipedInputStream uploadStream = new PipedInputStream();
            try (ZipOutputStream zipStream = new ZipOutputStream(new PipedOutputStream(uploadStream))) {

                record SaveDownload(SaveInfo info, byte[] data) {
                }

                // Create and download saves from all servers
                List<CompletableFuture<SaveDownload>> futures = new ArrayList<>(servers.size());
                for (int i = 0; i < servers.size(); i++) {
                    final int index = i;
                    Server server = servers.get(index);

                    String saveName = server.getName() + "_" + name.getAsString();
                    CompletableFuture<SaveDownload> saveFuture = saveAsyncWithMDC(server, saveName).thenComposeAsync(withMDC(saveInfo -> {

                        messageLines.set(index, "Downloading save from " + inlineServerDisplayName(server.getName()) + "...");
                        messageLinesUpdater.update();

                        LOGGER.info("Downloading save \"{}\" from {}", saveInfo.name(), serverNameForLog(server.getName()));

                        return requestAsyncWithMDC(server, "download save from", httpsApi -> {
                            try (InputStream saveData = httpsApi.downloadSave(saveInfo.name())) {
                                // Buffer the entire save file for parallel downloading during zipping process
                                // Since save files are normally only 5~15 MW this shouldn't be too bad
                                return new SaveDownload(saveInfo, saveData.readAllBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

                    }));

                    saveFuture.thenApplyAsync(withMDC(_ ->
                            "Saved " + inlineServerDisplayName(server.getName())
                    )).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                        messageLines.set(index, message);
                        messageLinesUpdater.update();
                    }));

                    futures.add(saveFuture);
                }

                // JDA seems to deadlock if the file upload request is submitted before any data is available
                CompletableFuture<@Nullable Message> uploadFuture = null;

                // Zip save files
                int saveCount = 0;
                for (int i = 0; i < futures.size(); i++) {
                    Server server = servers.get(i);
                    SaveDownload saveDownload;

                    try {
                        saveDownload = futures.get(i).join();
                    } catch (CompletionException | CancellationException e) {
                        continue; // skip failed download
                    }

                    if (uploadFuture == null) {
                        // Now that there is data available it is safe to submit the upload request
                        uploadFuture = CompletableFuture.supplyAsync(withMDC(() -> {
                            try {
                                // Need to run this with shouldQueue false to ensure it doesn't block other queued requests
                                return event.getHook().editOriginalAttachments(FileUpload.fromData(uploadStream, name.getAsString() + ".zip")).complete(false);
                            } catch (RateLimitedException e) {
                                throw new RuntimeException(e);
                            }
                        }));
                    }

                    saveCount++;
                    zipStream.putNextEntry(new ZipEntry(saveDownload.info.name() + SaveFileReader.EXTENSION));
                    try {
                        zipStream.write(saveDownload.data);
                        messageLines.set(i, saveDownload.info.formatted(server.getName()));
                    } catch (Exception e) {
                        messageLines.set(i, "Failed to zip save from " + inlineServerDisplayName(server.getName()));
                        LOGGER.error("Failed to zip save from {}", serverNameForLog(server.getName()), e);
                    } finally {
                        zipStream.closeEntry();
                    }
                    messageLinesUpdater.update();
                }

                // Wait for final message update to complete before closing upload stream
                messageLinesUpdater.stop();

                // Handle final upload completion
                if (uploadFuture != null) {
                    final int finalSaveCount = saveCount;
                    uploadFuture.whenCompleteAsync(withMDC((message, throwable) -> {

                        if (throwable != null) {
                            if (throwable.getCause() instanceof ErrorResponseException error
                                    && error.getErrorResponse() == ErrorResponse.REQUEST_ENTITY_TOO_LARGE) {
                                event.getHook().editOriginal("Backup was too large to attach").queue();
                            } else {
                                event.getHook().editOriginal("Failed to attach backup").queue();
                            }
                            LOGGER.error("Failed to upload zip file attachment", throwable);
                        }

                        if (message != null) {
                            Message.Attachment attachment = message.getAttachments().getFirst();
                            logAction(event, "backed up " + finalSaveCount + " server" + (finalSaveCount == 1 ? "" : "s") + " to " + attachment.getUrl());
                        }

                    }));
                }

            } catch (Exception e) {
                event.getHook().editOriginal("Error creating backup").queue();
                LOGGER.error("Error creating backup", e);
            }
        }));
    }

}
