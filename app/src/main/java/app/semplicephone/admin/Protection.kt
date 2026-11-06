package app.semplicephone.admin

import app.semplicephone.R
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import app.semplicephone.MainActivity

/**
 * Protezioni disponibili solo con SemplicePhone Device Owner. Ogni protezione è reversibile
 * dall'editor, e "Rimuovi Device Owner" le toglie tutte prima di rinunciare al ruolo.
 */
class Protection(private val context: Context) {

    enum class Item(val label: Int, val detail: Int = 0) {
        PIN_HOME(R.string.prot_pin_home, R.string.prot_pin_home_detail),
        BLOCK_UNINSTALL_SELF(R.string.prot_block_uninstall_self),
        STATUS_BAR(R.string.prot_status_bar, R.string.prot_status_bar_detail),
        NO_UNINSTALL_APPS(R.string.prot_no_uninstall),
        NO_INSTALL_APPS(R.string.prot_no_install, R.string.prot_no_install_detail),
        NO_CONFIG_WIFI(R.string.prot_no_wifi),
        NO_FACTORY_RESET(R.string.prot_no_reset, R.string.prot_no_reset_detail),
        NO_LOCK_SCREEN(R.string.prot_no_lock, R.string.prot_no_lock_detail),
    }

    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val prefs = context.getSharedPreferences("protection", Context.MODE_PRIVATE)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    fun isOn(item: Item): Boolean {
        if (!isDeviceOwner) return false
        return when (item) {
            Item.BLOCK_UNINSTALL_SELF -> dpm.isUninstallBlocked(admin, context.packageName)
            Item.NO_UNINSTALL_APPS -> restriction(UserManager.DISALLOW_UNINSTALL_APPS)
            Item.NO_INSTALL_APPS -> restriction(UserManager.DISALLOW_INSTALL_APPS)
            Item.NO_CONFIG_WIFI -> restriction(UserManager.DISALLOW_CONFIG_WIFI)
            Item.NO_FACTORY_RESET -> restriction(UserManager.DISALLOW_FACTORY_RESET)
            // Nessun getter di sistema per questi due: stato ricordato localmente.
            Item.PIN_HOME, Item.STATUS_BAR, Item.NO_LOCK_SCREEN -> prefs.getBoolean(item.name, false)
        }
    }

    fun set(item: Item, on: Boolean) {
        if (!isDeviceOwner) return
        when (item) {
            Item.PIN_HOME -> {
                if (on) {
                    val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addCategory(Intent.CATEGORY_DEFAULT)
                    }
                    dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, MainActivity::class.java))
                } else {
                    dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                }
            }
            Item.BLOCK_UNINSTALL_SELF -> dpm.setUninstallBlocked(admin, context.packageName, on)
            Item.STATUS_BAR -> dpm.setStatusBarDisabled(admin, on)
            Item.NO_UNINSTALL_APPS -> setRestriction(UserManager.DISALLOW_UNINSTALL_APPS, on)
            Item.NO_INSTALL_APPS -> setRestriction(UserManager.DISALLOW_INSTALL_APPS, on)
            Item.NO_CONFIG_WIFI -> setRestriction(UserManager.DISALLOW_CONFIG_WIFI, on)
            Item.NO_FACTORY_RESET -> setRestriction(UserManager.DISALLOW_FACTORY_RESET, on)
            // Niente schermata di blocco (vale solo se sul telefono non c'è PIN, sequenza o password).
            Item.NO_LOCK_SCREEN -> dpm.setKeyguardDisabled(admin, on)
        }
        prefs.edit().putBoolean(item.name, on).apply()
    }

    /**
     * Con Device Owner concede da sé i permessi runtime: così una config arrivata da remoto
     * (es. nuovo tile di chiamata) funziona senza che nessuno tocchi il telefono.
     */
    fun grantRuntimePermissions(permissions: Collection<String>) {
        if (!isDeviceOwner) return
        permissions.forEach { p ->
            runCatching {
                dpm.setPermissionGrantState(admin, context.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    /** Toglie tutte le protezioni e rinuncia al ruolo di Device Owner (irreversibile senza adb). */
    fun releaseDeviceOwner() {
        if (!isDeviceOwner) return
        Item.entries.forEach { set(it, false) }
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private fun restriction(key: String) = dpm.getUserRestrictions(admin).getBoolean(key, false)

    private fun setRestriction(key: String, on: Boolean) {
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }

    companion object {
        const val ADB_COMMAND = "adb shell dpm set-device-owner app.semplicephone/.admin.AdminReceiver"
    }
}
