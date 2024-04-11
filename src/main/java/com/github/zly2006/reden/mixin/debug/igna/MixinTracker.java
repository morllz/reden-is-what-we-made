package com.github.zly2006.reden.mixin.debug.igna;

import com.github.zly2006.reden.carpet.RedenCarpetSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(EntityTrackerEntry.class)
public class MixinTracker {
    @Shadow @Final private Entity entity;

    @Shadow private int trackingTick;

    @Shadow @Final private ServerWorld world;

    @Inject(
            method = "tick",
            at = @At("HEAD")
    )
    private void onSendPacket(CallbackInfo ci) {
        if ((trackingTick % 2) == 0 && entity instanceof ItemEntity item) {
            if (RedenCarpetSettings.Options.fixInvisibleShadowingItems) {
                ItemStack stack = item.getStack();
                PlayerManager playerManager = world.getServer().getPlayerManager();

                playerManager.sendToDimension(new EntityTrackerUpdateS2CPacket(
                        entity.getId(),
                        List.of(new DataTracker.SerializedEntry<>(
                                ItemEntity.STACK.id(),
                                ItemEntity.STACK.dataType(),
                                ItemEntity.STACK.dataType().copy(stack)
                        ))
                ), world.getRegistryKey());
            }
        }
    }
}
