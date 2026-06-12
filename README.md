# IntentLock

**AI-powered Android lock screen overlay** that intercepts every unlock, predicts your next app using Gemini Flash, and launches it directly — eliminating the home screen and distraction spirals.

Built by Carol & Nithilarisu | HackArena 2.0, Bangalore Zonals, trae.ai Track

---

## Quick Start

### 1. Add your Gemini API key

Edit `local.properties` (create it if missing):

```
sdk.dir=/path/to/your/Android/Sdk
GEMINI_API_KEY=your_gemini_api_key_here
```

Get a free key at https://aistudio.google.com/app/apikey

### 2. Open in Android Studio

Open the `IntentLock` folder in Android Studio Hedgehog or newer.

### 3. Run on a real device

> ⚠️ The AccessibilityService does **not** fire for screen unlocks on the emulator. Always test on a physical Android device.

Connect your device with USB debugging enabled and hit Run.

### 4. Grant permissions

The app walks you through 3 required permissions:
- **Display over other apps** → Settings → Apps → Special access
- **Accessibility service** → Settings → Accessibility → Downloaded apps
- **Usage access** → Settings → Apps → Special access

### 5. Lock and unlock your phone

The overlay appears automatically. Gemini predicts your app. If confidence ≥ 70%, it auto-launches after a 3-second countdown.

---

## Architecture

```
Unlock detected (AccessibilityService)
    ↓
ContextEngine.collect()          — hour, day, headphones, calendar, recent apps, battery
    ↓
GeminiPredictor.predict()        — Gemini Flash API call with context prompt
    ↓ (on failure/timeout)
FallbackPredictor.predict()      — offline frequency × recency scoring from Room DB
    ↓
OverlayService.showOverlay()     — WindowManager fullscreen overlay with countdown
```

## Key Files

| File | Purpose |
|---|---|
| `IntentLockService.kt` | AccessibilityService — detects unlocks |
| `ContextEngine.kt` | Collects signal data for the prompt |
| `GeminiPredictor.kt` | Gemini Flash API call + JSON parsing |
| `FallbackPredictor.kt` | Offline fallback scorer |
| `OverlayService.kt` | WindowManager overlay with countdown + quick-pick |
| `DataSeeder.kt` | Seeds 14 days of demo history on first launch |

## Confidence Threshold

- `≥ 70%` → auto-launch with 3-second countdown
- `< 70%` → show 4-app quick-pick grid, user taps to open

---

## Notes for Demo

- Seed data is pre-loaded so FallbackPredictor works from first launch
- Overlay uses `FLAG_SHOW_WHEN_LOCKED` + `FLAG_DISMISS_KEYGUARD` to appear above the lock screen
- Voice capture (mic button) sends transcribed thought to Google Keep
- "Not this" button reveals quick-pick grid of 4 recent apps
