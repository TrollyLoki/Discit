package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
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
import net.trollyloki.jicsit.save.SaveHeader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.InteractionUtils.findMessageAttachments;

@NullMarked
public final class AnalyzeSaveInteractions {
    private AnalyzeSaveInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeSaveInteractions.class);

    public static final String
            ANALYZE_SAVE_CONTEXT_COMMAND_NAME = "Analyze save file(s)";

    private static final String
            YES = "Yes",
            NO = "No";

    public static void onAnalyzeSaveFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = findMessageAttachments(event);
        if (attachments == null)
            return;

        event.deferReply().queue();

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        @SuppressWarnings("unchecked") CompletableFuture<MessageTopLevelComponent>[] futures = attachments.stream().map(
                attachment -> attachment.getProxy().download().thenApplyAsync(stream -> {
                    try (stream) {
                        return SaveFileReader.readInfo(SaveFileReader.saveNameOf(attachment.getFileName()), stream);
                    } catch (IOException e) {
                        MDC.setContextMap(mdc);
                        LOGGER.warn("Failed to read save file \"{}\"", attachment.getFileName(), e);
                        throw new CompletionException(e);
                    }
                }).thenApplyAsync(AnalyzeSaveInteractions::saveFileInfoContainer).exceptionally(
                        throwable -> TextDisplay.of(attachment.getUrl() + " is not a valid save file")
                )
        ).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).thenRunAsync(() -> {
            event.getHook().editOriginalComponents(Arrays.stream(futures).map(CompletableFuture::join).toList())
                    .useComponentsV2().queue();
        });
    }

    private static MessageTopLevelComponent saveFileInfoContainer(SaveFileInfo info) {
        SaveHeader header = info.header();
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + header.saveName()));

        String saved = "Saved " + TimeFormat.DEFAULT.format(header.saveTimestamp());
        if (info.originalSaveName() != null && !info.originalSaveName().equals(header.saveName())) {
            saved += " as " + info.originalSaveName();
        }
        components.add(TextDisplay.of(saved));

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("### " + header.sessionName()));
        components.add(TextDisplay.of("**Game Duration**\n" + formatDuration(header.playDuration())));
        components.add(TextDisplay.of("**Advanced Game Settings Enabled**\n" + (header.isAdvancedGameSettingsEnabled() ? YES : NO)));
        components.add(TextDisplay.of("**Modded**\n" + (header.isModded() ? YES : NO)));

        List<Mod> mods = getMods(info);
        if (mods != null) {
            components.add(TextDisplay.of("### Mods\n" + (
                    mods.isEmpty() ? "*Unknown*" : mods.stream().map(mod ->
                            "- [%s %s](https://ficsit.app/mod/%s)".formatted(mod.name(), mod.version(), mod.reference())
                    ).collect(Collectors.joining("\n"))
            )));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("### " + (header.isEdited() ? ":warning: Checksum Invalid" : ":white_check_mark: Checksum Valid")));

        return Container.of(components);
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
