# espMoto Android Companion App

Kotlin + Jetpack Compose companion application for **espMoto** (M5Stack StopWatch C152 handlebar display). Provides self-contained OpenStreetMap turn-by-turn navigation, QR-based BLE pairing, and live OBD-II / ELM327 profile configuration over Bluetooth Low Energy.

## Features
- **OpenStreetMap Search & Routing:** Geocodes destination addresses via public Nominatim, computes driving routes via OSRM, and previews routes on interactive `osmdroid` map tiles.
- **Share Intent Integration:** Accepts `geo:` URIs and shared Google Maps / location links directly from other apps (`ACTION_SEND`, `ACTION_VIEW`).
- **Background GPS Navigation:** Runs a foreground service (`OsmNavigationService`) with wake lock, off-route re-routing, and continuous BLE packet streaming even with the screen off.
- **QR Code BLE Pairing:** Scans the `espmoto://pair?mac=...&token=...` QR code on the StopWatch using CameraX + ML Kit and authenticates over BLE GATT.
- **Customizable OBD-II / ELM327 Configurator:** Includes presets (Euro 4/5+ Generic, Honda/Yamaha Euro 5+, Harley J1850 VPW & HDLAN CAN, or Custom), ELM327 BLE name filtering, tachometer max/redline RPM, display slot layout, and a custom PID hex/formula editor synced over BLE.
- **Web Simulator Mirroring (Optional):** HTTP mirror toggle for testing navigation and OBD configurations against the espMoto LVGL Web Simulator.

## Configuration (`.env`)
By default, the optional Web Simulator HTTP mirror targets `localhost:8152`. To point to another host or port on your local network without modifying source code:

```bash
cp .env.example .env
# Edit SIMULATOR_HOST and SIMULATOR_PORT in .env
```

`app/build.gradle.kts` reads `.env` at build time and injects `BuildConfig.SIMULATOR_HOST`.

## Building
1. Ensure Android SDK 35 and JDK 17 are installed (`local.properties` should point `sdk.dir` to your local Android SDK).
2. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`.
