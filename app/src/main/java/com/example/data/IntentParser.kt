package com.example.data

sealed class UserIntent(val category: String) {
    data class Alarm(val hour: Int, val minute: Int, val label: String) : UserIntent("alarm")
    data class Music(val searchQuery: String) : UserIntent("music")
    data class Search(val query: String, val provider: SearchProvider) : UserIntent("search") {
        enum class SearchProvider { GOOGLE, YOUTUBE }
    }
    data class SystemControl(val action: String) : UserIntent("system")
    data class AppLaunch(val appName: String) : UserIntent("app_launch")
    object General : UserIntent("general")
}

data class ParsedIntentResult(
    val intent: UserIntent,
    val category: String, // 'alarm', 'music', 'search', 'system', 'app_launch', 'general'
    val actionCommand: String?,
    val localizedResponse: String?
)

object IntentParser {

    /**
     * Classifies user voice or text queries into specific categories ('alarm', 'music', 'search', 'system', 'app_launch', 'general').
     * Supports both English and Telugu (script & transliterated).
     */
    fun parse(query: String, salutation: String = "sir"): ParsedIntentResult {
        val trimmed = query.trim()
        val lower = trimmed.lowercase()
        val isTeluguScript = query.any { it in 'ఀ'..'౿' }

        // 1. ALARM CATEGORY
        val isAlarmKeyword = lower.contains("alarm") || lower.contains("అలారం") || lower.contains("wake me up")
        if (isAlarmKeyword) {
            val hasSetAction = lower.contains("set") || lower.contains("pettu") || lower.contains("పెట్టు") ||
                    lower.contains("wake") || lower.contains("ki") || lower.contains("కి") ||
                    lower.contains("at") || lower.contains("for") || lower.contains(":") ||
                    lower.contains("am") || lower.contains("pm")

            if (hasSetAction) {
                val timeRegex = Regex("(\\d{1,2})[:\\.](\\d{2})\\s*(am|pm)?")
                val timeMatch = timeRegex.find(lower)
                var hour = 7
                var minute = 0
                var amPm = "am"
                if (timeMatch != null) {
                    hour = timeMatch.groupValues[1].toInt()
                    minute = timeMatch.groupValues[2].toInt()
                    amPm = timeMatch.groupValues[3]?.lowercase() ?: "am"
                } else {
                    val simpleTime = Regex("(\\d{1,2})\\s*(am|pm)").find(lower)
                    if (simpleTime != null) {
                        hour = simpleTime.groupValues[1].toInt()
                        amPm = simpleTime.groupValues[2]
                    } else {
                        val justHour = Regex("(\\d{1,2})\\b").find(lower)
                        if (justHour != null) {
                            hour = justHour.groupValues[1].toInt()
                        }
                    }
                }
                if (amPm == "pm" && hour < 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0
                hour = hour.coerceIn(0, 23)
                minute = minute.coerceIn(0, 59)

                var label = trimmed.replace(Regex("(?i)\\b(set|pettu|పెట్టు|wake|ki|కి|alarm|అలారం)\\b"), "")
                    .replace(Regex("(?i)\\b(at|for|on)\\b"), "")
                    .replace(Regex("(\\d{1,2})[:\\.]?(\\d{2})?\\s*(am|pm)?") , "")
                    .replace(Regex("(\\d{1,2})\\s*(am|pm)") , "")
                    .trim()
                if (label.isBlank()) label = "Alarm"

                val reply = if (isTeluguScript || lower.contains("pettu") || lower.contains("ki")) "అలారం సెట్ అయింది: ${formatTime(hour, minute)}, సర్." else "Alarm set for ${formatTime(hour, minute)}, $salutation."
                return ParsedIntentResult(
                    intent = UserIntent.Alarm(hour, minute, label),
                    category = "alarm",
                    actionCommand = "alarm:$hour:$minute:$label",
                    localizedResponse = reply
                )
            } else if (lower.contains("cancel") || lower.contains("delete") || lower.contains("remove") || lower.contains("off") || lower.contains("ఆఫ్") || lower.contains("రద్దు") || lower.contains("cancel cheyi")) {
                val reply = if (isTeluguScript) "అలారం రద్దు అయింది, సర్." else "Alarm cancelled, $salutation."
                return ParsedIntentResult(UserIntent.SystemControl("alarm_cancel"), "system", "sys:alarm_cancel", reply)
            }
        }

        // 2. MUSIC CATEGORY
        val isMusicKeyword = lower.contains("play") || lower.contains("music") || lower.contains("song") || lower.contains("padh") || lower.contains("పాడు") || lower.contains("pattu") || lower.contains("పాటు") || lower.contains("spotify")
        if (isMusicKeyword && (lower.contains("play") || lower.contains("padh") || lower.contains("పాడు") || lower.contains("spotify") || lower.contains("song"))) {
            val queryText = trimmed.replace(Regex("(?i)\\b(play|padh|పాడు|music|song|spotify|on)\\b"), "").trim()
            val reply = if (isTeluguScript) "సంగీతం ప్లే చేయడానికి చూస్తున్నాను, సర్." else "Looking for music to play, $salutation."
            return ParsedIntentResult(
                intent = UserIntent.Music(queryText),
                category = "music",
                actionCommand = "music:$queryText",
                localizedResponse = reply
            )
        }

        // 3. SEARCH CATEGORY
        val isSearchKeyword = lower.contains("search") || lower.contains("google") || lower.contains("find") || lower.contains("వెతుకు") || lower.contains("youtube") || lower.contains("యూట్యూబ్")
        if (isSearchKeyword) {
            val isYouTube = lower.contains("youtube") || lower.contains("యూట్యూబ్")
            var queryText = trimmed.replace(Regex("(?i)\\b(search|google|find|వెతుకు|youtube|యూట్యూబ్|for|on)\\b"), "").trim()
            if (queryText.isBlank()) queryText = trimmed

            val reply = if (isTeluguScript) "$queryText సెర్చ్ చేస్తున్నాను, సర్." else "Searching for \"$queryText\", $salutation."
            return ParsedIntentResult(
                intent = UserIntent.Search(queryText, if (isYouTube) UserIntent.Search.SearchProvider.YOUTUBE else UserIntent.Search.SearchProvider.GOOGLE),
                category = "search",
                actionCommand = "websearch:$queryText",
                localizedResponse = reply
            )
        }

        // 4. SYSTEM CONTROL - DEEP OS CONTROL (Actual toggles via WRITE_SECURE_SETTINGS + Accessibility)
        // 4.1 Flashlight
        if (lower.contains("sos") || lower.contains("strobe") || lower.contains("emergency light")) {
            val reply = if (isTeluguScript) "ఎమర్జెన్సీ SOS లైట్ ఆన్ చేశాను, సర్." else "Engaging emergency SOS flashlight mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("flashlight_sos"), "system", "sys:flashlight_sos", reply)
        }
        if (lower.contains("flashlight on") || lower.contains("torch on") || lower.contains("టార్చ్ ఆన్") || lower.contains("flashlight on cheyi") || lower.contains("torch veyyi") || lower.contains("light veyyi") || lower.contains("torch pettu") || lower.contains("light veyy") || lower.contains("లైట్ వెయ్యి") || lower.contains("టార్చ్ వెయ్యి")) {
            val reply = if (isTeluguScript || lower.contains("cheyi") || lower.contains("veyyi") || lower.contains("pettu")) "ఫ్లాష్‌లైట్ ఆన్ చేశాను, సర్." else "Flashlight engaged, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("flashlight_on"), "system", "sys:flashlight_on", reply)
        }
        if (lower.contains("flashlight off") || lower.contains("torch off") || lower.contains("టార్చ్ ఆఫ్‌") || lower.contains("flashlight off cheyi") || lower.contains("light bandh chey") || lower.contains("torch aapeyyi") || lower.contains("light aapeyyi") || lower.contains("లైట్ ఆపు")) {
            val reply = if (isTeluguScript || lower.contains("cheyi") || lower.contains("aapeyyi")) "ఫ్లాష్‌లైట్ ఆఫ్‌ చేశాను, సర్." else "Flashlight turned off, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("flashlight_off"), "system", "sys:flashlight_off", reply)
        }
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("టార్చ్") || lower.contains("లైట్")) {
            val reply = "Toggling flashlight, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("flashlight_toggle"), "system", "sys:flashlight_toggle", reply)
        }

        // 4.2 Volume
        if (lower.contains("volume up") || lower.contains("volume penchu") || lower.contains("వాల్యూమ్ పెంచు") || lower.contains("sound penchu") || lower.contains("sound ekkuva") || lower.contains("సౌండ్ పెంచు")) {
            val reply = if (isTeluguScript || lower.contains("penchu")) "వాల్యూమ్ పెంచాను, సర్." else "Increasing volume, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("volume_up"), "system", "sys:volume_up", reply)
        }
        if (lower.contains("volume down") || lower.contains("volume tagginchu") || lower.contains("వాల్యూమ్ తగ్గించు") || lower.contains("sound thagginchu") || lower.contains("sound thakkuva") || lower.contains("సౌండ్ తగ్గించు")) {
            val reply = if (isTeluguScript || lower.contains("tagginchu")) "వాల్యూమ్ తగ్గించాను, సర్." else "Decreasing volume, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("volume_down"), "system", "sys:volume_down", reply)
        }
        if (lower.contains("mute") || lower.contains("మ్యూట్")) {
            val reply = if (isTeluguScript) "మ్యూట్ చేశాను, సర్." else "Audio muted, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("volume_mute"), "system", "sys:volume_mute", reply)
        }
        if (lower.contains("volume") || lower.contains("sound") || lower.contains("వాల్యూమ్") || lower.contains("సౌండ్")) {
            val reply = "Adjusting volume, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("volume_set"), "system", "sys:volume_set", reply)
        }

        // 4.3 Sound Mode
        if (lower.contains("silent mode") || lower.contains("silent pettu") || lower.contains("సైలెంట్ మోడ్")) {
            val reply = if (isTeluguScript) "ఫోన్ సైలెంట్ మోడ్‌లో పెట్టాను, సర్." else "Phone set to silent mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mode_silent"), "system", "sys:mode_silent", reply)
        }
        if (lower.contains("vibrate mode") || lower.contains("vibration mode") || lower.contains("వైబ్రేషన్ మోడ్")) {
            val reply = if (isTeluguScript) "ఫోన్ వైబ్రేషన్ మోడ్‌లో పెట్టాను, సర్." else "Phone set to vibrate mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mode_vibrate"), "system", "sys:mode_vibrate", reply)
        }
        if (lower.contains("normal mode") || lower.contains("ring mode") || lower.contains("unmute phone") || lower.contains("రింగ్ మోడ్")) {
            val reply = if (isTeluguScript) "ఫోన్ నార్మల్ మోడ్‌కి మార్చాను, సర్." else "Phone set to normal ring mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mode_normal"), "system", "sys:mode_normal", reply)
        }

        // 4.4 Battery & Device Info
        if (lower.contains("battery diag") || lower.contains("battery health") || lower.contains("device health") || lower.contains("system info") || lower.contains("device info")) {
            val reply = if (isTeluguScript) "డివైస్ బ్యాటరీ మరియు హెల్త్ డయాగ్నస్టిక్స్ రన్ చేస్తున్నాను, సర్." else "Running full battery and system diagnostics, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("battery_diag"), "system", "sys:battery_diag", reply)
        }
        if (lower.contains("battery") || lower.contains("బ్యాటరీ") || lower.contains("charging") || lower.contains("ఛార్జింగ్") || lower.contains("power")) {
            val reply = if (isTeluguScript || lower.contains("entha") || lower.contains("undhi") || lower.contains("undi")) "బ్యాటరీ వివరాలు చెక్ చేస్తున్నాను, సర్." else "Querying power status, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("battery_check"), "system", "sys:battery_check", reply)
        }

        // 4.5 WiFi / Bluetooth / Airplane / Mobile Data - ACTUAL TOGGLES (require WRITE_SECURE_SETTINGS + Accessibility)
        if (lower.contains("accessibility") && (lower.contains("open") || lower.contains("enable") || lower.contains("turn on") || lower.contains("settings"))) {
            val reply = "Opening Accessibility settings, $salutation. Toggle AUREX ON in the list."
            return ParsedIntentResult(UserIntent.SystemControl("open_accessibility_settings"), "system", "sys:open_accessibility_settings", reply)
        }
        if (lower.contains("wifi on") || lower.contains("wifi on cheyi") || lower.contains("wifi pettu") || lower.contains("wifi enable") || lower.contains("turn on wifi") || lower.contains("wifi on kaavaali") || lower.contains("వైఫై ఆన్")) {
            val reply = if (isTeluguScript) "వైఫై ఆన్ చేస్తున్నాను, సర్." else "Enabling Wi-Fi, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("wifi_on"), "system", "sys:wifi_on", reply)
        }
        if (lower.contains("wifi off") || lower.contains("wifi off cheyi") || lower.contains("wifi disable") || lower.contains("turn off wifi") || lower.contains("wifi aapeyyi") || lower.contains("wifi bandh") || lower.contains("వైఫై ఆఫ్") || lower.contains("వైఫై reżిమ్")) {
            val reply = if (isTeluguScript) "వైఫై ఆఫ్ చేశాను, సర్." else "Disabling Wi-Fi, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("wifi_off"), "system", "sys:wifi_off", reply)
        }
        if (lower.contains("wifi toggle") || lower.contains("wifi") || lower.contains("వైఫై")) {
            val reply = "Toggling Wi-Fi, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("wifi_toggle"), "system", "sys:wifi_toggle", reply)
        }

        if (lower.contains("bluetooth on") || lower.contains("bluetooth on cheyi") || lower.contains("bluetooth pettu") || lower.contains("bluetooth enable") || lower.contains("turn on bluetooth") || lower.contains("bluetooth on kaavaali") || lower.contains("బ్లూటూత్ ఆన్")) {
            val reply = if (isTeluguScript) "బ్లూటూత్ ఆన్ చేస్తున్నాను, సర్." else "Enabling Bluetooth, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("bluetooth_on"), "system", "sys:bluetooth_on", reply)
        }
        if (lower.contains("bluetooth off") || lower.contains("bluetooth off cheyi") || lower.contains("bluetooth disable") || lower.contains("turn off bluetooth") || lower.contains("bluetooth aapeyyi") || lower.contains("bluetooth bandh") || lower.contains("బ్లూటూత్ ఆఫ్")) {
            val reply = if (isTeluguScript) "బ్లూటూత్ ఆఫ్ చేశాను, సర్." else "Disabling Bluetooth, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("bluetooth_off"), "system", "sys:bluetooth_off", reply)
        }
        if (lower.contains("bluetooth") || lower.contains("బ్లూటూత్")) {
            val reply = "Toggling Bluetooth, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("bluetooth_toggle"), "system", "sys:bluetooth_toggle", reply)
        }

        if (lower.contains("airplane on") || lower.contains("airplane mode on") || lower.contains("flight mode on") || lower.contains("airplane pettu") || lower.contains("విమాన మోడ్ ఆన్")) {
            val reply = if (isTeluguScript) "విమాన మోడ్ ఆన్ చేస్తున్నాను, సర్." else "Enabling Airplane mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("airplane_on"), "system", "sys:airplane_on", reply)
        }
        if (lower.contains("airplane off") || lower.contains("airplane mode off") || lower.contains("flight mode off") || lower.contains("airplane aapeyyi") || lower.contains("విమాన మోడ్ ఆఫ్")) {
            val reply = if (isTeluguScript) "విమాన మోడ్ ఆఫ్ చేశాను, సర్." else "Disabling Airplane mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("airplane_off"), "system", "sys:airplane_off", reply)
        }
        if (lower.contains("airplane") || lower.contains("flight mode") || lower.contains("విమాన మోడ్")) {
            val reply = "Toggling Airplane mode, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("airplane_toggle"), "system", "sys:airplane_toggle", reply)
        }

        if (lower.contains("mobile data on") || lower.contains("data on") || lower.contains("internet on") || lower.contains("మొబ్లైర్ డేటా ఆన్")) {
            val reply = if (isTeluguScript) "మొబ్లైర్ డేటా ఆన్ చేస్తున్నాను, సర్." else "Enabling mobile data, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mobile_data_on"), "system", "sys:mobile_data_on", reply)
        }
        if (lower.contains("mobile data off") || lower.contains("data off") || lower.contains("internet off") || lower.contains("మొబ్లైర్ డేటా ఆఫ్")) {
            val reply = if (isTeluguScript) "మొబ్లైర్ డేటా آف్ చేశాను, సర్." else "Disabling mobile data, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mobile_data_off"), "system", "sys:mobile_data_off", reply)
        }
        if (lower.contains("mobile data") || lower.contains("internet") || lower.contains("data toggle")) {
            val reply = "Toggling mobile data, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("mobile_data_toggle"), "system", "sys:mobile_data_toggle", reply)
        }

        // 4.6 Hotspot, Screenshot, Screen Record, Theme, Chat
        if (lower.contains("hotspot") || lower.contains("tethering") || lower.contains("హాట్‌స్పాట్")) {
            val wantsOn = lower.contains(" on") || lower.contains("start hotspot") || lower.contains("enable hotspot") || lower.contains("turn on") || lower.contains("open hotspot")
            val wantsOff = lower.contains(" off") || lower.contains("stop hotspot") || lower.contains("disable hotspot") || lower.contains("turn off") || lower.contains("close hotspot")
            return when {
                wantsOn && !wantsOff -> {
                    val reply = if (isTeluguScript) "హాట్‌స్పాట్ ఆన్ చేస్తున్నాను." else "Turning hotspot on."
                    ParsedIntentResult(UserIntent.SystemControl("hotspot_on"), "system", "sys:hotspot_on", reply)
                }
                wantsOff && !wantsOn -> {
                    val reply = if (isTeluguScript) "హాట్‌స్పాట్ ఆఫ్ చేస్తున్నాను." else "Turning hotspot off."
                    ParsedIntentResult(UserIntent.SystemControl("hotspot_off"), "system", "sys:hotspot_off", reply)
                }
                else -> {
                    val reply = if (isTeluguScript) "హాట్‌స్పాట్ టోగుల్ చేస్తున్నాను." else "Toggling hotspot."
                    ParsedIntentResult(UserIntent.SystemControl("hotspot_toggle"), "system", "sys:hotspot_toggle", reply)
                }
            }
        }
        if (lower.contains("night light") || lower.contains("nightlight") || lower.contains("eye comfort") || lower.contains("reading mode")) {
            val wantsOn = lower.contains(" on") || lower.contains("turn on") || lower.contains("enable")
            val wantsOff = lower.contains(" off") || lower.contains("turn off") || lower.contains("disable")
            return when {
                wantsOn && !wantsOff -> ParsedIntentResult(UserIntent.SystemControl("night_light_on"), "system", "sys:night_light_on", "Turning night light on.")
                wantsOff && !wantsOn -> ParsedIntentResult(UserIntent.SystemControl("night_light_off"), "system", "sys:night_light_off", "Turning night light off.")
                else -> ParsedIntentResult(UserIntent.SystemControl("night_light_toggle"), "system", "sys:night_light_toggle", "Toggling night light.")
            }
        }
        if (lower.contains("screenshot") || lower.contains("capture screen")) {
            val reply = if (isTeluguScript) "స్క్రీన్‌షాట్ క్యాప్చర్ రన్ చేస్తున్నాను, సర్." else "Triggering screenshot capture, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("take_screenshot"), "system", "sys:take_screenshot", reply)
        }
        if (lower.contains("screen record") || lower.contains("record screen")) {
            val reply = if (isTeluguScript) "స్క్రీన్ రికార్డింగ్ ప్రారంభించాను, సర్." else "Opening screen recorder settings, $salutation."
            return ParsedIntentResult(UserIntent.SystemControl("screen_record"), "system", "sys:screen_record", reply)
        }
        if (lower.contains("toggle dark mode") || lower.contains("switch theme") || lower.contains("toggle theme") || lower.contains("light mode") || lower.contains("dark mode")) {
            return ParsedIntentResult(UserIntent.SystemControl("toggle_theme"), "system", "sys:toggle_theme", "Toggling theme, $salutation.")
        }
        if (lower.contains("clear chat") || lower.contains("clear conversation") || lower.contains("reset chat") || lower.contains("చాట్ క్లియర్")) {
            return ParsedIntentResult(UserIntent.SystemControl("clear_chat"), "system", "sys:clear_chat", "Conversation history reset, $salutation.")
        }

        // 4.7 GPS & TURN-BY-TURN NAVIGATION
        val isNavTrigger = lower.startsWith("navigate to ") || lower.startsWith("directions to ") || lower.contains("route to ") || lower.contains("dooku ki vellali")
        if (isNavTrigger) {
            var destination = trimmed
                .replace(Regex("(?i)^(navigate to|directions to|route to)"), "")
                .replace(Regex("(?i)^(dooku ki vellali)"), "")
                .trim()
            if (destination.isBlank()) {
                return ParsedIntentResult(UserIntent.General, "general", null, "Where would you like me to navigate, $salutation?")
            }
            val reply = if (isTeluguScript) "$destination నవిగేట్ చేస్తున్నాను, సర్." else "Opening navigation to $destination, $salutation."
            return ParsedIntentResult(
                intent = UserIntent.Search(destination, UserIntent.Search.SearchProvider.GOOGLE),
                category = "search",
                actionCommand = "navigate:$destination",
                localizedResponse = reply
            )
        }

        // 4.8 App Launch
        if (lower.contains("open ") || lower.contains("launch ") || lower.contains("start ") || lower.contains("తెరవు")) {
            val appName = trimmed
                .replace(Regex("(?i)^(open|launch|start|తెరవు)\\s+"), "")
                .trim()
            if (appName.isNotBlank()) {
                val reply = if (isTeluguScript) "$appName ఓపెన్ చేస్తున్నాను, సర్." else "Opening $appName, $salutation."
                return ParsedIntentResult(
                    intent = UserIntent.AppLaunch(appName),
                    category = "app_launch",
                    actionCommand = "applaunch:$appName",
                    localizedResponse = reply
                )
            }
        }

        // 5. GENERAL
        return ParsedIntentResult(UserIntent.General, "general", null, null)
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", displayHour, minute, amPm)
    }
}