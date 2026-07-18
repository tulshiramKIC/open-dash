# Open Dash — Agent Rules

## ADB & App Installation

**Always use WiFi ADB for installs. Never use direct `adb install` — it hangs.**

### WiFi ADB Setup (one-time per USB session)
If WiFi ADB is not yet active, the USB cable must be connected first:
```bash
export ANDROID_HOME=/home/tulshiram/Android/Sdk

# 1. Switch device to TCP/IP mode (USB must be plugged in)
$ANDROID_HOME/platform-tools/adb -s 57adde04 tcpip 5555

# 2. Get phone's WiFi IP
$ANDROID_HOME/platform-tools/adb -s 57adde04 shell "ifconfig" | grep -A1 wlan1
# Phone IP: 10.43.99.71

# 3. Connect via WiFi
$ANDROID_HOME/platform-tools/adb connect 10.43.99.71:5555

# 4. Unplug USB — WiFi stays connected
```

### Reconnecting (subsequent sessions)
```bash
export ANDROID_HOME=/home/tulshiram/Android/Sdk
$ANDROID_HOME/platform-tools/adb connect 10.43.99.71:5555
```

### Installing the App
Always use Gradle — never `adb install` directly:
```bash
export ANDROID_HOME=/home/tulshiram/Android/Sdk && ./gradlew :app:installDebug --no-daemon
```

### Building
```bash
export ANDROID_HOME=/home/tulshiram/Android/Sdk && ./gradlew :app:assembleDebug --no-daemon
```

### Device Info
- **Device**: OnePlus CPH2729
- **Serial (USB)**: 57adde04
- **WiFi IP**: 10.43.99.71
- **WiFi ADB port**: 5555
- **App package**: com.opendash.app.mui3

## ADB Server Restart (if device not found)
```bash
export ANDROID_HOME=/home/tulshiram/Android/Sdk
$ANDROID_HOME/platform-tools/adb kill-server && sleep 1 && $ANDROID_HOME/platform-tools/adb start-server
$ANDROID_HOME/platform-tools/adb connect 10.43.99.71:5555
```

## Agent Communication Guidelines
**STRICT WARNING**: Keep all outputs extremely brief. Do not explain what you are doing, do not explain the reasons behind the code changes, and do not provide any explanations at all unless explicitly requested to explain by the user. Do not waste tokens.
