package com.github.zly2006.reden.mixin.itemShadow;

import com.github.zly2006.reden.access.ItemStackAccess;
import com.github.zly2006.reden.carpet.RedenCarpetSettings;
import com.github.zly2006.reden.itemShadow.ItemStackOwner;
import com.github.zly2006.reden.utils.UtilsKt;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.ChestBoatEntity;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.village.MerchantInventory;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.text.MessageFormat;
import java.util.List;

@Mixin({
        PlayerInventory.class,

        ChestBoatEntity.class,
        StorageMinecartEntity.class,
        MerchantInventory.class,

        AbstractFurnaceBlockEntity.class,
        LockableContainerBlockEntity.class,
        ChiseledBookshelfBlockEntity.class,
        //todo
        //  LecternBlockEntity.class,
        //  JukeboxBlockEntity.class,
        LootableContainerBlockEntity.class,
        CraftingInventory.class,
        CraftingResultInventory.class,
        DoubleInventory.class,
        SimpleInventory.class,
})
public class MixinAllInventories implements ItemStackOwner {
    // todo:
    //   SingleStackInventory
    @Inject(
            method = "setStack(ILnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"),
            require = 0 // allow any
    )
    private void beforeSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        if (!Thread.currentThread().getName().equals("Server thread") || stack.isEmpty()) {
            return;
        }
        if (RedenCarpetSettings.Debugger.debuggerItemShadow()) {
            List<ItemStackOwner> stackOwners = ((ItemStackAccess) stack).getStackOwners$reden();
            // I am an Inventory, of course!
            if (!stackOwners.contains(this)) {
                stackOwners.add(this);
            }
        }
    }

    @Inject(
            method = "setStack(ILnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"),
            require = 0 // allow any
    )
    private void afterSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        if (!Thread.currentThread().getName().equals("Server thread") || stack.isEmpty()) {
            return;
        }
        if (RedenCarpetSettings.Debugger.debuggerItemShadow()) {
            ((ItemStackAccess) stack).checkStackOwners();
            List<ItemStackOwner> stackOwners = ((ItemStackAccess) stack).getStackOwners$reden();
            if (stackOwners.size() > 1) {
                MinecraftServer server = UtilsKt.server;
                if (server != null) {
                    server.getPlayerManager().broadcast(
                            Text.of(
                                    MessageFormat.format("ItemStack {0}({1}) has {2} owners: {3}",
                                            stack,
                                            Integer.toHexString(stack.hashCode()),
                                            stackOwners.size(),
                                            stackOwners.stream().map(ItemStackOwner::toString)
                                                    .reduce((a, b) -> a + ", " + b).orElse("null"))
                            ),
                            false
                    );
                }
            }
        }
    }

    @NotNull
    @Override
    public Type getType$reden() {
        return Type.Inventory;
    }
}
