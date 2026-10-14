package com.chevstrap.rbx.integrations

import android.app.ActivityManager
import android.content.Context
import chevstrap.extensions.FileTool
import com.chevstrap.rbx.App
import com.chevstrap.rbx.CustomWatcher
import com.chevstrap.rbx.WatcherService
import com.chevstrap.rbx.appDirectories.RobloxAccessMode
import com.chevstrap.rbx.appDirectories.RobloxClientData
import com.chevstrap.rbx.enums.ServerType
import com.chevstrap.rbx.models.entities.ActivityData
import com.chevstrap.rbx.ui.NotifyIconWrapper
import com.chevstrap.rbx.utility.RootShell
import com.chevstrap.rbx.utility.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile

class ActivityWatcher {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val history: MutableList<ActivityData?> = ArrayList()

    private var discordRPC: RobloxDiscordRPC? = null

    @Volatile
    var isStopMonitoring = false
        private set

    @Volatile
    var isInExperience = false

    @Volatile
    var isEnableRPC = false

    @JvmField
    var data: ActivityData? = null

    companion object {
        private const val PROCESS_CHECK_MISSES = 2
    }

    private fun start() {
        val tag = "ActivityWatcher::start"
        data = ActivityData("")

        val clientData = RobloxClientData()

        val accessMode = try {
            clientData.getAccessMode()
        } catch (_: Exception) {
            RobloxAccessMode.NONE
        }

        App.logger.writeLine(tag, "Roblox access mode: $accessMode")

        // Resolve the log dir from the package that is actually reachable
        // (may differ from the preferred one in settings).
        val workingPkg = try {
            clientData.findWorkingPackage()
        } catch (_: Exception) {
            null
        }
        App.logger.writeLine(tag, "Working Roblox package: $workingPkg")

        val execPath = workingPkg
            ?.let { clientData.executablePathForPackage(it) }
            ?: clientData.executablePath
        if (execPath.isNullOrEmpty()) {
            App.logger.writeLine(tag, "Executable path is null or empty")
            App.isLastLogFoundOrMaybeNot = true
            WatcherService.stop(App.appContext)
            return
        }

        isEnableRPC = App.config.data.showGameActivity
        discordRPC = CustomWatcher.getInstance().robloxDiscordRPC

        if (accessMode == RobloxAccessMode.ROOT) {
            startViaRoot(execPath, workingPkg)
            return
        }

        if (accessMode == RobloxAccessMode.SHIZUKU) {
            startViaShizukuLogcat(workingPkg)
            return
        }

        if (accessMode == RobloxAccessMode.NONE) {
            App.logger.writeLine(
                tag,
                "No readable Roblox directory (need Virtual Space or root). " +
                        "Executable path: $execPath"
            )
            App.isLastLogFoundOrMaybeNot = true
            WatcherService.stop(App.appContext)
            return
        }

        val logLocation = File(execPath, "logs")
        if (!logLocation.exists() && !logLocation.mkdirs()) {
            App.logger.writeLine(
                tag,
                "Failed to create log directory: ${logLocation.absolutePath}"
            )
            App.isLastLogFoundOrMaybeNot = true
            return
        }

        App.logger.writeLine(
            tag,
            "Watching logs from ${logLocation.absolutePath}"
        )

        var randomAccessFile: RandomAccessFile? = null
        var currentLogPath: String? = null
        var processMisses = 0

        try {
            App.isLastLogFoundOrMaybeNot = true
            var latestLog = getLatestLog(logLocation)

            if (latestLog != null) {
                currentLogPath = latestLog.absolutePath
                randomAccessFile = RandomAccessFile(latestLog, "r")
                randomAccessFile.seek(randomAccessFile.length())

                App.logger.writeLine(
                    tag,
                    "Starting from EOF: ${latestLog.name} (${randomAccessFile.length()} bytes)"
                )
            } else {
                App.logger.writeLine(
                    tag,
                    "No existing Roblox log found. Waiting for a new log..."
                )
            }

            while (!isStopMonitoring) {
                latestLog = getLatestLog(logLocation)

                if (latestLog == null) {
                    Thread.sleep(500)
                    continue
                }

                if (currentLogPath != latestLog.absolutePath) {
                    App.logger.writeLine(
                        tag,
                        "Detected new Roblox log: ${latestLog.name}"
                    )

                    try {
                        randomAccessFile?.close()
                    } catch (_: IOException) {
                    }

                    currentLogPath = latestLog.absolutePath
                    randomAccessFile = RandomAccessFile(latestLog, "r")
                    randomAccessFile.seek(0)

                    App.logger.writeLine(
                        tag,
                        "Now watching: ${latestLog.name}"
                    )

                    continue
                }

                if (randomAccessFile == null) {
                    randomAccessFile = RandomAccessFile(latestLog, "r")
                    randomAccessFile.seek(randomAccessFile.length())
                    currentLogPath = null
                }

                if (randomAccessFile.length() < randomAccessFile.filePointer) {
                    App.logger.writeLine(
                        tag,
                        "Log file was truncated. Resetting reader."
                    )
                    randomAccessFile.seek(0)
                }

                var hasReadData = false

                while (!isStopMonitoring) {
                    val line = randomAccessFile.readLine() ?: break
                    hasReadData = true

                    val decodedLine = try {
                        String(
                            line.toByteArray(Charsets.ISO_8859_1),
                            Charsets.UTF_8
                        )
                    } catch (_: Exception) {
                        line
                    }

                    handleLogEntry(decodedLine)
                }

                if (!hasReadData) {
                    if (workingPkg != null) {
                        if (isProcessAlive(workingPkg, false)) {
                            processMisses = 0
                        } else if (++processMisses >= PROCESS_CHECK_MISSES) {
                            App.logger.writeLine(
                                tag,
                                "Roblox process $workingPkg exited. " +
                                        "Stopping watcher."
                            )
                            dispose()
                            return
                        }
                    }
                    Thread.sleep(500)
                }
            }
        } catch (e: InterruptedException) {
            if (!isStopMonitoring) {
                App.logger.writeLine(
                    tag,
                    "Activity watcher interrupted unexpectedly."
                )
                App.logger.writeException(tag, e)
            } else {
                App.logger.writeLine(
                    tag,
                    "Activity watcher stopped."
                )
            }

            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            App.logger.writeLine(
                tag,
                "Failed to run activity watcher!"
            )
            App.logger.writeException(tag, e)
        } catch (e: Exception) {
            App.logger.writeLine(
                tag,
                "Unexpected error in activity watcher!"
            )
            App.logger.writeException(tag, e)
        } finally {
            App.isLastLogFoundOrMaybeNot = true
            closeRandomAccessFile(randomAccessFile)
        }
    }

    /**
     * Best-effort check (no root) whether [packageName] still has a live
     * process. Fail-open: returns true on any error so a broken probe can
     * never kill a healthy watcher session.
     */
    private fun isRobloxProcessRunning(packageName: String): Boolean {
        return try {
            val ctx = App.appContext ?: return true
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true
            am.runningAppProcesses?.any { it.pkgList?.contains(packageName) == true }
                ?: true
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Pure liveness probe shared by both tail loops. Returns false when the
     * Roblox process is confirmed gone; fail-open (true) on any error so a
     * broken probe can never kill a healthy session. Callers count
     * consecutive misses and stop the session at [PROCESS_CHECK_MISSES].
     */
    private fun isProcessAlive(packageName: String, isRoot: Boolean): Boolean {
        return try {
            if (isRoot) {
                RootShell.isProcessRunning(packageName)
            } else {
                isRobloxProcessRunning(packageName)
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun getLatestLog(logLocation: File): File? {
        return try {
            FileTool.listFiles(logLocation)
                .filter { it.isFile }
                .maxWithOrNull(
                    compareBy<File> { it.lastModified() }
                        .thenBy { it.length() }
                )
        } catch (e: Exception) {
            App.logger.writeException(
                "ActivityWatcher::getLatestLog",
                e
            )
            null
        }
    }

    private fun closeRandomAccessFile(file: RandomAccessFile?) {
        try {
            file?.close()
        } catch (_: IOException) {
        }
    }

    /**
     * Tail Roblox logs through root `su` without ever writing into the
     * Roblox private directory. Reads are incremental:
     * `ls -1t` to find the newest log, then `tail -c +offset` for new bytes.
     */
    private fun startViaRoot(execPath: String, packageName: String?) {
        val tag = "ActivityWatcher::startViaRoot"
        val remoteLogDir = "$execPath/logs"

        App.logger.writeLine(tag, "Watching logs via root from $remoteLogDir")
        App.isLastLogFoundOrMaybeNot = true

        var currentRemoteLog: String? = null
        var offset = 0L
        var leftover = ""
        var processMisses = 0

        try {
            val initial = RootShell.latestFileName(remoteLogDir)
            if (initial != null) {
                currentRemoteLog = "$remoteLogDir/$initial"
                offset = RootShell.fileLength(currentRemoteLog).coerceAtLeast(0L)
                App.logger.writeLine(
                    tag,
                    "Starting from EOF: $initial ($offset bytes)"
                )
            } else {
                App.logger.writeLine(
                    tag,
                    "No existing Roblox log found. Waiting for a new log..."
                )
            }

            while (!isStopMonitoring) {
                val latest = try {
                    RootShell.latestFileName(remoteLogDir)
                } catch (e: Exception) {
                    App.logger.writeException(tag, e)
                    Thread.sleep(1000)
                    continue
                }

                if (latest == null) {
                    Thread.sleep(500)
                    continue
                }

                val remotePath = "$remoteLogDir/$latest"

                if (currentRemoteLog != remotePath) {
                    App.logger.writeLine(tag, "Detected new Roblox log: $latest")
                    currentRemoteLog = remotePath
                    offset = 0L
                    leftover = ""
                    App.logger.writeLine(tag, "Now watching: $latest")
                    // Fall through to read from offset 0 on next loop pass.
                }

                val path = requireNotNull(currentRemoteLog)

                val chunk = try {
                    RootShell.readFromOffset(path, offset)
                } catch (e: Exception) {
                    App.logger.writeException(tag, e)
                    ByteArray(0)
                }

                if (chunk.isEmpty()) {
                    // No new data. A live game always appends to its log,
                    // so only probe while idle: fast stop on close, zero
                    // extra cost while playing.
                    if (packageName != null) {
                        if (isProcessAlive(packageName, true)) {
                            processMisses = 0
                        } else if (++processMisses >= PROCESS_CHECK_MISSES) {
                            App.logger.writeLine(
                                tag,
                                "Roblox process $packageName exited. " +
                                        "Stopping watcher."
                            )
                            dispose()
                            return
                        }
                    }
                    // Either no new data, or our offset is past EOF
                    // (log rotated/truncated). Verify with a stat call.
                    if (offset > 0) {
                        val len = try {
                            RootShell.fileLength(path)
                        } catch (_: Exception) {
                            -1L
                        }
                        when {
                            len < 0 -> {
                                // File vanished (rotation). Re-resolve next loop.
                                currentRemoteLog = null
                                offset = 0L
                                leftover = ""
                            }
                            len < offset -> {
                                App.logger.writeLine(
                                    tag,
                                    "Log file was truncated. Resetting reader."
                                )
                                offset = 0L
                                leftover = ""
                                continue
                            }
                        }
                    }
                    Thread.sleep(500)
                    continue
                }

                offset += chunk.size

                val text = leftover + try {
                    String(chunk, Charsets.UTF_8)
                } catch (_: Exception) {
                    String(chunk, Charsets.ISO_8859_1)
                }

                val lines = text.split('\n')
                // Last element may be a partial line; buffer it.
                leftover = if (text.endsWith("\n")) "" else lines.last()
                val complete = if (text.endsWith("\n")) lines else lines.dropLast(1)

                for (raw in complete) {
                    if (isStopMonitoring) break
                    val line = raw.trimEnd('\r')
                    if (line.isNotEmpty()) {
                        handleLogEntry(line)
                    }
                }
            }
        } catch (e: InterruptedException) {
            if (!isStopMonitoring) {
                App.logger.writeLine(tag, "Activity watcher interrupted unexpectedly.")
                App.logger.writeException(tag, e)
            } else {
                App.logger.writeLine(tag, "Activity watcher stopped.")
            }
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            App.logger.writeLine(tag, "Unexpected error in root activity watcher!")
            App.logger.writeException(tag, e)
        } finally {
            App.isLastLogFoundOrMaybeNot = true
        }
    }

    /**
     * Tail Roblox `FLog` lines through Shizuku
     * (shell uid, no root, no private-dir reads). The stream is polled
     * non-blockingly so the idle liveness probe (shared miss budget
     * with the other tail loops) still fires when the game exits.
     */
    private fun startViaShizukuLogcat(packageName: String?) {
        val tag = "ActivityWatcher::startViaShizuku"
        App.logger.writeLine(
            tag,
            "Watching Roblox logcat via Shizuku (tag Roblox, no file reads)"
        )
        App.isLastLogFoundOrMaybeNot = true

        var process: Process? = null
        var reader: BufferedReader? = null
        var skipHistoric = true
        var openFailures = 0
        var eofBreaks = 0
        var processMisses = 0
        // Set once any logcat line arrives (historic replay counts).
        // The exit probe stays disabled until then, mirroring the file
        // loops, which only probe while a log exists: the watcher must
        // survive Roblox still launching (no pid yet, no lines yet).
        var streamLive = false

        fun openStream(): Boolean {
            closeShizukuStream(process, reader)
            process = null
            reader = null
            val proc = try {
                ShizukuShell.openRobloxLogcat()
            } catch (e: Exception) {
                App.logger.writeException(tag, e)
                null
            }
            if (proc == null) {
                if (++openFailures >= 3) {
                    App.logger.writeLine(
                        tag,
                        "Shizuku logcat unavailable (not running or denied). " +
                                "Stopping watcher."
                    )
                    dispose()
                    return false
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                return true
            }
            openFailures = 0
            process = proc
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            // -T 1 replays one historic line on every (re)open; drop it
            // so a stale "Joining game" never fires a false session.
            skipHistoric = true
            return true
        }

        try {
            if (!openStream()) return
            if (process == null) return

            while (!isStopMonitoring) {
                var drained = false
                var hitEof = false
                try {
                    val r = reader
                    if (r == null) {
                        if (!openStream()) return
                        if (process == null) return
                        continue
                    }
                    while (!isStopMonitoring && r.ready()) {
                        val line = r.readLine()
                        if (line == null) {
                            hitEof = true
                            break
                        }
                        drained = true
                        if (!streamLive) {
                            streamLive = true
                            eofBreaks = 0
                            App.logger.writeLine(
                                tag,
                                "Logcat stream live, following Roblox logs"
                            )
                        }
                        if (skipHistoric) {
                            skipHistoric = false
                            continue
                        }
                        if (line.isNotBlank()) {
                            handleLogEntry(line)
                        }
                    }
                    if (hitEof) {
                        App.logger.writeLine(tag, "Logcat stream hit EOF, reopening.")
                        if (++eofBreaks >= 5) {
                            App.logger.writeLine(
                                tag,
                                "Logcat stream keeps dying. Stopping watcher."
                            )
                            dispose()
                            return
                        }
                        if (!openStream()) return
                        if (process == null) return
                        continue
                    }
                    if (drained) {
                        processMisses = 0
                        eofBreaks = 0
                        continue
                    }
                } catch (e: IOException) {
                    App.logger.writeLine(tag, "Logcat stream broke, reopening.")
                    App.logger.writeException(tag, e)
                    if (!openStream()) return
                    if (process == null) return
                    continue
                } catch (e: Exception) {
                    App.logger.writeException(tag, e)
                    Thread.sleep(1000)
                    continue
                }

                // Idle: no new lines. Before the first line ever arrives
                // Roblox may still be launching, so wait (like the file
                // loops wait for a log) instead of probing. Once live,
                // probe liveness so closing the game stops the session
                // promptly, same as the file loops.
                if (!streamLive) {
                    Thread.sleep(500)
                    continue
                }
                if (packageName != null) {
                    val alive = try {
                        ShizukuShell.isProcessRunning(packageName)
                    } catch (_: Exception) {
                        true
                    }
                    if (alive) {
                        processMisses = 0
                    } else if (++processMisses >= PROCESS_CHECK_MISSES) {
                        App.logger.writeLine(
                            tag,
                            "Roblox process $packageName exited. " +
                                    "Stopping watcher."
                        )
                        dispose()
                        return
                    }
                }
                Thread.sleep(500)
            }
        } catch (e: InterruptedException) {
            if (!isStopMonitoring) {
                App.logger.writeLine(tag, "Activity watcher interrupted unexpectedly.")
                App.logger.writeException(tag, e)
            } else {
                App.logger.writeLine(tag, "Activity watcher stopped.")
            }
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            App.logger.writeLine(tag, "Unexpected error in Shizuku activity watcher!")
            App.logger.writeException(tag, e)
        } finally {
            App.isLastLogFoundOrMaybeNot = true
            closeShizukuStream(process, reader)
        }
    }

    private fun closeShizukuStream(process: Process?, reader: BufferedReader?) {
        try {
            reader?.close()
        } catch (_: IOException) {
        }
        if (process != null) {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateServerType(activityData: ActivityData) {
        val rootPlaceId = activityData.rootPlaceId
        val placeId = activityData.placeId

        activityData.serverType = when {
            rootPlaceId == 0L ||
                    placeId == 0L ||
                    rootPlaceId == placeId -> ServerType.PUBLIC

            else -> ServerType.RESERVED
        }

        App.logger.writeLine(
            "ActivityWatcher::updateServerType",
            "Server type updated: rootPlaceId=$rootPlaceId, " +
                    "placeId=$placeId, serverType=${activityData.serverType}"
        )
    }

    private fun addToHistory(activityData: ActivityData) {
        val previous = history.lastOrNull()

        if (
            previous != null &&
            previous.universeId == activityData.universeId &&
            (
                    previous.serverType == ServerType.RESERVED ||
                            activityData.serverType == ServerType.RESERVED
                    )
        ) {
            previous.timeJoined = listOfNotNull(
                previous.timeJoined,
                activityData.timeJoined
            ).minOrNull()

            previous.timeLeft = listOfNotNull(
                previous.timeLeft,
                activityData.timeLeft
            ).maxOrNull()

            App.logger.writeLine(
                "ActivityWatcher::addToHistory",
                "Merged reserved server into previous game history: " +
                        "universeId=${activityData.universeId}"
            )

            return
        }

        history.add(activityData)

        App.logger.writeLine(
            "ActivityWatcher::addToHistory",
            "Added game history: " +
                    "serverType=${activityData.serverType}, " +
                    "rootPlaceId=${activityData.rootPlaceId}, " +
                    "placeId=${activityData.placeId}, " +
                    "universeId=${activityData.universeId}"
        )
    }

    private fun handleLogEntry(line: String) {
        val tag = "ActivityWatcher::handleLogEntry"
        val joinServer = "[FLog::Output] ! Joining game"
        val leaveServer = "[FLog::Network] Time to disconnect replication data"
        val connection = "Info [DFLog::NetworkClient] Connection accepted from"
        val leavingRoblox = "[FLog::SingleSurfaceApp] setStage: (stage:None)"
        val universe = "[FLog::GameJoinLoadTime] Report game_join_loadtime:"
        val bsRPC = "[BloxstrapRPC]"

        if (
            !line.contains(joinServer) &&
            !line.contains(leaveServer) &&
            !line.contains(connection) &&
            !line.contains(bsRPC) &&
            !line.contains(leavingRoblox) &&
            !line.contains(universe)
        ) {
            return
        }

        val activityData = data ?: return

        try {
            if (!isInExperience && line.contains(leavingRoblox)) {
                dispose()
                return
            }

            if (
                isInExperience &&
                activityData.placeId != 0L &&
                line.contains(leaveServer)
            ) {
                activityData.timeLeft = Date()
                updateServerType(activityData)
                addToHistory(activityData)

                isInExperience = false
                data = ActivityData("")

                App.appContext?.let {
                    NotifyIconWrapper.hideConnectionNotification(it)

                    if (isEnableRPC) {
                        NotifyIconWrapper.hideDiscordRpcVisibilityNotification(it)
                    }
                }

                if (isEnableRPC) {
                    discordRPC?.setCurrentGame()
                }

                return
            }

            if (
                !isInExperience &&
                activityData.placeId == 0L &&
                line.contains(joinServer)
            ) {
                try {
                    val match = Regex(
                        """Joining game '([^']+)' place (\d+) at (.+)"""
                    ).find(line)

                    if (match != null) {
                        val jobId = match.groupValues[1].trim()
                        val placeId = match.groupValues[2]
                            .trim()
                            .toLongOrNull()
                        val serverIP = match.groupValues[3].trim()

                        if (jobId.isNotEmpty() && placeId != null) {
                            App.logger.writeLine(
                                tag,
                                "Joining game: placeId=$placeId, " +
                                        "jobId=$jobId, serverIP=$serverIP"
                            )

                            isInExperience = true
                            activityData.timeJoined = Date()
                            activityData.placeId = placeId
                            activityData.jobId = jobId

                            updateServerType(activityData)
                        } else {
                            App.logger.writeLine(
                                tag,
                                "Failed to parse join experience info or game info: $line"
                            )
                        }
                    } else {
                        App.logger.writeLine(
                            tag,
                            "Failed to parse join experience info or game info: $line"
                        )
                    }
                } catch (e: Exception) {
                    App.logger.writeLine(
                        tag,
                        "Failed to parse join experience info or game info"
                    )
                    App.logger.writeException(tag, e)
                }

                return
            }

            if (
                isInExperience &&
                activityData.placeId != 0L &&
                line.contains(connection)
            ) {
                try {
                    val match = Regex(
                        """Connection accepted from\s+(.+)$"""
                    ).find(line)

                    if (match != null) {
                        val location = match.groupValues[1]
                            .trim()
                            .substringBefore("|")
                            .trim()

                        if (location.isNotEmpty()) {
                            App.logger.writeLine(
                                tag,
                                "Connection string: $line"
                            )

                            activityData.machineAddress = location

                            if (
                                activityData.machineAddress.isNullOrEmpty() ||
                                activityData.universeId.toULong() == 0UL
                            ) {
                                App.logger.writeLine(
                                    tag,
                                    "Failed to parse connection string: $line"
                                )
                                App.logger.writeLine(
                                    tag,
                                    "Machine address: ${activityData.machineAddress}"
                                )
                                App.logger.writeLine(
                                    tag,
                                    "Universe ID: ${activityData.universeId}"
                                )
                            } else {
                                App.appContext?.let {
                                    NotifyIconWrapper.showConnectionNotification(
                                        it,
                                        activityData,
                                        location,
                                        activityData.universeId.toString()
                                    )

                                    if (isEnableRPC) {
                                        NotifyIconWrapper.showDiscordRpcVisibilityNotification(
                                            it,
                                            discordRPC?.isVisibleState() != null
                                        )
                                    }
                                }
                            }
                        } else {
                            App.logger.writeLine(
                                tag,
                                "Unexpected connection format: $line"
                            )
                        }
                    } else {
                        App.logger.writeLine(
                            tag,
                            "Unexpected connection format: $line"
                        )
                    }
                } catch (e: Exception) {
                    App.logger.writeLine(
                        tag,
                        "Failed to parse connection string"
                    )
                    App.logger.writeException(tag, e)
                }

                return
            }

            if (
                isInExperience &&
                activityData.placeId != 0L &&
                line.contains(universe)
            ) {
                try {
                    val userIndex = line.indexOf("userid:")

                    if (userIndex != -1) {
                        val userStart = userIndex + "userid:".length
                        val userEnd = line.indexOf(",", userStart)
                            .takeIf { it != -1 }
                            ?: line.length

                        line.substring(userStart, userEnd)
                            .trim()
                            .toLongOrNull()
                            ?.let {
                                activityData.userId = it
                            }
                    }

                    val universeIndex = line.indexOf("universeid:")

                    if (universeIndex != -1) {
                        val universeStart =
                            universeIndex + "universeid:".length

                        val universeEnd = line.indexOf(
                            ",",
                            universeStart
                        ).takeIf { it != -1 } ?: line.length

                        line.substring(
                            universeStart,
                            universeEnd
                        )
                            .trim()
                            .toLongOrNull()
                            ?.let { universeId ->
                                activityData.setUniverseId(universeId) { success ->
                                    if (success) {
                                        updateServerType(activityData)

                                        if (isEnableRPC) {
                                            discordRPC?.setCurrentGame()
                                        }
                                    }
                                }
                            }
                    }
                } catch (e: Exception) {
                    App.logger.writeLine(
                        tag,
                        "Failed to parse user/universe id info"
                    )
                    App.logger.writeException(tag, e)
                }

                return
            }

            if (line.contains(bsRPC) && isInExperience) {
                if (!App.config.data.discordAllowCustomPresence) {
                    return
                }

                val jsonStart = line.indexOf(
                    "{",
                    line.indexOf(bsRPC)
                )

                if (jsonStart == -1) {
                    App.logger.writeLine(
                        tag,
                        "Failed to assert format for RPC message entry"
                    )
                    App.logger.writeLine(tag, line)
                    return
                }

                val messagePlain = line.substring(jsonStart).trim()

                App.logger.writeLine(
                    tag,
                    "Received message: '$messagePlain'"
                )

                try {
                    if (isEnableRPC) {
                        discordRPC?.processRPCMessage(
                            JSONObject(messagePlain)
                        )
                    }
                } catch (e: Exception) {
                    App.logger.writeLine(
                        tag,
                        "Failed to parse message! " +
                                "(JSON deserialization threw an exception)"
                    )
                    App.logger.writeException(tag, e)
                }
            }
        } catch (e: Exception) {
            App.logger.writeLine(
                tag,
                "Failed to handle log entry"
            )
            App.logger.writeException(tag, e)
        }
    }

    internal fun isInExperienceState(): Boolean = isInExperience

    internal fun getActivityData(): ActivityData? = data

    fun dispose() {
        val tag = "ActivityWatcher::dispose"

        if (isStopMonitoring) {
            return
        }

        isStopMonitoring = true

        App.logger.writeLine(
            tag,
            "Disposing activity watcher session"
        )

        runCatching {
            WatcherService.stop(App.appContext)
        }

        try {
            if (isEnableRPC) {
                discordRPC?.dispose()
            }
        } catch (e: Exception) {
            App.logger.writeLine(
                tag,
                "Failed to dispose Discord RPC."
            )
            App.logger.writeException(tag, e)
        }

        App.appContext?.let {
            NotifyIconWrapper.hideConnectionNotification(it)

            if (isEnableRPC) {
                NotifyIconWrapper.hideDiscordRpcVisibilityNotification(it)
            }
        }

        watcherScope.cancel()

        if (!executor.isShutdown) {
            executor.shutdownNow()
        }
    }

    fun runWatcher() {
        val tag = "ActivityWatcher::runWatcher"

        if (isStopMonitoring) {
            App.logger.writeLine(
                tag,
                "Cannot start activity watcher because it has been disposed."
            )
            return
        }

        if (executor.isShutdown) {
            App.logger.writeLine(
                tag,
                "Cannot start activity watcher because executor is shut down."
            )
            return
        }

        App.logger.writeLine(
            tag,
            "Starting activity watcher session"
        )

        try {
            executor.submit {
                start()
            }

            // Hold a foreground state so background network restrictions
            // don't block API calls while the Roblox game is on screen.
            WatcherService.start(App.appContext)
        } catch (e: Exception) {
            App.logger.writeLine(
                tag,
                "Failed to submit activity watcher task."
            )
            App.logger.writeException(tag, e)
        }
    }
}
