package app.semplicephone

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.semplicephone.data.AppCatalog
import app.semplicephone.data.AppEntry
import app.semplicephone.data.ConfigRepository
import app.semplicephone.data.Contacts
import app.semplicephone.launch.Permissions
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileIcon
import app.semplicephone.model.IconKind
import app.semplicephone.model.TileType
import app.semplicephone.security.PinStore
import app.semplicephone.sos.SosScreen
import app.semplicephone.reminders.ReminderScheduler
import app.semplicephone.ui.settings.RemindersEditor
import app.semplicephone.ui.settings.RemoteScreen
import app.semplicephone.remote.RemoteClient
import app.semplicephone.ui.settings.ProtectionScreen
import app.semplicephone.admin.Protection as DeviceProtection
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.ui.HomeScreen
import app.semplicephone.ui.SempliceTheme
import app.semplicephone.ui.settings.LayoutEditor
import app.semplicephone.ui.settings.PinMode
import app.semplicephone.ui.settings.PinScreen
import app.semplicephone.ui.settings.SosEditor
import app.semplicephone.ui.settings.TileEditor
import app.semplicephone.ui.settings.PhoneEditor
import app.semplicephone.launch.ShortcutPins
import app.semplicephone.phone.PhoneGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Navigazione minima a stati: niente back stack di sistema, ogni schermata sa dove torna. */
sealed interface Screen {
    data object Home : Screen
    data class Pin(val mode: PinMode, val then: Screen) : Screen
    data object Layout : Screen
    data class EditTile(val pageIndex: Int, val tile: Tile, val isNew: Boolean) : Screen
    data object Sos : Screen
    data object Protection : Screen
    data object SosCountdown : Screen
    data object Reminders : Screen
    data object Remote : Screen
    data object Phone : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var repo: ConfigRepository
    private lateinit var launcher: TileLauncher
    private lateinit var pins: PinStore
    private lateinit var catalog: AppCatalog
    private lateinit var contacts: Contacts
    private lateinit var protection: DeviceProtection
    private lateinit var scheduler: ReminderScheduler

    /** Picker contatti o dialog permessi aperti dall'editor: non richiudere le impostazioni in onStop. */
    private var externalActivity = false
    private var permissionsTick by mutableIntStateOf(0)

    private val homePressCount = mutableIntStateOf(0)
    private val needsHomeRole = mutableStateOf(false)

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var draft by mutableStateOf<LauncherConfig?>(null)
    private var editPage by mutableIntStateOf(0)
    private var saveError by mutableStateOf<String?>(null)
    private var apps by mutableStateOf<List<AppEntry>>(emptyList())

    private val requestHomeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshHomeRole() }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        repo = ConfigRepository.get(applicationContext)
        contacts = Contacts(applicationContext)
        protection = DeviceProtection(applicationContext)
        scheduler = ReminderScheduler(applicationContext)
        launcher = TileLauncher(this, contacts, onSos = ::onSosRequested)
        pins = PinStore(applicationContext)
        catalog = AppCatalog(applicationContext)
        apps = catalog.roles()
        lifecycleScope.launch {
            repo.load()
            // Riallinea gli allarmi a ogni avvio (es. dopo un aggiornamento o un restore).
            scheduler.scheduleAll(repo.config.value)
            RemoteClient.schedule(applicationContext)
        }

        onBackPressedDispatcher.addCallback(this) { goBack() }

        setContent {
            val config by repo.config.collectAsStateWithLifecycle()
            SempliceTheme {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        config = config,
                        launcher = launcher,
                        homePressCount = homePressCount.intValue,
                        showSetHomeBanner = needsHomeRole.value,
                        onSetHome = ::askHomeRole,
                        onOpenSettings = ::openSettings,
                    )
                    is Screen.Pin -> PinScreen(
                        store = pins,
                        mode = s.mode,
                        onSuccess = { navigate(s.then) },
                        // draft esiste solo dopo un PIN corretto: annullare un cambio PIN torna all'editor.
                        onCancel = { if (draft != null) screen = Screen.Layout else closeSettings() },
                    )
                    Screen.Layout -> draft?.let { d ->
                        LayoutEditor(
                            draft = d,
                            pageIndex = editPage.coerceIn(0, d.pages.lastIndex),
                            error = saveError,
                            onPageIndex = { editPage = it },
                            onDraftChange = { draft = it },
                            onEditTile = { p, t -> screen = Screen.EditTile(p, t, isNew = false) },
                            onAddTile = { p, c, r -> screen = Screen.EditTile(p, newTile(c, r), isNew = true) },
                            onEditSos = { screen = Screen.Sos },
                            onChangePin = { screen = Screen.Pin(PinMode.CREATE, then = Screen.Layout) },
                            onResetDefaults = { lifecycleScope.launch { draft = repo.defaultConfig(); editPage = 0 } },
                            onSave = ::saveDraft,
                            onExit = ::closeSettings,
                            missingPermissions = remember(d, permissionsTick) { Permissions.missing(this@MainActivity, d) },
                            onRequestPermissions = { askPermissions(d) },
                            missingSpecial = remember(d, permissionsTick) { Permissions.missingSpecial(this@MainActivity, d) },
                            onOpenSpecial = { Permissions.openSpecial(this@MainActivity, it) },
                            onExternalStart = { externalActivity = true },
                            restrictionHelp = remember(d, permissionsTick) {
                                app.semplicephone.launch.Restrictions.likelyRestricted(this@MainActivity) && (
                                    Permissions.missing(this@MainActivity, d).any { app.semplicephone.launch.Restrictions.isRestrictedPermission(it.permission) } ||
                                        Permissions.missingSpecial(this@MainActivity, d).any { it.key == "badges" })
                            },
                            extraSections = {
                                OutlinedButton(onClick = { screen = Screen.Phone }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(stringResource(R.string.layout_phone_settings))
                                }
                                OutlinedButton(onClick = { screen = Screen.Reminders }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(stringResource(R.string.layout_reminders, d.reminders.size))
                                }
                                OutlinedButton(onClick = { screen = Screen.Remote }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(stringResource(R.string.layout_remote))
                                }
                                OutlinedButton(onClick = { screen = Screen.Protection }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(stringResource(R.string.layout_protection))
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    // Lingua solo per SemplicePhone (pagina di sistema «Lingua dell'app»).
                                    OutlinedButton(
                                        onClick = {
                                            externalActivity = true
                                            runCatching {
                                                startActivity(
                                                    android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:$packageName")),
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    ) { Text(stringResource(R.string.layout_language)) }
                                    Text(stringResource(R.string.language_hint), fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFF4A4A4A))
                                }
                            },
                        )
                    }
                    is Screen.EditTile -> draft?.let { d ->
                        val page = d.pages[s.pageIndex]
                        TileEditor(
                            initial = s.tile,
                            page = page,
                            isNew = s.isNew,
                            sos = d.sos,
                            sosCountElsewhere = d.pages.sumOf { p -> p.tiles.count { it.type == TileType.SOS && it.id != s.tile.id } },
                            apps = apps,
                            launcher = launcher,
                            onSave = { t -> draft = d.withTile(s.pageIndex, t); screen = Screen.Layout },
                            onDelete = { draft = d.withoutTile(s.pageIndex, s.tile.id); screen = Screen.Layout },
                            onCancel = { screen = Screen.Layout },
                            onExternalStart = { externalActivity = true },
                        )
                    }
                    Screen.Reminders -> draft?.let { d ->
                        RemindersEditor(
                            initial = d.reminders,
                            scheduler = scheduler,
                            onSave = { draft = d.copy(reminders = it); screen = Screen.Layout },
                            onCancel = { screen = Screen.Layout },
                            onExternalStart = { externalActivity = true },
                        )
                    }
                    Screen.Remote -> RemoteScreen(onBack = { screen = Screen.Layout })
                    Screen.Phone -> draft?.let { d ->
                        PhoneEditor(
                            initial = d.phone,
                            hasSosNumbers = d.sos.numbers.isNotEmpty(),
                            onSave = { draft = d.copy(phone = it); screen = Screen.Layout },
                            onCancel = { screen = Screen.Layout },
                        )
                    }
                    Screen.Protection -> ProtectionScreen(protection, onBack = { screen = Screen.Layout })
                    Screen.SosCountdown -> SosScreen(
                        cfg = config.sos,
                        launcher = launcher,
                        contactNames = { contacts.displayName(it) },
                        onDone = { screen = Screen.Home },
                    )
                    Screen.Sos -> draft?.let { d ->
                        SosEditor(
                            initial = d.sos,
                            onSave = { draft = d.copy(sos = it); screen = Screen.Layout },
                            onCancel = { screen = Screen.Layout },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        externalActivity = false
        permissionsTick++
        refreshHomeRole()
        lifecycleScope.launch(Dispatchers.IO) { runCatching { PhoneGuard.checkAll(applicationContext) } }
    }

    /** Uscendo dall'app (schermo spento, altra app) le impostazioni si richiudono: serve di nuovo il PIN. */
    override fun onStop() {
        super.onStop()
        // L'SOS in corso non si interrompe se lo schermo si spegne o parte la chiamata.
        if (screen != Screen.Home && screen != Screen.SosCountdown && !externalActivity) closeSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (screen != Screen.Home) closeSettings() else homePressCount.intValue++
        }
    }

    private fun openSettings() {
        screen = Screen.Pin(if (pins.isSet()) PinMode.ENTER else PinMode.CREATE, then = Screen.Layout)
    }

    private fun navigate(target: Screen) {
        if (target == Screen.Layout && draft == null) {
            draft = repo.config.value
            editPage = 0
            saveError = null
            lifecycleScope.launch { apps = withContext(Dispatchers.IO) { catalog.load() } }
        }
        screen = target
    }

    private fun closeSettings() {
        draft = null
        saveError = null
        screen = Screen.Home
    }

    private fun goBack() {
        screen = when (screen) {
            Screen.Home -> Screen.Home
            is Screen.Pin -> if (draft != null) Screen.Layout else { closeSettings(); Screen.Home }
            Screen.Layout -> { closeSettings(); Screen.Home }
            is Screen.EditTile, Screen.Sos, Screen.Protection, Screen.Reminders, Screen.Remote, Screen.Phone -> Screen.Layout
            Screen.SosCountdown -> Screen.Home
        }
    }

    private fun saveDraft() {
        val d = draft ?: return
        lifecycleScope.launch {
            runCatching { repo.save(d) }
                .onSuccess {
                    scheduler.scheduleAll(d)
                    withContext(Dispatchers.IO) {
                        ShortcutPins.sync(applicationContext, d)
                        PhoneGuard.checkAll(applicationContext)
                    }
                    askPermissions(d)
                    closeSettings()
                }
                .onFailure { saveError = it.message ?: it.javaClass.simpleName }
        }
    }

    private fun askPermissions(cfg: LauncherConfig) {
        protection.grantRuntimePermissions(Permissions.required(this, cfg).map { it.permission })
        val missing = Permissions.missing(this, cfg).map { it.permission }.distinct()
        if (missing.isNotEmpty()) {
            externalActivity = true
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun onSosRequested() {
        if (repo.config.value.sos.numbers.isNotEmpty()) screen = Screen.SosCountdown
    }

    private fun newTile(col: Int, row: Int) = Tile(
        id = "t" + System.currentTimeMillis().toString(36),
        col = col,
        row = row,
        type = TileType.APP,
        target = "@dialer",
        label = getString(R.string.label_phone),
        icon = TileIcon(IconKind.AUTO),
        color = "#1F4FA8",
    )

    private fun roleManager(): RoleManager = getSystemService(RoleManager::class.java)

    private fun refreshHomeRole() {
        val rm = roleManager()
        needsHomeRole.value = rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun askHomeRole() {
        val rm = roleManager()
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            requestHomeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        }
    }
}

private fun LauncherConfig.withTile(pageIndex: Int, tile: Tile): LauncherConfig = copy(
    pages = pages.mapIndexed { i, p ->
        if (i != pageIndex) p
        else p.copy(tiles = if (p.tiles.any { it.id == tile.id }) p.tiles.map { if (it.id == tile.id) tile else it } else p.tiles + tile)
    },
)

private fun LauncherConfig.withoutTile(pageIndex: Int, id: String): LauncherConfig = copy(
    pages = pages.mapIndexed { i, p -> if (i != pageIndex) p else p.copy(tiles = p.tiles.filterNot { it.id == id }) },
)
