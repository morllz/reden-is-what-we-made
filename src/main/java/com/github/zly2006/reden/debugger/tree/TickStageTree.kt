package com.github.zly2006.reden.debugger.tree

import com.github.zly2006.reden.access.ServerData.Companion.data
import com.github.zly2006.reden.access.TickStageTreeOwnerAccess
import com.github.zly2006.reden.debugger.TickStage
import com.github.zly2006.reden.debugger.TickStageWithWorld
import com.github.zly2006.reden.debugger.stages.TickStageWorldProvider
import com.github.zly2006.reden.debugger.tickPackets
import com.github.zly2006.reden.utils.server
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.block.BlockState
import net.minecraft.server.world.BlockEvent
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.tick.OrderedTick
import org.slf4j.LoggerFactory

@Serializable
class TickStageTree(
    val activeStages: MutableList<TickStage> = mutableListOf()
) {
    companion object {
        val LOGGER = LoggerFactory.getLogger("Reden/TickStageTree")!!
    }
    val activeStage get() = activeStages.lastOrNull()

    /**
     * Stages that have been ticked.
     */
    private val history = mutableListOf<TickStage>()
    // only used for debugging, DO NOT use it in production!! it is very very slow
    private val stacktrace = false

    @Transient
    private val stacktraces: MutableList<Array<StackTraceElement>?> = mutableListOf()

    @Transient
    private var stepOverUntil: TickStage? = null

    @Transient
    private var stepOverCallback: (() -> Unit)? = null
    private var steppingInto = false

    @Transient
    private var stepIntoCallback: (() -> Unit)? = null

    fun clear() {
        checkOnThread()
        LOGGER.debug("clear()")
        activeStages.clear()
        history.clear()
        if (steppingInto || stepOverUntil != null) {
            server.data.frozen = false
        }
        steppingInto = false
        stepOverUntil = null
        stepOverCallback = null
        stepIntoCallback = null
    }

    internal fun push(stage: TickStage) {
        checkOnThread()
        require(stage.parent == activeStage) {
            "Stage $stage is not a child of $activeStage"
        }
        if (stage in activeStages) {
            LOGGER.error("Stage $stage is already active")
        }
        activeStage?.children?.add(stage)
        activeStages.add(stage)
        if (stacktrace) {
            stacktraces.add(Thread.getAllStackTraces()[Thread.currentThread()])
        }
        LOGGER.debug("[{}] push {}", activeStages.size, stage)

        // Note: some network packets should not trigger step into
        if (steppingInto && stage !is TickStageWorldProvider) {
            steppingInto = false
            stepIntoCallback?.invoke()
            stepIntoCallback = null
            LOGGER.debug("step into")
            server.data.freeze("step-into")
            while (server.data.frozen && server.isRunning) {
                tickPackets(server)
            }
        }
        LOGGER.debug("preTick {}", stage)
        stage.status = TickStage.StageStatus.Pending
        stage.preTick()
    }

    private fun checkOnThread() {
        if (!server.isOnThread) error("Calling tick stage tree off thread.")
    }

    fun pop(clazz: Class<out TickStage>) {
        stacktraces.add(arrayOf())
        val stage = pop()
        require(clazz.isInstance(stage)) {
            "popped stage expected to be $clazz, but got ${stage.javaClass}"
        }
        stacktraces.removeLastOrNull()
    }

    internal fun pop(): TickStage {
        checkOnThread()

        val stage = activeStages.removeLast().also(history::add)
        stacktraces.removeLastOrNull()
        LOGGER.debug("[{}] pop {}", activeStages.size, stage)
        stage.status = TickStage.StageStatus.Ticked
        stage.postTick()
        stage.status = TickStage.StageStatus.Finished
        if (stage == stepOverUntil) {
            LOGGER.debug("stage == stepOverUntil")
            stepOverUntil = null
            stepOverCallback?.invoke()
            stepOverCallback = null
            server.data.freeze("step-over")
            while (server.data.frozen && server.isRunning) {
                tickPackets(server)
            }
        }
        LOGGER.debug("preTick {}", stage)
        return stage
    }

    fun with(stage: TickStage, block: () -> Unit) {
        try {
            push(stage)
            block()
        } catch (e: Exception) {
            LOGGER.error("Exception in stage $stage", e)
            LOGGER.error("Active stages:")
            for (tickStage in activeStages) {
                LOGGER.error("  $tickStage")
            }
        } finally {
            pop(stage.javaClass)
        }
    }

    fun stepOver(activeStage: TickStage, callback: () -> Unit): Boolean {
        stepOverUntil = activeStage
        stepOverCallback = callback
        steppingInto = false
        server.data.frozen = false
        return true
    }

    fun stepInto(callback: () -> Unit) {
        synchronized(this) {
            // Here we use synchronized to make it able to be called from other threads
            // @see com.github.zly2006.reden.network.Pause
            stepOverUntil = null
            steppingInto = true
            stepIntoCallback = callback
            server.data.frozen = false
        }
    }

    fun onBlockChanging(pos: BlockPos, state: BlockState, world: ServerWorld) {
        if ((activeStage as? TickStageWorldProvider)?.world == null) {
            // Note: no available world, we should add a stage to track this block change
            // This is usually caused by other mods.
            push(TickStageWorldProvider("set_block", activeStage!!, world))
        }
        val stage = activeStage as? TickStageWithWorld ?: return
        val oldState = stage.world?.getBlockState(pos) ?: return
        activeStage!!.changedBlocks.computeIfAbsent(pos) { TickStage.BlockChange(oldState, state) }
    }

    fun onBlockChanged(pos: BlockPos, state: BlockState) {
        val stage = activeStage
        if (stage is TickStageWorldProvider && stage.name == "set_block") {
            pop(TickStageWorldProvider::class.java)
        }
    }

    fun <T> onTickScheduled(orderedTick: OrderedTick<T>) {
        (orderedTick as TickStageTreeOwnerAccess).tickStageTree = this
        activeStage?.hasScheduledTicks = true
    }

    fun onBlockEventAdded(blockEvent: BlockEvent) {
        (blockEvent as TickStageTreeOwnerAccess).tickStageTree = this
        activeStage?.hasBlockEvents = true
    }
}
