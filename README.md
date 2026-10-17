# Steady Dots

Anti-travel-sickness overlay for Android. Dots sit on top of every app and drift with
the vehicle's acceleration, so your eyes get the same motion cues your inner ear feels
(the same idea as Apple's "Vehicle Motion Cues").

## Build
1. Open this folder in Android Studio (Ladybug or newer). Let it sync. It will add the
   Gradle wrapper scripts if prompted.
2. Run on a phone (Android 8.0 / API 26+).
3. Tap **Allow "display over other apps"**, enable Steady Dots, come back, tap **Start dots**.

## How it works
| File | Job |
|---|---|
| `OverlayService.kt` | Foreground service. Adds a full-screen, touch-through overlay window; reads gravity + linear acceleration; converts them into car-relative forward/right; pushes to the view. |
| `DotsOverlayView.kt` | Draws the dots. They sit on a damped spring driven by the acceleration, so motion is smooth and they return home when the car is steady. |
| `MainActivity.kt` | Settings: size, spacing, columns, opacity, sensitivity, dots vs lines, edges only. Changes apply live. |
| `DotsTileService.kt` + `ToggleActivity.kt` | Quick Settings tile toggle. |
| `Prefs.kt` | SharedPreferences wrapper. |

Direction convention: braking → dots slide up, accelerating → down, turning left → right.

## Tuning knobs
- `ACCEL_SMOOTHING` (OverlayService): lower = less road-buzz jitter, more lag.
- `STIFFNESS`, `GAIN_DP`, `MAX_OFFSET_DP` (DotsOverlayView): feel and travel of the dots.

## Gotchas
- The overlay window alpha is capped at 0.8; above that Android 12+ blocks touches to
  apps underneath.
- Google Play requires a justification for the `specialUse` foreground service type.
