package net.trollyloki.discit;

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

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.trollyloki.discit.InteractionUtils.getGuildManager;
import static net.trollyloki.discit.LoggingUtils.setMDC;
import static net.trollyloki.discit.interactions.AddInteractions.*;
import static net.trollyloki.discit.interactions.AdvancedGameSettingsInteractions.*;
import static net.trollyloki.discit.interactions.AnalyzeSaveInteractions.ANALYZE_SAVE_CONTEXT_COMMAND_NAME;
import static net.trollyloki.discit.interactions.AnalyzeSaveInteractions.onAnalyzeSaveFromMessage;
import static net.trollyloki.discit.interactions.BackupInteractions.BACKUP_COMMAND_NAME;
import static net.trollyloki.discit.interactions.BackupInteractions.onBackupCommand;
import static net.trollyloki.discit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_BUTTON_ID;
import static net.trollyloki.discit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_MODAL_ID;
import static net.trollyloki.discit.interactions.ChangePasswordInteractions.onChangePasswordButton;
import static net.trollyloki.discit.interactions.ChangePasswordInteractions.onChangePasswordModal;
import static net.trollyloki.discit.interactions.InvalidateTokensInteractions.INVALIDATE_TOKENS_BUTTON_ID;
import static net.trollyloki.discit.interactions.InvalidateTokensInteractions.onInvalidateTokensButton;
import static net.trollyloki.discit.interactions.ListInteractions.*;
import static net.trollyloki.discit.interactions.ReloadInteractions.*;
import static net.trollyloki.discit.interactions.RenameInteractions.RENAME_BUTTON_ID;
import static net.trollyloki.discit.interactions.RenameInteractions.RENAME_MODAL_ID;
import static net.trollyloki.discit.interactions.RenameInteractions.onRenameButton;
import static net.trollyloki.discit.interactions.RenameInteractions.onRenameModal;
import static net.trollyloki.discit.interactions.SaveInteractions.*;
import static net.trollyloki.discit.interactions.ServerOptionsInteractions.*;
import static net.trollyloki.discit.interactions.SettingsInteractions.*;
import static net.trollyloki.discit.interactions.UploadInteractions.*;

@NullMarked
public class InteractionListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionListener.class);

    public static final String
            CANCEL_BUTTON_ID = "cancel",
            DASHBOARD_REFRESH_BUTTON_ID = "dashboard-refresh";

    public static String buildId(Object... arguments) {
        return Arrays.stream(arguments).map(Object::toString).collect(Collectors.joining(":"));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        setMDC(event);
        switch (event.getName()) {
            case SETTINGS_COMMAND_NAME -> onSettingsCommand(event);
            case ADD_COMMAND_NAME -> onAddCommand(event);
            case LIST_COMMAND_NAME -> onListCommand(event);
            case RELOAD_COMMAND_NAME -> onReloadCommand(event);
            case SAVE_COMMAND_NAME -> onSaveCommand(event);
            case UPLOAD_COMMAND_NAME -> onUploadCommand(event);
            case BACKUP_COMMAND_NAME -> onBackupCommand(event);
            default -> LOGGER.warn("Unknown slash command {}", event.getName());
        }
    }

    @Override
    public void onMessageContextInteraction(MessageContextInteractionEvent event) {
        setMDC(event);
        switch (event.getName()) {
            case UPLOAD_CONTEXT_COMMAND_NAME -> onUploadFromMessage(event);
            case ANALYZE_SAVE_CONTEXT_COMMAND_NAME -> onAnalyzeSaveFromMessage(event);
            default -> LOGGER.warn("Unknown message context command {}", event.getName());
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        setMDC(event);
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case ADMIN_ROLE_SELECT_ID -> onAdminRoleSelect(event);
            case ACTION_LOG_CHANNEL_SELECT_ID -> onActionLogChannelSelect(event);
            case DASHBOARD_CHANNEL_SELECT_ID -> onDashboardChannelSelect(event);
            default -> LOGGER.warn("Unknown entity select ID {}", event.getComponentId());
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        setMDC(event);
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case CANCEL_BUTTON_ID -> onCancelButton(event);
            case ADD_RETRY_BUTTON_ID -> onRetryButton(event, id[1], Integer.parseInt(id[2]));
            case ADD_CONFIRM_BUTTON_ID -> onAddConfirmButton(event, id[1], Integer.parseInt(id[2]), id[3]);
            case CLAIM_BUTTON_ID -> onClaimButton(event, id[1]);
            case AUTHENTICATE_BUTTON_ID -> onAuthenticateButton(event, id[1], false);
            case LIST_AUTHENTICATE_BUTTON_ID -> onAuthenticateButton(event, id[1], true);
            case LIST_DEAUTHENTICATE_BUTTON_ID -> onDeauthenticateButtonOnList(event, id[1]);
            case LIST_REMOVE_BUTTON_ID -> onListRemoveButton(event, id[1]);
            case DASHBOARD_REFRESH_BUTTON_ID -> onDashboardRefreshButton(event, id[1]);
            case RELOAD_BUTTON_ID -> onReloadButton(event, id[1]);
            case SAVE_BUTTON_ID -> onSaveButton(event, id[1]);
            case UPLOAD_BUTTON_ID -> onUploadButton(event, id[1]);
            case RENAME_BUTTON_ID -> onRenameButton(event, id[1]);
            case SERVER_OPTIONS_BUTTON_ID -> onServerOptionsButton(event, id[1]);
            case SET_SERVER_OPTION_COMPONENT_ID -> onSetServerOptionButton(event, id[1], id[2], id[3]);
            case AGS_BUTTON_ID -> onAdvancedGameSettingsButton(event, id[1]);
            case AGS_ENABLE_BUTTON_ID -> onAdvancedGameSettingEnableButton(event, id[1], id[2]);
            case CHANGE_PASSWORD_BUTTON_ID -> onChangePasswordButton(event, id[1], id[2]);
            case INVALIDATE_TOKENS_BUTTON_ID -> onInvalidateTokensButton(event, id[1]);
            default -> LOGGER.warn("Unknown button ID {}", event.getComponentId());
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        setMDC(event);
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case LIST_SELECT_ID -> onListSelect(event);
            case AUTOLOAD_SESSION_NAME_SELECT_ID -> onSetAutoloadSessionNameSelect(event, id[1]);
            case SET_SERVER_OPTION_COMPONENT_ID -> onSetServerOptionSelect(event, id[1], id[2]);
            case AGS_VALUE_SELECT_ID -> onAdvancedGameSettingValueSelect(event, id[1], id[2]);
            default -> LOGGER.warn("Unknown string select ID {}", event.getComponentId());
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        setMDC(event);
        String[] id = event.getModalId().split(":");
        switch (id[0]) {
            case CLAIM_MODAL_ID -> onClaimModal(event, id[1]);
            case AUTHENTICATE_MODAL_ID -> onAuthenticateModal(event, id[1], Boolean.parseBoolean(id[2]));
            case RELOAD_MODAL_ID -> onReloadModal(event);
            case SAVE_MODAL_ID -> onSaveModal(event, id.length > 1 ? id[1] : null);
            case UPLOAD_MODAL_ID -> onUploadModal(event, id.length > 1 ? id[1] : null);
            case RENAME_MODAL_ID -> onRenameModal(event, id[1]);
            case CHANGE_PASSWORD_MODAL_ID -> onChangePasswordModal(event, id[1], id[2]);
            default -> LOGGER.warn("Unknown modal ID {}", event.getModalId());
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
