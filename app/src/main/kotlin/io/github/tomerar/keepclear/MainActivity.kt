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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        // PR H3 (owner brief H3 item 6): the paper splash on every Android version, then Theme.KeepClear.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // SEC-7: ignore touches while another app's overlay covers KeepClear.
        window.decorView.filterTouchesWhenObscured = true
        if (savedInstanceState == null) {
            handleRecheck(intent)
            handleShortcut(intent)
        }
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
                // G4a: the read-only gallery comparison; its coordinator is created only when a run starts (C220).
                val galleryVm: GalleryViewModel = viewModel()
                // G4b (GAL-21, E-G09): every scan (also the one after a clean) updates what the groups may show.
                LaunchedEffect(state.summary) { galleryVm.controller.sync(state.summary) }
                var notificationsOff by remember { mutableStateOf(!MoveNotifications.allowed(this)) }
                var explainNotifications by remember { mutableStateOf(false) }
                // ONB-1: the intro on first launch, or when Settings asks for it again.
                var showIntro by remember { mutableStateOf(!Onboarding.seen(this)) }
                if (showIntro) {
                    IntroScreen(
                        onDone = {
                            Onboarding.setSeen(this, true)
                            showIntro = false
                        },
                        // Owner brief H2 item 1: "New phone? Restore from a backup" opens the restore flow.
                        onRestore = {
                            Onboarding.setSeen(this, true)
                            showIntro = false
                            vm.selectTab(Tab.BACKUP)
                            moveVm.beginRestore()
                        },
                    )
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
                // Owner brief H4 item 5: the newest backup was stopped by the phone (Settings › Troubleshooting).
                val stoppedByPhone by remember {
                    moveVm.state.map { st ->
                        st.jobs.maxByOrNull { it.job.createdAt }?.job?.pauseReason ==
                            io.github.tomerar.keepclear.engine.move.PauseReason.STOPPED_BY_PHONE
                    }.distinctUntilChanged()
                }.collectAsStateWithLifecycle(false)
                val battery = remember { batteryActions() }
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
                var checkReminderOn by androidx.compose.runtime.remember {
                    mutableStateOf(app.checkReminderPrefs.enabled)
                }
                val keepOriginalsInTrash by app.freeSettings.state.collectAsStateWithLifecycle()
                // PR H2b: "Swap the swipe directions" and the one-time hint, read by the swipe screen.
                val swipeSwapped by app.swipeSettings.swapped.collectAsStateWithLifecycle()
                val swipeHintSeen by app.swipeSettings.hintSeen.collectAsStateWithLifecycle()
                // D6: forgetting the Nextcloud revokes its app password on the server first; it runs in the app's
                // scope (survives rotation, PR #44 review) and its state lives in the application.
                val nextcloudForget by app.nextcloudForget.collectAsStateWithLifecycle()
                // PR H3: history, the crash card, "What's new" and the report sheet.
                val history by app.history.state.collectAsStateWithLifecycle()
                val crash by app.crashNotice.collectAsStateWithLifecycle()
                val whatsNew by app.whatsNew.collectAsStateWithLifecycle()
                var report by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                var reportPreparing by remember { mutableStateOf(false) }
                var reportNoEmail by remember { mutableStateOf(false) }
                var reportCopied by remember { mutableStateOf(false) }
                report?.let { body ->
                    ReportSheet(
                        ReportUi(
                            body = body,
                            preparing = reportPreparing,
                            noEmailApp = reportNoEmail,
                            copied = reportCopied,
                            onSend = { text, attach ->
                                reportPreparing = attach
                                lifecycleScope.launch {
                                    val uri = if (attach) diagnosticsUri(app) else null
                                    reportPreparing = false
                                    val sent = sendReport(text, uri)
                                    reportNoEmail = !sent
                                    if (sent) report = null
                                }
                            },
                            onCopy = { text ->
                                DeviceCheckShare.copy(this@MainActivity, text)
                                reportCopied = true
                            },
                            onDismiss = {
                                report = null
                                reportNoEmail = false
                                reportCopied = false
                            },
                        ),
                    )
                }
                whatsNew?.let { entry -> WhatsNewSheet(entry) { app.whatsNew.value = null } }
                // PR H5: test mode's „לייצא את הרשימה”: Android's "create document" screen, then the CSV is written
                // there (off the main thread); nothing else is written and nothing is read back.
                // Review of PR #61: the rows wait in the application, so a rotation while Android's screen is open
                // doesn't lose them; if they are gone (the process was ended), nothing is written and it says so.
                val exportSaved = remember { mutableStateOf<Boolean?>(null) }
                val exportLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TestExport.MIME)) { uri ->
                        val pending = app.pendingExport.take()
                        // Written only while test mode is still on, only to the place the user just chose.
                        val rows = TestExport.toWrite(uri != null, app.testMode.isOn, pending)
                        if (uri != null && rows == null) exportSaved.value = false
                        if (uri != null && rows != null) {
                            lifecycleScope.launch {
                                exportSaved.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                                            out.write(TestExport.csv(rows).toByteArray(Charsets.UTF_8))
                                        }
                                    }.isSuccess
                                }
                            }
                        }
                    }
                val testExporter = remember(exportLauncher) {
                    TestExporter(testModeOn = { app.testMode.isOn }, saved = exportSaved) { rows ->
                        app.pendingExport.put(rows)
                        exportSaved.value = null
                        runCatching { exportLauncher.launch(TestExport.fileName(System.currentTimeMillis())) }
                            .onFailure { exportSaved.value = false }
                    }
                }
                CompositionLocalProvider(
                    LocalTestExporter provides testExporter,
                    LocalThumbnailLoader provides thumbnails,
                    LocalAppIcons provides appIcons,
                    LocalSwipePrefs provides SwipePrefs(
                        swapped = swipeSwapped,
                        hintSeen = swipeHintSeen,
                        onHintSeen = app.swipeSettings::markHintSeen,
                    ),
                ) {
                    AppScreens(
                        gallery = GalleryHost(
                            flow = galleryVm.state,
                            actions = GalleryActions(
                                onStart = {
                                    galleryVm.controller.start(
                                        vm.state.value.summary,
                                        (vm.state.value.scan as? ScanState.Done)?.complete == true,
                                    )
                                },
                                onStop = galleryVm.controller::stop,
                                onContinue = galleryVm.controller::continueRun,
                                onAllowAccess = { mediaLauncher.launch(MediaAccess.requestPermissions()) },
                                onCheckAccess = {
                                    galleryVm.controller.checkScope()
                                    galleryVm.controller.refresh(vm.state.value.summary)
                                },
                                onKeepThisTime = galleryVm.controller::keepThisTime,
                                onDontOffer = { path ->
                                    vm.keep(path)
                                    galleryVm.controller.dontOffer(path)
                                },
                                onToggleChoice = galleryVm.controller::toggleChoice,
                            ),
                            disposal = galleryVm.controller::disposal,
                            onUnchoose = galleryVm.controller::unchoose,
                            onChoicesDone = galleryVm.controller::clearChoices,
                        ),
                        // G4b: the comparison's REVIEW choices use Home's clean path, its Undo and the Trash.
                        galleryReview = GalleryReviewActions(
                            onDispose = vm::cleanGallery,
                            onUndo = vm::undo,
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
                        ),
                        settingsActions = SettingsActions(
                            networkHost = networkHost,
                            choices = choices,
                            summary = state.summary,
                            onRemoveKept = vm::removeKept,
                            onRemoveJunk = vm::removeJunk,
                            keepOriginalsInTrash = keepOriginalsInTrash,
                            onKeepOriginalsInTrash = app.freeSettings::setKeepInTrash,
                            swipeSwapped = swipeSwapped,
                            onSwipeSwapped = app.swipeSettings::setSwapped,
                            reminderOn = reminderOn,
                            onReminder = { on ->
                                app.reminderPrefs.enabled = on
                                reminderOn = on
                                SpaceReminder.schedule(this@MainActivity, on)
                            },
                            checkReminderOn = checkReminderOn,
                            onCheckReminder = { on ->
                                app.checkReminderPrefs.enabled = on
                                checkReminderOn = on
                                CheckReminder.schedule(this@MainActivity, on)
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
                            onOpenChannel = { id ->
                                runCatching {
                                    startActivity(NotificationChannels.settingsIntent(this@MainActivity, id))
                                }
                            },
                            troubleshooting = TroubleshootingUi(
                                lastRunAt = app.moveController.runLog.lastRunAt,
                                stoppedByPhone = stoppedByPhone,
                                battery = battery,
                            ),
                            onLanguage = ::setLanguage,
                            onSystemLanguage = if (AppLanguage.systemKeeps()) ::openSystemLanguage else null,
                            onReplayIntro = { showIntro = true },
                            onOpenPrivacy = ::openPrivacy,
                            version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                            trashBytes = state.trash.bytes,
                            showDeviceCheck = DeviceCheckGate.visible(),
                            onOpenDeviceCheck = {
                                deviceCheckVm.runIfEmpty()
                                vm.open(Screen.DeviceCheck)
                            },
                            onReport = {
                                reportNoEmail = false
                                reportCopied = false
                                report = reportBody(app, state, testModeOn)
                            },
                        ),
                        info = InfoUi(
                            history = history,
                            about = AboutUi(
                                version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                                versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode,
                                onOpenSource = { openLink(SOURCE_URL) },
                                onOpenPrivacy = ::openPrivacy,
                                onTranslate = { openLink(TRANSLATE_URL) },
                            ),
                        ),
                        deviceCheck = DeviceCheckUi(
                            enabled = DeviceCheckGate.visible(),
                            state = deviceCheckState,
                            onRun = deviceCheckVm::run,
                            onCopy = { DeviceCheckShare.copy(this@MainActivity, it) },
                            onShare = { DeviceCheckShare.share(this@MainActivity, it) },
                        ),
                        previewActions = PreviewActions(
                            onKeep = vm::keep,
                            onAlwaysJunk = vm::alwaysJunk,
                            onUndoKeep = vm::undoKeep,
                            onKeepNoticeDone = vm::dismissKeepNotice,
                        ),
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
                            // Owner brief H4 item 8: Android 15+ archive, where the app's store supports it.
                            canArchive = { pkg -> AppArchive.canArchive(this@MainActivity, pkg) },
                            onArchive = { pkg ->
                                // SAFETY-9: never in trial mode (the screen explains; this is the backstop).
                                val archives = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                                if (!app.testMode.isOn && archives) {
                                    runCatching { AppArchive.request(this@MainActivity, pkg) }
                                }
                            },
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
                            onOpenHelp = { vm.open(Screen.Help) },
                        ),
                        selectionActions = SelectionActions(
                            onSelect = vm::select,
                            onSwipe = vm::swipe,
                            onSwipeEnd = vm::endSwipe,
                        ),
                        notificationsOff = notificationsOff,
                        moveActions = MoveActions(
                            onPickTarget = { items ->
                                moveVm.pendingItems = items
                                // Owner brief H1 item 3: Android's picker opens at the plugged-in drive.
                                treeLauncher.launch(moveVm.state.value.drive?.let { app.drives.pickerStart(it) })
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
                                // H1c: the settings screen that grants it; the `play` build can't have it.
                                onAllFilesAccess = if (BuildConfig.FLAVOR == "foss") {
                                    {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                "package:$packageName".toUri(),
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onChooseAgain = moveVm::chooseAgain,
                                thumbnail = moveVm::restoreThumbnail,
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
                            battery = battery,
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
                                // Owner brief H3 item 3: once per unexpected exit; sharing is the user's choice.
                                topNotice = crash?.let { record ->
                                    {
                                        CrashCard(
                                            onShare = {
                                                shareCrash(record)
                                                app.crashNotice.value = null
                                            },
                                            onDismiss = { app.crashNotice.value = null },
                                        )
                                    }
                                },
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

    /** Owner brief H4 item 5: Android's battery list and this phone maker's steps (screens only). */
    private fun batteryActions(): BatteryActions {
        val steps = BatterySteps.forMaker(Build.MANUFACTURER)
        return BatteryActions(
            steps = steps,
            onAllowBackground = { runCatching { startActivity(BatterySteps.settingsIntent()) } },
            onOpenGuide = { runCatching { startActivity(BatterySteps.guideIntent(steps)) } },
        )
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

    /**
     * Owner brief H4 item 7: the in-app language. Android 13+ keeps it per app (LocaleManager); on Android 11–12
     * KeepClear keeps it itself and shows its screens again in it ([AppLanguage]).
     */
    private fun setLanguage(tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (tag == null) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(tag)
            }
            getSystemService(android.app.LocaleManager::class.java).applicationLocales = locales
        } else {
            AppLanguage.store(this, tag)
            NotificationChannels.registerAll(AppLanguage.wrap(applicationContext))
            recreate()
        }
    }

    private fun currentLanguage(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Languages.chipTag(getSystemService(android.app.LocaleManager::class.java).applicationLocales.get(0)?.language)
    } else {
        Languages.chipTag(AppLanguage.stored(this))
    }

    /** Owner brief H4 item 7: Android 13+'s own per-app language page. */
    private fun openSystemLanguage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { startActivity(AppLanguage.systemSettingsIntent(this)) }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
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

    /**
     * GAL-1 (C236): the comparison is foreground only. Leaving the app (Home, the lock screen, another app) stops a
     * running one at its next item; it stays stopped until the user taps Continue. A rotation is not leaving.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as KeepClearApplication).stopGalleryInBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRecheck(intent)
        handleShortcut(intent)
    }

    /** Owner brief H3 item 7: a launcher shortcut opens its screen, nothing else ([ShortcutNavigator]). */
    private fun handleShortcut(intent: Intent?) {
        val route = ShortcutRoute.of(intent) ?: return
        Shortcuts.handle(
            route,
            Shortcuts.navigator(androidx.lifecycle.ViewModelProvider(this)[HomeViewModel::class.java]),
        )
    }

    /** Owner brief H3 item 2: the email's text, from totals and counts only ([ReportFacts]). */
    private fun reportBody(app: KeepClearApplication, state: HomeUiState, testMode: Boolean): String {
        val facts = ReportFacts.of(
            home = state,
            backups = androidx.lifecycle.ViewModelProvider(this)[MoveViewModel::class.java].state.value.jobs.size,
            device = ReportFacts.Device(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            app = io.github.tomerar.keepclear.devcheck.AppFacts(
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                packageManager.getPackageInfo(packageName, 0).longVersionCode,
                BuildConfig.FLAVOR,
                BuildConfig.BUILD_TYPE,
            ),
            language = resources.configuration.locales[0].language,
            testMode = testMode,
        )
        return ProblemReport.body(getString(R.string.report_prompt), facts)
    }

    /** The diagnostics file (device check, no paths), or null if it couldn't be made (the email goes without it). */
    private suspend fun diagnosticsUri(app: KeepClearApplication): android.net.Uri? = try {
        withContext(Dispatchers.IO) { ProblemReport.writeDiagnostics(app) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("KeepClear", "diagnostics file not made: ${e.javaClass.simpleName}")
        null
    }

    /** Opens the email app; false when the phone has none. KeepClear itself sends nothing. */
    private fun sendReport(body: String, attachment: android.net.Uri?): Boolean {
        val address = getString(R.string.report_email)
        val intent = ProblemReport.intent(address, getString(R.string.report_subject), body, attachment)
        return try {
            startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
    }

    /** Owner brief H3 item 3: the redacted report through the share sheet; the user picks where it goes. */
    private fun shareCrash(record: ExitRecord) {
        val text = CrashText.report(
            record,
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            packageManager.getPackageInfo(packageName, 0).longVersionCode,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.SDK_INT,
        )
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_title))
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    /**
     * Owner brief H1 item 11: the "check the backup again" reminder was tapped: the Backup tab offers the check. The
     * extra only names which backup to offer (the check itself reads only, and waits for the user's tap).
     */
    private fun handleRecheck(intent: Intent?) {
        val jobId = intent?.getStringExtra(CheckReminder.EXTRA_RECHECK) ?: return
        intent.removeExtra(CheckReminder.EXTRA_RECHECK)
        val provider = androidx.lifecycle.ViewModelProvider(this)
        provider[HomeViewModel::class.java].selectTab(Tab.BACKUP)
        provider[MoveViewModel::class.java].offerRecheck(jobId)
    }

    /** MOVE-13: share a receipt as plain text. */
    private fun shareReceipt(view: JobView) {
        val r = view.receipt
        val text = getString(
            R.string.move_receipt_text,
            r.targetLabel,
            HumanLocation.folder(r.rootRel),
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

/** PR H3: About's links (opened in the browser; KeepClear itself has no internet for them). */
private const val SOURCE_URL = "https://github.com/tomerar/keepCleanr"
private const val TRANSLATE_URL = "https://github.com/tomerar/keepCleanr/blob/main/CONTRIBUTING.md#translations"
