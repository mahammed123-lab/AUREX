package com.example.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Deep-control accessibility bridge. Enables AUREX to perform actions that
 * normal apps cannot - doing them for you:
 *  - tap the WhatsApp/Telegram send button automatically after prefilling text
 *  - press back / home / recents / open quick settings
 *  - long-press or click any on-screen text/send button
 * The user enables it once in Accessibility settings.
 */
class AurexAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AurexAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null

        // Apps that refuse to pay while any 3rd-party accessibility service
        // runs. NPCI UPI apps and the common Indian banking apps.
        val BLOCKING_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",                  // Google Pay
            "com.phonepe.app",                                          // PhonePe
            "net.one97.paytm",                                          // Paytm
            "net.one97.paytm.p2b",                                       // Paytm for Business
            "in.org.npci.upiapp",                                       // BHIM
            "com.freecharge.android",                                   // Freecharge
            "com.mobikwik_new",                                         // Mobikwik
            "in.amazon.mShop.android.shopping",                         // Amazon (India build, as on this device)
            "com.amazon.mShop.android.shopping",                         // Amazon (global build, same app family)
            "com.csam.icici.bank.imobile",                              // ICICI iMobile
            "com.hdfcbank.mobilebanking",                               // HDFC
            "com.sbi.upi",                                              // SBI UPI
            "com.sbi.lotusintouch",                                     // SBI YONO
            "com.idfcfirstbank.onemap",                                 // IDFC
            "com.axis.mobile",                                          // Axis
            "com.mycompany.kotak",                                      // Kotak
            "com.canarabank.mobility"                                   // Canara Bank (installed on this device)
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AUREX accessibility connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // UPI apps (Paytm, PhonePe, GPay, bank apps - NPCI anti-fraud rules)
        // refuse payments while ANY third-party accessibility service is
        // enabled; they cannot distinguish AUREX from malware. So AUREX
        // gets out of the way the moment a payment app comes to the
        // foreground. There is no "arm" switch: keeping the service on
        // during a payment gains nothing (the payment would be blocked
        // anyway) and costs the user their payment, so it always yields.
        // PaymentModeManager then prompts the one-tap re-enable when the
        // user is back (a service cannot re-enable itself, by design).
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == "android" || pkg == packageName) return
            if (BLOCKING_PACKAGES.contains(pkg)) {
                Log.i(TAG, "Payment app ($pkg) in foreground - auto-disabling service so UPI works")
                // Let PaymentModeManager know why the service vanished so the
                // app can prompt the one-tap restore on return.
                PaymentModeManager.onServiceYielded(this)
                disableSelf()
            }
        }
    }

    override fun onInterrupt() {}

    fun clickSendButton() {
        val root = rootInActiveWindow ?: return
        val candidates = root.findAccessibilityNodeInfosByText("Send")
        val any = candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull()
        any?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun clickByText(text: String) {
        val root = rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun clickByContentDescription(desc: String) {
        val root = rootInActiveWindow ?: return
        val target = root.findAccessibilityNodeInfosByViewId(desc).firstOrNull()
            ?: root.findAccessibilityNodeInfosByText(desc).firstOrNull()
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun globalBack(): Boolean = performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun globalQuickSettings(): Boolean = performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    fun globalNotifications(): Boolean = performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun globalLockScreen(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else false
    fun globalPowerDialog(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) else false

    // ── Automation skill: find + tap a quick-settings tile by label ──
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * Toggle a quick-settings tile and verify the result by the tile switch's
     * checked state. Opens the shade, waits for the tile, clicks it, then
     * (if the click was swallowed) falls back to a coordinate tap at the
     * center of the tile. The shade is always collapsed afterwards, even on
     * failure. onDone(toggledOk, newState) fires on the main thread.
     */
    fun toggleTileByLabel(
        labels: List<String>,
        targetState: Boolean? = null,
        collapseAfter: Boolean = true,
        onDone: ((toggledOk: Boolean, newState: Boolean?) -> Unit)? = null
    ) {
        globalQuickSettings()
        val attempt = object : Runnable {
            var tries = 0
            var phase = 0            // 0=find+click, 1=verify, 2=verify tap fallback
            var before: Boolean? = null

            fun finish(ok: Boolean, newState: Boolean?) {
                if (collapseAfter) uiHandler.postDelayed({ collapseStatusBar() }, 300)
                onDone?.invoke(ok, newState)
            }

            fun giveUp() {
                // Never leave the shade hanging open after a failure.
                if (collapseAfter) collapseStatusBar()
                onDone?.invoke(false, null)
            }

            fun okState(newState: Boolean?): Boolean = when {
                targetState != null -> newState == targetState
                newState == null -> before != null      // couldn't verify; click reported success
                before == null -> true                  // couldn't read before; trust the flip
                else -> newState != before
            }

            override fun run() {
                when (phase) {
                    0 -> {
                        val switch = findTileSwitch(labels)
                        if (switch == null) {
                            if (tries < 10) { tries++; uiHandler.postDelayed(this, 380) } else giveUp()
                            return
                        }
                        before = try { switch.isChecked } catch (e: Throwable) { null }
                        // Already in the desired state - nothing to do.
                        if (targetState != null && before == targetState) {
                            finish(true, targetState)
                            return
                        }
                        val clicked = try {
                            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (e: Throwable) { false }
                        if (!clicked) {
                            if (tries < 10) { tries++; uiHandler.postDelayed(this, 380) } else giveUp()
                            return
                        }
                        phase = 1
                        uiHandler.postDelayed(this, 500)
                    }
                    1 -> {
                        val after = findTileSwitch(labels)
                        val newState = try { after?.isChecked } catch (e: Throwable) { null }
                        if (before != null && newState != null && newState == before) {
                            // Click reported success but state did not change -
                            // some OEM SystemUIs (Realme/ColorOS) swallow
                            // performAction(CLICK) on shade tiles. Tap the tile
                            // center by coordinates instead.
                            val r = Rect()
                            try {
                                after?.getBoundsInScreen(r)
                                if (!r.isEmpty) tap(r.exactCenterX(), r.exactCenterY())
                            } catch (e: Throwable) {}
                            phase = 2
                            uiHandler.postDelayed(this, 500)
                        } else {
                            finish(okState(newState), newState)
                        }
                    }
                    else -> {
                        val after = findTileSwitch(labels)
                        val newState = try { after?.isChecked } catch (e: Throwable) { null }
                        finish(okState(newState), newState)
                    }
                }
            }
        }
        uiHandler.postDelayed(attempt, 700)
    }

    // ── Quick-settings tile labels, grouped by function ──
    // Realme/ColorOS specifics: the mobile-data tile is labelled "SIM1"
    // (carrier slot), and the English (India) build spells it
    // "Aeroplane mode". Matching is a prefix match on the tile Switch's
    // content-desc ("Wi-Fi,", "SIM1, ", "Bluetooth").
    private val wifiLabels = listOf("Wi-Fi", "WiFi", "WLAN", "Wifi")
    private val btLabels = listOf("Bluetooth")
    private val airplaneLabels = listOf("Aeroplane mode", "Airplane mode", "Flight mode", "Aeroplane")
    private val dataLabels = listOf("SIM1", "SIM 1", "Mobile data", "Cellular", "Data")
    private val dndLabels = listOf("Do not disturb", "DND", "Do Not Disturb")
    private val hotspotLabels = listOf("Hotspot", "Hotspot & tethering", "Personal hotspot", "Portable hotspot", "Wi-Fi hotspot")
    private val nightLightLabels = listOf("Night light", "Night Light", "Eye comfort", "Reading mode", "Night mode", "Nit")
    private val batterySaverLabels = listOf("Battery saver", "Battery saving", "Power saving", "Super power saving", "Low power")

    fun toggleWifiTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(wifiLabels, targetState, onDone = onDone)
    fun toggleBluetoothTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(btLabels, targetState, onDone = onDone)
    fun toggleAirplaneTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(airplaneLabels, targetState, onDone = onDone)
    fun toggleDataTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(dataLabels, targetState, onDone = onDone)
    fun toggleDndTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(dndLabels, targetState, onDone = onDone)
    fun toggleHotspotTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(hotspotLabels, targetState, onDone = onDone)
    fun toggleNightLightTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(nightLightLabels, targetState, onDone = onDone)
    fun toggleBatterySaverTile(targetState: Boolean? = null, onDone: ((Boolean, Boolean?) -> Unit)? = null) =
        toggleTileByLabel(batterySaverLabels, targetState, onDone = onDone)

    /**
     * Find a quick-settings tile switch. Realme/ColorOS exposes tiles as
     * android.widget.Switch nodes (clickable + checkable) whose content-desc
     * begins with the tile name. Matching the switch itself - never the
     * nested label TextView or the "Open ... settings." chevron button -
     * guarantees we toggle the tile instead of opening its settings page.
     */
    private fun findTileSwitch(labels: List<String>): AccessibilityNodeInfo? {
        for (root in allRoots()) {
            val found = walkForTileSwitch(root, labels)
            if (found != null) return found
        }
        return null
    }

    private fun walkForTileSwitch(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        if (node.isClickable && node.isCheckable &&
            node.className?.toString() == "android.widget.Switch"
        ) {
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val matched = labels.any { label ->
                desc.startsWith(label, ignoreCase = true) ||
                    text.startsWith(label, ignoreCase = true)
            }
            if (matched) return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = walkForTileSwitch(child, labels)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Collect accessibility roots from ALL windows, not just the active one.
     * On Realme/ColorOS the SystemUI shade is often not the "active" window
     * while open, so rootInActiveWindow alone never sees the tiles - the old
     * code retried 10 times, found nothing, and silently gave up.
     */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                try { w?.root?.let { roots.add(it) } } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
        try {
            val active = rootInActiveWindow
            if (active != null) roots.add(active)
        } catch (e: Throwable) {}
        return roots
    }

    private fun collapseStatusBar() {
        // BACK alone doesn't close the shade on many OEM builds (Realme/
        // ColorOS ignores GLOBAL_ACTION_BACK on the quick-settings panel).
        // Swipe UP from the bottom of the screen — the universal "close the
        // shade" gesture — and fall back to BACK if the swipe can't be built.
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        try {
            val path = Path().apply {
                moveTo(w / 2f, h * 0.95f)   // near the bottom of the screen
                lineTo(w / 2f, h * 0.40f)     // flick up past the shade
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
                .build()
            if (dispatchGesture(gesture, null, null)) return
        } catch (e: Throwable) {
            // fall through to BACK
        }
        performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun tap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun tapBounds(bounds: Rect) {
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun performGlobalActionCompat(action: Int): Boolean {
        return try {
            performGlobalAction(action)
        } catch (e: Throwable) {
            false
        }
    }

    private val TAG = "AurexAccessibility"
}
