package net.trollyloki.dicsit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import net.trollyloki.dicsit.AttachmentInfo;
import net.trollyloki.dicsit.GuildManager;
import net.trollyloki.dicsit.InteractionUtils;
import net.trollyloki.dicsit.Server;
import net.trollyloki.dicsit.interactions.cache.AutoKeyedCache;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.*;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public final class DeployInteractions {
    private DeployInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeployInteractions.class);

    public static final String
            DEPLOY_SAVE_CONTEXT_COMMAND_NAME = "Deploy save",
            DEPLOY_COMMAND_NAME = "deploy",
            DEPLOY_SAVE_SUBCOMMAND_NAME = "save",
            DEPLOY_RESTART_SUBCOMMAND_NAME = "restart",
            DEPLOY_LOCK_SUBCOMMAND_NAME = "lock",
            DEPLOY_UNLOCK_SUBCOMMAND_NAME = "unlock",
            DEPLOY_SAVE_CANCEL_BUTTON_ID = "deploy-save-cancel",
            DEPLOY_SAVE_CONFIRM_BUTTON_ID = "deploy-save-confirm",
            DEPLOY_RESTART_CANCEL_BUTTON_ID = "deploy-restart-cancel",
            DEPLOY_RESTART_CONFIRM_BUTTON_ID = "deploy-restart-confirm";

    private static final AutoKeyedCache<AttachmentInfo> ATTACHMENT_CACHE = new AutoKeyedCache<>();

    public static void onDeploySaveFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = findSaveFileAttachments(event);
        if (attachments.isEmpty()) {
            event.reply("Could not find a save file attached to that message").setEphemeral(true).queue();
            return;
        }

        onDeploySaveHelper(event, attachments.getFirst());
    }

    public static void onDeployCommand(SlashCommandInteractionEvent event) {
        switch (event.getSubcommandName()) {
            case DEPLOY_SAVE_SUBCOMMAND_NAME -> {

                Message.Attachment attachment = event.getOption("save", OptionMapping::getAsAttachment);
                if (attachment == null) {
                    event.reply("Please provide a save file").setEphemeral(true).queue();
                    return;
                }

                if (!isSaveFile(attachment)) {
                    event.reply(attachment.getUrl() + " is not a save file").setEphemeral(true).queue();
                    return;
                }

                onDeploySaveHelper(event, attachment);
            }
            case DEPLOY_RESTART_SUBCOMMAND_NAME -> onDeployRestartHelper(event);
            case DEPLOY_LOCK_SUBCOMMAND_NAME -> {

                String password = event.getOption("password", "", OptionMapping::getAsString);

                onDeployPasswordHelper(event, password);
            }
            case DEPLOY_UNLOCK_SUBCOMMAND_NAME -> onDeployPasswordHelper(event, "");

            case null -> LOGGER.error("Missing deploy subcommand");
            default -> LOGGER.error("Unknown deploy subcommand {}", event.getSubcommandName());
        }
    }

    private static void onDeploySaveHelper(IReplyCallback callback, Message.Attachment attachment) {
        Map<UUID, Server> eventServers = getAllEventServersIfAdmin(callback);
        if (eventServers == null)
            return;

        UUID key = ATTACHMENT_CACHE.put(new AttachmentInfo(attachment));
        callback.reply("Are you sure you want to deploy " + attachment.getUrl() + " to **all " + eventServers.size() + "** event servers?").setComponents(ActionRow.of(
                Button.primary(buildId(DEPLOY_SAVE_CONFIRM_BUTTON_ID, callback.getUser().getId(), key, false), "Deploy Save"),
                Button.secondary(buildId(DEPLOY_SAVE_CONFIRM_BUTTON_ID, callback.getUser().getId(), key, true), "Deploy Save and Restart"),
                Button.secondary(buildId(DEPLOY_SAVE_CANCEL_BUTTON_ID, callback.getUser().getId(), key), "Cancel")
        )).setEphemeral(isDashboard(callback)).queue();
    }

    private static void onDeployRestartHelper(IReplyCallback callback) {
        Map<UUID, Server> eventServers = getAllEventServersIfAdmin(callback);
        if (eventServers == null)
            return;

        callback.reply("Are you sure you want to deploy a restart of **all " + eventServers.size() + "** event servers?").setComponents(ActionRow.of(
                Button.primary(buildId(DEPLOY_RESTART_CONFIRM_BUTTON_ID, callback.getUser().getId()), "Deploy Restart"),
                Button.secondary(buildId(DEPLOY_RESTART_CANCEL_BUTTON_ID, callback.getUser().getId()), "Cancel")
        )).setEphemeral(isDashboard(callback)).queue();
    }

    public static void onDeploySaveCancelButton(ButtonInteractionEvent event, String userId, String keyString) {
        event.deferEdit().queue();
        if (event.getUser().getId().equals(userId)) {
            ATTACHMENT_CACHE.pop(UUID.fromString(keyString));
            event.getHook().deleteOriginal().queue();
        }
    }

    public static void onDeploySaveConfirmButton(ButtonInteractionEvent event, String userId, String keyString, boolean restart) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        AttachmentInfo attachmentInfo = ATTACHMENT_CACHE.pop(UUID.fromString(keyString));
        if (attachmentInfo == null) {
            event.deferEdit().queue();
            event.getHook().deleteOriginal().queue();
            event.getHook().sendMessage("Context expired, please try again").setEphemeral(true).queue();
            return;
        }

        Map<UUID, Server> eventServers = getAllEventServersIfAdmin(event);
        if (eventServers == null)
            return;

        event.deferEdit().queue();

        NamedAttachmentProxy attachment = attachmentInfo.getProxy();
        String saveName = SaveFileReader.saveNameOf(attachment.getFileName());
        splitAndConsumeAttachment(event.getHook(), attachment, eventServers.size(), (uploadStreams, uploadExecutor) -> {
            GuildManager guildManager = getGuildManager(event);

            LOGGER.info("Deploying save \"{}\" to {} event servers", saveName, eventServers.size());

            Function<String, String> successFeedback = restart
                    ? servers -> servers + " will be restarted when there are no players connected"
                    : servers -> attachment.getUrl() + " will be loaded on " + servers + " when there are no players connected";
            String logAction = restart
                    ? "uploaded " + attachment.getUrl() + " and deferred restarting"
                    : "uploaded and deferred loading " + attachment.getUrl() + " on";
            deployRequest(
                    event,
                    eventServers,
                    "Uploading " + attachment.getUrl() + " to",
                    successFeedback,
                    logAction,
                    (index, entry) -> requestAsyncWithMDC(entry.getValue(), "upload to", httpsApi -> {
                        try (InputStream uploadStream = uploadStreams[index]) {
                            httpsApi.uploadSave(uploadStream, saveName, false, false);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, uploadExecutor).thenRunAsync(withMDC(() -> {
                        if (restart) {
                            guildManager.deferRestart(entry.getKey());
                        } else {
                            guildManager.deferLoad(entry.getKey(), saveName);
                        }
                    }))
            );
        });
    }

    public static void onDeployRestartCancelButton(ButtonInteractionEvent event, String userId) {
        event.deferEdit().queue();
        if (event.getUser().getId().equals(userId)) {
            event.getHook().deleteOriginal().queue();
        }
    }

    public static void onDeployRestartConfirmButton(ButtonInteractionEvent event, String userId) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        Map<UUID, Server> eventServers = getAllEventServersIfAdmin(event);
        if (eventServers == null)
            return;

        GuildManager guildManager = getGuildManager(event);
        for (UUID serverId : eventServers.keySet()) {
            guildManager.deferRestart(serverId);
        }

        logAction(event, "deferred restarting **" + eventServers.size() + "** event servers");

        event.editMessage("**" + eventServers.size() + "** event servers will be restarted when there are no players connected")
                .setComponents(Collections.emptySet()).queue();
    }

    public static void onDeployPasswordHelper(IReplyCallback callback, String password) {
        Map<UUID, Server> eventServers = getAllEventServersIfAdmin(callback);
        if (eventServers == null)
            return;

        callback.deferReply(isDashboard(callback)).queue();

        LOGGER.info("{} the client password for {} event servers", password.isEmpty() ? "Removing" : "Setting", eventServers.size());

        Function<String, String> successFeedback = password.isEmpty()
                ? servers -> servers + " are no longer locked behind a password"
                : servers -> servers + " have been locked behind the provided password";
        deployRequest(
                callback,
                eventServers,
                password.isEmpty() ? "Unlocking" : "Locking",
                successFeedback,
                password.isEmpty() ? "unlocked" : "locked",
                (_, entry) -> requestAsyncWithMDC(entry.getValue(), "set client password for", httpsApi -> {
                    httpsApi.setClientPassword(password);
                })
        );
    }

    private static void deployRequest(IDeferrableCallback callback, Map<UUID, Server> eventServers, String actioning, Function<String, String> successFeedback, String logAction, BiFunction<Integer, Map.Entry<UUID, Server>, CompletableFuture<?>> requestFunction) {

        ExecutorService messageEditExecutor = Executors.newSingleThreadExecutor();
        // These objects must ONLY be accessed via the messageEditExecutor
        List<String> errorList = new ArrayList<>();
        int[] counters = {eventServers.size(), 0};
        @Nullable CompletableFuture<?>[] editFuture = {null};

        Function<Integer, String> uploadingLineGenerator = count -> actioning + " **" + count + "** event servers...";
        callback.getHook().editOriginal(uploadingLineGenerator.apply(eventServers.size()))
                .setComponents(Collections.emptySet()).queue();

        int i = 0;
        for (Map.Entry<UUID, Server> entry : eventServers.entrySet()) {

            requestFunction.apply(i, entry).whenCompleteAsync(withMDC((_, throwable) -> {
                int uploadingCount = --counters[0];
                int successCount;
                if (throwable != null) {
                    successCount = counters[1];
                    errorList.add(InteractionUtils.exceptionMessage(throwable));
                } else {
                    successCount = ++counters[1];
                }

                if (uploadingCount == 0) {
                    messageEditExecutor.shutdown();
                    if (successCount > 0) {
                        logAction(callback, logAction + " **" + successCount + "** event servers");
                    }
                }

                StringBuilder prefixBuilder = new StringBuilder();
                if (uploadingCount > 0) {
                    prefixBuilder.append(uploadingLineGenerator.apply(uploadingCount));
                }
                if (successCount > 0) {
                    if (!prefixBuilder.isEmpty()) prefixBuilder.append('\n');
                    prefixBuilder.append(successFeedback.apply("**" + successCount + "** event servers"));
                }

                StringBuilder errorLinesBuilder = new StringBuilder();
                // Add as many error messages as can fit, starting from the "newest" ones at the end
                for (int l = errorList.size() - 1; l >= 0; l--) {
                    String line = errorList.get(l);

                    if (prefixBuilder.length() + errorLinesBuilder.length() + 1 + line.length() > Message.MAX_CONTENT_LENGTH) {
                        // Adding this line would take us over the limit
                        break;
                    }

                    errorLinesBuilder.insert(0, line).insert(0, '\n');
                }

                if (editFuture[0] != null) editFuture[0].cancel(true);
                editFuture[0] = callback.getHook().editOriginal(prefixBuilder.append(errorLinesBuilder).toString())
                        .setComponents(Collections.emptySet()).submit();
            }), messageEditExecutor);

            i++;
        }

    }

}
