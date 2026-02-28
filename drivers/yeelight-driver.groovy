/**
 * Yeelight WiFi Bulb — Hubitat Driver
 * LAN control via Yeelight JSON-RPC over TCP port 55443
 *
 * Namespace : thecrazymonkey
 * Author    : Ivan
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.ColorUtils

metadata {
    definition(
        name      : "Yeelight WiFi Bulb",
        namespace : "thecrazymonkey",
        author    : "Ivan",
        description: "LAN control for Yeelight smart bulbs via TCP port 55443"
    ) {
        capability "Switch"
        capability "Light"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "Refresh"
        capability "Initialize"

        command "toggle"
        command "poll"
        command "configure"

        attribute "connection",  "string"   // "connected" / "disconnected"
    }

    preferences {
        input name: "ipAddress",
              type: "text",
              title: "Bulb IP Address",
              description: "Local IP address of the Yeelight bulb",
              required: true

        input name: "transitionTime",
              type: "number",
              title: "Transition Time (ms)",
              description: "Smooth transition duration in milliseconds (minimum 30)",
              defaultValue: 400,
              required: false

        input name: "pollInterval",
              type: "enum",
              title: "Poll Interval",
              options: ["0":"Disabled", "1":"1 minute", "5":"5 minutes", "10":"10 minutes", "30":"30 minutes"],
              defaultValue: "5",
              required: false

        input name: "logEnable",
              type: "bool",
              title: "Enable Debug Logging",
              defaultValue: false

        input name: "txtEnable",
              type: "bool",
              title: "Enable Description Text Logging",
              defaultValue: true
    }
}

@Field static final Integer YEELIGHT_PORT   = 55443
@Field static final Integer CT_MIN          = 1700
@Field static final Integer CT_MAX          = 6500
@Field static final Integer RECONNECT_DELAY = 30
@Field static final Double  HUE_SCALE       = 3.59

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logDebug "installed()"
    initialize()
}

def updated() {
    logDebug "updated()"
    initialize()
}

def configure() {
    logDebug "configure()"
    initialize()
}

def initialize() {
    logDebug "initialize()"

    atomicState.msgId       = 1
    atomicState.pendingCmds = [:]
    state.retrying          = false

    unschedule()

    try { telnetClose() } catch (e) { /* ignore if not open */ }
    pauseExecution(500)

    connectTelnet()
    schedulePoll()
}

// ---------------------------------------------------------------------------
// Telnet connection management
// ---------------------------------------------------------------------------

def connectTelnet() {
    if (!ipAddress) {
        log.warn "${device.displayName}: IP address not configured — skipping telnet connect"
        return
    }
    if (!(ipAddress ==~ /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/)) {
        log.error "${device.displayName}: invalid IP address '${ipAddress}' — skipping telnet connect"
        return
    }
    logDebug "connectTelnet() → ${ipAddress}:${YEELIGHT_PORT}"
    try {
        telnetConnect([termChars: [13, 10]], ipAddress, YEELIGHT_PORT, null, null)
        sendEvent(name: "connection", value: "connected")
        runIn(2, "refresh")
    } catch (e) {
        log.error "${device.displayName}: telnetConnect failed — ${e.message}"
        sendEvent(name: "connection", value: "disconnected")
        scheduleReconnect()
    }
}

def telnetStatus(String status) {
    log.warn "${device.displayName}: telnetStatus — ${status}"
    sendEvent(name: "connection", value: "disconnected")
    try { telnetClose() } catch (e) { /* ignore */ }
    scheduleReconnect()
}

def scheduleReconnect() {
    logDebug "scheduleReconnect() in ${RECONNECT_DELAY}s"
    runIn(RECONNECT_DELAY, "connectTelnet")
}

// ---------------------------------------------------------------------------
// Outgoing message helpers
// ---------------------------------------------------------------------------

// Note: atomicState read-modify-write is not truly atomic (TOCTOU race).
// Concurrent threads may read the same msgId or overwrite each other's
// pendingCmds entries. This is an accepted Hubitat platform limitation.
private void sendJsonRpc(String method, List params, Map pendingMeta) {
    Integer id = atomicState.msgId ?: 1
    atomicState.msgId = id + 1

    Map pending = atomicState.pendingCmds ?: [:]
    pending[id.toString()] = pendingMeta + [ts: now()]
    atomicState.pendingCmds = pending

    Map msg = [id: id, method: method, params: params]
    String json = JsonOutput.toJson(msg)
    logDebug "sendJsonRpc → ${json}"
    sendHubCommand(new hubitat.device.HubAction("${json}\r\n", hubitat.device.Protocol.TELNET))
}

private void sendCommand(String method, List params) {
    sendJsonRpc(method, params, [method: method, params: params])
}

private void sendGetProp(List propNames) {
    sendJsonRpc("get_prop", propNames, [method: "get_prop", props: propNames])
}

// ---------------------------------------------------------------------------
// Incoming message parsing
// ---------------------------------------------------------------------------

def parse(String msg) {
    logDebug "parse ← ${msg}"

    // Mark connected on first received message
    if (device.currentValue("connection") != "connected") {
        sendEvent(name: "connection", value: "connected")
    }

    Map data
    try {
        data = new JsonSlurper().parseText(msg)
    } catch (e) {
        log.error "${device.displayName}: JSON parse error — ${e.message} | raw: ${msg}"
        return
    }

    if (data.containsKey("error")) {
        handleErrorResponse(data)
    } else if (data.containsKey("result")) {
        handleCommandResponse(data)
    } else if (data.method == "props") {
        handlePropsNotification(data.params)
    }
}

def handleErrorResponse(Map data) {
    Integer code = data.error?.code as Integer
    String message = data.error?.message ?: "unknown"

    // id:null "invalid command" is a known Yeelight firmware quirk — ignore
    if (data.id == null) {
        logDebug "ignoring id:null error (${code}: ${message})"
        return
    }

    String idStr = data.id.toString()
    Map pending = atomicState.pendingCmds ?: [:]
    Map cmd = pending[idStr]
    String method = cmd?.method ?: "unknown"

    if (cmd) pending.remove(idStr)
    purgeStalePendingCmds(pending)
    atomicState.pendingCmds = pending

    // Error -5000 means the bulb is off — use set_scene to atomically power
    // on and apply the setting in one command (no timing race).
    if (code == -5000 && cmd?.params && !state.retrying) {
        state.retrying = true
        List origParams = cmd.params as List
        List sceneParams = buildSceneParams(method, origParams)
        if (sceneParams) {
            log.info "${device.displayName}: '${method}' failed (bulb off) — retrying via set_scene"
            sendCommand("set_scene", sceneParams)
            sendEvent(name: "switch", value: "on")
        } else {
            log.warn "${device.displayName}: '${method}' failed (bulb off) — cannot build set_scene, giving up"
            state.retrying = false
        }
        return
    }

    if (state.retrying) {
        log.warn "${device.displayName}: set_scene retry of '${method}' also failed — error ${code}: ${message}"
        state.retrying = false
        return
    }

    log.warn "${device.displayName}: command '${method}' (id:${idStr}) failed — error ${code}: ${message}"
}

private List buildSceneParams(String method, List origParams) {
    switch (method) {
        case "set_ct_abx":
            // set_scene "ct" <ct_value> <brightness>
            Integer ct     = origParams[0] as Integer
            Integer bright = (device.currentValue("level") ?: 100) as Integer
            return ["ct", ct, bright]

        case "set_hsv":
            // set_scene "hsv" <hue> <saturation>
            Integer hue = origParams[0] as Integer
            Integer sat = origParams[1] as Integer
            return ["hsv", hue, sat]

        case "set_bright":
            Integer b = origParams[0] as Integer
            String mode = device.currentValue("colorMode") ?: "CT"
            if (mode == "HSV") {
                Integer hue = hubToYeelightHue((device.currentValue("hue") ?: 0) as Integer)
                Integer sat = (device.currentValue("saturation") ?: 100) as Integer
                return ["hsv", hue, sat]
            } else {
                Integer ct = clamp((device.currentValue("colorTemperature") ?: 4000) as Integer, CT_MIN, CT_MAX)
                return ["ct", ct, b]
            }

        default:
            return null
    }
}

def handleCommandResponse(Map data) {
    String idStr = data.id?.toString()
    if (!idStr) return

    // Successful response clears retry state
    if (state.retrying) {
        state.retrying = false
    }

    Map pending = atomicState.pendingCmds ?: [:]
    Map cmd     = pending[idStr]

    if (cmd) pending.remove(idStr)
    purgeStalePendingCmds(pending)
    atomicState.pendingCmds = pending

    if (!cmd) return

    if (cmd.method == "get_prop") {
        List propNames = cmd.props
        List results   = data.result
        if (propNames && results && propNames.size() == results.size()) {
            Map props = [:]
            propNames.eachWithIndex { name, i -> props[name] = results[i] }
            handlePropsNotification(props)
        }
    }
}

def handlePropsNotification(Map props) {
    logDebug "handlePropsNotification: ${props}"

    if (props.containsKey("power"))      updateSwitchState(props.power as String)
    if (props.containsKey("bright"))     safeInteger(props.bright)?.with { updateLevel(it) }
    if (props.containsKey("ct"))         safeInteger(props.ct)?.with { updateColorTemp(it) }
    if (props.containsKey("rgb"))        safeLong(props.rgb)?.with { updateRgb(it) }
    if (props.containsKey("hue"))        safeInteger(props.hue)?.with { updateHue(it) }
    if (props.containsKey("sat"))        safeInteger(props.sat)?.with { updateSaturation(it) }
    if (props.containsKey("color_mode")) safeInteger(props.color_mode)?.with { updateColorMode(it) }
}

// ---------------------------------------------------------------------------
// State update helpers
// ---------------------------------------------------------------------------

def updateSwitchState(String power) {
    String value = (power == "on") ? "on" : "off"
    descLog "${device.displayName} switch is ${value}"
    sendEvent(name: "switch", value: value)
}

def updateLevel(Integer bright) {
    if (bright == null) return
    descLog "${device.displayName} level is ${bright}%"
    sendEvent(name: "level", value: bright, unit: "%")
}

def updateColorTemp(Integer ct) {
    if (ct == null || ct == 0) return
    Integer kelvin = clamp(ct, CT_MIN, CT_MAX)
    descLog "${device.displayName} color temperature is ${kelvin}K"
    sendEvent(name: "colorTemperature", value: kelvin, unit: "K")
    setGenericColorName(kelvin)
}

def updateRgb(Long rgb) {
    if (rgb == null || rgb == 0) return
    Integer r = ((rgb >> 16) & 0xFF) as Integer
    Integer g = ((rgb >> 8)  & 0xFF) as Integer
    Integer b = (rgb & 0xFF)          as Integer
    List<Double> hsv = ColorUtils.rgbToHSV([r, g, b])
    Integer hubHue = Math.round(hsv[0]) as Integer
    Integer sat    = Math.round(hsv[1]) as Integer
    descLog "${device.displayName} RGB (${r},${g},${b}) → hue ${hubHue}, sat ${sat}"
    sendEvent(name: "hue",        value: hubHue)
    sendEvent(name: "saturation", value: sat)
    setGenericColorNameFromHSV(hubHue, sat)
}

def updateHue(Integer hue) {
    if (hue == null) return
    Integer hubHue = yeelightToHubHue(hue)
    descLog "${device.displayName} hue is ${hubHue}"
    sendEvent(name: "hue", value: hubHue)
    setGenericColorNameFromHSV(hubHue, (device.currentValue("saturation") ?: 100) as Integer)
}

def updateSaturation(Integer sat) {
    if (sat == null) return
    descLog "${device.displayName} saturation is ${sat}"
    sendEvent(name: "saturation", value: sat)
}

def updateColorMode(Integer mode) {
    if (mode == null) return
    String modeStr
    switch (mode) {
        case 1:  modeStr = "RGB"; break
        case 2:  modeStr = "CT";  break
        case 3:  modeStr = "HSV"; break
        default: modeStr = "CT";  break
    }
    descLog "${device.displayName} color mode is ${modeStr}"
    sendEvent(name: "colorMode", value: modeStr)
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

def on() {
    logDebug "on()"
    sendEvent(name: "switch", value: "on")   // optimistic
    sendCommand("set_power", ["on", "smooth", resolveDuration()])
}

def off() {
    logDebug "off()"
    sendEvent(name: "switch", value: "off")  // optimistic
    sendCommand("set_power", ["off", "smooth", resolveDuration()])
}

def toggle() {
    logDebug "toggle()"
    String current = device.currentValue("switch") ?: "off"
    sendEvent(name: "switch", value: (current == "on") ? "off" : "on") // optimistic
    sendCommand("toggle", [])
}

def setLevel(BigDecimal level, BigDecimal rate = null) {
    logDebug "setLevel(${level}, ${rate})"
    Integer bright = clamp(Math.round(level) as Integer, 0, 100)
    if (bright == 0) {
        off()
        return
    }

    sendEvent(name: "level", value: bright, unit: "%")
    sendCommand("set_bright", [bright, "smooth", resolveDuration(rate)])
}

def setHue(BigDecimal hue) {
    logDebug "setHue(${hue})"
    Integer hubHue = clamp(Math.round(hue) as Integer, 0, 100)
    Integer sat    = (device.currentValue("saturation") ?: 100) as Integer
    sendEvent(name: "hue",       value: hubHue)
    sendEvent(name: "colorMode", value: "HSV")
    sendCommand("set_hsv", [hubToYeelightHue(hubHue), sat, "smooth", resolveDuration()])
}

def setSaturation(BigDecimal saturation) {
    logDebug "setSaturation(${saturation})"
    Integer sat    = clamp(Math.round(saturation) as Integer, 0, 100)
    Integer hubHue = (device.currentValue("hue") ?: 0) as Integer
    sendEvent(name: "saturation", value: sat)
    sendEvent(name: "colorMode",  value: "HSV")
    sendCommand("set_hsv", [hubToYeelightHue(hubHue), sat, "smooth", resolveDuration()])
}

def setColor(Map colorMap) {
    logDebug "setColor(${colorMap})"
    Integer hubHue = (colorMap.hue        ?: 0) as Integer
    Integer sat    = (colorMap.saturation ?: 100) as Integer
    sendEvent(name: "hue",        value: hubHue)
    sendEvent(name: "saturation", value: sat)
    sendEvent(name: "colorMode",  value: "HSV")
    sendCommand("set_hsv", [hubToYeelightHue(hubHue), sat, "smooth", resolveDuration()])
    if (colorMap.level != null) {
        setLevel(colorMap.level as BigDecimal)
    }
}

def setColorTemperature(BigDecimal temperature, BigDecimal level = null, BigDecimal rate = null) {
    logDebug "setColorTemperature(${temperature}, ${level}, ${rate})"

    Integer ct = clamp(Math.round(temperature) as Integer, CT_MIN, CT_MAX)
    sendEvent(name: "colorTemperature", value: ct, unit: "K")
    sendEvent(name: "colorMode",        value: "CT")
    sendCommand("set_ct_abx", [ct, "smooth", resolveDuration(rate)])
    if (level != null) {
        setLevel(level)
    }
}

def refresh() {
    logDebug "refresh()"
    sendGetProp(["power", "bright", "ct", "rgb", "hue", "sat", "color_mode"])
}

def poll() {
    logDebug "poll()"
    refresh()
}

// ---------------------------------------------------------------------------
// Polling scheduler
// ---------------------------------------------------------------------------

def schedulePoll() {
    String interval = pollInterval ?: "5"
    switch (interval) {
        case "1":  runEvery1Minute("poll");   break
        case "5":  runEvery5Minutes("poll");  break
        case "10": runEvery10Minutes("poll"); break
        case "30": runEvery30Minutes("poll"); break
        case "0":  logDebug "polling disabled"; break
    }
}

// ---------------------------------------------------------------------------
// Utility helpers
// ---------------------------------------------------------------------------

private void purgeStalePendingCmds(Map pending) {
    Long cutoff = now() - 30000
    pending.entrySet().removeIf { it.value.ts != null && it.value.ts < cutoff }
}

private Integer safeInteger(Object val) {
    if (val == null) return null
    String s = val.toString().trim()
    if (s == "") return null
    try { return s as Integer } catch (e) { return null }
}

private Long safeLong(Object val) {
    if (val == null) return null
    String s = val.toString().trim()
    if (s == "") return null
    try { return s as Long } catch (e) { return null }
}

private Integer clamp(Integer value, Integer min, Integer max) {
    return Math.min(max, Math.max(min, value))
}

private Integer hubToYeelightHue(Integer h) { Math.round(h * HUE_SCALE) as Integer }
private Integer yeelightToHubHue(Integer h)  { Math.round(h / HUE_SCALE) as Integer }

private Integer resolveDuration(BigDecimal rate = null) {
    rate != null ? Math.max(30, Math.round(rate * 1000) as Integer)
                 : Math.max(30, (transitionTime ?: 400) as Integer)
}

private void setGenericColorName(Integer kelvin) {
    String name
    if      (kelvin <= 2000)  name = "Candlelight"
    else if (kelvin <= 2500)  name = "Warm White"
    else if (kelvin <= 3000)  name = "Incandescent"
    else if (kelvin <= 3500)  name = "Soft White"
    else if (kelvin <= 4000)  name = "Neutral White"
    else if (kelvin <= 5000)  name = "Cool White"
    else if (kelvin <= 6000)  name = "Daylight"
    else                      name = "Bright Daylight"
    sendEvent(name: "colorName", value: name)
}

private void setGenericColorNameFromHSV(Integer hue, Integer sat) {
    if (sat < 10) {
        sendEvent(name: "colorName", value: "White")
        return
    }
    String name
    if      (hue < 4  || hue >= 96)  name = "Red"
    else if (hue < 13)               name = "Orange"
    else if (hue < 21)               name = "Yellow"
    else if (hue < 46)               name = "Green"
    else if (hue < 63)               name = "Cyan"
    else if (hue < 79)               name = "Blue"
    else if (hue < 88)               name = "Violet"
    else                             name = "Pink"
    sendEvent(name: "colorName", value: name)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void descLog(String msg) {
    if (txtEnable) log.info msg
}
