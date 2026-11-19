# Phase 0: real-device feasibility check

**English** · [中文](PHASE0.zh-CN.md)

> **Result (2026-11-19, nubia NX733J, Android 15):** the package is `com.ubercab.driver`. While an offer popup is up, Uber hides its whole UI layer from the accessibility tree (only the map remains), so offers have to be read with OCR. Popup text: `CA$5.50` / `including estimated tip` / `15 min (1.3 km) total` / `Accept`; prices can also be whole dollars (`CA$6`). With Uber in the background, the offer card is a large window with no readable content (null root) on top of other apps. OCR is confirmed working in both cases.

Goal: capture the structured text of **at least one real Uber Driver offer popup** and confirm whether price, distance and time can be read from accessibility nodes, or whether OCR is required.

> Only do this **while parked**.

## Method A: FKUB's built-in capture (recommended)

1. Install FKUB and turn on its accessibility service (see the README).
2. Open the **Debug** tab and tap **Start capture**.
3. Switch to Uber Driver and go online as usual. Every time Uber's on-screen text changes (an offer appears, the countdown ticks, the popup closes) a snapshot is saved, at most one per second; map movement alone doesn't create duplicates. A small red badge `● REC N` in the top-left corner shows the count.
4. After one or two offers, go back to FKUB's Debug tab and tap **Stop capture**; everything is zipped automatically.
5. Under **Captures** tap **Share zip**. **View latest** shows the text in a snapshot: text like `"$9.00"`, `"29.9 km"`, `"42 min"` means nodes are readable; blanks or only the map means OCR is needed.
6. Each session holds up to 1000 snapshots and stops at the limit. Check before sharing: snapshots may contain store names or addresses.

Other ways: **Capture in 10 s** for a single snapshot; in debug mode, the system accessibility button; debug mode + auto snapshot.

A snapshot contains every window's type / package / bounds, the full node tree of target-app windows (class, id, text, contentDescription, bounds, clickable) and any OCR lines. Screenshots are off by default; turn on **Include screenshots in snapshots** on the Debug tab.

## Method B: GKD snapshots

1. Install GKD (https://gkd.li) and grant accessibility and overlay permissions.
2. In GKD settings enable the snapshot options (e.g. screenshot snapshot / floating button; see https://gkd.li/guide/snapshot for the current version).
3. When Uber Driver shows an offer, tap the GKD floating button to save a snapshot.
4. In GKD's snapshot list, share / export the zip (screenshot + node tree JSON).

Running GKD and FKUB accessibility services at the same time is fine; turn GKD off afterwards.

## What to confirm from snapshots

| Item | Finding |
|---|---|
| Uber Driver package (expected `com.ubercab.driver`) | ✅ `com.ubercab.driver` |
| Is the offer popup its own window or part of the main window (windowType) | Hidden from the tree; in the background it's a separate null-root window |
| Exact price text (e.g. `$9.00`, `CA$9.00`) | `CA$5.50`, `CA$6` |
| Other amounts next to the price (boosts, tip notes, …) | `including estimated tip` below the price |
| Total distance or per leg (pickup + drop-off) | Total: `15 min (1.3 km) total` |
| Total time, per leg, or "X min away" | Total, same line as distance |
| Accept button text (e.g. `Accept` / `Match`) | `Accept` |
| Stable view ids (`viewIdResourceName`) | Not available (nodes hidden) |
| How multi-stop deliveries are shown | Not seen yet |
| Is the destination readable (for the Phase 2 out-of-town check) | Not checked yet |
| Conclusion: nodes readable / OCR needed | **OCR needed** |

## Updating the adapter from snapshots

1. Paste a snapshot's text line by line into FKUB's **Mock → Mock screen text** and check the adapter output.
2. Adjust the keywords in `UberAdapterConfig` (`core/src/main/kotlin/com/offerpilot/core/parser/UberDriverAdapter.kt`). If distances are shown per leg and should be added up, turn on `sumLegsWhenNoTotal`.
3. Turn the real text into a unit test in `core/src/test` (with addresses and other personal data removed).
4. Once every keyword is verified, set `verifiedOnRealDevice` to `true`.
5. When nodes are unreadable, the OCR path is used (`app/.../capture/ScreenOcr.kt`).

## Real-device integration checklist (spec 10.2)

- [x] Offer recognized automatically (via OCR), verdict within about a second
- [x] Works when Uber pops an offer over another app
- [ ] Switching to maps / another app removes the hint immediately; other apps' text is never read
- [ ] No stale verdict after the offer disappears (accepted / rejected / timed out)
- [ ] The same offer isn't announced twice
- [ ] Incomplete recognition only says "Offer details incomplete", no recommendation
- [ ] Turning off the accessibility permission exits cleanly and removes the overlay
- [ ] The overlay never covers the accept button or intercepts touches
- [ ] Battery use over one hour
