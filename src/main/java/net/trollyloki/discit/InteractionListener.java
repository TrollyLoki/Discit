package net.trollyloki.discit;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.attachmentupload.AttachmentUpload;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.label.LabelChildComponent;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.trollyloki.jicsit.save.SaveFileReader;
import net.trollyloki.jicsit.save.SaveHeader;
import net.trollyloki.jicsit.save.Session;
import net.trollyloki.jicsit.server.https.CertificateUtils;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.https.exception.InvalidTokenException;
import net.trollyloki.jicsit.server.https.exception.PasswordlessLoginNotPossibleException;
import net.trollyloki.jicsit.server.query.QueryApi;
import net.trollyloki.jicsit.server.query.ServerState;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.trollyloki.discit.Utils.defaultSaveName;
import static net.trollyloki.discit.Utils.serverDisplayName;
import static net.trollyloki.discit.Utils.validateHostAddress;

@NullMarked
public class InteractionListener extends ListenerAdapter {

    private final Discit discit;

    public InteractionListener(Discit discit) {
        this.discit = discit;

        discit.getJDA().updateCommands().addCommands(
                Commands.slash("settings", "Change settings"),
                Commands.slash("add", "Add a server").addOptions(
                        new OptionData(OptionType.STRING, "host", "Server host address")
                                .setRequired(true),
                        new OptionData(OptionType.INTEGER, "port", "Server port")
                                .setRequired(true)
                                .setRequiredRange(0, 65535)
                ),
                Commands.slash("list", "List added servers"),
                Commands.slash("reload", "Save and reload the active session on one or more servers"),
                Commands.slash("save", "Create and download a save from a server"),
                Commands.slash("upload", "Upload a save file to one or more servers"),
                Commands.message("Upload save file")
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "settings" -> onSettings(event);
            case "add" -> onAdd(event);
            case "list" -> onListCommand(event);
            case "reload" -> onReloadCommand(event);
            case "save" -> onSaveCommand(event);
            case "upload" -> onUploadCommand(event);
        }
    }

    @Override
    public void onMessageContextInteraction(MessageContextInteractionEvent event) {
        switch (event.getName()) {
            case "Upload save file" -> onUploadFromMessage(event);
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case "admin-role" -> onSelectAdminRole(event);
            case "action-log-channel" -> onSelectActionLogChannel(event);
            case "dashboard-channel" -> onSelectDashboardChannel(event);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case "cancel" -> onCancel(event);
            case "retry" -> onAddRetry(event, id[1], Integer.parseInt(id[2]));
            case "add-confirm" -> onAddConfirm(event, id[1], Integer.parseInt(id[2]), id[3]);
            case "claim" -> onStartClaim(event, UUID.fromString(id[1]));
            case "remove" -> onRemove(event, UUID.fromString(id[1]));
            case "authenticate" -> onAuthenticate(event, UUID.fromString(id[1]));
            case "deauthenticate" -> onDeauthenticate(event, UUID.fromString(id[1]));
            case "refresh" -> onRefresh(event, UUID.fromString(id[1]));
            case "dashboard-reload" -> onReloadFromDashboard(event, UUID.fromString(id[1]));
            case "dashboard-save" -> onSaveFromDashboard(event, UUID.fromString(id[1]));
            case "dashboard-upload" -> onUploadFromDashboard(event, UUID.fromString(id[1]));
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String[] id = event.getComponentId().split(":");
        switch (id[0]) {
            case "details" -> onListDetails(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String[] id = event.getModalId().split(":");
        switch (id[0]) {
            case "claim" -> onClaim(event, UUID.fromString(id[1]));
            case "authentication" -> onAuthentication(event, UUID.fromString(id[1]));
            case "reload" -> onReload(event);
            case "save" -> onSave(event);
            case "upload" -> onUpload(event);
        }
    }

    private GuildManager getGuildManager(Interaction event) {
        Guild guild = event.getGuild();
        if (guild == null) //FIXME: Is this going to cause problems?
            throw new UnsupportedOperationException("This operation can only be done from within a guild");
        return discit.getGuildManager(guild.getId());
    }

    private boolean cannotManageGuild(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member != null && member.hasPermission(Permission.MANAGE_SERVER)) {
            return false;
        }
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    private boolean missingAdminRole(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member != null && getGuildManager(callback).hasAdminRole(member)) {
            return false;
        }
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    private static final List<ChannelType> GUILD_MESSAGE_CHANNEL_TYPES = ChannelType.guildTypes().stream().filter(ChannelType::isMessage).toList();

    private void onSettings(SlashCommandInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        GuildManager guildManager = getGuildManager(event);

        EntitySelectMenu.Builder adminRoleSelect = EntitySelectMenu
                .create("admin-role", EntitySelectMenu.SelectTarget.ROLE);
        Role currentAdminRole = guildManager.getAdminRole();
        if (currentAdminRole != null) {
            adminRoleSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentAdminRole));
        }

        EntitySelectMenu.Builder actionLogChannelSelect = EntitySelectMenu
                .create("action-log-channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(GUILD_MESSAGE_CHANNEL_TYPES);
        GuildMessageChannel currentActionLogChannel = guildManager.getActionLogChannel();
        if (currentActionLogChannel != null) {
            actionLogChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentActionLogChannel));
        }

        EntitySelectMenu.Builder dashboardChannelSelect = EntitySelectMenu
                .create("dashboard-channel", EntitySelectMenu.SelectTarget.CHANNEL)
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

    private void onSelectAdminRole(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setAdminRole(selection.getId());

        event.reply("Administrator role set to " + selection.getAsMention())
                .setAllowedMentions(Collections.emptySet()).setEphemeral(true).queue();
        guildManager.logAction(event.getUser(), "set the administrator role to " + selection.getAsMention());
    }

    private void onSelectActionLogChannel(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setActionLogChannel(selection.getId());

        event.reply("Action log channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        guildManager.logAction(event.getUser(), "set the action log channel to " + selection.getAsMention());
    }

    private void onSelectDashboardChannel(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = event.getValues().get(0);

        GuildManager guildManager = getGuildManager(event);
        guildManager.setDashboardChannel(selection.getId());

        event.reply("Dashboard channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        guildManager.logAction(event.getUser(), "set the dashboard channel to " + selection.getAsMention());
    }

    private void onCancel(ButtonInteractionEvent event) {
        event.deferEdit().queue();

        event.getHook().deleteOriginal().queue();
    }

    private void onAdd(SlashCommandInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        String host = event.getOption("host", OptionMapping::getAsString);
        if (host == null) {
            event.reply("Please provide a host address").setEphemeral(true).queue();
            return;
        }

        Integer port = event.getOption("port", OptionMapping::getAsInt);

        event.deferReply(true).queue();
        tryAdd(event.getHook(), host, port != null ? port : 7777);
    }

    private void tryAdd(InteractionHook hook, String host, int port) {
        InetAddress address = validateHostAddress(host);
        if (address == null) {
            hook.editOriginal("Invalid address").queue();
            return;
        }

        CompletableFuture.runAsync(() -> {
            try (QueryApi queryApi = QueryApi.of(address, port, Duration.ofSeconds(3))) {

                ServerState serverState = queryApi.pollServerState();
                String fingerprint = CertificateUtils.getServerFingerprint(host, port);

                hook.editOriginal("").setComponents(
                        TextDisplay.of("Is this the server you are trying to add?"),
                        Container.of(
                                TextDisplay.of("## " + serverDisplayName(serverState.name())),
                                TextDisplay.of("### Fingerprint\n```" + fingerprint + "```"),
                                TextDisplay.of("### Build Version\n```" + serverState.build() + "```")
                        ),
                        ActionRow.of(
                                Button.success("add-confirm:" + host + ":" + port + ":" + fingerprint, "Confirm"),
                                Button.secondary("cancel", "Cancel")
                        )
                ).useComponentsV2().queue();

            } catch (Exception e) {
                hook.editOriginal("Could not connect to that server").setComponents(ActionRow.of(
                        Button.primary("retry:" + host + ":" + port, "Retry")
                )).queue();
                e.printStackTrace();
            }
        });
    }

    private void onAddRetry(ButtonInteractionEvent event, String host, int port) {
        if (missingAdminRole(event))
            return;

        event.editComponents(ActionRow.of(
                Button.primary("null", "Retrying...").asDisabled()
        )).queue();
        tryAdd(event.getHook(), host, port);
    }

    private void onAddConfirm(ButtonInteractionEvent event, String host, int port, String fingerprint) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        UUID serverId = guildManager.addServer(host, port, fingerprint);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            System.err.println("Added server is null???");
            return;
        }

        String name;
        try (QueryApi queryApi = server.queryApi(Duration.ofSeconds(3))) {
            name = "**" + serverDisplayName(queryApi.pollServerState().name()) + "**";
        } catch (Exception e) {
            name = "a server";
        }
        event.editComponents(TextDisplay.of("Added " + name)).useComponentsV2().queue();
        guildManager.logAction(event.getUser(), "added " + name);

        // Check if the server is unclaimed
        CompletableFuture.runAsync(() -> {
            try {
                server.httpsApi(Duration.ofSeconds(3)).passwordlessLogin(PrivilegeLevel.INITIAL_ADMIN);

                event.getHook().editOriginalComponents(
                        TextDisplay.of("The server you just added is currently unclaimed"),
                        TextDisplay.of("Would you like to claim it now?"),
                        ActionRow.of(
                                Button.success("claim:" + serverId, "Yes"),
                                Button.danger("cancel", "No")
                        )
                ).useComponentsV2().queue();
            } catch (Exception e) {
                // Could not log in as initial admin so the server must be claimed already
                if (!(e instanceof PasswordlessLoginNotPossibleException)) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void onStartClaim(ButtonInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        Server server = getGuildManager(event).getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        event.replyModal(Modal.create("claim:" + serverId, "Claim Server").addComponents(
                Label.of("Server Name", TextInput.of("name", TextInputStyle.SHORT)),
                Label.of("Admin Password", TextInput.of("password1", TextInputStyle.SHORT)),
                Label.of("Repeat Admin Password", TextInput.of("password2", TextInputStyle.SHORT)),
                TextDisplay.of("The provided password is only used to claim the server and generate an API token. It will not be stored afterwards.")
        ).build()).queue();
    }

    private void onClaim(ModalInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        ModalMapping name = event.getValue("name");
        if (name == null) {
            event.reply("Please provide a name").setEphemeral(true).queue();
            return;
        }

        ModalMapping password1 = event.getValue("password1");
        if (password1 == null) {
            event.reply("Please provide a password").setEphemeral(true).queue();
            return;
        }

        ModalMapping password2 = event.getValue("password2");
        if (password2 == null) {
            event.reply("Please repeat the password").setEphemeral(true).queue();
            return;
        }

        if (!password1.getAsString().equals(password2.getAsString())) {
            event.reply("Provided password and repeated password were not the same").setEphemeral(true).queue();
            return;
        }

        HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));

        event.deferEdit().queue();
        CompletableFuture.runAsync(() -> {
            try {
                httpsApi.setToken(null);
                httpsApi.passwordlessLogin(PrivilegeLevel.INITIAL_ADMIN);
                httpsApi.claimServer(name.getAsString(), password1.getAsString());

                event.getHook().editOriginalComponents(TextDisplay.of("Successfully claimed the server"))
                        .useComponentsV2().queue();
                guildManager.logAction(event.getUser(), "claimed **" + serverDisplayName(name.getAsString()) + "**");
            } catch (ApiException e) {
                event.getHook().editOriginalComponents(TextDisplay.of("Unable to claim the server: " + e.getMessage()))
                        .useComponentsV2().queue();
                return;
            } catch (Exception e) {
                event.getHook().editOriginalComponents(TextDisplay.of("Failed to claim the server"))
                        .useComponentsV2().queue();
                e.printStackTrace();
                return;
            }

            String token = generateToken(httpsApi);
            verifyAndSetToken(event, guildManager, serverId, name.getAsString(), token);
        });
    }

    private static StringSelectMenu.Builder serverSelectMenu(String customId, Map<UUID, Server> servers, @Nullable Collection<UUID> selectedServerIds) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(customId);
        servers.forEach((id, server) -> builder.addOption(serverDisplayName(server.getName()), id.toString()));
        if (selectedServerIds != null)
            builder.setDefaultValues(selectedServerIds.stream().map(UUID::toString).toList());
        return builder;
    }

    private static StringSelectMenu.Builder serverSelectMenu(String customId, Map<UUID, Server> servers) {
        return serverSelectMenu(customId, servers, null);
    }

    private static ActionRow serverSelectForDetails(GuildManager guildManager) {
        Map<UUID, Server> servers = guildManager.getServers();
        if (servers.isEmpty()) {
            return ActionRow.of(
                    StringSelectMenu.create("null").addOption("null", "null")
                            .setPlaceholder("No servers added").setDisabled(true)
                            .build()
            );
        } else {
            return ActionRow.of(
                    serverSelectMenu("details", servers)
                            .setPlaceholder("Select a server to view details")
                            .build()
            );
        }
    }

    private void onListCommand(SlashCommandInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        int serverCount = guildManager.getServers().size();

        Guild guild = event.getGuild();
        String message = guild != null ? guild.getName() + " has " : "You have ";
        if (serverCount > 1) message += serverCount + " servers";
        else if (serverCount == 1) message += "1 server";
        else message += "no servers";

        event.replyComponents(
                TextDisplay.of(message),
                serverSelectForDetails(guildManager)
        ).useComponentsV2().setEphemeral(true).queue();
    }

    private void onListDetails(StringSelectInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        UUID serverId = UUID.fromString(event.getValues().get(0));
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        List<Button> buttons = new ArrayList<>(3);
        buttons.add(Button.primary("authenticate:" + serverId, server.hasToken() ? "Reauthenticate" : "Authenticate"));
        if (server.hasToken()) {
            buttons.add(Button.secondary("deauthenticate:" + serverId, "Deauthenticate"));
        }
        buttons.add(Button.danger("remove:" + serverId, "Remove"));

        event.editComponents(
                Container.of(
                        TextDisplay.of("## " + serverDisplayName(server.getName())),
                        TextDisplay.of("### Host\n||```" + server.getHost() + "```||"),
                        TextDisplay.of("### Port\n```" + server.getPort() + "```"),
                        TextDisplay.of("### Fingerprint\n```" + server.getFingerprint() + "```"),
                        ActionRow.of(buttons)
                ),
                serverSelectForDetails(guildManager)
        ).useComponentsV2().queue();
    }

    private void onRemove(ButtonInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.removeServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        event.editComponents(
                TextDisplay.of("Server removed"),
                serverSelectForDetails(guildManager)
        ).useComponentsV2().queue();
        guildManager.logAction(event.getUser(), "removed **" + serverDisplayName(server.getName()) + "**");
    }

    private void onAuthenticate(ButtonInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        Server server = getGuildManager(event).getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        event.replyModal(Modal.create("authentication:" + serverId, "Authenticate").addComponents(
                TextDisplay.of("Enter authentication for " + serverDisplayName(server.getName())),
                Label.of("Method", "The method of authentication you wish to use", StringSelectMenu.create("type")
                        .addOption("API Token", "token", "Directly enter an API Token generated using the `server.GenerateAPIToken` command")
                        .addOption("Admin Password", "password", "Generate an API Token automatically using the server's admin password")
                        .setDefaultValues("token")
                        .build()),
                Label.of("Authentication", "The token or password", TextInput.of("authentication", TextInputStyle.SHORT)),
                TextDisplay.of("When **Admin Password** is selected the provided password is only used to generate an API token. It will not be stored afterwards.")
        ).build()).queue();
    }

    private void onAuthentication(ModalInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        ModalMapping type = event.getValue("type");
        if (type == null) {
            event.reply("Please select an authentication type").setEphemeral(true).queue();
            return;
        }

        ModalMapping authentication = event.getValue("authentication");
        if (authentication == null) {
            event.reply("Please provide authentication").setEphemeral(true).queue();
            return;
        }

        event.deferEdit().queue();
        CompletableFuture.runAsync(() -> {
            String token = authentication.getAsString();

            if (type.getAsStringList().get(0).equals("password")) {
                // Convert password into token
                try {
                    HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
                    httpsApi.setToken(null);
                    httpsApi.passwordLogin(PrivilegeLevel.ADMIN, token);

                    token = generateToken(httpsApi);
                } catch (ApiException e) {
                    event.getHook().sendMessage("Unable to generate token: " + e.getMessage()).setEphemeral(true).queue();
                    return;
                } catch (Exception e) {
                    event.getHook().sendMessage("Failed to generate token").setEphemeral(true).queue();
                    e.printStackTrace();
                    return;
                }
            }

            verifyAndSetToken(event, guildManager, serverId, null, token);
        });
    }

    private static String generateToken(HttpsApi httpsApi) {
        String output = httpsApi.runCommand("server.GenerateAPIToken").outputLines()[0];
        return output.substring(output.indexOf(':') + 1).trim();
    }

    private static void verifyAndSetToken(ModalInteractionEvent event, GuildManager guildManager, UUID serverId, @Nullable String serverName, String token) {
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.getHook().sendMessage("Unknown server").setEphemeral(true).queue();
            return;
        }

        // Validate token
        try {
            PrivilegeLevel privilegeLevel = PrivilegeLevel.ofToken(token);
            if (privilegeLevel != PrivilegeLevel.API_TOKEN) {
                event.getHook().sendMessage("Incorrect token type").setEphemeral(true).queue();
                return;
            }
        } catch (IllegalArgumentException e) {
            event.getHook().sendMessage("Incorrect token format").setEphemeral(true).queue();
            return;
        }

        // Verify token
        try {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            httpsApi.setToken(token);
            httpsApi.verifyAuthenticationToken();
        } catch (InvalidTokenException e) {
            event.getHook().sendMessage("Token is invalid").setEphemeral(true).queue();
            return;
        } catch (Exception e) {
            event.getHook().sendMessage("Failed to verify token").setEphemeral(true).queue();
            e.printStackTrace();
            return;
        }

        // Save token
        guildManager.setServerToken(serverId, token);

        serverName = serverName != null ? serverName : server.getName();
        event.getHook().sendMessage("Authentication successful").setEphemeral(true).queue();
        guildManager.logAction(event.getUser(), "added an authentication token for **" + serverDisplayName(serverName) + "**");
    }

    private void onDeauthenticate(ButtonInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        guildManager.setServerToken(serverId, null);

        event.reply("Authentication removed").setEphemeral(true).queue();
        guildManager.logAction(event.getUser(), "removed the authentication token for **" + serverDisplayName(server.getName()) + "**");
    }

    private void onRefresh(ButtonInteractionEvent event, UUID serverId) {
        // no permission required

        GuildManager guildManager = getGuildManager(event);
        guildManager.refreshServer(serverId);

        event.deferEdit().queue();
    }

    private void onReloadCommand(SlashCommandInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        Map<UUID, Server> servers = getGuildManager(event).getServers();
        if (servers.isEmpty()) {
            event.reply("There are no servers that can be reloaded").setEphemeral(true).queue();
            return;
        }

        event.replyModal(Modal.create("reload", "Reload Session").addComponents(
                Label.of("Servers", "The server(s) that should be reloaded",
                        serverSelectMenu("servers", servers)
                                .setMaxValues(10)
                                .setPlaceholder("Select one or more servers")
                                .build())
        ).build()).queue();
    }

    private void onReloadFromDashboard(ButtonInteractionEvent event, UUID serverId) {
        if (missingAdminRole(event))
            return;

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        reload(event, guildManager, Collections.singletonList(server));
    }

    private void onReload(ModalInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        ModalMapping serverIds = event.getValue("servers");
        if (serverIds == null) {
            event.reply("Please select servers").setEphemeral(true).queue();
            return;
        }

        GuildManager guildManager = getGuildManager(event);

        List<Server> servers = new ArrayList<>(serverIds.getAsStringList().size());
        for (String serverId : serverIds.getAsStringList()) {
            Server server = guildManager.getServer(UUID.fromString(serverId));
            if (server == null) {
                event.reply("Unknown server selected, please try again").setEphemeral(true).queue();
                return;
            }
            servers.add(server);
        }

        reload(event, guildManager, servers);
    }

    private void reload(IReplyCallback interaction, GuildManager guildManager, List<Server> servers) {
        List<String> messageLines = Collections.synchronizedList(servers.stream()
                .map(server -> "Reloading **" + serverDisplayName(server.getName()) + "**...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        interaction.reply(String.join("\n", messageLines))
                .setEphemeral(guildManager.isDashboard(interaction.getChannel()))
                .queue();

        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            Server server = servers.get(index);

            CompletableFuture.runAsync(() -> {
                try {
                    HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));

                    httpsApi.save("reload_continue");
                    httpsApi.loadSave("reload_continue", false);

                    messageLines.set(index, "Successfully reloaded **" + serverDisplayName(server.getName()) + "**");
                    guildManager.logAction(interaction.getUser(), "reloaded **" + serverDisplayName(server.getName()) + "**");
                } catch (ApiException e) {
                    messageLines.set(index, "Unable to reload **" + serverDisplayName(server.getName()) + "**: " + e.getMessage());
                } catch (Exception e) {
                    messageLines.set(index, "Failed to reload **" + serverDisplayName(server.getName()) + "**");
                    System.err.println(e);
                }
                synchronized (messageLines) {
                    interaction.getHook().editOriginal(String.join("\n", messageLines)).queue();
                }
            });
        }
    }

    private void onSaveCommand(SlashCommandInteractionEvent event) {
        onSaveHelper(event, event, null);
    }

    private void onSaveFromDashboard(ButtonInteractionEvent event, UUID serverId) {
        onSaveHelper(event, event, Collections.singleton(serverId));
    }

    // There's no common interface for IReplyCallback and IModalCallback
    private void onSaveHelper(IReplyCallback replyCallback, IModalCallback modalCallback, @Nullable Collection<UUID> selectedServerIds) {
        if (missingAdminRole(replyCallback))
            return;

        Map<UUID, Server> servers = getGuildManager(replyCallback).getServers();
        if (servers.isEmpty()) {
            replyCallback.reply("There are no servers that can be saved").setEphemeral(true).queue();
            return;
        }

        modalCallback.replyModal(Modal.create("save", "Create Save").addComponents(
                Label.of("Server", serverSelectMenu("server", servers, selectedServerIds)
                        .setPlaceholder("Select a server")
                        .build()),
                //TODO: Limit length / check validity?
                Label.of("Save Name", "Optional", TextInput.create("name", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setPlaceholder("Session Name_DDMMYY-HHMMSS")
                        .build())
        ).build()).queue();
    }

    private void onSave(ModalInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        ModalMapping serverIds = event.getValue("server");
        if (serverIds == null) {
            event.reply("Please select a server").setEphemeral(true).queue();
            return;
        }

        UUID serverId = UUID.fromString(serverIds.getAsStringList().get(0));

        GuildManager guildManager = getGuildManager(event);
        Server server = guildManager.getServer(serverId);
        if (server == null) {
            event.reply("Unknown server").setEphemeral(true).queue();
            return;
        }

        ModalMapping name = event.getValue("name");
        String saveName = name != null ? name.getAsString() : null;

        HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));

        event.deferReply(guildManager.isDashboard(event.getChannel())).queue();
        CompletableFuture.runAsync(() -> {
            boolean saved = false;
            try {
                String actualSaveName = saveName;
                if (actualSaveName == null || actualSaveName.isBlank()) {
                    String sessionName = httpsApi.queryServerState().activeSessionName();
                    actualSaveName = defaultSaveName(sessionName, LocalDateTime.now(Clock.systemUTC()));
                }

                httpsApi.save(actualSaveName);
                saved = true;
                event.getHook().editOriginal("Downloading `" + actualSaveName + SaveFileReader.EXTENSION + "` from **" + serverDisplayName(server.getName()) + "**...").queue();

                String sessionName = null;
                Instant timestamp = Instant.now();

                Session session = httpsApi.enumerateSessions().current();
                if (session != null) {
                    sessionName = session.sessionName();
                    for (SaveHeader header : session.saveHeaders()) {
                        if (header.saveName().equals(actualSaveName)) {
                            timestamp = header.saveTimestamp();
                            break;
                        }
                    }
                }

                event.getHook().editOriginal((sessionName != null ? sessionName + " on " : "") + "**" + serverDisplayName(server.getName()) + "** at " + TimeFormat.DEFAULT.atInstant(timestamp))
                        .setFiles(FileUpload.fromData(httpsApi.downloadSave(actualSaveName), actualSaveName + SaveFileReader.EXTENSION))
                        .queue(message -> guildManager.logAction(event.getUser(), "downloaded " + message.getAttachments().get(0).getUrl() + " from **" + serverDisplayName(server.getName()) + "**"));
            } catch (ApiException e) {
                event.getHook().editOriginal((saved ? "Unable to download save from " : "Unable to save ") + "**" + serverDisplayName(server.getName()) + "**: " + e.getMessage()).queue();
            } catch (Exception e) {
                event.getHook().editOriginal((saved ? "Failed to download save from " : "Failed to save ") + "**" + serverDisplayName(server.getName()) + "**").queue();
                System.err.println(e);
            }
        });
    }

    private void onUploadCommand(SlashCommandInteractionEvent event) {
        onUploadHelper(event, event, null, AttachmentUpload::of);
    }

    private void onUploadFromDashboard(ButtonInteractionEvent event, UUID serverId) {
        onUploadHelper(event, event, Collections.singleton(serverId), AttachmentUpload::of);
    }

    private void onUploadFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = event.getTarget().getAttachments();
        if (attachments.isEmpty()) {
            event.reply("That message does not have any attachments").setEphemeral(true).queue();
            return;
        }

        onUploadHelper(event, event, null, customId -> {
            StringSelectMenu.Builder builder = StringSelectMenu.create(customId);
            boolean first = true;
            for (Message.Attachment attachment : attachments) {
                String value = event.getTarget().getId() + ":" + attachment.getId();
                builder.addOption(attachment.getFileName(), value);
                if (first) {
                    builder.setDefaultValues(value);
                    first = false;
                }
            }
            return builder.build();
        });
    }

    // There's no common interface for IReplyCallback and IModalCallback
    private void onUploadHelper(IReplyCallback replyCallback, IModalCallback modalCallback, @Nullable Collection<UUID> selectedServerIds, Function<String, LabelChildComponent> saveFileComponentCreator) {
        if (missingAdminRole(replyCallback))
            return;

        Map<UUID, Server> servers = getGuildManager(replyCallback).getServers();
        if (servers.isEmpty()) {
            replyCallback.reply("There are no servers that can be uploaded to").setEphemeral(true).queue();
            return;
        }

        modalCallback.replyModal(Modal.create("upload", "Upload Save").addComponents(
                Label.of("Servers", "The server(s) that the save should be uploaded to",
                        serverSelectMenu("servers", servers, selectedServerIds)
                                .setMaxValues(10)
                                .setPlaceholder("Select one or more servers")
                                .build()),
                Label.of("Save File", saveFileComponentCreator.apply("save")),
                Label.of("Action", "The action to perform with the uploaded save", StringSelectMenu.create("action")
                        .addOption("Nothing", "nothing", "Just upload the save")
                        .addOption("Load", "load", "Load the save")
                        .addOption("Load with Advanced Game Settings", "load-creative", "Load the save with Advanced Game Settings enabled")
                        .setDefaultValues("nothing")
                        .build())
        ).build()).queue();
    }

    private void onUpload(ModalInteractionEvent event) {
        if (missingAdminRole(event))
            return;

        ModalMapping serverIds = event.getValue("servers");
        if (serverIds == null) {
            event.reply("Please select servers").setEphemeral(true).queue();
            return;
        }

        Message.Attachment saveFileAttachment = null;

        ModalMapping save = event.getValue("save");
        if (save != null) {
            switch (save.getType()) {
                case FILE_UPLOAD -> {
                    saveFileAttachment = save.getAsAttachmentList().get(0);
                }
                case STRING_SELECT -> {
                    String[] ids = save.getAsStringList().get(0).split(":");
                    Message message = event.getChannel().retrieveMessageById(ids[0]).complete();
                    for (Message.Attachment attachment : message.getAttachments()) {
                        if (attachment.getId().equals(ids[1])) {
                            saveFileAttachment = attachment;
                            break;
                        }
                    }
                }
            }
        }

        if (saveFileAttachment == null) {
            event.reply("Please provide a save file").setEphemeral(true).queue();
            return;
        }

        ModalMapping action = event.getValue("action");
        if (action == null) {
            event.reply("Please select an action").setEphemeral(true).queue();
            return;
        }

        GuildManager guildManager = getGuildManager(event);

        List<Server> servers = new ArrayList<>(serverIds.getAsStringList().size());
        for (String serverId : serverIds.getAsStringList()) {
            Server server = guildManager.getServer(UUID.fromString(serverId));
            if (server == null) {
                event.reply("Unknown server selected, please try again").setEphemeral(true).queue();
                return;
            }
            servers.add(server);
        }

        String actionString = action.getAsStringList().get(0);
        boolean load = actionString.startsWith("load");
        boolean loadCreative = actionString.equals("load-creative");

        String file = saveFileAttachment.getUrl();
        String name = SaveFileReader.saveNameOf(saveFileAttachment.getFileName());

        event.deferReply(guildManager.isDashboard(event.getChannel())).queue();
        saveFileAttachment.getProxy().download().whenComplete((stream, error) -> {
            if (stream == null) {
                event.getHook().editOriginal("Failed to retrieve file").queue();
                if (error != null) error.printStackTrace();
                return;
            }

            List<String> messageLines = Collections.synchronizedList(servers.stream()
                    .map(server -> "Uploading " + file + " to **" + serverDisplayName(server.getName()) + "**...")
                    .collect(Collectors.toList())
            );
            // No need to synchronize here, the list won't be changing yet
            event.getHook().editOriginal(String.join("\n", messageLines)).queue();

            PipedInputStream[] uploadStreams = new PipedInputStream[servers.size()];
            PipedOutputStream[] outputStreams = new PipedOutputStream[uploadStreams.length];
            try {
                for (int i = 0; i < uploadStreams.length; i++) {
                    uploadStreams[i] = new PipedInputStream();
                    outputStreams[i] = new PipedOutputStream(uploadStreams[i]);
                }

                new Thread(() -> {
                    try (stream) {
                        byte[] buffer = new byte[1024];

                        int read;
                        do {
                            read = stream.read(buffer);
                            if (read > 0) {
                                for (PipedOutputStream outputStream : outputStreams) {
                                    outputStream.write(buffer, 0, read);
                                }
                            }
                        } while (read >= 0);
                    } catch (IOException e) {
                        event.getHook().editOriginal("Failed to transfer data").queue();
                        e.printStackTrace();
                    } finally {
                        for (PipedOutputStream outputStream : outputStreams) {
                            try {
                                outputStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }).start();

                for (int i = 0; i < servers.size(); i++) {
                    final int index = i;
                    Server server = servers.get(index);

                    CompletableFuture.runAsync(() -> {
                        try (InputStream uploadStream = uploadStreams[index]) {
                            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));

                            httpsApi.uploadSave(uploadStream, name, load, loadCreative);

                            messageLines.set(index, "Successfully uploaded " + file + " to **" + serverDisplayName(server.getName()) + "**");
                            guildManager.logAction(event.getUser(), "uploaded " + file + " to **" + serverDisplayName(server.getName()) + "**");
                        } catch (ApiException e) {
                            messageLines.set(index, "Unable to upload " + file + " to **" + serverDisplayName(server.getName()) + "**: " + e.getMessage());
                        } catch (Exception e) {
                            messageLines.set(index, "Failed to upload " + file + " to **" + serverDisplayName(server.getName()) + "**");
                            System.err.println(e);
                        }
                        synchronized (messageLines) {
                            event.getHook().editOriginal(String.join("\n", messageLines)).queue();
                        }
                    });
                }
            } catch (Exception e) {
                event.getHook().editOriginal("Failed to start data transfer").queue();
                e.printStackTrace();
            }
        });
    }

}
