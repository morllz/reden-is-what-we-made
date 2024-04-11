package com.github.zly2006.reden.mixin.undo;

import com.github.zly2006.reden.access.PlayerData;
import com.github.zly2006.reden.access.UndoableAccess;
import com.github.zly2006.reden.mixinhelper.UpdateMonitorHelper;
import com.github.zly2006.reden.utils.DebugKt;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public class MixinExplosion implements UndoableAccess {
    @Shadow @Final private World world;
    @Unique long undoId;

    @Inject(
            method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/explosion/Explosion$DestructionType;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/registry/entry/RegistryEntry;)V",
            at = @At("RETURN")
    )
    private void onInit(CallbackInfo ci) {
        if (world.isClient) return;
        PlayerData.UndoRecord recording = UpdateMonitorHelper.INSTANCE.getRecording();
        if (recording != null) {
            DebugKt.debugLogger.invoke("Explosion happened, adding it into record "+ recording.getId());
            undoId = recording.getId();
        }
    }

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void beforeAffectWorld(boolean particles, CallbackInfo ci) {
        if (world.isClient) return;
        UpdateMonitorHelper.pushRecord(undoId, () -> "explosion.blocks");
    }

    @Inject(method = "affectWorld", at = @At("RETURN"))
    private void afterAffectWorld(boolean particles, CallbackInfo ci) {
        if (world.isClient) return;
        UpdateMonitorHelper.popRecord(() -> "explosion.blocks");
    }

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("HEAD"))
    private void beforeDamageEntities(CallbackInfo ci) {
        if (world.isClient) return;
        UpdateMonitorHelper.pushRecord(undoId, () -> "explosion.entities");
    }

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("RETURN"))
    private void afterDamageEntities(CallbackInfo ci) {
        if (world.isClient) return;
        UpdateMonitorHelper.popRecord(() -> "explosion.entities");
    }

    @Override
    public void setUndoId$reden(long undoId) {
        this.undoId = undoId;
    }

    @Override
    public long getUndoId$reden() {
        return undoId;
    }
}
