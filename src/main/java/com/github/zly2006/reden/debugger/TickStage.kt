package com.github.zly2006.reden.debugger

import com.github.zly2006.reden.utils.codec.BlockPosSerializer
import com.github.zly2006.reden.utils.codec.TextSerializer
import com.github.zly2006.reden.utils.readBlockState
import com.github.zly2006.reden.utils.writeBlockState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import okhttp3.internal.toHexString

/**
 * See [wiki page](https://wiki.redenmc.com/R-Debugger/Tick_Stage)
 *
 * Represents a set of vanilla, e.g. tick worlds, tick entities.
 * It may have children stages, which means these stages are callee
 * and the parent stage is caller in jvm stack.
 */
@Serializable
abstract class TickStage(
    val name: String,
    val parent: TickStage?,
    @Serializable(with = TextSerializer::class)
    open val displayName: MutableText,
    @Serializable(with = TextSerializer::class)
    open val description: MutableText = Text.empty()
) {
    constructor(name: String, parent: TickStage?): this(
        name, parent,
        Text.translatable("reden.debugger.tick_stage.$name"),
        Text.translatable("reden.debugger.tick_stage.$name.desc")
    )
    companion object {
        private var id = 0
    }
    var id = TickStage.id++
    val children = mutableListOf<TickStage>()
    enum class DisplayLevel {
        ALWAYS_HIDE, HIDE, ALWAYS_FOLD, FULL
    }
    var displayLevel: DisplayLevel = DisplayLevel.FULL
    enum class StageStatus {
        /**
         * Initialized, but not started
         */
        Initialized,

        /**
         * Pre-tick stages, waiting for the tick to start
         *
         * Can be sent to clients
         */
        Pending,

        /**
         * Ticked stages, all its data including children should not change anymore
         *
         * Can be sent to clients
         */
        Ticked,

        /**
         * Dying stages
         */
        Finished
    }
    var status = StageStatus.Initialized

    @Serializable
    data class BlockChange(
        @Transient
        val before: BlockState = Blocks.AIR.defaultState,
        @Transient
        val after: BlockState = Blocks.AIR.defaultState
    )

    val changedBlocks = mutableMapOf<
            @Serializable(BlockPosSerializer::class)
            BlockPos,
            BlockChange
            >()
    var hasScheduledTicks = false
    var hasBlockEvents = false

    open fun writeByteBuf(buf: PacketByteBuf) {
        buf.writeEnumConstant(displayLevel)
        buf.writeEnumConstant(status)
        buf.writeMap(changedBlocks, PacketByteBuf::writeBlockPos) { _, it ->
            buf.writeBlockState(it.before)
            buf.writeBlockState(it.after)
        }
        buf.writeBoolean(hasScheduledTicks)
        buf.writeBoolean(hasBlockEvents)
    }

    open fun readByteBuf(buf: PacketByteBuf) {
        displayLevel = buf.readEnumConstant(DisplayLevel::class.java)
        status = buf.readEnumConstant(StageStatus::class.java)
        changedBlocks.clear()
        buf.readMap({ changedBlocks }, PacketByteBuf::readBlockPos) {
            BlockChange(buf.readBlockState(), buf.readBlockState())
        }
        hasScheduledTicks = buf.readBoolean()
        hasBlockEvents = buf.readBoolean()
    }

    open fun reset(): Unit = throw UnsupportedOperationException("Reset not supported for tick stage $name")

    open fun postTick() {
    }

    open fun preTick() {
    }

    open fun focused(mc: MinecraftClient) {
        assert(mc.isOnThread) { "Focused on wrong thread" }
    }

    open fun unfocused(mc: MinecraftClient) {
        assert(mc.isOnThread) { "Focused on wrong thread" }
    }

    override fun toString(): String {
        return "$name@${hashCode().toHexString()}"
    }
}
