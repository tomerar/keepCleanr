// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.core.storage.saf.GrantedFolders
import io.github.tomerar.keepclear.devcheck.DeviceCheckGate
import io.github.tomerar.keepclear.devcheck.DeviceCheckShare
import io.github.tomerar.keepclear.devcheck.DeviceCheckUi
import io.github.tomerar.keepclear.devcheck.DeviceCheckViewModel
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val thumbnails by lazy { ThumbnailLoader(contentResolver) }
    private val appIcons by lazy { AppIconLoader(packageManager) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // SEC-7: ignore touches while another app's overlay covers KeepClear.
        window.decorView.filterTouchesWhenObscured = true
        setContent {
            KeepClearTheme {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val mediaLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        vm.refresh()
                    }
                val app = application as KeepClearApplication
                val testModeOn by app.testMode.on.collectAsStateWithLifecycle()
                val moveVm: MoveViewModel = viewModel()
                // Test builds only: Settings › Advanced › Device check (read-only).
                val deviceCheckVm: DeviceCheckViewModel = viewModel()
                val deviceCheckState by deviceCheckVm.state.collectAsStateWithLifecycle()
                // M17b: Settings › Advanced › Back up to Nextcloud.
                val nextcloudVm: NextcloudViewModel = viewModel()
                val nextcloudState by nextcloudVm.state.collectAsStateWithLifecycle()
                val nextcloudHost by app.nextcloud.hostState.collectAsStateWithLifecycle()
                var notificationsOff by remember { mutableStateOf(!MoveNotifications.allowed(this)) }
                var explainNotifications by remember { mutableStateOf(false) }
                // ONB-1: the intro on first launch, or when Settings asks for it again.
                var showIntro by remember { mutableStateOf(!Onboarding.seen(this)) }
                if (showIntro) {
                    IntroScreen(onDone = {
                        Onboarding.setSeen(this, true)
                        showIntro = false
                    })
                    return@KeepClearTheme
                }
                val treeLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) moveVm.onTreePicked(uri)
                    }
                // F2: the reminder's notification permission; the switch turns on only when it is granted.
                var onNotificationAnswer by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
                val reminderPermissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        notificationsOff = !granted
                        onNotificationAnswer?.invoke(granted)
                        onNotificationAnswer = null
                    }
                // E7: only the set-up result is read here, never the whole MOVE state (progress would recompose all).
                val networkSetup by remember {
                    moveVm.state.map { it.networkSetup }.distinctUntilChanged()
                }.collectAsStateWithLifecycle(null)
                val notificationLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        // ONB-10: the backup runs either way; without the permission progress shows only in the app.
                        notificationsOff = !granted
                        moveVm.start()
                    }
                // M12: Android's own confirmation for its trash (MediaStore), one dialog at a time.
                val consentLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                        vm.consentResult(result.resultCode == RESULT_OK)
                    }
                // E12: each request's dialog is launched once, also across rotation, theme and language changes.
                MediaConsentEffect(
                    consent = state.consent,
                    build = { vm.nextConsentRequest() },
                    onLaunched = vm::consentLaunched,
                    launch = { request ->
                        (request as? android.content.IntentSender)?.let {
                            consentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build())
                        }
                    },
                )
                // M12b, ONB-6: the system folder picker, opened at WhatsApp's folder.
                val whatsAppLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        vm.whatsAppFolderPicked(uri)
                    }
                val externalCacheLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        vm.refresh()
                    }
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    // M13: usage access or an uninstall may have changed while KeepClear was in the background.
                    if (vm.state.value.screens.last() == Screen.Apps) loadApps(vm)
                    notificationsOff = !MoveNotifications.allowed(this@MainActivity)
                    onPauseOrDispose { }
                }
                if (explainNotifications) {
                    AlertDialog(
                        onDismissRequest = {
                            explainNotifications = false
                            markAskedNotifications()
                            moveVm.start()
                        },
                        title = { Text(stringResource(R.string.move_notif_title)) },
                        text = { Text(stringResource(R.string.move_notif_body)) },
                        confirmButton = {
                            Button(onClick = {
                                explainNotifications = false
                                markAskedNotifications()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    moveVm.start()
                                }
                            }) { Text(stringResource(R.string.move_notif_allow)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                explainNotifications = false
                                markAskedNotifications()
                                moveVm.start()
                            }) { Text(stringResource(R.string.move_notif_skip)) }
                        },
                    )
                }
                // M15: Settings shows the only address KeepClear may connect to (PRIVACY.md "Network").
                val networkHost by app.networkDrives.hostState.collectAsStateWithLifecycle()
                val choices by app.userChoices.state.collectAsStateWithLifecycle()
                var reminderOn by androidx.compose.runtime.remember { mutableStateOf(app.reminderPrefs.enabled) }
                // D6: forgetting the Nextcloud revokes its app password on the server first; it runs in the app's
                // scope (survives rotation, PR #44 review) and its state lives in the application.
                val nextcloudForget by app.nextcloudForget.collectAsStateWithLifecycle()
                CompositionLocalProvider(
                    LocalThumbnailLoader provides thumbnails,
                    LocalAppIcons provides appIcons,
                ) {
                    AppScreens(
                        settingsActions = SettingsActions(
                            networkHost = networkHost,
                            choices = choices,
                            onRemoveKept = vm::removeKept,
                            onRemoveJunk = vm::removeJunk,
                            reminderOn = reminderOn,
                            onReminder = { on ->
                                app.reminderPrefs.enabled = on
                                reminderOn = on
                                SpaceReminder.schedule(this@MainActivity, on)
                            },
                            // F2: explained first in Settings; allowed() is always true below Android 13.
                            needsNotificationPermission = !MoveNotifications.allowed(this@MainActivity),
                            onAskNotifications = { answer ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    onNotificationAnswer = answer
                                    reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    answer(true)
                                }
                            },
                            onSetUpNetworkDrive = moveVm::setUpNetworkDrive,
                            savedNetworkDrive = moveVm::savedNetworkDrive,
                            networkSetup = networkSetup,
                            onNetworkSetupSeen = moveVm::clearNetworkSetup,
                            onForgetNetworkDrive = { lifecycleScope.launch { app.forgetNetworkDrive() } },
                            nextcloudHost = nextcloudHost,
                            onForgetNextcloud = { app.forgetNextcloudInBackground() },
                            forgettingNextcloud = nextcloudForget.forgetting,
                            nextcloudForgot = nextcloudForget.result,
                            onOpenNextcloud = {
                                nextcloudVm.begin()
                                vm.open(Screen.Nextcloud)
                            },
                            currentLanguage = currentLanguage(),
                            onLanguage = ::setLanguage,
                            onReplayIntro = { showIntro = true },
                            onOpenPrivacy = ::openPrivacy,
                            version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                            trashBytes = state.trash.bytes,
                            showDeviceCheck = DeviceCheckGate.visible(),
                            onOpenDeviceCheck = {
                                deviceCheckVm.runIfEmpty()
                                vm.open(Screen.DeviceCheck)
                            },
                        ),
                        deviceCheck = DeviceCheckUi(
                            enabled = DeviceCheckGate.visible(),
                            state = deviceCheckState,
                            onRun = deviceCheckVm::run,
                            onCopy = { DeviceCheckShare.copy(this@MainActivity, it) },
                            onShare = { DeviceCheckShare.share(this@MainActivity, it) },
                        ),
                        previewActions = PreviewActions(onKeep = vm::keep, onAlwaysJunk = vm::alwaysJunk),
                        appsActions = AppsActions(
                            onAllowUsageAccess = {
                                runCatching {
                                    startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                            },
                            onUninstall = { pkg ->
                                // Android's own uninstall window; KeepClear never removes an app itself.
                                // SAFETY-9: never in trial mode (the screen explains; this is the backstop).
                                if (!app.testMode.isOn) {
                                    runCatching { startActivity(Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())) }
                                }
                            },
                            onOpenAppInfo = ::openAppInfo,
                            testModeOn = testModeOn,
                        ),
                        appActions = AppActions(
                            labelOf = ::appLabel,
                            onAllowUsageAccess = {
                                runCatching {
                                    startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                            },
                            onOpenApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                            onOpenAppInfo = { pkg ->
                                vm.openingAppInfo(pkg)
                                openAppInfo(pkg)
                            },
                        ),
                        testMode = TestModeUi(
                            on = testModeOn,
                            turnOn = app.testMode::turnOn,
                            turnOff = app.testMode::turnOff,
                        ),
                        state = state,
                        onOpen = vm::open,
                        onBack = {
                            // Leaving the Nextcloud set-up stops a sign-in that is still waiting (nothing is saved).
                            if (state.screens.lastOrNull() == Screen.Nextcloud) nextcloudVm.cancel()
                            vm.back()
                        },
                        nextcloud = NextcloudUi(
                            state = nextcloudState,
                            connectedHost = nextcloudHost,
                            onSignIn = nextcloudVm::signIn,
                            onManual = nextcloudVm::manual,
                            onOpenLogin = ::openLoginPage,
                            onLoginOpened = nextcloudVm::loginOpened,
                            onCancel = nextcloudVm::cancel,
                        ),
                        onRestore = vm::restore,
                        onPurge = vm::purge,
                        // E7: the MOVE screen reads this itself; progress doesn't recompose every screen.
                        moveFlow = moveVm.state,
                        // F1b: the tabs, the Trash chip (the trash's one entry point besides results) and Settings.
                        tabs = TabsUi(
                            onSelectTab = { tab ->
                                // M13: the Apps tab loads sizes and last use each time it is shown.
                                if (tab == Tab.APPS) loadApps(vm)
                                vm.selectTab(tab)
                            },
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
                            onOpenSettings = { vm.open(Screen.Settings) },
                        ),
                        selectionActions = SelectionActions(onSelect = vm::select, onSwipe = vm::swipe),
                        notificationsOff = notificationsOff,
                        moveActions = MoveActions(
                            onPickTarget = { items ->
                                moveVm.pendingItems = items
                                treeLauncher.launch(null)
                            },
                            onNetworkDrive = { items, location ->
                                moveVm.pendingItems = items
                                moveVm.onNetworkDrive(location)
                            },
                            savedNetworkDrive = moveVm::savedNetworkDrive,
                            networkHost = networkHost,
                            nextcloudHost = nextcloudHost,
                            onNextcloud = { items ->
                                moveVm.pendingItems = items
                                moveVm.onNextcloud()
                            },
                            restore = RestoreActions(
                                onStart = moveVm::beginRestore,
                                onPickFolder = { treeLauncher.launch(null) },
                                onNetworkDrive = moveVm::onNetworkDrive,
                                savedNetworkDrive = moveVm::savedNetworkDrive,
                                networkHost = networkHost,
                                onCancel = moveVm::cancelRestore,
                                nextcloudHost = nextcloudHost,
                                onNextcloud = moveVm::onNextcloud,
                                onChooseBackup = moveVm::chooseBackup,
                                onRestore = moveVm::runRestore,
                                onClose = moveVm::closeRestore,
                            ),
                            onStart = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationsOff &&
                                    !askedNotifications()
                                ) {
                                    explainNotifications = true
                                } else {
                                    moveVm.start()
                                }
                            },
                            onCancelPick = moveVm::cancelPick,
                            onPause = moveVm::pause,
                            onStop = moveVm::stop,
                            onResume = moveVm::resume,
                            onFree = moveVm::freeOriginals,
                            onCheck = moveVm::checkBackup,
                            onCancelCheck = moveVm::cancelCheck,
                            onShare = { id ->
                                moveVm.state.value.jobs.firstOrNull { it.job.id == id }?.let(::shareReceipt)
                            },
                            onOpenGooglePhotos = ::openGooglePhotos,
                            hasRemovableDrive = ::hasRemovableDrive,
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
                        ),
                        safeActions = SafeActions(
                            onClean = vm::clean,
                            onUndo = vm::undo,
                            onTrim = vm::trimCaches,
                            // SAFE-6 (foss with All files access): Android's own one-dialog external cache clear.
                            onClearExternalCaches = {
                                // Test mode (SAFETY-9): Android's cache clear would really delete; don't open it.
                                if (app.testMode.isOn) {
                                    vm.trimCaches()
                                } else {
                                    externalCacheLauncher.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                                }
                            },
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
                            onRestoreChanged = vm::restoreChanged,
                            onConfirmLastCopy = vm::confirmLastCopies,
                            onOneByOne = { openOneByOne(vm) },
                            onDismissLastCopy = vm::dismissLastCopies,
                            onAndroidTrash = vm::androidTrash,
                            onRelease = vm::release,
                        ),
                        home = {
                            HomeScreen(
                                state = state,
                                showDebugTools = BuildConfig.DEBUG,
                                actions = HomeActions(
                                    onRequestMedia = { mediaLauncher.launch(MediaAccess.requestPermissions()) },
                                    onRequestAllFiles = {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                "package:$packageName".toUri(),
                                            ),
                                        )
                                    },
                                    onScanAgain = vm::scan,
                                    onCancelScan = vm::cancelScan,
                                    onTrim = vm::trimCaches,
                                    onOneByOne = { openOneByOne(vm) },
                                    onOpenBackup = { vm.selectTab(Tab.BACKUP) },
                                    onClean = vm::clean,
                                    onUndo = vm::undo,
                                    onPickWhatsApp = {
                                        val initial = android.provider.DocumentsContract.buildDocumentUri(
                                            GrantedFolders.EXTERNAL_STORAGE,
                                            GrantedFolders.initialDocumentId(),
                                        )
                                        runCatching { whatsAppLauncher.launch(initial) }
                                    },
                                    onDismissWrongFolder = vm::dismissWrongFolder,
                                    onOpenCategory = { vm.open(Screen.CategoryList(it)) },
                                    onOpenTrash = {
                                        vm.loadTrash()
                                        vm.open(Screen.Trash)
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    private val prefs get() = getSharedPreferences("move", MODE_PRIVATE)

    private fun askedNotifications() = MainThreadPolicy.settings { prefs.getBoolean("askedNotifications", false) }

    private fun markAskedNotifications() = MainThreadPolicy.settings {
        prefs.edit { putBoolean("askedNotifications", true) }
    }

    /** MOVE-16: hand over to Google Photos' own "Free up space"; KeepClear can't see its cloud state. */
    private fun openGooglePhotos() {
        packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")?.let(::startActivity)
    }

    private fun loadApps(vm: HomeViewModel) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppsSource(this@MainActivity).load() }
            vm.setAppsInfo(apps)
        }
    }

    /** SAFE-10: launchable apps by name (package visibility via the launcher-intent query). */
    private fun openOneByOne(vm: HomeViewModel) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { AppEntry(it, appLabel(it)) }
            .sortedBy { it.label.lowercase() }
        vm.setApps(apps)
        vm.open(Screen.OneAppAtATime)
        // SAFE-10: with usage access, sort by temporary-file size (largest first).
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { AppsSource(this@MainActivity).load() } ?: return@launch
            val cache = info.associate { it.pkg to it.cacheBytes }
            vm.setApps(apps.map { it.copy(cacheBytes = cache[it.pkg]) }.sortedByDescending { it.cacheBytes ?: -1 })
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun openAppInfo(pkg: String) {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()),
            )
        }
    }

    /** Per-app language (Android 13+); older phones open the system language settings. */
    private fun setLanguage(tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (tag == null) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(tag)
            }
            getSystemService(android.app.LocaleManager::class.java).applicationLocales = locales
        } else {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
        }
    }

    private fun currentLanguage(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(android.app.LocaleManager::class.java).applicationLocales.get(0)?.language
                ?.let { if (it == "he") "iw" else it }
        } else {
            null
        }

    /**
     * M17b: the Nextcloud sign-in page in the browser. The engine already checked it is on the server the user
     * typed (https, or this phone itself); only http(s) links are ever opened.
     */
    private fun openLoginPage(url: String) {
        val uri = url.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    /** PRIV-5: the privacy policy, opened in the browser (KeepClear itself has no internet access). */
    private fun openPrivacy() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_URL.toUri())) }
    }

    /** A USB drive or memory card is mounted (checked before Android's folder picker, review PR C). */
    private fun hasRemovableDrive(): Boolean =
        getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
            .any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }

    /** MOVE-13: share a receipt as plain text. */
    private fun shareReceipt(view: JobView) {
        val r = view.receipt
        val text = getString(
            R.string.move_receipt_text,
            r.targetLabel,
            r.rootRel,
            r.verified,
            ByteSize.format(r.verifiedBytes, resources.configuration.locales[0]),
            r.total - r.verified,
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                null,
            ),
        )
    }
}

private const val PRIVACY_URL = "https://github.com/tomerar/keepCleanr/blob/main/docs/PRIVACY.md"
