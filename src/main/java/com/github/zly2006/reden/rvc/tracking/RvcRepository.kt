package com.github.zly2006.reden.rvc.tracking

import com.github.zly2006.reden.Reden
import com.github.zly2006.reden.gui.LoginRedenScreen
import com.github.zly2006.reden.report.httpClient
import com.github.zly2006.reden.report.ua
import com.github.zly2006.reden.rvc.gui.hud.gameplay.RvcMoveStructureLitematicaTask
import com.github.zly2006.reden.rvc.gui.hud.gameplay.RvcMoveStructureTask
import com.github.zly2006.reden.rvc.remote.IRemoteRepository
import com.github.zly2006.reden.rvc.tracking.WorldInfo.Companion.getWorldInfo
import com.github.zly2006.reden.rvc.tracking.network.LocalNetworkWorker
import com.github.zly2006.reden.rvc.tracking.network.ServerNetworkWorker
import com.github.zly2006.reden.task.Task
import com.github.zly2006.reden.task.taskStack
import com.github.zly2006.reden.utils.ResourceLoader
import com.github.zly2006.reden.utils.litematicaInstalled
import com.github.zly2006.reden.utils.redenApiBaseUrl
import com.github.zly2006.reden.utils.redenError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.minecraft.SharedConstants
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.NetworkSide
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import okhttp3.Request
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.InitCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.jetbrains.annotations.Contract
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists

private var ghCredential = Pair("RedenMC", "")
    get() {
        if (field.second.isEmpty() || tokenUpdated + 600 * 1000 < System.currentTimeMillis()) {
            val obj = httpClient.newCall(Request.Builder().apply {
                ua()
                url("$redenApiBaseUrl/mc/git/github")
            }.build()).execute().use {
                if (it.code == 401) {
                    MinecraftClient.getInstance().setScreen(LoginRedenScreen())
                }
                require(it.isSuccessful) {
                    "Failed to get private key from Reden API: ${it.code} ${it.message}"
                }
                Json.decodeFromString<JsonObject>(it.body!!.string())
            }
            if ("token" !in obj || obj["token"] is JsonNull) {
                MinecraftClient.getInstance().setScreen(LoginRedenScreen())
            }
            field = Pair(obj["name"]!!.jsonPrimitive.content, obj["token"]!!.jsonPrimitive.content)
            tokenUpdated = System.currentTimeMillis()
        }
        return field
    }
private var tokenUpdated = 0L

@OptIn(ExperimentalSerializationApi::class)
class RvcRepository(
    internal val git: Git,
    val name: String = git.repository.workTree.name,
    val side: NetworkSide
) {
    var headCache: TrackedStructure? = null
        private set
    fun clearCache() {
        headCache = null
    }

    /**
     * At `.git/placement.json`
     */
    private val placementJson = git.repository.directory.resolve("placement.json")

    /**
     * @see TrackedStructure.placementInfo
     */
    var placementInfo: PlacementInfo? =
        if (placementJson.exists()) Json.decodeFromStream(placementJson.inputStream()) else null
        set(value) {
            field = value
            if (value != null) {
                placementJson.writeText(Json.encodeToString(value))
            }
            else {
                placementJson.delete()
            }
        }
    var placed = false

    fun commit(structure: TrackedStructure, message: String, committer: PlayerEntity?, author: PersonIdent? = null) {
        require(structure.repository == this) { "The structure is not from this repository" }
        require(structure.placementInfo != null) { "The structure is not placed in this world" }
        headCache = structure
        this.createReadmeIfNotExists()
        val path = git.repository.workTree.toPath()
        structure.refreshPositions()
        val minPos = BlockPos(
            structure.cachedPositions.keys.minOfOrNull { it.x } ?: 0,
            structure.cachedPositions.keys.minOfOrNull { it.y } ?: 0,
            structure.cachedPositions.keys.minOfOrNull { it.z } ?: 0
        )
        if (git.branchList().call().isEmpty()) {
            // if this is the first commit, reset the origin
            structure.placementInfo = structure.placementInfo!!.copy(origin = minPos)
            structure.repository.placementInfo = structure.placementInfo
        }
        structure.collectAllFromWorld()
        RvcFileIO.save(path, structure)
        git.add().addFilepattern(".").call()
        val cmd = git.commit()
        if (committer != null && author == null) {
            cmd.setAuthor(committer.nameForScoreboard, committer.uuid.toString() + "@mc-player.redenmc.com")
        }
        if (author != null) {
            cmd.setAuthor(author)
        }
        if (committer != null) {
            cmd.setCommitter(committer.nameForScoreboard, committer.uuid.toString() + "@mc-player.redenmc.com")
        }
        cmd.setMessage("$message\n\nUser-Agent: Reden-RVC/${Reden.MOD_VERSION} Minecraft/${SharedConstants.getGameVersion().name}")
        cmd.setSign(false)
        val commit = cmd.call()
    }

    fun push(remote: IRemoteRepository, force: Boolean = false) {
        val push = git.push()
            .setRemote(remote.gitUrl)
            .setForce(force)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(ghCredential.first, ghCredential.second))
            .call()
        push.forEach {
            it.peerUserAgent
        }
    }

    fun fetch() {
        headCache = null
        git.fetch().call()
        TODO() // Note: currently we have no gui for this
    }

    @Contract(pure = true)
    fun hasChanged(): Boolean {
        RvcFileIO.save(
            git.repository.workTree.toPath(),
            headCache ?: return false
        )
        return !git.status().call().isClean
    }

    private fun configure(structure: TrackedStructure) {
        if (placementInfo != null) {
            structure.placementInfo = placementInfo
            structure.networkWorker = when (side) {
                NetworkSide.CLIENTBOUND -> LocalNetworkWorker(
                    structure,
                    placementInfo!!.worldInfo.getWorld() as ServerWorld,
                    placementInfo!!.worldInfo.getClientWorld()!!
                )

                NetworkSide.SERVERBOUND -> ServerNetworkWorker(
                    structure,
                    placementInfo!!.worldInfo.getWorld() as ServerWorld
                )
            }
        }
    }

    fun head(): TrackedStructure {
        try {
            if (headCache == null) {
                val refs = git.branchList().call()
                headCache =
                    if (refs.isEmpty()) TrackedStructure(name, this)
                    else if (refs.any { it.name == RVC_BRANCH_REF }) checkoutBranch(RVC_BRANCH)
                    else checkout(refs.first().name)
            }
            configure(headCache!!)
            return headCache!!
        } catch (e: Exception) {
            redenError("Failed to load RVC head structure from repository ${this.name}", e, log = true)
        }
    }

    fun checkout(tag: String) = TrackedStructure(name, this).apply {
        configure(this)
        git.checkout().setName(tag).setForced(true).call()
        RvcFileIO.load(git.repository.workTree.toPath(), this)
    }

    fun checkoutBranch(branch: String) = checkout("refs/heads/$branch")

    fun createReadmeIfNotExists() {
        git.repository.workTree.resolve("README.md").writeText(
            ResourceLoader.loadString("assets/rvc/README.md")
                .replace("\${name}", name)
        )
        git.add().addFilepattern("README.md").call()
    }

    fun createLicense(license: String, author: String? = null) {
        val year = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy"))
        val content = ResourceLoader.loadString("assets/rvc/licenses/$license.txt")
            .replace("\${year}", year)
            .replace("\${year-start}", year)
            .replace("\${author}", author ?: "")

        if (git.repository.workTree.resolve("LICENSE").exists()) {
            error("LICENSE already exists")
        }
        git.repository.workTree.resolve("LICENSE").writeText(content)

        TODO()
    }

    fun setWorld() {
        clearCache()
        val mc = MinecraftClient.getInstance()
        placementInfo = PlacementInfo(mc.getWorldInfo())
    }

    fun delete() {
        val path = git.repository.workTree.toPath()
        git.close()
        if (path.exists()) {
            // java.nio doesn't remove the readonly attribute of file on Windows
            // use the java.io method instead
            path.toFile().deleteRecursively()
        }
    }

    fun startPlacing() {
        clearCache()
        val world = MinecraftClient.getInstance().world!! // place locally may be fast? // todo
        Task.all<RvcMoveStructureTask>().forEach { it.onCancel() }
        val structure = head()
        structure.placementInfo = PlacementInfo(MinecraftClient.getInstance().getWorldInfo(), BlockPos.ORIGIN)
        taskStack.add(
            if (litematicaInstalled)
                RvcMoveStructureLitematicaTask(world, structure)
            else
                RvcMoveStructureTask(world, structure)
        )
    }

    val headHash: String get() = git.repository.resolve("HEAD").name()
    val headBranch: String get() = git.repository.branch

    companion object {
        val path = Path("rvc")
        const val RVC_BRANCH = "rvc"
        const val RVC_BRANCH_REF = "refs/heads/$RVC_BRANCH"
        fun create(name: String, worldInfo: WorldInfo, side: NetworkSide): RvcRepository {
            val git = Git.init()
                .setDirectory(path / name)
                .setInitialBranch(RVC_BRANCH)
                .call()
            return RvcRepository(git, side = side).apply {
                placementInfo = PlacementInfo(worldInfo, BlockPos.ORIGIN)
                createReadmeIfNotExists()
            }
        }

        fun clone(url: String, side: NetworkSide): RvcRepository {
            var name = url.split("/").last().removeSuffix(".git")
            var i = 1
            while ((path / name).exists()) {
                name = "$name$i"
                i++
            }
            return RvcRepository(
                git = Git.cloneRepository()
                    .setCredentialsProvider(
                        UsernamePasswordCredentialsProvider(
                            ghCredential.first,
                            ghCredential.second
                        )
                    )
                    .setURI(url)
                    .setDirectory(path / name)
                    .call(),
                side = side
            )
        }

        fun fromArchive(worktreeOrGitPath: Path, side: NetworkSide): RvcRepository {
            return RvcRepository(
                git = Git.open(worktreeOrGitPath.toFile()),
                side = side
            )
        }
    }
}

private fun CloneCommand.setDirectory(path: Path) = setDirectory(path.toFile())
private fun InitCommand.setDirectory(path: Path) = setDirectory(path.toFile())
