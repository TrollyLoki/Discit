package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.trollyloki.discit.GuildManager;
import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.List;

import static net.trollyloki.discit.InteractionUtils.cannotManageGuild;
import static net.trollyloki.discit.InteractionUtils.getGuildManager;
import static net.trollyloki.discit.InteractionUtils.logAction;

@NullMarked
public final class SettingsInteractions {
    private SettingsInteractions() {
    }

    public static final String
            SETTINGS_COMMAND_NAME = "settings",
            ADMIN_ROLE_SELECT_ID = "admin-role",
            ACTION_LOG_CHANNEL_SELECT_ID = "action-log-channel",
            DASHBOARD_CHANNEL_SELECT_ID = "dashboard-channel";

    private static final List<ChannelType> GUILD_MESSAGE_CHANNEL_TYPES = ChannelType.guildTypes().stream().filter(ChannelType::isMessage).toList();

    public static void onSettingsCommand(SlashCommandInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        GuildManager guildManager = getGuildManager(event);

        EntitySelectMenu.Builder adminRoleSelect = EntitySelectMenu
                .create(ADMIN_ROLE_SELECT_ID, EntitySelectMenu.SelectTarget.ROLE);
        Role currentAdminRole = guildManager.getAdminRole();
        if (currentAdminRole != null) {
            adminRoleSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentAdminRole));
        }

        EntitySelectMenu.Builder actionLogChannelSelect = EntitySelectMenu
                .create(ACTION_LOG_CHANNEL_SELECT_ID, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(GUILD_MESSAGE_CHANNEL_TYPES);
        GuildMessageChannel currentActionLogChannel = guildManager.getActionLogChannel();
        if (currentActionLogChannel != null) {
            actionLogChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentActionLogChannel));
        }

        EntitySelectMenu.Builder dashboardChannelSelect = EntitySelectMenu
                .create(DASHBOARD_CHANNEL_SELECT_ID, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(GUILD_MESSAGE_CHANNEL_TYPES);
        GuildMessageChannel currentDashboardChannel = guildManager.getDashboardChannel();
        if (currentDashboardChannel != null) {
            dashboardChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentDashboardChannel));
        }

        String title = "## Settings";
        if (event.getGuild() != null) {
            title += " for " + event.getGuild().getName();
        }
        event.replyComponents(TextDisplay.of(title),
                Container.of(
                        TextDisplay.of("### Administrator Role"),
                        TextDisplay.of("Users with this role will have full administrator access to **all added servers**"),
                        ActionRow.of(adminRoleSelect.setPlaceholder("Select a role").build())
                ),
                Container.of(
                        TextDisplay.of("### Action Log Channel"),
                        TextDisplay.of("A message will be sent to this channel each time an action that requires administrator access is performed"),
                        ActionRow.of(actionLogChannelSelect.setPlaceholder("Select a text channel").build())
                ),
                Container.of(
                        TextDisplay.of("### Dashboard Channel"),
                        TextDisplay.of("Live server statuses will be displayed in this channel"),
                        ActionRow.of(dashboardChannelSelect.setPlaceholder("Select a text channel").build())
                )
        ).useComponentsV2().setEphemeral(true).queue();
    }

    public static void onAdminRoleSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setAdminRole(selection.getId());

        event.reply("Administrator role set to " + selection.getAsMention())
                .setAllowedMentions(Collections.emptySet()).setEphemeral(true).queue();
        logAction(event, "set the administrator role to " + selection.getAsMention());
    }

    public static void onActionLogChannelSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setActionLogChannel(selection.getId());

        event.reply("Action log channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the action log channel to " + selection.getAsMention());
    }

    public static void onDashboardChannelSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setDashboardChannel(selection.getId());

        event.reply("Dashboard channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the dashboard channel to " + selection.getAsMention());
    }

}
