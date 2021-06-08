package dev.gegy.roles;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import dev.gegy.roles.api.override.RoleOverrideType;
import dev.gegy.roles.command.RoleCommand;
import dev.gegy.roles.config.PlayerRolesConfig;
import dev.gegy.roles.override.ChatFormatOverride;
import dev.gegy.roles.override.NameStyleOverride;
import dev.gegy.roles.override.command.CommandPermissionOverride;
import dev.gegy.roles.override.permission.PermissionKeyOverride;
import dev.gegy.roles.override.command.CommandPermissionEvaluator;
import dev.gegy.roles.override.command.CommandRequirementHooks;
import dev.gegy.roles.override.command.CommandTestContext;
import dev.gegy.roles.override.command.MatchableCommand;
import dev.gegy.roles.api.PermissionResult;
import dev.gegy.roles.store.PlayerRoleManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public final class PlayerRoles implements ModInitializer {
    public static final String ID = "player_roles";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    public static final RoleOverrideType<CommandPermissionOverride> COMMANDS = RoleOverrideType.register("commands", CommandPermissionOverride.CODEC)
            .withChangeListener(owner -> {
                if (owner instanceof ServerPlayerEntity) {
                    ServerPlayerEntity player = (ServerPlayerEntity) owner;
                    MinecraftServer server = player.getServer();
                    if (server != null) {
                        server.getCommandManager().sendCommandTree(player);
                    }
                }
            });

    public static final RoleOverrideType<ChatFormatOverride> CHAT_STYLE = RoleOverrideType.register("chat_format", ChatFormatOverride.CODEC);
    public static final RoleOverrideType<NameStyleOverride> NAME_FORMAT = RoleOverrideType.register("name_style", NameStyleOverride.CODEC);
    public static final RoleOverrideType<Boolean> COMMAND_FEEDBACK = RoleOverrideType.register("command_feedback", Codec.BOOL);
    public static final RoleOverrideType<Boolean> MUTE = RoleOverrideType.register("mute", Codec.BOOL);
    public static final RoleOverrideType<Integer> PERMISSION_LEVEL = RoleOverrideType.register("permission_level", Codec.intRange(0, 4));

    private static boolean registered;

    @Override
    public void onInitialize() {
        List<String> errors = PlayerRolesConfig.setup();
        if (!errors.isEmpty()) {
            LOGGER.warn("Failed to load player-roles config! ({} errors)", errors.size());
            for (String error : errors) {
                LOGGER.warn(" - {}", error);
            }
        }

        PlayerRoleManager.setup();

        if (FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
            PermissionKeyOverride.register();
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            RoleCommand.register(dispatcher);
        });

        // cursed solution to make sure we run our handler after everything else
        // worldedit registers commands in the server started listener, so we need to override that
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            if (registered) {
                return;
            }
            registered = true;

            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                this.hookCommands(server.getCommandManager().getDispatcher());
            });
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            this.hookCommands(server.getCommandManager().getDispatcher());
        });
    }

    private void hookCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        try {
            CommandRequirementHooks<ServerCommandSource> hooks = CommandRequirementHooks.tryCreate((nodes, existing) -> {
                MatchableCommand command = MatchableCommand.compile(nodes);

                return source -> {
                    PermissionResult result = CommandPermissionEvaluator.canUseCommand(source, command);
                    if (result == PermissionResult.ALLOW) return true;
                    if (result == PermissionResult.DENY) return false;
                    if (result == PermissionResult.HIDDEN) return !CommandTestContext.isSuggesting();

                    return existing.test(source);
                };
            });

            hooks.hookAll(dispatcher);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to hook command requirements", e);
        }
    }

    public static void sendMuteFeedback(ServerPlayerEntity player) {
        player.sendMessage(new LiteralText("You are muted!").formatted(Formatting.RED), true);
    }
}