package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import net.trollyloki.discit.InteractionUtils;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

        Runnable updateMessage = () -> {
            synchronized (messageLines) {
                event.getHook().editOriginal(String.join("\n", messageLines)).queue();
            }
        };

        LOGGER.info("Backing up {} servers", servers.size());

        // Save all servers
        @SuppressWarnings("unchecked") CompletableFuture<@Nullable SaveInfo>[] futures = new CompletableFuture[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            Server server = servers.get(index);

            CompletableFuture<@Nullable SaveInfo> saveFuture = saveAsyncWithMDC(server, server.getName() + "_" + name.getAsString());
            saveFuture.thenApplyAsync(withMDC(_ ->
                    "Saved " + inlineServerDisplayName(server.getName())
            )).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                messageLines.set(index, message);
                updateMessage.run();
            }));

            // Replace exceptional completion will null value to make sure below allOf call succeeds
            futures[index] = saveFuture.exceptionally(_ -> null);
        }

        // Download and zip save files
        CompletableFuture.allOf(futures).thenRunAsync(withMDC(() -> {
            Map<Integer, SaveInfo> saves = new HashMap<>(futures.length);
            for (int i = 0; i < futures.length; i++) {
                SaveInfo saveInfo = futures[i].join();
                if (saveInfo == null) continue;

                saves.put(i, saveInfo);
            }

            if (saves.isEmpty()) {
                // nothing to download, abort early
                return;
            }

            PipedInputStream uploadStream = new PipedInputStream();
            try (ZipOutputStream zipStream = new ZipOutputStream(new PipedOutputStream(uploadStream))) {

                CompletableFuture.runAsync(withMDC(() -> {
                    Message message;
                    try {
                        // Need to run this with shouldQueue false to ensure it doesn't block other queued requests
                        message = event.getHook().editOriginalAttachments(FileUpload.fromData(uploadStream, name.getAsString() + ".zip")).complete(false);
                    } catch (Exception e) {
                        if (e instanceof ErrorResponseException error && error.getErrorResponse() == ErrorResponse.REQUEST_ENTITY_TOO_LARGE) {
                            event.getHook().editOriginal("Backup is too large to attach").queue();
                        } else {
                            event.getHook().editOriginal("Failed to attach zip file").queue();
                        }
                        LOGGER.error("Failed to add zip file attachment", e);
                        return;
                    }
                    logAction(event, "backed up " + saves.size() + " server" + (saves.size() == 1 ? "" : "s") + " to " + message.getAttachments().getFirst().getUrl());
                }));

                for (int i = 0; i < servers.size(); i++) {
                    Server server = servers.get(i);
                    SaveInfo saveInfo = saves.get(i);
                    if (saveInfo == null) continue;

                    messageLines.set(i, "Downloading save from " + inlineServerDisplayName(server.getName()) + "...");
                    updateMessage.run();

                    LOGGER.info("Downloading save \"{}\" from {}", saveInfo.name(), serverNameForLog(server.getName()));

                    zipStream.putNextEntry(new ZipEntry(saveInfo.name() + SaveFileReader.EXTENSION));
                    try (InputStream saveData = server.httpsApi(Duration.ofSeconds(3)).downloadSave(saveInfo.name())) {
                        saveData.transferTo(zipStream);
                        messageLines.set(i, saveInfo.formatted(server.getName()));
                    } catch (Exception e) {
                        messageLines.set(i, "Failed to download save from " + inlineServerDisplayName(server.getName()));
                    } finally {
                        zipStream.closeEntry();
                    }
                }

                try {
                    synchronized (messageLines) {
                        event.getHook().editOriginal(String.join("\n", messageLines)).complete();
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to send final message edit", e);
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        })).exceptionallyAsync(withMDC(throwable -> {
            event.getHook().editOriginal("Failed to transfer data").queue();
            LOGGER.error("Error creating backup file", throwable);
            return null;
        }));
    }

}
