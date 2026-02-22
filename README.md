# Hubitat Yeelight LAN Driver

Local LAN control for Yeelight smart bulbs on Hubitat Elevation. No cloud account or internet connection required — communicates directly over TCP port 55443.

## Prerequisites

1. **Static IP address** — assign a static/reserved IP to each bulb in your router's DHCP settings. The driver connects to a fixed IP; if it changes, you lose control.

2. **LAN Control enabled** — open the Yeelight mobile app, go to the bulb's settings, and enable **LAN Control** (sometimes listed under Developer Mode or Settings → LAN Control). The bulb will then accept TCP connections on port 55443.

3. **Same subnet** — the Hubitat hub and the bulbs must be on the same local network subnet.

## Installation

Upload files in this order:

### 1. Install the Driver

1. In the Hubitat web UI, go to **Drivers Code** → **+ New Driver**
2. Paste the contents of `drivers/yeelight-driver.groovy`
3. Click **Save** — verify no compile errors appear

### 2. Install the App

1. Go to **Apps Code** → **+ New App**
2. Paste the contents of `apps/yeelight-app.groovy`
3. Click **Save** — verify no compile errors appear

### 3. Install the App Instance

1. Go to **Apps** → **Add User App**
2. Select **Yeelight Manager**
3. The app will open on the main page

### 4. Add Devices

#### Auto-Discovery

1. Tap **Discover Devices**
2. Tap **Scan Network** — the app broadcasts an SSDP M-SEARCH over UDP and listens for 5 seconds
3. Bulbs that respond appear in a list with their name, IP address, and model
4. Check the box next to each bulb you want to add, then tap **Add Selected Devices**
5. Bulbs already managed by the app are shown as "already added" and cannot be double-added

> **Note:** LAN Control must be enabled on each bulb (see Prerequisites) for it to respond to discovery.

#### Add Manually

1. Tap **Add Manually**
2. Enter the bulb's IP address (e.g. `192.168.1.100`)
3. Enter an optional friendly name
4. Tap **Add Device**
5. The child device will appear in your Hubitat device list with the driver pre-configured

## Driver Preferences

| Preference | Default | Description |
|------------|---------|-------------|
| Bulb IP Address | — | Local IP of the bulb (required) |
| Transition Time (ms) | 400 | Smooth effect duration; minimum 30 ms |
| Poll Interval | 5 minutes | How often to query the bulb's state; set to Disabled to turn off |
| Enable Debug Logging | false | Verbose logs in Hubitat Logs page |
| Enable Description Text Logging | true | Informational state-change logs |

## Capabilities

| Capability | Commands |
|------------|----------|
| Switch | `on()`, `off()` |
| Light | — |
| SwitchLevel | `setLevel(level, rate)` |
| ColorControl | `setColor(colorMap)`, `setHue()`, `setSaturation()` |
| ColorTemperature | `setColorTemperature(temp, level, rate)` |
| ColorMode | attribute only — "CT" / "RGB" / "HSV" |
| Refresh | `refresh()` |
| Initialize | `initialize()` |

Extra commands: `toggle()`, `poll()`, `configure()`

Custom attributes:
- `connection` — "connected" / "disconnected"
- `colorMode` — "CT" / "RGB" / "HSV"

## Bulk Actions (from App)

The Yeelight Manager app page provides three bulk action buttons:

- **Initialize All** — reconnects all bulbs and reschedules polling
- **Poll All** — immediately queries state of all bulbs
- **Configure All** — equivalent to Initialize All

## Troubleshooting

**Bulb shows "disconnected"**
- Verify LAN Control is enabled in the Yeelight app
- Check the IP address is correct and the bulb is online
- Ensure the Hubitat hub can reach the bulb on TCP port 55443 (no firewall blocking)
- The driver automatically retries connection every 30 seconds

**Commands don't respond**
- Check Hubitat Logs for error messages (enable Debug Logging temporarily)
- Try **Initialize** from the device page to force a reconnect
- Power-cycle the bulb if it appears stuck

**Device not created**
- Ensure you uploaded and saved the driver before creating devices via the app
- Check the namespace in the driver matches `ivanjx`

## Known Limitations

- **Max 4 simultaneous TCP connections** — Yeelight bulbs accept at most 4 concurrent connections; avoid creating duplicate driver instances for the same bulb
- **LAN Control must remain enabled** — some Yeelight firmware versions disable it after a factory reset or firmware update; re-enable it in the app if control is lost
- **Color Temperature range** — limited to 1700 K – 6500 K (hardware constraint)

## Color Temperature Reference

| Range | Name |
|-------|------|
| ≤ 2000 K | Candlelight |
| 2001–2500 K | Warm White |
| 2501–3000 K | Incandescent |
| 3001–3500 K | Soft White |
| 3501–4000 K | Neutral White |
| 4001–5000 K | Cool White |
| 5001–6000 K | Daylight |
| > 6000 K | Bright Daylight |
