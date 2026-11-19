# TvOS Native Launcher (Android TV)

A fast, lightweight, **100% Kotlin** Apple tvOS–inspired home launcher built specifically for Android TV and 2 GB RAM TV boxes. No WebView, no heavy frameworks, and ultra-low memory usage.

---

## Download (Direct APK)

1. Open the **[Releases](../../releases/latest)** page.
2. Under **Assets**, download **`TvOS-Launcher-v1.0.apk`** directly (no ZIP extraction needed).
3. Install it on your Android TV (via USB, Send Files to TV, or Downloader) and set it as your default Home app in **Settings → RAM & System → Set as Default Home Launcher**.

---

## Features

- **100% Pure Kotlin & Zero Bloat:** Built with native Android Views and `Canvas`—tiny APK size and instant cold start.
- **2 GB RAM Optimized:** True-color 4K WebP wallpapers decoded directly into GPU `HARDWARE` memory (0 MB Java heap), smart `LruCache` trimming on backgrounding, and 60 FPS GPU-only D-pad focus animations.
- **3D Code-Drawn Vector Icons:** Sharp, pillowed tvOS-style app cards rendered in code—razor-sharp at 1080p and 4K.
- **Built-In Control Center & Settings:** Live RAM monitor, volume slider, in-app Wi-Fi & Bluetooth manager, StandBy clock, 6-tile or 9-tile Liquid Glass dock, and live hourly + 5-day Open-Meteo weather.
- **Local Wi-Fi 4K Wallpaper Upload:** Upload custom 4K wallpapers straight from your phone browser to your TV over local Wi-Fi.

---

## Requirements

- Android TV / Google TV running **Android 8.0 (API 26)** or higher.
- Works great on both 1080p and 4K displays, including budget 2 GB RAM devices.

---

## Building From Source

This repository builds automatically via GitHub Actions (JDK 17 + Gradle 8.11) and publishes a direct `.apk` to **Releases** on every release tag or push to `main`.