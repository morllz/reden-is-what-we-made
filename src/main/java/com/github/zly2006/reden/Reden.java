package com.github.zly2006.reden;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.github.zly2006.reden.access.BlockEntityInterface;
import com.github.zly2006.reden.access.PlayerData;
import com.github.zly2006.reden.behalf.BehalfCommandKt;
import com.github.zly2006.reden.carpet.RedenCarpetSettings;
import com.github.zly2006.reden.indexing.IndexingKt;
import com.github.zly2006.reden.network.ChannelsKt;
import com.github.zly2006.reden.rvc.RvcCommandKt;
import com.github.zly2006.reden.transformers.ThisIsReden;
import com.github.zly2006.reden.utils.ResourceLoader;
import com.github.zly2006.reden.utils.TaskScheduler;
import com.github.zly2006.reden.utils.UtilsKt;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;

public class Reden implements ModInitializer, CarpetExtension {
    public static final String MOD_ID = "reden";
    public static final String MOD_NAME = "Reden";
    public static final String CONFIG_FILE = "reden/config.json";
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private static final ModMetadata MOD_METADATA = FabricLoader.getInstance().getModContainer(MOD_ID).get().getMetadata();
    public static final Version MOD_VERSION = MOD_METADATA.getVersion();
    public static final Date BUILD_TIME = new Date(Long.parseLong(MOD_METADATA.getCustomValue("reden").getAsObject().get("build_timestamp").getAsString()));
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int REDEN_HIGHEST_MIXIN_PRIORITY = 10;
    public static final Identifier LOGO = identifier("reden_16.png");

    @Override
    public String version() {
        return "reden";
    }

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(RedenCarpetSettings.Options.class);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return ResourceLoader.loadLang(lang);
    }

    public static boolean isRedenDev() {
        return Boolean.parseBoolean(System.getProperty("reden.debug", String.valueOf(FabricLoader.getInstance().isDevelopmentEnvironment())));
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(UtilsKt::setServer);
        ChannelsKt.registerChannels();
        CarpetServer.manageExtension(this);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            BehalfCommandKt.registerBehalf(dispatcher);
            // Debug command
            if (isRedenDev()) {
                dispatcher.register(CommandManager.literal("reden-debug")
                        .then(CommandManager.literal("version").executes(context -> {
                            context.getSource().sendMessage(Text.of("Reden v" + MOD_VERSION.getFriendlyString()));
                            context.getSource().sendMessage(Text.of("Build time: " + BUILD_TIME));
                            return 1;
                        }))
                        .then(CommandManager.literal("top-undo").executes(context -> {
                            PlayerData.Companion.data(context.getSource().getPlayer()).topUndo();
                            return 1;
                        }))
                        .then(CommandManager.literal("top-redo").executes(context -> {
                            PlayerData.Companion.data(context.getSource().getPlayer()).topRedo();
                            return 1;
                        }))
                        .then(CommandManager.literal("schematic")
                                // Note: single-player mode only
                                // Note:
                                //   rendering is only available in placement part (by litematica)
                                //   and the move structure task (reden hack)
                                .then(CommandManager.literal("setblock")
                                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                                .then(CommandManager.argument("block", BlockStateArgumentType.blockState(access))
                                                        .executes(context -> {
                                                            MinecraftClient client = MinecraftClient.getInstance();
                                                            assert client.player != null;
                                                            var pos = BlockPosArgumentType.getBlockPos(context, "pos");
                                                            SchematicWorldHandler.getSchematicWorld().setBlockState(pos, BlockStateArgumentType.getBlockState(context, "block").getBlockState(), 3);
                                                            SchematicWorldHandler.getSchematicWorld().scheduleChunkRenders(pos.getX() >> 4, pos.getZ() >> 4);
                                                            LitematicaRenderer.getInstance().getWorldRenderer().markNeedsUpdate();
                                                            client.player.sendMessage(SchematicWorldHandler.getSchematicWorld().getBlockState(pos).getBlock().getName());
                                                            return 1;
                                                        })))))
                        .then(CommandManager.literal("last-saved-nbt")
                                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                        .executes(context -> {
                                            BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
                                            BlockEntity blockEntity = context.getSource().getWorld().getBlockEntity(pos);
                                            if (blockEntity == null) {
                                                context.getSource().sendError(Text.of("No block entity at " + pos.toShortString()));
                                                return 0;
                                            }
                                            NbtCompound lastSavedNbt = ((BlockEntityInterface) blockEntity).getLastSavedNbt$reden();
                                            if (lastSavedNbt == null) {
                                                context.getSource().sendError(Text.of("No last saved NBT at " + pos.toShortString()));
                                                return 0;
                                            }
                                            context.getSource().sendMessage(Text.of(lastSavedNbt.toString()));
                                            return 1;
                                        })))
                        .then(CommandManager.literal("shadow-item")
                                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(access))
                                        .executes(context -> {
                                            ItemStackArgument itemStackArgument = ItemStackArgumentType.getItemStackArgument(context, "item");
                                            ItemStack stack = itemStackArgument.createStack(1, true);
                                            PlayerInventory inventory = context.getSource().getPlayer().getInventory();
                                            for (int i = 0; i < 2; i++) {
                                                int emptySlot = inventory.getEmptySlot();
                                                inventory.setStack(emptySlot, stack);
                                            }
                                            context.getSource().getPlayer().currentScreenHandler.syncState();
                                            return 1;
                                        })))
                        .then(CommandManager.literal("totem-of-undying").executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player != null) {
                                player.networkHandler.sendPacket(new EntityStatusS2CPacket(player, EntityStatuses.USE_TOTEM_OF_UNDYING));
                            }
                            return 1;
                        }))
                        .then(CommandManager.literal("delay-test")
                                .executes(context -> {
                                    try {
                                        Thread.sleep(35 * 1000);
                                    } catch (InterruptedException ignored) {
                                    }
                                    context.getSource().sendMessage(Text.of("35 seconds passed"));
                                    return 1;
                                })));
            }
            RvcCommandKt.registerRvc(dispatcher);
            if (!(dispatcher instanceof ThisIsReden)) {
                throw new RuntimeException("This is not Reden!");
            } else {
                LOGGER.info("This is Reden!");
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(TaskScheduler.INSTANCE);

        new Thread(() -> {
            LOGGER.info("Loading indexes...");
            try {
                IndexingKt.getEntityId();
                IndexingKt.getBlockId();
                IndexingKt.getPropertyId();
            } catch (Exception e) {
                Reden.LOGGER.error("Loading indexes.", e);
            }
        }, "Reden Indexer").start();
    }

    @Contract("_ -> new")
    public static @NotNull Identifier identifier(@NotNull String id) {
        return new Identifier(MOD_ID, id);
    }
}
