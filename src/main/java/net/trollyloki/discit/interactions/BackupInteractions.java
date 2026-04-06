package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionUtils.getAllServersIfAdmin;
import static net.trollyloki.discit.InteractionUtils.isDashboard;
import static net.trollyloki.discit.InteractionUtils.logAction;
import static net.trollyloki.discit.InteractionUtils.saveAsyncWithMDC;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class BackupInteractions {
    private BackupInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupInteractions.class);

    public static final String
            BACKUP_COMMAND_NAME = "backup";

    public static void onBackupCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event);
        if (servers == null)
            return;

        String name = event.getOption("name", OptionMapping::getAsString);
        if (name == null) {
            event.reply("Please provide a name for the backup").setEphemeral(true).queue();
            return;
        }

        Server[] serverArray = servers.values().toArray(Server[]::new);
        List<String> messageLines = Collections.synchronizedList(Arrays.stream(serverArray)
                .map(server -> "Saving " + inlineServerDisplayName(server.getName()) + "...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        event.reply(String.join("\n", messageLines))
                .setEphemeral(isDashboard(event))
                .queue();

        LOGGER.info("Backing up {} servers", serverArray.length);

        // Save all servers
        @SuppressWarnings("unchecked") CompletableFuture<@Nullable SaveInfo>[] futures = new CompletableFuture[serverArray.length];
        for (int i = 0; i < serverArray.length; i++) {
            final int index = i;
            Server server = serverArray[index];

            CompletableFuture<@Nullable SaveInfo> saveFuture = saveAsyncWithMDC(server, server.getName() + "_" + name);
            saveFuture.thenApplyAsync(withMDC(saveInfo ->
                    "Saved " + inlineServerDisplayName(server.getName())
            )).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                messageLines.set(index, message);
                synchronized (messageLines) {
                    event.getHook().editOriginal(String.join("\n", messageLines)).queue();
                }
            }));

            // Replace exceptional completion will null value to make sure below allOf call succeeds
            futures[index] = saveFuture.exceptionally(t -> null);
        }

        // Download and zip save files
        CompletableFuture.allOf(futures).thenRunAsync(withMDC(() -> {
            List<String> finalMessageLines = new ArrayList<>(futures.length);
            Map<Integer, SaveInfo> saves = new HashMap<>(futures.length);
            for (int i = 0; i < futures.length; i++) {
                SaveInfo saveInfo = futures[i].join();
                if (saveInfo == null) continue;

                saves.put(i, saveInfo);
                finalMessageLines.add(saveInfo.formatted(serverArray[i].getName()));
            }

            if (saves.isEmpty()) {
                // nothing to download, abort early
                return;
            }

            String serversString = saves.size() + " server" + (saves.size() == 1 ? "" : "s");
            messageLines.add("Downloading save files from " + serversString + "...");
            synchronized (messageLines) {
                event.getHook().editOriginal(String.join("\n", messageLines)).queue();
            }

            PipedInputStream uploadStream = new PipedInputStream();
            try (ZipOutputStream zipStream = new ZipOutputStream(new PipedOutputStream(uploadStream))) {
                event.getHook().editOriginal(String.join("\n", finalMessageLines))
                        .setFiles(FileUpload.fromData(uploadStream, name + ".zip"))
                        .queue(message -> logAction(event, "backed up " + serversString + " to " + message.getAttachments().get(0).getUrl()));

                for (Map.Entry<Integer, SaveInfo> entry : saves.entrySet()) {
                    Server server = serverArray[entry.getKey()];
                    SaveInfo saveInfo = entry.getValue();

                    LOGGER.info("Downloading save \"{}\" from {}", saveInfo.name(), serverNameForLog(server.getName()));

                    try (InputStream saveData = server.httpsApi(Duration.ofSeconds(3)).downloadSave(saveInfo.name())) {
                        zipStream.putNextEntry(new ZipEntry(saveInfo.name() + SaveFileReader.EXTENSION));
                        saveData.transferTo(zipStream);
                        zipStream.closeEntry();
                    }
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
