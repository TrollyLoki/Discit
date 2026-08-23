package net.trollyloki.dicsit;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.trollyloki.dicsit.InteractionUtils.getGuildManager;
import static net.trollyloki.dicsit.LoggingUtils.setMDC;
import static net.trollyloki.dicsit.interactions.AboutInteractions.ABOUT_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.AboutInteractions.onAboutCommand;
import static net.trollyloki.dicsit.interactions.AddInteractions.*;
import static net.trollyloki.dicsit.interactions.AnalyzeSaveInteractions.ANALYZE_SAVE_CONTEXT_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.AnalyzeSaveInteractions.onAnalyzeSaveFromMessage;
import static net.trollyloki.dicsit.interactions.BackupInteractions.BACKUP_COMMAND_NAME;
import static net.trollyloki.dicsit.interactions.BackupInteractions.BACKUP_MODAL_ID;
import static net.trollyloki.dicsit.interactions.BackupInteractions.onBackupCommand;
import static net.trollyloki.dicsit.interactions.BackupInteractions.onBackupModal;
import static net.trollyloki.dicsit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_MODAL_ID;
import static net.trollyloki.dicsit.interactions.ChangePasswordInteractions.onChangePasswordButton;
import static net.trollyloki.dicsit.interactions.ChangePasswordInteractions.onChangePasswordModal;
import static net.trollyloki.dicsit.interactions.CreativeModeInteractions.*;
import static net.trollyloki.dicsit.interactions.DeferredActionsInteractions.*;
import static net.trollyloki.dicsit.interactions.DeployInteractions.*;
import static net.trollyloki.dicsit.interactions.InvalidateTokensInteractions.INVALIDATE_TOKENS_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.InvalidateTokensInteractions.onInvalidateTokensButton;
import static net.trollyloki.dicsit.interactions.ListInteractions.*;
import static net.trollyloki.dicsit.interactions.NewSessionInteractions.*;
import static net.trollyloki.dicsit.interactions.ReloadInteractions.*;
import static net.trollyloki.dicsit.interactions.RenameInteractions.RENAME_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.RenameInteractions.RENAME_MODAL_ID;
import static net.trollyloki.dicsit.interactions.RenameInteractions.onRenameButton;
import static net.trollyloki.dicsit.interactions.RenameInteractions.onRenameModal;
import static net.trollyloki.dicsit.interactions.SaveInteractions.*;
import static net.trollyloki.dicsit.interactions.ServerOptionsInteractions.*;
import static net.trollyloki.dicsit.interactions.SettingsInteractions.*;
import static net.trollyloki.dicsit.interactions.UploadInteractions.*;

@NullMarked
public class InteractionListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionListener.class);

    public static final String
            CANCEL_BUTTON_ID = "cancel",
            DASHBOARD_REFRESH_BUTTON_ID = "dashboard-refresh";

    public static String buildId(Object... arguments) {
        return Arrays.stream(arguments).map(Object::toString)
                .map(string -> string.replaceAll(":", "\\\\:"))
                .collect(Collectors.joining(":"));
    }

    private static String[] splitId(String id) {
        String[] split = id.split("(?<!\\\\):");
        for (int i = 0; i < split.length; i++) {
            split[i] = split[i].replaceAll("\\\\:", ":");
        }
        return split;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        setMDC(event);
        try {
            switch (event.getName()) {
                case ABOUT_COMMAND_NAME -> onAboutCommand(event);
                case SETTINGS_COMMAND_NAME -> onSettingsCommand(event);
                case ADD_COMMAND_NAME -> onAddCommand(event);
                case LIST_COMMAND_NAME -> onListCommand(event);
                case RELOAD_COMMAND_NAME -> onReloadCommand(event, false);
                case RESTART_COMMAND_NAME -> onReloadCommand(event, true);
                case SAVE_COMMAND_NAME -> onSaveCommand(event);
                case UPLOAD_COMMAND_NAME -> onUploadCommand(event);
                case BACKUP_COMMAND_NAME -> onBackupCommand(event);
                case DEFERRED_ACTIONS_COMMAND_NAME -> onDeferredActionsCommand(event);
                case DEPLOY_COMMAND_NAME -> onDeployCommand(event);
                default -> LOGGER.warn("Unknown slash command {}", event.getName());
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onMessageContextInteraction(MessageContextInteractionEvent event) {
        setMDC(event);
        try {
            switch (event.getName()) {
                case UPLOAD_CONTEXT_COMMAND_NAME -> onUploadFromMessage(event);
                case DEPLOY_SAVE_CONTEXT_COMMAND_NAME -> onDeploySaveFromMessage(event);
                case ANALYZE_SAVE_CONTEXT_COMMAND_NAME -> onAnalyzeSaveFromMessage(event);
                default -> LOGGER.warn("Unknown message context command {}", event.getName());
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        setMDC(event);
        try {
            String[] id = splitId(event.getComponentId());
            switch (id[0]) {
                case ADMIN_ROLE_SELECT_ID -> onAdminRoleSelect(event);
                case SAVE_MANAGER_ROLE_SELECT_ID -> onSaveManagerRoleSelect(event);
                case DASHBOARD_CHANNEL_SELECT_ID -> onDashboardChannelSelect(event);
                case LOG_CHANNEL_SELECT_ID -> onLogChannelSelect(event);
                case ALERT_ROLE_SELECT_ID -> onAlertRoleSelect(event);
                case SERVER_CHANNEL_SELECT_ID -> onServerChannelSelect(event, id[1]);
                default -> LOGGER.warn("Unknown entity select ID {}", event.getComponentId());
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        setMDC(event);
        try {
            String[] id = splitId(event.getComponentId());
            switch (id[0]) {
                case CANCEL_BUTTON_ID -> onCancelButton(event);
                case UNSET_ALERT_ROLE_BUTTON_ID -> onUnsetAlertRoleButton(event);
                case ADD_RETRY_BUTTON_ID -> onRetryButton(event, id[1], Integer.parseInt(id[2]));
                case ADD_CONFIRM_BUTTON_ID -> onAddConfirmButton(event, id[1], Integer.parseInt(id[2]), id[3]);
                case CLAIM_BUTTON_ID -> onClaimButton(event, id[1], Boolean.parseBoolean(id[2]));
                case AUTHENTICATE_BUTTON_ID -> onAuthenticateButton(event, id[1], false);
                case LIST_AUTHENTICATE_BUTTON_ID -> onAuthenticateButton(event, id[1], true);
                case LIST_DEAUTHENTICATE_BUTTON_ID -> onDeauthenticateButtonOnList(event, id[1]);
                case UPDATE_FINGERPRINT_BUTTON_ID -> onUpdateFingerprintButton(event, id[1]);
                case CANCEL_FINGERPRINT_UPDATE_BUTTON_ID -> onCancelFingerprintUpdateButton(event, id[1]);
                case CONFIRM_FINGERPRINT_BUTTON_ID -> onConfirmFingerprintButton(event, id[1], id[2]);
                case LIST_REMOVE_BUTTON_ID -> onListRemoveButton(event, id[1]);
                case UNSET_SERVER_CHANNEL_BUTTON_ID -> onUnsetServerChannelButton(event, id[1]);
                case ALLOW_RELOADING_BUTTON_ID -> onAllowReloadingButton(event, id[1], Boolean.parseBoolean(id[2]));
                case DISABLE_SAVING_BUTTON_ID -> onDisableSavingButton(event, id[1], Boolean.parseBoolean(id[2]));
                case EVENT_SERVER_BUTTON_ID -> onEventServerButton(event, id[1], Boolean.parseBoolean(id[2]));
                case DASHBOARD_REFRESH_BUTTON_ID -> onDashboardRefreshButton(event, id[1]);
                case RELOAD_CANCEL_BUTTON_ID -> onReloadCancelButton(event, id[1], id[2]);
                case RELOAD_CONFIRM_BUTTON_ID -> onReloadConfirmButton(event, false, id[1], id[2], false);
                case RELOAD_DEFER_BUTTON_ID -> onReloadConfirmButton(event, false, id[1], id[2], true);
                case RESTART_CONFIRM_BUTTON_ID -> onReloadConfirmButton(event, true, id[1], id[2], false);
                case RESTART_DEFER_BUTTON_ID -> onReloadConfirmButton(event, true, id[1], id[2], true);
                case RELOAD_BUTTON_ID -> onReloadButton(event, false, id[1]);
                case RESTART_BUTTON_ID -> onReloadButton(event, true, id[1]);
                case SAVE_BUTTON_ID -> onSaveButton(event, id[1]);
                case UPLOAD_BUTTON_ID -> onUploadButton(event, id[1]);
                case UPLOAD_CANCEL_BUTTON_ID -> onUploadCancelButton(event, id[1], id[2]);
                case UPLOAD_CONFIRM_BUTTON_ID -> onUploadConfirmButton(event, id[1], id[2], false);
                case UPLOAD_AND_DEFER_LOAD_BUTTON_ID -> onUploadConfirmButton(event, id[1], id[2], true);
                case NEW_SESSION_BUTTON_ID -> onNewSessionButton(event, id[1]);
                case NEW_SESSION_CANCEL_BUTTON_ID -> onNewSessionCancelButton(event, id[1], id[2]);
                case NEW_SESSION_CONFIRM_BUTTON_ID -> onNewSessionConfirmButton(event, id[1], id[2]);
                case RENAME_BUTTON_ID -> onRenameButton(event, id[1]);
                case SERVER_OPTIONS_BUTTON_ID -> onServerOptionsButton(event, id[1]);
                case SET_SERVER_OPTION_COMPONENT_ID -> onSetServerOptionButton(event, id[1], id[2], id[3]);
                case CREATIVE_MODE_BUTTON_ID -> onCreativeModeButton(event, id[1]);
                case CREATIVE_ENABLE_BUTTON_ID -> onCreativeModeSettingEnableButton(event, id[1], id[2]);
                case CHANGE_PASSWORD_BUTTON_ID -> onChangePasswordButton(event, id[1], id[2]);
                case INVALIDATE_TOKENS_BUTTON_ID -> onInvalidateTokensButton(event, id[1]);
                case CANCEL_ALL_DEFERRED_ACTIONS_BUTTON_ID -> onCancelAllDeferredActionsButton(event);
                case CANCEL_DEFERRED_LOAD_BUTTON_ID ->
                        onCancelDeferredLoadButton(event, id[1], Boolean.parseBoolean(id[2]));
                case CANCEL_DEFERRED_RELOAD_BUTTON_ID ->
                        onCancelDeferredReloadButton(event, id[1], Boolean.parseBoolean(id[2]));
                case CANCEL_DEFERRED_RESTART_BUTTON_ID ->
                        onCancelDeferredRestartButton(event, id[1], Boolean.parseBoolean(id[2]));
                case DEPLOY_SAVE_CANCEL_BUTTON_ID -> onDeploySaveCancelButton(event, id[1], id[2]);
                case DEPLOY_SAVE_CONFIRM_BUTTON_ID ->
                        onDeploySaveConfirmButton(event, id[1], id[2], Boolean.parseBoolean(id[3]));
                case DEPLOY_RESTART_CANCEL_BUTTON_ID -> onDeployRestartCancelButton(event, id[1]);
                case DEPLOY_RESTART_CONFIRM_BUTTON_ID -> onDeployRestartConfirmButton(event, id[1]);
                default -> LOGGER.warn("Unknown button ID {}", event.getComponentId());
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        setMDC(event);
        try {
            String[] id = splitId(event.getComponentId());
            switch (id[0]) {
                case DEFAULT_OFFLINE_ALERT_DELAY_SELECT_ID -> onDefaultOfflineAlertDelaySelect(event);
                case LIST_SELECT_ID -> onListSelect(event);
                case OFFLINE_ALERT_DELAY_SELECT_ID -> onOfflineAlertDelaySelect(event, id[1]);
                case AUTOLOAD_SESSION_NAME_SELECT_ID -> onSetAutoloadSessionNameSelect(event, id[1]);
                case SET_SERVER_OPTION_COMPONENT_ID -> onSetServerOptionSelect(event, id[1], id[2]);
                case CREATIVE_VALUE_SELECT_ID -> onCreativeModeSettingValueSelect(event, id[1], id[2]);
                default -> LOGGER.warn("Unknown string select ID {}", event.getComponentId());
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        setMDC(event);
        try {
            String[] id = splitId(event.getModalId());
            switch (id[0]) {
                case CLAIM_MODAL_ID -> onClaimModal(event, id[1], Boolean.parseBoolean(id[2]));
                case AUTHENTICATE_MODAL_ID -> onAuthenticateModal(event, id[1], Boolean.parseBoolean(id[2]));
                case RELOAD_MODAL_ID -> onReloadModal(event, false);
                case RESTART_MODAL_ID -> onReloadModal(event, true);
                case SAVE_MODAL_ID -> onSaveModal(event, id.length > 1 ? id[1] : null);
                case UPLOAD_MODAL_ID -> onUploadModal(event, id.length > 1 ? id[1] : null);
                case BACKUP_MODAL_ID -> onBackupModal(event);
                case NEW_SESSION_MODAL_ID -> onNewSessionModal(event, id[1]);
                case RENAME_MODAL_ID -> onRenameModal(event, id[1]);
                case CHANGE_PASSWORD_MODAL_ID -> onChangePasswordModal(event, id[1], id[2]);
                default -> LOGGER.warn("Unknown modal ID {}", event.getModalId());
            }
        } finally {
            MDC.clear();
        }
    }

    public void onCancelButton(ButtonInteractionEvent event) {
        event.deferEdit().queue();

        event.getHook().deleteOriginal().queue();
    }

    public void onDashboardRefreshButton(ButtonInteractionEvent event, String serverIdString) {
        // no permission required

        LOGGER.info("Forcing refresh of server {}", serverIdString);
        getGuildManager(event).refreshServer(UUID.fromString(serverIdString));

        event.deferEdit().queue();
    }

}
