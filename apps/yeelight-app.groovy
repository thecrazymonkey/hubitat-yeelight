/**
 * Yeelight Manager — Hubitat Parent App
 * Manages Yeelight WiFi Bulb child devices via LAN control
 *
 * Namespace : thecrazymonkey
 * Author    : Ivan
 */

definition(
    name            : "Yeelight Manager",
    namespace       : "thecrazymonkey",
    author          : "Ivan",
    description     : "Manage Yeelight smart bulbs on your local network",
    category        : "Lighting",
    iconUrl         : "",
    iconX2Url       : "",
    singleInstance  : true,
    installOnOpen   : true
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
    page(name: "addDevicePage")
    page(name: "removeDevicePage")
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "Yeelight Manager", install: true, uninstall: true) {

        section("Devices") {
            List children = getChildDevices()
            if (children) {
                children.each { dev ->
                    String ip    = dev.getDataValue("ipAddress") ?: "unknown IP"
                    String st    = dev.currentValue("switch") ?: "unknown"
                    String conn  = dev.currentValue("connection") ?: "unknown"
                    paragraph "<b>${dev.displayName}</b> — ${ip} | switch: ${st} | ${conn}"
                }
            } else {
                paragraph "No Yeelight devices added yet."
            }
        }

        section("Add Devices") {
            href "discoveryPage", title: "Discover Devices",
                 description: "Scan your network for Yeelight bulbs"
            href "addDevicePage", title: "Add Manually",
                 description: "Enter a bulb's IP address directly"
        }

        section("Remove Devices") {
            href "removeDevicePage", title: "Remove a Device",
                 description: "Select a device to remove"
        }

        section("Bulk Actions") {
            input name: "btnInitAll",      type: "button", title: "Initialize All"
            input name: "btnPollAll",      type: "button", title: "Poll All"
            input name: "btnConfigureAll", type: "button", title: "Reinitialize All"
        }
    }
}

def discoveryPage() {
    dynamicPage(name: "discoveryPage", title: "Discover Yeelight Devices", nextPage: "mainPage") {

        section {
            input name: "btnDiscover", type: "button", title: "Scan Network"
        }

        if (state.lastDiscoveryTime) {
            section {
                paragraph "Last scan: ${new Date(state.lastDiscoveryTime)}"
            }
        }

        if (state.discovering) {
            section { paragraph "Scanning… please wait up to 5 seconds." }
        }

        Map discovered = state.discoveredDevices ?: [:]
        if (discovered) {
            Set managedIps = getChildDevices().collect { it.getDataValue("ipAddress") } as Set

            List unmanaged = discovered.keySet().findAll { !managedIps.contains(it) }
            List managed   = discovered.keySet().findAll {  managedIps.contains(it) }

            if (unmanaged) {
                section("Discovered Devices") {
                    unmanaged.each { ip ->
                        Map info  = discovered[ip]
                        String label = info.name ?: ip
                        String model = info.model ? " (${info.model})" : ""
                        input name: "discover_${ip.replace('.', '_')}",
                              type: "bool",
                              title: "${label} — ${ip}${model}",
                              defaultValue: false
                    }
                }
                section {
                    input name: "btnAddDiscovered", type: "button", title: "Add Selected Devices"
                }
            }

            if (managed) {
                section("Already Added") {
                    managed.each { ip ->
                        Map info  = discovered[ip]
                        String label = info.name ?: ip
                        paragraph "${label} — ${ip} (already added)"
                    }
                }
            }
        } else if (state.lastDiscoveryTime && !state.discovering) {
            section { paragraph "No Yeelight bulbs found. Make sure LAN Control is enabled on each bulb." }
        }
    }
}

def addDevicePage() {
    dynamicPage(name: "addDevicePage", title: "Add Yeelight Device", nextPage: "mainPage") {
        section("Device Details") {
            input name: "newDeviceIp",
                  type: "text",
                  title: "Bulb IP Address",
                  description: "e.g. 192.168.1.100",
                  required: false,
                  submitOnChange: false

            input name: "newDeviceName",
                  type: "text",
                  title: "Device Name",
                  description: "Friendly name for this bulb",
                  required: false,
                  submitOnChange: false
        }
        section {
            input name: "btnAddDevice", type: "button", title: "Add Device"
        }
        if (state.lastAddResult) {
            section { paragraph state.lastAddResult }
            state.lastAddResult = null
        }
    }
}

def removeDevicePage() {
    dynamicPage(name: "removeDevicePage", title: "Remove Yeelight Device", nextPage: "mainPage") {
        List children = getChildDevices()
        if (children) {
            section("Select Device to Remove") {
                children.each { dev ->
                    String ip = dev.getDataValue("ipAddress") ?: "unknown IP"
                    href "removeDevicePage",
                         title: "Remove: ${dev.displayName} (${ip})",
                         description: "Tap to select this device for removal",
                         params: [removeDni: dev.deviceNetworkId]
                }
            }
            if (state.removeDni) {
                def target = getChildDevice(state.removeDni)
                if (target) {
                    section("Confirm Removal") {
                        paragraph "Are you sure you want to remove <b>${target.displayName}</b>?"
                        input name: "btnRemoveDevice", type: "button", title: "Confirm Remove"
                    }
                }
            }
        } else {
            section { paragraph "No devices to remove." }
        }
    }
}

// ---------------------------------------------------------------------------
// Button handler
// ---------------------------------------------------------------------------

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnDiscover":
            ssdpDiscover()
            break

        case "btnAddDiscovered":
            Map discovered = state.discoveredDevices ?: [:]
            Set managedIps = getChildDevices().collect { it.getDataValue("ipAddress") } as Set
            discovered.each { ip, info ->
                if (managedIps.contains(ip)) return
                String key = "discover_${ip.replace('.', '_')}"
                if (settings[key]) {
                    String label = info.name ?: "Yeelight ${ip}"
                    String result = createYeelightDevice(ip, label)
                    log.info "Yeelight Manager: discovery add ${ip} — ${result}"
                    app.updateSetting(key, [type: "bool", value: false])
                }
            }
            break

        case "btnAddDevice":
            if (newDeviceIp) {
                String name = newDeviceName ?: "Yeelight ${newDeviceIp}"
                String result = createYeelightDevice(newDeviceIp.trim(), name.trim())
                state.lastAddResult = result
                app.updateSetting("newDeviceIp",  [type: "text", value: ""])
                app.updateSetting("newDeviceName", [type: "text", value: ""])
            } else {
                state.lastAddResult = "Error: IP address is required."
            }
            break

        case "btnRemoveDevice":
            if (state.removeDni) {
                try {
                    deleteChildDevice(state.removeDni)
                    log.info "Yeelight Manager: removed device ${state.removeDni}"
                } catch (e) {
                    log.error "Yeelight Manager: failed to remove ${state.removeDni} — ${e.message}"
                }
                state.removeDni = null
            }
            break

        case "btnInitAll":
            getChildDevices().each { it.initialize() }
            log.info "Yeelight Manager: initialized all devices"
            break

        case "btnPollAll":
            getChildDevices().each { it.poll() }
            log.info "Yeelight Manager: polled all devices"
            break

        case "btnConfigureAll":
            getChildDevices().each { it.configure() }
            log.info "Yeelight Manager: configured all devices"
            break
    }
}

// Handle href params for selecting device to remove
def removeDevicePage(Map params) {
    if (params?.removeDni) {
        state.removeDni = params.removeDni
    }
    removeDevicePage()
}

// ---------------------------------------------------------------------------
// SSDP Discovery
// ---------------------------------------------------------------------------

def ssdpDiscover() {
    String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"
    sendHubCommand(new hubitat.device.HubAction(msg, hubitat.device.Protocol.LAN, "239.255.255.250@1982"))
    log.info "Yeelight Manager: sent SSDP M-SEARCH"
    state.discovering = true
    if (!state.discoveredDevices) state.discoveredDevices = [:]
    runIn(5, "finishDiscovery")
}

def finishDiscovery() {
    state.discovering        = false
    state.lastDiscoveryTime  = now()
    log.info "Yeelight Manager: discovery scan complete — found ${state.discoveredDevices?.size() ?: 0} device(s)"
}

def locationEventHandler(evt) {
    String desc = evt?.description
    if (!desc || !desc.contains("yeelight://")) return
    parseSsdpResponse(desc)
}

def parseSsdpResponse(String description) {
    Map info = [:]
    String ip = null

    description.split(/\r\n|\n/).each { line ->
        String lower = line.toLowerCase()
        if (lower.startsWith("location:")) {
            // Location: yeelight://192.168.1.100:55443
            def m = line =~ /yeelight:\/\/([0-9.]+):\d+/
            if (m) ip = m[0][1]
        } else if (lower.startsWith("model:")) {
            info.model = line.substring(line.indexOf(':') + 1).trim()
        } else if (lower.startsWith("name:")) {
            info.name = line.substring(line.indexOf(':') + 1).trim()
        } else if (lower.startsWith("id:")) {
            info.id = line.substring(line.indexOf(':') + 1).trim()
        } else if (lower.startsWith("power:")) {
            info.power = line.substring(line.indexOf(':') + 1).trim()
        } else if (lower.startsWith("ct:")) {
            info.ct = line.substring(line.indexOf(':') + 1).trim()
        }
    }

    if (ip) {
        if (!state.discoveredDevices) state.discoveredDevices = [:]
        state.discoveredDevices[ip] = info
        log.info "Yeelight Manager: discovered bulb at ${ip} — ${info}"
    }
}

// ---------------------------------------------------------------------------
// Child device management
// ---------------------------------------------------------------------------

private String createYeelightDevice(String ip, String name) {
    if (!(ip ==~ /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/)) {
        log.warn "Yeelight Manager: invalid IP address '${ip}'"
        return "Error: '${ip}' is not a valid IP address."
    }
    String dni = "yeelight-${ip.replace('.', '-')}"

    if (getChildDevice(dni)) {
        log.warn "Yeelight Manager: device ${dni} already exists"
        return "Device with IP ${ip} already exists."
    }

    try {
        def child = addChildDevice(
            "thecrazymonkey",
            "Yeelight WiFi Bulb",
            dni,
            [name: name, label: name, isComponent: false]
        )
        child.updateSetting("ipAddress", [type: "text", value: ip])
        child.updateDataValue("ipAddress", ip)
        child.initialize()
        log.info "Yeelight Manager: created device '${name}' (${ip}) with DNI ${dni}"
        return "Device '${name}' added successfully."
    } catch (e) {
        log.error "Yeelight Manager: failed to create device — ${e.message}"
        return "Error creating device: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    log.info "Yeelight Manager: installed"
    initialize()
}

def updated() {
    log.info "Yeelight Manager: updated"
    initialize()
}

def initialize() {
    log.info "Yeelight Manager: initialized"
    unsubscribe()
    subscribe(location, null, locationEventHandler)
}

def uninstalled() {
    log.info "Yeelight Manager: uninstalling — removing all child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}
