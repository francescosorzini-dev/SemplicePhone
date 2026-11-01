package app.semplicephone.admin

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

    enum class Item(val label: String, val detail: String) {
        PIN_HOME("Fissa SemplicePhone come Home", "Il tasto Home non chiede più quale launcher usare"),
        BLOCK_UNINSTALL_SELF("Impedisci di disinstallare SemplicePhone", ""),
        STATUS_BAR("Blocca la tendina delle notifiche", "La barra resta visibile (ora, batteria) ma non si apre"),
        NO_UNINSTALL_APPS("Impedisci di disinstallare app", ""),
        NO_INSTALL_APPS("Impedisci di installare app", "Anche dal Play Store"),
        NO_CONFIG_WIFI("Blocca modifiche Wi-Fi", ""),
        NO_FACTORY_RESET("Impedisci il ripristino dati di fabbrica", "Dalle impostazioni di sistema"),
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
            Item.PIN_HOME, Item.STATUS_BAR -> prefs.getBoolean(item.name, false)
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
        }
        prefs.edit().putBoolean(item.name, on).apply()
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
