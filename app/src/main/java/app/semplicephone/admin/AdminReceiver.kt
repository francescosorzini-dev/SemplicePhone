package app.semplicephone.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Receiver di amministrazione. Serve solo se SemplicePhone viene impostato come Device Owner:
 * adb shell dpm set-device-owner app.semplicephone/.admin.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver()
