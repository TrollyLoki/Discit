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
import java.util.stream.Stream;
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
                            return Collections.singletonList(TextDisplay.of(attachment.getUrl() + " is not a save file"));
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

                List<Container> messageContainers = new ArrayList<>(containers.size());
                int totalSize = 0;
                for (Container container : containers) {
                    // The container itself and each of its child components count towards the size
                    // (since we are only using text displays and separators here there will be no additional nesting)
                    int size = 1 + container.getComponents().size();
                    if (size > Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE) {
                        throw new UnsupportedOperationException("Container too large to send in a message ("
                                + size + " > " + Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE + ")");
                    }

                    if (totalSize + size > Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE) {
                        // Send the current message and start a new one for this container
                        event.getHook().sendMessageComponents(messageContainers).useComponentsV2().queue();
                        messageContainers = new ArrayList<>();
                        totalSize = 0;
                    }

                    messageContainers.add(container);
                    totalSize += size;
                }
                // Send any remaining containers
                if (!messageContainers.isEmpty()) {
                    event.getHook().sendMessageComponents(messageContainers).useComponentsV2().queue();
                }

            } catch (Exception e) {
                MDC.setContextMap(mdc);
                LOGGER.error("Failed to update reply", e);
            }
        });
    }

    private static SaveFileInfo readSaveFileInfo(String filename, InputStream data) throws IOException {
        return SaveFileReader.readInfo(SaveFileReader.saveNameOf(filename), data);
    }

    private enum SaveFileStatus {
        NONE(":x:"),
        INVALID(":warning:"),
        UNKNOWN(":grey_question:"),
        VALID(":white_check_mark:");

        private final String emoji;

        SaveFileStatus(String emoji) {
            this.emoji = emoji;
        }

        public String getEmoji() {
            return emoji;
        }

        public static SaveFileStatus of(SaveFileInfo saveFileInfo) {
            if (saveFileInfo.checksum() == null)
                return SaveFileStatus.UNKNOWN;
            else if (saveFileInfo.header().isEdited())
                return SaveFileStatus.INVALID;
            else
                return SaveFileStatus.VALID;
        }
    }

    private record SaveFileEntry(String name, SaveFileStatus status) implements Comparable<SaveFileEntry> {
        @Override
        public int compareTo(SaveFileEntry other) {
            return status.compareTo(other.status);
        }

        public TextDisplay toTextDisplay() {
            return TextDisplay.ofFormat("%s `%s` %s", status.getEmoji(), name, switch (status) {
                case NONE -> "is not a save file";
                case UNKNOWN -> "has an unknown checksum";
                case INVALID -> "has an invalid checksum";
                case VALID -> "has a valid checksum";
            });
        }
    }

    private static List<TextDisplay> zipFileComponents(String filename, ZipInputStream zipStream) throws IOException {
        List<SaveFileEntry> entries = new ArrayList<>();

        ZipEntry zipEntry;
        while ((zipEntry = zipStream.getNextEntry()) != null) {
            if (zipEntry.isDirectory()) continue;

            SaveFileStatus status;
            try {
                SaveFileInfo info = readSaveFileInfo(zipEntry.getName(), zipStream);
                status = SaveFileStatus.of(info);
            } catch (IOException e) {
                LOGGER.warn("Failed to read save file \"{}\" within \"{}\"", zipEntry.getName(), filename, e);
                status = SaveFileStatus.NONE;
            }
            entries.add(new SaveFileEntry(zipEntry.getName(), status));

            zipStream.closeEntry();
        }

        TextDisplay header = TextDisplay.of("## " + filename);

        if (entries.isEmpty()) {
            return List.of(header, TextDisplay.of("*empty*"));
        }

        entries.sort(null); // sort by status, "worst" first

        // Don't forget to include the outer container and header text display
        if (entries.size() + 2 <= Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE) {
            return Stream.concat(Stream.of(header), entries.stream().map(SaveFileEntry::toTextDisplay)).toList();
        }

        // Leave room for the outer container, header text display, and remaining summary line
        int includedCount = Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE - 3;
        List<TextDisplay> components = new ArrayList<>(includedCount + 2);

        components.add(header);

        for (int i = 0; i < includedCount; i++) {
            components.add(entries.get(i).toTextDisplay());
        }

        int remainingCount = entries.size() - includedCount;
        components.add(TextDisplay.ofFormat("*and %,d more%s*", remainingCount, switch (entries.get(includedCount).status) {
            // All remaining entries will be no "worse" than the very first remaining entry due to sorting
            case NONE -> "";
            case INVALID, UNKNOWN -> " save files";
            case VALID -> " save files with valid checksums";
        }));

        return components;
    }

    private static List<ContainerChildComponent> saveFileInfoComponents(SaveFileInfo info) {
        List<ContainerChildComponent> components = new ArrayList<>();

        String header = "## " + info.header().saveName() + "\nCreated " + TimeFormat.DEFAULT.format(info.header().saveTimestamp());
        if (info.originalSaveName() != null) {
            header += " as " + info.originalSaveName();
        }
        components.add(TextDisplay.of(header));

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
        SaveFileStatus status = SaveFileStatus.of(info);
        components.add(TextDisplay.of("### " + status.getEmoji() + switch (status) {
            case VALID -> " Valid checksum";
            case INVALID -> " Invalid checksum";
            default -> " Unknown checksum";
        }));

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
