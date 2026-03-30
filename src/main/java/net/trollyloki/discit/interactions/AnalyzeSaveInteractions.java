package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.trollyloki.jicsit.save.Mod;
import net.trollyloki.jicsit.save.ModMetadata;
import net.trollyloki.jicsit.save.SaveFileInfo;
import net.trollyloki.jicsit.save.SaveFileReader;
import net.trollyloki.jicsit.save.SaveFormatException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.InteractionUtils.findMessageAttachments;

@NullMarked
public final class AnalyzeSaveInteractions {
    private AnalyzeSaveInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeSaveInteractions.class);

    public static final String
            ANALYZE_SAVE_CONTEXT_COMMAND_NAME = "Analyze save file(s)";

    public static void onAnalyzeSaveFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = findMessageAttachments(event);
        if (attachments == null)
            return;

        event.deferReply().queue();

        LOGGER.info("Analyzing {} save file(s)", attachments.size());

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        @SuppressWarnings("unchecked") CompletableFuture<List<ContainerChildComponent>>[] futures = attachments.stream().map(
                attachment -> attachment.getProxy().download().thenApplyAsync(downloadStream -> {
                    MDC.setContextMap(mdc);
                    if ("application/zip".equals(attachment.getContentType())) {

                        try (ZipInputStream zipStream = new ZipInputStream(downloadStream)) {
                            return zipFileComponents(attachment.getFileName(), zipStream);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to read zip file \"{}\"", attachment.getFileName(), e);
                            return Collections.singletonList(TextDisplay.of("Failed to read zip file " + attachment.getUrl()));
                        }

                    } else {

                        try (downloadStream) {
                            return saveFileInfoComponents(readSaveFileInfo(attachment.getFileName(), downloadStream));
                        } catch (Exception e) {
                            LOGGER.warn("Failed to read save file \"{}\"", attachment.getFileName(), e);
                            return Collections.singletonList(TextDisplay.of(attachment.getUrl() + " is not a valid save file"));
                        }

                    }
                }).exceptionallyAsync(throwable -> {
                    MDC.setContextMap(mdc);
                    LOGGER.error("Failed to retrieve attachment \"{}\"", attachment.getFileName(), throwable.getCause());
                    return Collections.singletonList(TextDisplay.of("Failed to retrieve " + attachment.getUrl()));
                })
        ).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).thenRunAsync(() -> {
            try {
                List<Container> containers = Arrays.stream(futures).map(CompletableFuture::join).map(Container::of).toList();
                event.getHook().editOriginalComponents(containers).useComponentsV2().queue();
            } catch (Exception e) {
                MDC.setContextMap(mdc);
                LOGGER.error("Failed to update reply", e);
            }
        });
    }

    private static SaveFileInfo readSaveFileInfo(String filename, InputStream data) throws IOException {
        return SaveFileReader.readInfo(SaveFileReader.saveNameOf(filename), data);
    }

    private static List<ContainerChildComponent> zipFileComponents(String filename, ZipInputStream zipStream) throws IOException {
        List<ContainerChildComponent> components = new ArrayList<>();
        components.add(TextDisplay.of("## " + filename));

        ZipEntry zipEntry;
        while ((zipEntry = zipStream.getNextEntry()) != null) {
            if (zipEntry.isDirectory()) continue;

            String emoji;
            String message;
            try {
                SaveFileInfo info = readSaveFileInfo(zipEntry.getName(), zipStream);
                if (info.header().isEdited()) {
                    emoji = ":warning:";
                    message = "has an invalid checksum";
                } else {
                    emoji = ":white_check_mark:";
                    message = "has a valid checksum";
                }
            } catch (IOException e) {
                emoji = ":x:";
                message = "is not a valid save file";
                LOGGER.warn("Failed to read save file \"{}\" within \"{}\"", zipEntry.getName(), filename, e);
            }
            components.add(TextDisplay.ofFormat("%s `%s` %s", emoji, zipEntry.getName(), message));

            zipStream.closeEntry();
        }

        if (components.size() == 1) { // no entries were processed
            components.add(TextDisplay.of("*empty*"));
        }
        return components;
    }

    private static List<ContainerChildComponent> saveFileInfoComponents(SaveFileInfo info) {
        List<ContainerChildComponent> components = new ArrayList<>();
        components.add(TextDisplay.of("## " + info.header().saveName()));

        String saved = "Created " + TimeFormat.DEFAULT.format(info.header().saveTimestamp());
        if (info.originalSaveName() != null) {
            saved += " as " + info.originalSaveName();
        }
        components.add(TextDisplay.of(saved));

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("### " + info.header().sessionName()));
        components.add(TextDisplay.of("**Game Duration**\n" + formatDuration(info.header().playDuration())));
        components.add(TextDisplay.of("**Advanced Game Settings Enabled**\n" + (info.header().isAdvancedGameSettingsEnabled() ? "Yes" : "No")));
        components.add(TextDisplay.of("**Modded**\n" + (info.header().isModded() ? "Yes" : "No")));

        List<Mod> mods = getMods(info);
        if (mods != null) {
            components.add(TextDisplay.of("### Mods\n" + (
                    mods.isEmpty() ? "*Unknown*" : mods.stream().map(mod ->
                            "- [%s %s](https://ficsit.app/mod/%s)".formatted(mod.name(), mod.version(), mod.reference())
                    ).collect(Collectors.joining("\n"))
            )));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("### " + (info.header().isEdited() ? ":warning: Invalid checksum" : ":white_check_mark: Valid checksum")));

        return components;
    }

    private static @Nullable List<Mod> getMods(SaveFileInfo info) {
        try {
            ModMetadata modMetadata = info.parseModMetadata();
            if (modMetadata != null) {
                return modMetadata.mods();
            }
        } catch (SaveFormatException e) {
            if (info.header().isModded()) {
                return Collections.emptyList();
            }
        }
        return null;
    }

}
