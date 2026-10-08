# Baby Motion Alert

Watches your phone screen (your baby cam's full-screen live view) and fires a loud alarm plus a flashing red overlay when it detects movement.

Works entirely on-device: it mirrors the screen at a small resolution via Android's MediaProjection API, compares consecutive frames, and alarms when enough pixels change.

## Install on your phone (no Android Studio)

1. Every push to `main` triggers the **Build APK** workflow (Actions tab).
2. When it finishes (~3-5 min), go to **Releases** -> "BabyMotionAlert APK" -> download `BabyMotionAlert.apk` in your phone's browser (sign in to GitHub first, since the repo is private).
3. Tap the downloaded file, allow "install from unknown sources" when prompted, install.

## How to use

1. Open **Baby Motion Alert**.
2. Tap **Start monitoring**:
   - First time it will send you to grant **"Display over other apps"** (needed for the red flash). Enable it, go back, tap Start again.
   - Accept the **screen recording** prompt (choose "Entire screen").
3. Switch to your **baby cam app** and put the live view in full screen.
4. Leave the phone with the screen on. When the image changes enough, you get a **5-second looping alarm + red flashing overlay**, then an 8-second cooldown.
5. Tap **Test alarm + red flash** anytime to check the volume/overlay work.
6. **Stop monitoring** via the button, or via the screen-cast icon in the status bar.

## Tuning (important for night vision)

- The **slider** sets what % of the screen must change to trigger. Default is **1.5%** -- good for a toddler sitting up or rolling.
- IR night vision is grainy. If you get false alarms, raise the slider (2.5-4%).
- If it misses small movements, lower it (0.5-1%).
- Advanced knobs are constants at the top of `MotionDetectionService.kt`:
  - `PIXEL_DIFF_THRESHOLD` (28): raise to ~40 for very grainy night vision.
  - `FRAME_INTERVAL_MS` (400): how often frames are compared.
  - `ALARM_DURATION_MS` / `COOLDOWN_MS`: alarm length and quiet period.

## Notes & limitations

- The screen must stay **on** with the live view visible. Set a long screen timeout, or enable "Stay awake while charging" in Developer options.
- Alarm plays on the **alarm volume stream** -- make sure alarm volume is up, not just media volume.
- Android shows a persistent notification + status-bar cast icon while monitoring (system requirement for screen capture).
- Keep the phone plugged in for long sessions.
- This is a helper, not a safety device -- it can miss movement or false-alarm; keep doing your normal checks.
