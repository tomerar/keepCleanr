// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import android.app.usage.StorageStatsManager
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.view.View
import io.github.tomerar.keepclear.AppsSource
import io.github.tomerar.keepclear.BuildConfig
import io.github.tomerar.keepclear.ClonedApps
import io.github.tomerar.keepclear.KeepClearApplication
import io.github.tomerar.keepclear.MoveNotifications
import io.github.tomerar.keepclear.ScanRoots
import io.github.tomerar.keepclear.backup.saf.DriveFormat
import io.github.tomerar.keepclear.classifyRun
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.homeClassifier
import kotlinx.coroutines.CancellationException

/**
 * Collects [DeviceFacts] on the phone (test builds only, Settings › Advanced › Device check). **Read-only**: it
 * reads settings, sizes and folder listings, runs Home's scanner and classifier in memory (nothing goes into the
 * scan index), and reads KeepClear's own trash and backup records. It never writes, moves, renames or deletes
 * anything and never touches the action executor or the trash manager (ArchitectureTest).
 *
 * Every section is collected on its own: one that fails is listed under "Could not check" with the exception's
 * class name only (messages can hold paths).
 */
class DeviceCheckCollector(private val app: KeepClearApplication) {
    private val failures = mutableListOf<Failure>()

    private suspend fun <T> section(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures += Failure(name, e.javaClass.simpleName)
        null
    }

    /** Blocking (file system, database); call off the main thread. */
    suspend fun collect(now: Long = System.currentTimeMillis()): DeviceFacts {
        failures.clear()
        // A device check never crashes: an access check that throws counts as "off".
        val allFiles = if (BuildConfig.FLAVOR ==
            "foss"
        ) {
            runCatching { MediaAccess.hasAllFiles() }.getOrDefault(false)
        } else {
            null
        }
        val volumes = section("Storage") { volumes() }.orEmpty()
        val clones = section("Cloned apps") { clones(allFiles == true) }
        return DeviceFacts(
            takenAtMillis = now,
            zone = java.time.ZoneId.systemDefault().id,
            app = appFacts(),
            device = device(),
            display = display(),
            access = section("Permissions") { access(allFiles) } ?: AccessInfo(allFiles, "unknown", false, false, 0),
            scan = section("Scan") { scan(allFiles == true) },
            volumes = volumes.map { it.facts },
            clones = clones,
            bins = if (allFiles == true) section("Recycle bins") { bins(volumes) }.orEmpty() else emptyList(),
            drives = section("Removable drives") { drives(volumes) }.orEmpty(),
            own = section("KeepClear's own state") { own() },
            failures = failures.toList(),
        )
    }

    private fun appFacts(): AppFacts {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return AppFacts(info.versionName.orEmpty(), info.longVersionCode, BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE)
    }

    private fun device(): DeviceInfo {
        val config = app.resources.configuration
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osBuild = Build.DISPLAY,
            release = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            locale = config.locales[0].toLanguageTag(),
            rtl = config.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    private fun display(): DisplayInfo {
        val config = app.resources.configuration
        return DisplayInfo(
            fontScale = config.fontScale,
            densityDpi = config.densityDpi,
            widthDp = config.screenWidthDp,
            heightDp = config.screenHeightDp,
            smallestWidthDp = config.smallestScreenWidthDp,
            night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
    }

    private fun access(allFiles: Boolean?) = AccessInfo(
        allFiles = allFiles,
        media = MediaAccess.current(app).name,
        notifications = MoveNotifications.allowed(app),
        usageAccess = AppsSource(app).hasUsageAccess(),
        grantedFolders = app.grantedFolderRoots.size,
    )

    /** A mounted or unmounted volume, with the path kept here only (never in the facts). */
    private class Volume(val facts: VolumeFacts, val root: String?, val uuid: String?, val removable: Boolean)

    private fun volumes(): List<Volume> {
        val sm = app.getSystemService(StorageManager::class.java)
        val labels = VolumeLabels()
        val all = sm.storageVolumes.sortedByDescending { it.isPrimary }
        return all.map { v ->
            val label = labels.next(v.isPrimary, v.isRemovable, usbHint(v))
            val dir = v.directory?.takeIf { v.state == Environment.MEDIA_MOUNTED }
            if (dir == null) {
                Volume(VolumeFacts(label, mounted = false), null, v.uuid, v.isRemovable)
            } else {
                val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                val stats = if (v.isPrimary) app.getSystemService(StorageStatsManager::class.java) else null
                Volume(
                    VolumeFacts(
                        label = label,
                        mounted = true,
                        total = stat?.totalBytes,
                        available = stat?.availableBytes,
                        free = stat?.freeBytes,
                        settingsTotal = stats?.let {
                            runCatching { it.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
                        },
                        settingsFree = stats?.let {
                            runCatching { it.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
                        },
                    ),
                    dir.absolutePath,
                    v.uuid,
                    v.isRemovable,
                )
            }
        }
    }

    /** Android has no public "is USB" flag; its own description says "USB" for USB drives. Never reported. */
    private fun usbHint(v: StorageVolume): Boolean =
        runCatching { v.getDescription(app).contains("usb", ignoreCase = true) }.getOrDefault(false)

    private suspend fun scan(allFiles: Boolean): ScanFacts? {
        val granted = app.grantedFolders.keys.sorted()
        val roots = ScanRoots.of(allFiles, app.volumeRoots(), cloneRoots(allFiles), granted)
        if (MediaAccess.current(app) == MediaAccess.NONE && roots.isEmpty()) return null
        val primary = app.getSystemService(StorageManager::class.java).primaryStorageVolume.directory?.absolutePath
        val classifier = homeClassifier(app)
        return ScanCheck.run(
            scanner = Scanner(app.localGateway, app.mediaSource),
            roots = roots,
            primaryRoot = primary,
            classify = { items -> classifyRun(app.localGateway, classifier, items, System.currentTimeMillis()).first },
            clock = SystemClock::elapsedRealtime,
            fat32Max = DriveFormat.FAT32.maxFileSize ?: Long.MAX_VALUE,
        )
    }

    private fun cloneRoots(allFiles: Boolean) = ClonedApps.probe(allFiles, ::exists, ::canList).readable

    private suspend fun clones(allFiles: Boolean): CloneFacts {
        val (listed, users) = CloneProbe.users(app.fileGateway, allFiles, ::exists, ::canList)
        val primary = app.getSystemService(StorageManager::class.java).primaryStorageVolume.directory?.absolutePath
        return CloneFacts(
            myUser = Process.myUid() / PER_USER_RANGE,
            profiles = app.getSystemService(UserManager::class.java).userProfiles.size,
            listedUsers = listed,
            mainWhatsApp = if (allFiles &&
                primary != null
            ) {
                WhatsAppFolders.measure(app.fileGateway, primary)
            } else {
                emptyMap()
            },
            users = users,
        )
    }

    private suspend fun bins(volumes: List<Volume>): List<BinFacts> {
        val roots = volumes.mapNotNull { v -> v.root?.let { v.facts.label to it } } +
            cloneRoots(true).map { root -> "clone user ${root.substringAfterLast('/')}" to root }
        return roots.flatMap { (label, root) -> OemBins.check(app.fileGateway, label, root) }
    }

    private fun drives(volumes: List<Volume>): List<DriveFacts> {
        val mounts = DriveFormat.readMounts()
        return volumes.filter { it.removable && it.facts.mounted }.map { v ->
            val format = v.uuid?.let { DriveFormat.detect(it, mounts) } ?: DriveFormat.UNKNOWN
            DriveFacts(
                label = v.facts.label,
                format = format.name,
                mountType = v.uuid?.let { MountTable.typeOf(it, mounts) },
                maxFileSize = format.maxFileSize,
                total = v.facts.total,
                available = v.facts.available,
            )
        }
    }

    /** Read-only queries of KeepClear's own records. */
    private suspend fun own(): OwnState {
        val trash = app.database.trashDao().all()
            .groupBy { it.state }
            .mapValues { (_, list) -> Count(list.size.toLong(), list.sumOf { it.size }) }
        val jobs = app.moveController.store.jobs()
        val choices = app.userChoices.state.value
        return OwnState(
            testMode = app.testMode.isOn,
            trash = trash,
            jobs = jobs.groupingBy { it.state.name }.eachCount(),
            pauseReasons = jobs.mapNotNull { it.pauseReason?.name }.groupingBy { it }.eachCount(),
            kept = choices.kept.size,
            junkFolders = choices.junk.size,
            networkDrive = app.networkDrives.hostState.value != null,
            reminder = app.reminderPrefs.enabled,
        )
    }

    private fun exists(path: String) = java.io.File(path).exists()

    private fun canList(path: String) = java.io.File(path).list() != null

    private companion object {
        /** Android's uid range per user (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000
    }
}
