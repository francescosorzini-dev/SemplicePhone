package app.semplicephone

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.semplicephone.data.ConfigRepository
import app.semplicephone.launch.TileLauncher
import app.semplicephone.ui.HomeScreen
import app.semplicephone.ui.SempliceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repo: ConfigRepository
    private lateinit var launcher: TileLauncher

    private val homePressCount = mutableIntStateOf(0)
    private val needsHomeRole = mutableStateOf(false)

    private val requestHomeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshHomeRole() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        repo = ConfigRepository(applicationContext)
        launcher = TileLauncher(this)
        lifecycleScope.launch { repo.load() }

        // Il launcher non ha un "indietro": evita uscite accidentali verso schermate vuote.
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            val config by repo.config.collectAsStateWithLifecycle()
            SempliceTheme {
                HomeScreen(
                    config = config,
                    launcher = launcher,
                    homePressCount = homePressCount.intValue,
                    showSetHomeBanner = needsHomeRole.value,
                    onSetHome = ::askHomeRole,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressCount.intValue++
    }

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
