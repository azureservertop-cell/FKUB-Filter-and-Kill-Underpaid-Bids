# FKUB · Filter & Kill Underpaid Bids

[![Android CI](https://github.com/azureservertop-cell/FKUB-Filter-and-Kill-Underpaid-Bids/actions/workflows/android.yml/badge.svg)](https://github.com/azureservertop-cell/FKUB-Filter-and-Kill-Underpaid-Bids/actions/workflows/android.yml)
[![Latest APK](https://img.shields.io/badge/download-FKUB--debug.apk-1f6feb)](https://github.com/azureservertop-cell/FKUB-Filter-and-Kill-Underpaid-Bids/releases/download/latest/FKUB-debug.apk)

**English** · [中文](README.zh-CN.md)

> An Android helper for Uber Eats couriers: it reads each delivery offer, checks it against your own numbers and tells you at a glance whether it's worth taking. (Internal codename OfferPilot; the package id stays `com.offerpilot.app` so updates install over older builds.)

Android 11–15 (tested on Android 15) · Kotlin · Jetpack Compose · no root

When Uber Driver pops up a delivery offer, FKUB reads the price, distance and time, evaluates them with your rules, and answers with a **coloured glow around the screen edge**, a small pill showing the estimated net hourly, a tier sound, an optional spoken verdict and vibration, all before the offer disappears.

> **Recognition and advice only. It never taps anything.** There is no auto-accept or auto-reject, and the accessibility config declares no gesture capability. Everything is computed on the device. The only network use is downloading public regional fuel prices in Auto fuel-price mode; offers and screenshots never leave the phone.

## Download

On your phone open **Releases → latest** and download [`FKUB-debug.apk`](https://github.com/azureservertop-cell/FKUB-Filter-and-Kill-Underpaid-Bids/releases/download/latest/FKUB-debug.apk). CI rebuilds it on every push to `main`.

1. Install it (allow "install unknown apps").
2. Open FKUB → **Status** → **Choose app** and pick the delivery app you drive with (e.g. Uber Driver). Nothing is read until you pick one.
3. **Status** → **Open accessibility settings** → turn on **FKUB**.
   - On Android 13+ a sideloaded app's switch may be greyed out: Settings → Apps → FKUB → ⋮ (top right) → **Allow restricted settings**, then turn it on again.
4. The app is in English by default. Tap **中文** at the top of the Status page to switch the UI, overlay and speech to Chinese.

## How it works

```mermaid
flowchart LR
    A[Uber Driver<br/>offer popup] -->|window events| B[OfferMonitorService]
    B -->|text hidden from<br/>accessibility tree| C[Screenshot of Uber's window<br/>+ on-device ML Kit OCR]
    B -->|text readable| D[Node text]
    C --> E[UberDriverAdapter<br/>parse price · km · min]
    D --> E
    E --> F[OfferTracker<br/>dedupe · settle · withdraw]
    F --> G[OfferEvaluator<br/>your costs + target $/h]
    G --> H[Edge glow · pill · sound<br/>speech · vibration]
```

### Reading offers

Phase 0 on a real device (Android 15) showed that **while an offer popup is up, Uber Driver hides its whole UI from the accessibility tree**. So FKUB:

1. watches window events from Uber Driver (`com.ubercab.driver`);
2. when an offer is probably on screen but its text is hidden, takes an accessibility screenshot of **Uber's window only** (`takeScreenshotOfWindow` on Android 14+);
3. runs ML Kit text recognition **on the device**; the bitmap is recycled right away and never saved;
4. parses lines like `CA$5.50` / `including estimated tip` / `15 min (1.3 km) total` / `Accept`, repairing common OCR misreads (e.g. `CA$678` → `CA$6.78`).

This works with Uber in the foreground and when Uber pops an offer over another app (maps, launcher, …). FKUB does not try to get around Uber hiding its UI.

## Rules (default)

On the **Rules** tab enter your car's **fuel consumption (L/100 km)**, **maintenance ($/100 km)**, fuel price (**Auto** by default: the weekly average for your city from public government data, refreshed about twice a day; or **Manual**), your **target net per hour** (default $18) and the **share of the shown price that isn't tip** (default 85%, roughly a 15–18% tip).

```
tip-free pay      = Uber's shown price (incl. estimated tip) × 85%
vehicle cost / km = (L/100km × fuel price + maintenance per 100 km) / 100
required pay      = km × vehicle cost/km + hours × target net hourly
ratio             = tip-free pay / required pay

🔴 red    below target : ratio < 1 (spoken as "losing money" if it doesn't even cover vehicle cost)
🟡 yellow meets target : 1 ≤ ratio < 1.2, i.e. $18/h net even with no tip
🟢 green  good pay     : 1.2 ≤ ratio < 1.5
🔵 blue   must take    : ratio ≥ 1.5
missing / ambiguous / implausible / low confidence / stale → unknown (no glow, grey pill)
```

85%, 1.2 and 1.5 are all adjustable. The Rules tab also lists, for your car, the minimum price Uber has to show for each tier at different times and distances.

Defaults (10 L/100 km, $1.36/L, $10/100 km maintenance → $0.236/km; $18/h → $0.30/min; 85% tip-free):

| Offer | Shown · distance · time | Tip-free | Required | Ratio | Est. net hourly | Verdict |
|---|---|---|---|---|---|---|
| A | $9 · 29.92 km · 42 min | $7.65 | $19.66 | 0.39 | $0.84/h | 🔴 below target |
| B | $33.01 · 40.99 km · 63 min | $28.06 | $28.57 | 0.98 | $17.51/h | 🔴 below target (short by $0.60) |
| C | $7 · 3 km · 10 min | $5.95 | $3.71 | 1.60 | $31.45/h | 🔵 must take |
| Real | $5.50 · 1.3 km · 15 min | $4.68 | $4.81 | 0.97 | $17.47/h | 🔴 below target (short by $0.16) |

Under **Advanced** you can switch to the simple rule from the original spec, `max(km × $0.80, min × $0.50)` (also applied to the tip-free pay).

These are your personal filter rules, not Uber's pay standard. Minimum-earnings guarantees are calculated per pay period and are not included in single offers.

## Alerts

**Out-of-town alert** (on by default, Rules tab): when the city in the drop-off address (e.g. `Sunset Rd, St Thomas`) isn't one of your cities, the pill shows `⚠ St Thomas · Net $17/h` and speech adds "Out of town to St Thomas, watch the empty return." Only whole city names are compared, never streets, neighbourhoods or postal codes. Your cities default to the fuel-price city; add nearby towns you're happy with, e.g. `London, Thamesford`. If the card shows no city, there's no alert. It doesn't change the verdict.

| Tier | Edge glow | Sound (built-in) | Vibration |
|---|---|---|---|
| 🔵 must take | blue | meme air horn | three pulses |
| 🟢 good pay | green | coin | one pulse |
| 🟡 meets target | yellow | none | two pulses |
| 🔴 below target | red | wet fart | one long |

The pill shows only the estimated net hourly, e.g. `Net $17/h`. Each tier's sound can be turned off, given its own volume, previewed or replaced with your own audio file. Built-in clips come from free asset packs, see [SOUND_CREDITS.md](SOUND_CREDITS.md).

## App tabs

- **Status**: language, app to watch (picked from your installed apps), service state, last recognition, speech / vibration / glow / pill / OCR / debug switches, sounds.
- **Rules**: car costs, target earnings, tier thresholds, price table, simple-rule mode.
- **Stats**: a log of every offer FKUB read (time, shown price, km and minutes only), re-judged with your current rules: this week's share of offers below your own standard (vs last week), tier split, price vs distance scatter, net-hourly histogram, offers by hour of day and a recent list, for today / 7 days / 30 days / all. Logging is on by default and can be switched off, cleared or exported as CSV.
- **Mock**: test the rules without Uber (spec cases A/B/C) or paste screen text to test the adapter; previews the real overlay and speech when the service is on.
- **Debug**: target package, continuous capture (one tap to start, one to stop, zipped for sharing), single snapshots, capture list.

## Architecture

```
core/   pure Kotlin/JVM, no Android dependency, unit-tested
  model/     DeliveryOffer, EvaluationResult, Verdict, ScreenText
  parser/    OfferParser (money / distance / time, OCR repair), OfferAdapter, UberDriverAdapter
  rules/     OfferEvaluator, EvaluatorSettings
  tracking/  OfferTracker (dedupe, settle time for incomplete offers, withdraw when gone)
  stats/     OfferRecord, OfferStats (history re-evaluated with the current rules)
  format/    OfferMessages (pill / speech / detail text, English + Chinese)
  i18n/      Lang
  OfferPipeline  recognize → track → evaluate

app/    Android
  accessibility/  OfferMonitorService, NodeReader, MonitorState
  capture/        ScreenOcr (accessibility screenshot + ML Kit)
  overlay/        OverlayController, EdgeGlowView (TYPE_ACCESSIBILITY_OVERLAY, not touchable)
  feedback/       SoundPlayer, Haptics
  speech/         Speaker (TextToSpeech on the navigation-guidance channel)
  settings/       AppSettings, SettingsRepository (DataStore)
  debug/          SnapshotStore
  history/        OfferHistory (offer log CSV in app-private storage)
  action/         OfferActionHandler (no-op only)
  ui/             Compose screens
```

`OfferEvaluator` only takes a `DeliveryOffer` and knows nothing about accessibility; when Uber changes its UI only `UberDriverAdapter` / `UberAdapterConfig` need updating. The adapter uses no view IDs.

### Failing safe

- Only windows of the app(s) you picked are read (none until you pick one); other apps are seen by package name only, to notice that you left Uber. OCR captures only Uber's window.
- Missing price / distance / time, indistinguishable candidates, several accept buttons, implausible values (≤ 0, huge, average speed > 130 km/h), confidence < 0.6 or data older than 30 s → **unknown**.
- When the popup disappears or you switch apps, the glow and pill are removed and speech stops immediately.
- The same offer isn't announced twice within 20 s; incomplete offers get 0.7 s to fill in before "incomplete" is reported.
- A screen only counts as an offer if it has the **Accept** button or the "estimated tip" label, so Uber's trip history and earnings pages (which also show prices, km and minutes) are ignored. A window that keeps showing no offer is OCR'd at most every 5 s instead of continuously.
- Events are throttled (≤ 1 read per 150 ms), nodes are read on a worker thread with a 1500-node cap, OCR runs at most every 0.8 s, and every error degrades to "no offer" without affecting Uber Driver.

## Building

```bash
./gradlew :core:test              # core unit tests (JDK 17+ only)
./gradlew :app:assembleDebug      # needs Android SDK 35
```

CI (`.github/workflows/android.yml`) runs the tests, builds the APK and publishes it to the `latest` release on every push to `main`.

## Tests

`core/src/test` has 89 unit tests: price / distance / duration parsing (`$9.00`, `29.92 km`, `1 hr 3 min`, miles), missing and malformed values, negative / zero / huge values, multiple amounts, formula boundaries, tip deduction, return-trip cost, adapter ambiguity, real-device offer text and OCR misread repair, dedupe, offer disappearance, ignoring trip-history screens, drop-off city and out-of-town matching, offer statistics, fuel-price CSV parsing, and the English / Chinese messages.

Integration checks that need a real device are listed in [docs/PHASE0.md](docs/PHASE0.md).

## Privacy

The only network requests are for public fuel-price data in Auto mode (Ontario open data, falling back to Statistics Canada); they only download whole public datasets, and your city is matched on the phone. Switch fuel price to Manual and the app makes no network requests at all. Screenshots are processed in memory and never saved by default. The offer log keeps only time, price, km and minutes in app-private storage (it is deleted with the app, and you can clear it or turn it off on the Stats tab). Debug snapshots are only saved when you start a capture (or turn on auto snapshot) and stay on the phone in `Android/data/com.offerpilot.app/files/snapshots/`. Addresses are never saved, logged or uploaded. If the out-of-town alert is on, the city name in the drop-off address is compared with the city you set, in memory only, and then discarded. Names and order numbers are not read. Debug snapshots and the unreadable-offer log (both off by default) save the text on screen, which can include addresses; they stay on the phone.

## Status and limitations

- Verified on one phone (nubia, Android 15) in London, Ontario, with Canadian-dollar offers in English. Other Uber layouts (multi-stop, other languages, other regions) haven't been seen yet; the adapter answers "unknown" rather than guessing.
- OCR needs Android 14+ for window-only screenshots; Android 11–13 fall back to a full screenshot cropped to Uber's window.
- Auto fuel price uses government regional averages (weekly for Ontario cities, monthly from Statistics Canada elsewhere), so it lags the pump by days to weeks. The data layouts are discovered at runtime; if a source changes, the app keeps the last price and shows the error.
- Debug builds only, signed with a fixed debug key kept in the repo (`app/fkub-debug.keystore`), so each new build installs over the last one. Builds from before 2026-11-20 used a random key per build: uninstall those once before installing.

## Credits

The approach (watch UI events → read accessibility nodes → match rules) is inspired by GKD; no GKD code is used. Sound credits are in [SOUND_CREDITS.md](SOUND_CREDITS.md).
