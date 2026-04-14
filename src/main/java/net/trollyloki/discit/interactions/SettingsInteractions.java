package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.trollyloki.discit.GuildManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.function.BiConsumer;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class SettingsInteractions {
    private SettingsInteractions() {
    }

    public static final String
            SETTINGS_COMMAND_NAME = "settings",
            ADMIN_ROLE_SELECT_ID = "admin-role",
            DASHBOARD_CHANNEL_SELECT_ID = "dashboard-channel",
            LOG_CHANNEL_SELECT_ID = "log-channel",
            OFFLINE_ALERT_DELAY_SELECT_ID = "offline-alert-delay";

    private static final int[] DELAY_OPTIONS_SECONDS = {-1, 5, 10, 20, 30, 60, 2 * 60, 3 * 60, 4 * 60, 5 * 60, 10 * 60, 20 * 60, 30 * 60, 60 * 60};

    public static void onSettingsCommand(SlashCommandInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Member member = event.getMember();
        boolean canManageGuild = member != null && member.hasPermission(Permission.MANAGE_SERVER);

        EntitySelectMenu.Builder adminRoleSelect = EntitySelectMenu.create(ADMIN_ROLE_SELECT_ID, EntitySelectMenu.SelectTarget.ROLE);
        Role currentAdminRole = guildManager.getAdminRole();
        if (currentAdminRole != null) {
            adminRoleSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentAdminRole));
        }

        EntitySelectMenu.Builder dashboardChannelSelect = messageChannelSelect(DASHBOARD_CHANNEL_SELECT_ID);
        GuildMessageChannel currentDashboardChannel = guildManager.getDashboardChannel();
        if (currentDashboardChannel != null) {
            dashboardChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentDashboardChannel));
        }

        EntitySelectMenu.Builder logChannelSelect = messageChannelSelect(LOG_CHANNEL_SELECT_ID);
        GuildMessageChannel currentLogChannel = guildManager.getLogChannel();
        if (currentLogChannel != null) {
            logChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentLogChannel));
        }

        Duration currentOfflineAlertDelay = guildManager.getOfflineAlertDelay();
        StringSelectMenu offlineAlertDelaySelect = createIntSelectMenu(OFFLINE_ALERT_DELAY_SELECT_ID, seconds -> {
            if (seconds < 0) return "Disable alerts";
            else return formatDuration(seconds);
        }, currentOfflineAlertDelay == null ? -1 : (int) currentOfflineAlertDelay.toSeconds(), DELAY_OPTIONS_SECONDS);

        String title = "## Settings";
        if (event.getGuild() != null) {
            title += " for " + event.getGuild().getName();
        }
        event.replyComponents(Container.of(
                TextDisplay.of(title),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### Administrator Role"),
                TextDisplay.of("Users with this role will have full administrator access to **all added servers**"),
                ActionRow.of(adminRoleSelect.setPlaceholder("Select a role").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Dashboard Channel"),
                TextDisplay.of("Live server statuses will be displayed in this channel"),
                ActionRow.of(dashboardChannelSelect.setPlaceholder("Select a channel").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Log Channel"),
                TextDisplay.of("A message will be sent to this channel each time an action that requires administrator access is performed"),
                ActionRow.of(logChannelSelect.setPlaceholder("Select a channel").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Offline Alert Delay"),
                TextDisplay.of("If a server goes and stays offline for this amount of time a message mentioning the administrator role will be sent to the log channel"),
                ActionRow.of(offlineAlertDelaySelect)
        )).useComponentsV2().setEphemeral(true).queue();
    }

    public static void onAdminRoleSelect(EntitySelectInteractionEvent event) {
        IMentionable selection = onEntitySelectHelper(event, GuildManager::setAdminRole);
        if (selection == null)
            return;

        event.reply("Administrator role set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the administrator role to " + selection.getAsMention());
    }

    public static void onDashboardChannelSelect(EntitySelectInteractionEvent event) {
        IMentionable selection = onEntitySelectHelper(event, GuildManager::setDashboardChannel);
        if (selection == null)
            return;

        event.reply("Dashboard channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the dashboard channel to " + selection.getAsMention());
    }

    public static void onLogChannelSelect(EntitySelectInteractionEvent event) {
        IMentionable selection = onEntitySelectHelper(event, GuildManager::setLogChannel);
        if (selection == null)
            return;

        event.reply("Log channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the log channel to " + selection.getAsMention());
    }

    private static @Nullable IMentionable onEntitySelectHelper(EntitySelectInteractionEvent event, BiConsumer<GuildManager, String> setter) {
        if (cannotManageGuild(event))
            return null;

        IMentionable selection = event.getValues().getFirst();

        setter.accept(getGuildManager(event), selection.getId());

        return selection;
    }

    public static void onOfflineAlertDelaySelect(StringSelectInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        int seconds = Integer.parseInt(event.getValues().getFirst());
        Duration duration = seconds < 0 ? null : Duration.ofSeconds(seconds);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setOfflineAlertDelay(duration);

        if (duration != null) {
            String formatted = formatDuration(duration.toSeconds());
            event.reply("Offline alert delay set to " + formatted).setEphemeral(true).queue();
            logAction(event, "set the offline alert delay to " + formatted);
        } else {
            event.reply("Offline alerts disabled").setEphemeral(true).queue();
            logAction(event, "disabled offline alerts");
        }
    }

}
