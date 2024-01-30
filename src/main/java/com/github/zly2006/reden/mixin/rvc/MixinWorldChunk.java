package com.github.zly2006.reden.mixin.rvc;

import com.github.zly2006.reden.access.ClientData;
import com.github.zly2006.reden.rvc.tracking.RvcRepository;
import com.github.zly2006.reden.rvc.tracking.WorldInfo;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

import static com.github.zly2006.reden.access.ClientData.getData;

@Mixin(WorldChunk.class)
public abstract class MixinWorldChunk {
    @Shadow @Final private World world;

    @Shadow public abstract World getWorld();

    @Inject(
            method = "setBlockState",
            at = @At("TAIL")
    )
    private void onBlockChanged(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        //todo
        if (world.isClient) {
            ClientData data = getData(MinecraftClient.getInstance());
            WorldInfo worldInfo = WorldInfo.Companion.getWorldInfo(MinecraftClient.getInstance());
            for (RvcRepository repo : data.getRvcStructures().values()) {
                if (repo.getPlacementInfo() == null) continue;
                if (!worldInfo.equals(repo.getPlacementInfo().getWorldInfo())) continue;
                var structure = repo.head();
                if (structure.isInArea(structure.getRelativeCoordinate(pos))) {
                    if (state.isAir()) {
                        structure.onBlockRemoved(pos);
                    } else {
                        structure.onBlockAdded(pos);
                    }
                } else if (Arrays.stream(Direction.values()).anyMatch(dir -> structure.isInArea(structure.getRelativeCoordinate(pos.offset(dir))))) {
                    structure.onBlockAdded(pos);
                }
            }
        }
    }
}
