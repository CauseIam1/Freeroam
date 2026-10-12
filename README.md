# FreeRoam Tactical Edge-Compute System

## Overview

### A covert, edge-compute architecture for real-time AI-assisted situational awareness on the **Red Magic 9 Pro**. FreeRoam features automatic multi-user identity detection, a walkie-talkie loop with Polaris (AI) via the Polaris Gateway — **voice in** (watch PLAY/PAUSE push-to-talk or the in-app mic button, both working with the screen locked) and **voice out** (fully on-device TTS synthesis) — every AI reply is spoken by a local Kokoro engine (sherpa-onnx), streamed sentence-by-sentence while the reply generates (speak-while-she-writes, v3.5) — and since v3.6, playback is fully text-only: no WAV artifacts, every bubble replays by re-synthesizing from text. No cloud TTS. No server-side audio round-trips. Since v3.7.2 her words also reach the Garmin HUD **before** synthesis — a pre-Kokoro accumulator pushes each accumulated title to the watch before that chunk enters Kokoro, so the watch shows her live first words while she speaks — and since v3.7.1 every finished reply loops the watch **3 marquee passes** before returning to "Polaris standing by - press PLAY to talk".

### - **Current app version:** versionName **1.0.6** (versionCode **9**) — doc **v3.12** — v3.8 (Oct 11, 2026) built on the **host Gradle path** (`./build-apk.sh`, ~2 min warm-cache, 233 MB debug APK) and deployed to Rich's RedMagic (FY24031100E6) over USB through the full pinned pipeline: build → `aapt` version gate + `apksigner` cert gate (cert SHA-256 `7c02562a49c8da3f…583658e50a` = the device's installed lineage) → `adb install -r` Success **in place** (data lineage intact: `firstInstallTime=2026-10-04`, `lastUpdateTime=2026-10-11`) → relaunch verified. v3.7.2 ships the **HUD-first, Kokoro-second** speech pipeline (changelog + §Garmin below), closing the Oct-10/11 field series: same-press voice self-heal (v3.7), post-playback 3-pass marquee loop → standing-by (v3.7.1). **Field report Oct 11:** GPS live-feed verified accurate end-to-end — Polaris noticed the operator walking away from the phone; the double-tap PTT voice loss recurred once (a same-press self-heal escape — **parked** by operator ruling, see the self-heal bullet); HUD live-words / replay-lead / drain-loop checks still await the operator's run. **Field report Oct 11, evening:** Polaris answered "your GPS is **17 min old**" — root-caused to the v3.5.1 foreground-only GPS feed and **fixed in v3.8** (changelog row doc v3.12 below). Lock-screen soak **"so far so good"** held (Oct 11). **Field report Oct 11, night — WireGuard walk test PASSED:** operator walked to the back of the property with Wi-Fi off and the WG tunnel on; Polaris pulled his live coordinates cleanly — the FGS screen-off posting, the LAN → WG-hub endpoint flip, and IP-based identity over the tunnel all verified in the field; GPS feed accepted end-to-end. **History (v1.0.5/6 era, Oct 3–5):** Rich's phone fresh-installed Oct 3 on the then-operative committed container pin after the Oct-1 host key was lost/regenerated; Matt's RedMagic (192.168.50.98) needed uninstall + reinstall (his Sep-29 v1.0.4 was signed by that same lost host key) with all four runtime grants re-applied via `pm grant` before first launch (identity never fell back to Rich; gateway polls resolved `user_id='matt'`). Lineage note: the phone now upgrades from **host-signed** builds — see the signing bullet below
- **Unified LGI look (doc v3.10, shipped Oct 5, 2026):** the phone is now a faithful port of the LGI HUD (`LGI/hud_ui.py` v1.4 QSS tokens + `gateway_feed.py` TASK_ROW_STYLES) — the same chrome as the X17 overlay and the dashboard Holodeck: ◈ LGI — LOOKING GLASS header with POL/TTS/MIC LEDs, ⋯ kebab → ⚙ CONTROLS drawer, ▼ TACTICAL OPERATIONS bottom pane with the gateway status-room task feed, LGI chat tinting, VOICE/SPD/MIC chips. Full breakdown: [§12 — LGI Tactical HUD](#12-lgi-tactical-hud--the-unified-look-oct-5-2026)
- **Looking-Glass chat unification (Oct 1, 2026, operator live-fire verified):** Polaris replies push, not poll — the gateway broadcasts every outgoing reply and stream delta to the sender's persistent per-identity room (`room=user_identity`) in addition to the IP room, so the phone, every other device of that identity, the dashboard, and the Looking Glass render simultaneously (verified live: a message sent from the RedMagic appeared on phone + dashboard with zero 3-second lag). New installs and chat openings seed the **latest 5 messages per side** from the gateway's master timeline ("Looking-Glass seed v2"), so even a long one-sided stretch opens with full context on both sides. The legacy 3-second history polls remain as a fallback safety net only
- **Wake-reconnect chat polarity fix (v1.0.5, operator field-verified Oct 3, 2026):** history rows replayed from the gateway — the WS `chat_history` packet on every wake/reconnect and the REST seed on install/open — were classified by an exact, case-sensitive sender match. The master timeline stores `"sender": "Polaris"` with a capital P; the old check missed it and fell through to the legacy user-side default, so every wake-reconnect re-seed painted the whole window **You**. Both `ChatRepository` (REST) and `PolarisWebSocketClient` (WS) now share one `parseHistorySenderPolarity(role, sender)` helper: role is authoritative when present (`user` vs `assistant`/`polaris`/`ai`), then sender case-insensitively (`user`/`self`/the declared identity → user side); bare legacy rows keep the legacy user-side default; unknown senders fail-safe to the AI side — a mislabeled "You" bubble is worse than a mislabeled Polaris bubble
- **Voice push-to-talk (v3.3):** watch PLAY/PAUSE / mic button → `VoiceCaptureController` (`SpeechRecognizer`, on-device engine preferred) → live partials in the input field → stop → auto-send — captures survive screen-lock via the mic-typed foreground service
- **Voice engine self-heal (v1.0.4):** the RedMagic's on-device speech engine can wedge mid-capture — no callbacks at all — and every later press hears nothing until a reboot (paired with a device-level logd freeze on this ROM). `finalizeAndDeliver()` now counts silent captures (`SILENT_CAPTURES_BEFORE_ENGINE_HOP`) and arms an engine hop: the next PTT press is served by the **system (cloud) recognizer** instead of the wedged on-device engine; a successful capture clears the hop and returns to the private engine. A wedge used to cost one failed press, no reboot — **v3.6.3 (v3.7) same-press self-heal removed even that:** a 2 s warmup watchdog (zero callbacks of any kind since `startListening()` = the wedge signature) hops engines and restarts the capture within the same press (budget 2, the red capture UI never drops); timer- or error-driven blank captures auto-retry transparently while a manual stop on an empty press still reports honestly; and the wedge-lost live partial folds back into the running transcript so the restart continues the operator's sentence. **Field return Oct 11, 2026:** one double-tap PTT press again lost its speech capture (second press fine) — a rare escape from the same-press watchdog; signature not yet captured (no logcat window during the event). **Parked by operator ruling — leave for now**
- **Android build signing (v1.0.4):** the debug keystore is committed (`android/app/freeroam-debug.keystore`) and pinned into the build image (`freeroam/Dockerfile` → `/root/.android/debug.keystore`) so every build signs identically and `adb install -r` upgrades in place — fresh images previously generated a new key, forcing uninstall + reinstall. Note: ADB-over-TCP (5555) does not survive phone reboots on this ROM — re-arm via USB once; the gateway's watchdog (`run_gateway.sh`) reconnects automatically. **Two signing worlds (field-verified Oct 1, 2026):** no `signingConfig` in the gradle files references the committed keystore — host Gradle builds sign with the machine default `~/.android/debug.keystore`, container builds sign with the committed `freeroam-debug.keystore`, and the two keys differ. A host rebuild will not in-place-upgrade a container-signed install (and vice versa) without a full uninstall + reinstall. **Key lineage (cert-verified Oct 3, 2026):** the Oct-1 v1.0.4 install was signed by the host `~/.android/debug.keystore` of that day (cert SHA-1 `86b8c88a…`) — that keystore was subsequently regenerated/lost, so upgrading to v1.0.5 failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and forced ONE final fresh install. **Key lineage era 1 (cert-verified Oct 3, 2026 — superseded):** the Oct-3 v1.0.5 fresh install pinned the device to the committed container key (SHA-1 `acf51847…` — identical to the committed `android/app/freeroam-debug.keystore`, which the Dockerfile bakes to `/root/.android/debug.keystore`). **Key lineage era 2 — current (cert-verified Oct 11, 2026):** the device has run the **host `~/.android/debug.keystore` lineage since at least its Oct 4, 2026 first install** (dumpsys `firstInstallTime=2026-10-04`): the Oct-10/11 field series (v3.6.3 → v3.7.2, versionCode 7→8) was built by `./build-apk.sh` (host Gradle = machine-default key) and upgraded in place over USB — the v3.7.2 pre-install gate verified the APK cert (SHA-256 `7c02562a49c8da3f…583658e50a`) against the device's pinned lineage before `adb install -r`. The committed `android/app/freeroam-debug.keystore` is therefore the **legacy v1.0.5 container pin**: every future *host* Gradle build upgrades the phone in place, while a *container* build now forces a full uninstall + reinstall. The host key is regenerable — if it is ever lost/regenerated again, the device re-pins on the next fresh install (recovery recipe: the signature-mismatch bullet in Build System)
- **Reboot-proof wireless ADB (v1.0.4 era):** Wireless debugging is ON (`Settings.Global adb_wifi_enabled=1`) and the Mainframe is paired (pair GUID `adb-FY24031100E6-71NED1`). The Mainframe runs Google platform-tools v37 at `~/platform-tools/adb` (v37 has mDNS + TLS pairing; the distro adb v28 on `/usr/bin` does not). After any phone reboot the device re-appears automatically on the host as `adb-FY24031100E6-71NED1._adb-tls-connect._tcp` in `adb devices` — **no USB, no `adb tcpip`, no manual step**. From Polaris's sandbox (whose ADB is Debian 34, no mDNS): reach the phone through `ssh_execute` on the Mainframe → `~/platform-tools/adb`; the container's `:5555` bridge remains the quick path whenever TCP mode is armed. **Oct 5, 2026 caveat:** with the phone's screen OFF this ROM stops advertising the `_adb-tls-connect` mDNS service entirely — the wireless serial times out (and 5555, if not still armed, is down too), so installs in that state go over USB; `adb tcpip 5555` re-arms the legacy endpoint afterwards and the gateway watchdog reconnects on its own (done for Rich — 192.168.50.42:5555 live again).
- **Matt's phone wireless ADB (field-verified Sep 30, 2026):** Matt's RedMagic 9 (`FY24031100BE`, Android 16) is Wi-Fi-connected the same way: wireless debugging is ON and the Mainframe is TLS-paired (pair GUID **`adb-FY24031100BE-usU8h8`**, pairing is persistent across reboots). The phone serves two endpoints: legacy tcpip adbd on **5555** (static, currently armed — resets on reboot like Rich's) and the paired TLS wireless-debugging endpoint on a **dynamic** port (33679 at pairing time — re-discover with `adb mdns services`, connect to the `_adb-tls-connect` entry). One-touch recovery with no USB: `freeroam/polaris-gateway/rearm_phones.sh` (`--tcpip` also tries re-arming 5555 over the wireless transport; it re-asserts Matt's ACCESS_FINE_LOCATION grant and relaunches the app if it died). The gateway container's `run_gateway.sh` watchdog now arms **both** phones on 5555 (Rich 192.168.50.42 | Matt 192.168.50.98); from the sandbox only that 5555 path exists (Debian adb v28, no mDNS/TLS).
- **Watch tether + HUD (v3.3; play-state model v3.5.2; HUD model v3.7.1/v3.7.2):** the AVRCP media session stays listed with the silent tether track, whose title now **renders the current HUD state** (`lastPolarisTitle`: her live streaming text mid-speech, her full reply at completion — re-seeds carry the HUD instead of stomping it). While she speaks, accumulated WS deltas retitle the track every 1.5 s — **HUD-first since v3.7.2**: each chunk is pushed to the watch BEFORE it enters Kokoro, so the first thing the watch sees is her actual first words (the "Polaris is speaking..." placeholder race is dead). When her voice drains the deltas have already stopped — nothing re-pushes mid-drain until completion (the very bug v3.7.2 fixed), the completed reply re-pushes as **pass 1 of a 3-marquee-pass loop** (fresh mediaId per pass, length-scaled cadence ~60 ms/char clamped 3–12 s; cancels on PTT / new stream-replay / STOP), after which `restoreStandbyTitle()` returns the watch to "Polaris standing by - press PLAY to talk" with a fresh mediaId — idle is deterministic PLAY-under-standby, ready for the next PTT
- **Strict text-chat loop:** speak or type → send → gateway → LLM → response → spoken aloud (v2.9 hardening: text in / text out only — voice capture just feeds the same text pipeline)
- **On-device speech pipeline (v3.1; text-only since v3.6):** `KokoroTTS` → `StreamingTtsPlayer` direct PCM playback (no audio artifacts); `PolarisAudioStore` remains only for the legacy WAV sweep

> **Gateway internals** — service architecture, full port map, baked-source policy, and `run_gateway.sh` — are documented authoritatively in [`Polaris-gateway.md`](Polaris-gateway.md). This file covers the end-to-end system with emphasis on the Android edge application.

## Architecture Overview

```
┌───────────────────────────────────────────────────────────────┐
│                 RED MAGIC 9 PRO (edge device)                  │
│                                                                │
│  MainActivity (◈ LGI — LOOKING GLASS)                           │
│  ├─ Header: ◈ title • POL/TTS/MIC LEDs • ⋯ kebab                │
│  ├─ ⚙ CONTROLS drawer: voice • speed • identity                 │
│  ├─ ▼ TACTICAL OPERATIONS: status-room task feed                │
│  ├─ Rolling 10-message chat window (Room-backed)                │
│  └─ Mic button • auto-speak • bubble playback • mini player    │
│                                                                │
│  On-device TTS pipeline                                        │
│  ├─ KokoroTTS            sherpa-onnx, 24 kHz mono PCM16         │
│  ├─ PolarisAudioStore    polaris_<id>.wav cache (MediaStore)    │
│  └─ MediaSessionManager  ExoPlayer playback (speed-aware)       │
│                                                                │
│  Network layer                                                │
│  ├─ PolarisWebSocketClient  ws://192.168.50.51:8082             │
│  │    (chat events, stateful auto-reconnect)                    │
│  ├─ ChatRepository          "dumb terminal" over gateway API    │
│  └─ NetworkRepository / PolarisGateway (REST endpoints)         │
│                                                                │
│  Voice capture (walkie-talkie input)                           │
│  ├─ VoiceCaptureController  SpeechRecognizer STT + partials    │
│  │    PARTIAL wake lock • 30 s cap • 2.5 s end-of-speech        │
│  └─ MediaSessionManager     WatchSessionPlayer: watch PLAY/    │
│       PAUSE → toggleVoiceCapture() (AVRCP media buttons)       │
│                                                                │
│  Tactical services                                             │
│  ├─ TacticalBackgroundService  mic FGS (dataSync|microphone)   │
│  │    started by MainActivity.onStart() (START_STICKY)         │
│  ├─ ClipboardMonitorService    clipboard → TTS readout         │
│  ├─ GpsForegroundService      location FGS (v3.8)              │
│  │    owns GpsReporter: 15 s fix POSTs → Burst Service :8083   │
│  │    LAN primary · WG-hub 10.20.30.1 fallback per-POST flip   │
│                                                                │
│  Local storage                                                │
│  ├─ ConversationDatabase (Room)   chat_db                      │
│  └─ ChatArchiverWorker             periodic transcript archive  │
│                                                                │
│  Overlays: MiniPlayerView (floating bottom)                     │
└───────────────────────────────────────────────────────────────┘
                          │ LAN (192.168.50.x)
                          ▼
┌───────────────────────────────────────────────────────────────┐
│              POLARIS GATEWAY (192.168.50.51)                    │
│  ├─ Chat Gateway (Flask-SocketIO)   host port 8082             │
│  ├─ Burst Service                   host port 8083             │
│  ├─ Sandbox Service                 host port 7007             │
│  ├─ Ollama LLM backend              port 11434                 │
│  └─ Per-user context under /data/freeroam/<user>/               │
│      (profiles • transcripts • behavior libs)                  │
└───────────────────────────────────────────────────────────────┘
                          ▼
┌───────────────────────────────────────────────────────────────┐
│         POLARIS DASHBOARD (Next.js 14, port 7000)              │
│  ├─ Real-time chat monitoring across all users                  │
│  ├─ Multi-stream visualization (Rich / Matt / Operator)         │
│  └─ Operator transcript + history API access                    │
└───────────────────────────────────────────────────────────────┘
```

## User Identity Mapping

### Automatic IP-Based Detection (Android App)

The FreeRoam Android app **automatically detects user identity** from the device's local WiFi IP address — no login, no configuration:

| Device IP Last Octet | User Identity | Notes |
|---------------------|---------------|-------|
| `.42` | Rich | Rich's Red Magic 9 Pro |
| `.98` | Matt | Matt's device |
| Other | Rich (fallback) | Unknown devices default to Rich (logged) |

- Before an IP is resolved, the identity chip shows the placeholder `"Dashboard"`.
- Detected `userIdentity` + `deviceIpAddress` are stored in the WebSocket client and reused verbatim on reconnect — identity never drifts between sessions.
- Tap the identity button (now hosted inside the ⚙ CONTROLS drawer since the LGI look, v3.10) to confirm: `Connected as: <identity> (<ip>)`

### Platform Summary

| Platform | User Identity | IP Address | Gateway Profile |
|----------|--------------|------------|------------------|
| FreeRoam (Rich's Red Magic 9 Pro) | Rich | 192.168.50.42 | `/data/freeroam/rich/` |
| FreeRoam (Matt's device) | Matt | 192.168.50.98 | `/data/freeroam/matt/` |
| Dashboard (Operator) | Operator | 192.168.50.51 | `/data/freeroam/operator/` |

### WireGuard Tunnel Origin (server-side, restored Oct 3, 2026)

The IP-detection above is client-side (the app reads its own WLAN IP). When the phone rides the WireGuard tunnel instead, packets reach the gateway MASQUERADE'd/hairpinned and the gateway resolves them server-side — endpoint-agnostic, no app change required (live-verified Oct 3, 2026 over the rich-cell profile on home WiFi):

| Gateway-visible source | Resolves to |
|---|---|
| `10.20.30.3` (Rich tunnel IP), `172.18.0.1` (docker-proxy hairpin catch-all), `192.168.50.1` (router-LAN SNAT with WG off at home) | Rich |
| `10.20.30.2` (Matt tunnel IP) | Matt |

WS sessions additionally announce the app's resolved identity explicitly and win over the IP-derived mapping; the full matrix lives in [`Polaris-gateway.md`](Polaris-gateway.md) §1.

The **GPS feed rides the same tunnel** (v3.8): `GpsReporter` POSTs the fix every 15 s to `POST /api/gps` (Burst Receiver, host port 8083 → container 5001) with **no device key** in the payload — the server resolves the reporter from the source IP against exactly the matrix above (`10.20.30.3` → rich). The app even carries a two-endpoint flip for this: every POST targets the **LAN primary** (`192.168.50.51:8083`), any failure flips to the **WireGuard-hub fallback** (`10.20.30.1:8083`, `POLARIS_WG_SERVER_IP`), and the next success restores the LAN URL — so an off-Wi-Fi roam never stalls the feed, it just needs the tunnel up. **Field test PASSED (Oct 11, late):** end-of-property walk with Wi-Fi off and the WG tunnel toggled on while v3.8's location FGS kept posting — Polaris pulled the operator's live coordinates cleanly; the LAN → WG-hub flip and IP-based identity (`10.20.30.3` → rich) both held in the field.

## Key Features

### 1. Zero-Config Multi-User Support

- **Same APK for all users** — identity is derived automatically from the LAN IP
- Each user maintains a separate server-side chat history and gateway profile
- Unknown devices fall back to the Rich identity client-side (logged, not blocked)
- **Reinstall fragility (field-verified Sep 30, 2026):** runtime grants survive `adb install -r` but NOT uninstall/reinstall or "clear data" — a reset APK boots with `Dashboard` → identity falls back to `Rich` until ACCESS_FINE_LOCATION is granted and the app restarts. Repair:
  `adb -s 192.168.50.98:5555 shell pm grant com.freeroam.tactical android.permission.ACCESS_FINE_LOCATION && adb -s 192.168.50.98:5555 shell am force-stop com.freeroam.tactical ; adb -s 192.168.50.98:5555 shell monkey -p com.freeroam.tactical -c android.intent.category.LAUNCHER 1` — or just run `rearm_phones.sh`, which self-heals this (grant re-assert + relaunch) whenever Matt's phone is reachable.

### 2. Walkie-Talkie Flow (v3.3)

```
Voice input (v3.3): watch PLAY/PAUSE press or mic button tap
  → toggleVoiceCapture() → duck her TTS → VoiceCaptureController.startListening()
      → live partials stream into the input field (mic icon tinted red)
  → second press/tap → stopListening() → final transcript → auto-send

Text input
User types text → [Send]
  → PolarisWebSocketClient.sendMessage()              ("✓ Sent" toast)
  → Polaris Gateway :8082 → Ollama LLM (user profile + behavior lib)
  → response broadcast → onChatMessage()
  → auto-speak gate: only AI messages < 5 s old are spoken
  → synthesizeMessageToFile(autoPlay = true)
      ├─ KokoroTTS.setVoice / setSpeed
      ├─ KokoroTTS.synthesize(text)     → 24 kHz mono PCM16
      ├─ PolarisAudioStore.savePcmAsWav → polaris_<id>.wav (MediaStore)
      └─ playPolarisMessage()
          └─ MediaSessionManager.playUri(uri, currentSpeed)  [ExoPlayer]
  → PolarisAvatarView (AI face) + PolarisMiniPlayerView (controls)
  → message bubble becomes replayable audio
  → natural end: STATE_ENDED → 600 ms → watch tether (pause + rewind to 0:00)
      → Garmin keeps marquee-scrolling her reply as the track title
```

### 3. Audio Bubble Playback (text-only since v3.6)

- Tap any Polaris message bubble to toggle playback (`toggleBubblePlayback`) — per-bubble ▶/⏸ (`ic_play`/`ic_pause`)
- No WAV artifacts in the chat path: every replay **re-synthesizes from text** through the shared Kokoro `speakText` engine (play → pause → resume → restart across taps)
- Streaming replies speak live as they arrive and hand off to this same replay path on completion; `PolarisAudioStore` remains only for the legacy WAV sweep

### 4. Mini Player

- `PolarisMiniPlayerView` overlay hosts play/pause, stop, close, and seek controls
- Drives the shared ExoPlayer instance held by `MediaSessionManager`

### 5. Auto-Speak Behavior

- Fresh AI responses (age < 5 seconds) are spoken automatically
- The 5-second gate prevents re-speaking old messages when the rolling window or chat history rebuilds
- During periodic history sync, **genuinely new** Polaris messages received from other devices are spoken, with a 1-second `lastSpokenTimestamp` dedupe gate preventing double-speak

### 6. Voice Options

- **Default:** `af_emma` • **Alternatives:** `af_heart`, `af_sarah`, `af_bella`
- Selector in the ⚙ CONTROLS drawer (⋯ kebab) since the LGI look (v3.10); the **VOICE** chip in the drawer mirrors the active voice; applies to all subsequent synthesis

### 7. Speed Control

- Slider range 0–100 maps to **1.0x – 2.0x** via `speed = 1.0 + (progress / 100.0)`
- Default: **1.0x** (slider at 0)
- Applied to both the ExoPlayer playback rate (`setPlaybackSpeed`) and the TTS engine (`setSpeed`)
- Slider lives in the ⚙ CONTROLS drawer (LGI look, v3.10); the **SPD** chip in the TACTICAL OPERATIONS strip mirrors the rate live on every slider change

### 8. Rolling 10-Message Window

- The chat displays only the **last 10 messages** (`MAX_CHAT_MESSAGES = 10`)
- Applies identically to new incoming messages and loaded history (chronological, trimmed to the most recent 10)
- Pruning is UI-only — server-side storage retains the full history

### 9. Clipboard Readout (Control+S Equivalent)

- `ClipboardMonitorService` mirrors the X17 laptop's Control+S hotkey on Android
- Reads the primary clipboard, synthesizes via KokoroTTS, and plays through Kokoro's built-in AudioTrack player with an amplitude-tracked completion callback
- Skips empty or unchanged clipboard content; auto-stops after playback; absorbs native ONNX init failures so the process survives model problems

### 10. Garmin Watch Media Controls & Push-to-Talk

- **PLAY/PAUSE → push-to-talk toggle** (v3.3): 1st press starts a capture, 2nd press stops + sends — works with the screen locked
- **STOP → cancel capture** (mid-PTT, nothing sent) or **halt playback tethered** — the session stays listed and her reply keeps scrolling
- **BACK / NEXT → disabled** (v3.3): stripped from the advertised AVRCP commands; presses are logged and dropped
- **Watch tether + HUD loop (v3.7.1/v3.7.2):** the media session never dies — the seed carries the current HUD (her live words mid-speech, her reply after completion), a finished reply loops **3 marquee passes** then returns to standing-by, and her words reach the watch **before** synthesis (details: [Garmin Watch PTT Integration](#garmin-watch-push-to-talk-ptt-integration))

### 11. Smart WebSocket Reconnection

- Stateful auto-reconnect with no identity drift: on reconnect, the client re-joins with the same stored `userIdentity` / `userIp`
- Connection is kept alive across app pause; on resume, reconnect occurs only if actually disconnected
- **Identity-room delivery (Oct 1, 2026):** every connection joins two rooms — the legacy IP room (now sender-only for error emits) and a persistent `room=user_identity` broadcast room keyed by the resolved identity (Rich / Matt / Operator). Outgoing replies and `chat_stream` deltas blast to the identity room: push-instant cross-device delivery with no identity drift. Gateway-log signature per connect: `WS connect (rooms: <ip>, <identity>)`, `Identity announce: '<raw>' -> resolved as '<identity>'`, and history polls stamped `Identity override: user_id='<identity>'`
- **History polarity fix (v1.0.5):** `chat_history` replay rows are classified by the shared `parseHistorySenderPolarity(role, sender)` — role first, then sender, both case-insensitive (gateway rows carry `"sender": "Polaris"`, capital P); unknown senders fail-safe AI-side, so a wake-reconnect re-seed can never paint the window "You" again (full detail in the polarity bullet at the top of this file)

### 12. LGI Tactical HUD — the Unified Look (Oct 5, 2026)

The phone's UI is now a faithful port of the **LGI HUD** (`LGI/hud_ui.py` v1.4 QSS tokens + `gateway_feed.py` TASK_ROW_STYLES) — the same ops-terminal chrome as the X17 overlay and the dashboard Holodeck split-view. `activity_main.xml` is a glass stack (`lgi_bg`) with weights **45 / 37 / 16** (chat / ops / input):

```
◈ LGI — LOOKING GLASS · ●POL ●TTS ●MIC            [⋯]
──────────────────────────────────────────────────────
 CHAT (weight 45 · lgi_deep · 10-message window)
 ▼ TACTICAL OPERATIONS (weight 37 · lgi_ops_bg)
   [▼ toggle]              N ACTIVE · HH:mm   [●/○ FEED]
   task feed rows (newest first):
     ▶ voice-pipeline       12:03:41
     ⚙ ssh_execute → ok     12:03:38
   SPD: 1.0x                                MIC: IDLE
 [ input (lgi_input_bg) ]   (mic)   (send)
```

**Header strip** — cyan `◈ LGI — LOOKING GLASS` title (`lgi_hud_title`), then 9dp dot LEDs (`led_on` green `#22c55e` / `led_off` dim `#64748b`):

| LED | Semantics (code hooks) |
|-----|------------------------|
| **POL** | Gateway link state — on with `CONNECTED`, off with `DISCONNECTED` (its label doubles as the classic red/green monospace status text) |
| **TTS** | She is speaking — on at whole-text speak + streaming begin; off on stream end, STOP, replay halt, and natural playback end |
| **MIC** | Capture lifecycle — on at PTT start, off on every terminal path; the MIC chip flips `IDLE` ↔ `LISTENING` |
| **FEED** (ops header) | Status-room pipe — follows the same link state as POL |

**⋯ kebab → ⚙ CONTROLS drawer** (hidden pull-down, `lgi_panel_bg`): voice spinner (`af_emma` leads), speed slider (0–100 ≡ 1.0x–2.0x with a live `x.x` value), identity person button — plus the VOICE chip.

**▼ TACTICAL OPERATIONS pane:**

- `▼/▶ TACTICAL OPERATIONS` toggle (`lgi_ops_title_open/closed`) collapses the feed container
- **Task feed** — gateway status-room events, newest-first monospace one-liners (`icon  title[ · N%]  HH:mm:ss`, 10sp, single line, middle-ellipsis). Kinds + colors are a verbatim port of `gateway_feed.py TASK_ROW_STYLES`:

| Kind | Icon | Color |
|------|------|-------|
| `task_registered` | ◇ | `#93c5fd` |
| `task_started` | ▶ | `#22d3ee` |
| `task_progress` | ⟳ | `#f59e0b` |
| `task_completed` | ✓ | `#22c55e` |
| `task_failed` | ✗ | `#ef4444` |
| `chat_tool` | ⚙ | `#c084fc` |

- Unknown kinds fall back to `•`; the row title resolves title → name → task → id → "task event"; progress 0–99 appends ` · N%`
- Rolling cap **50 rows** (`MAX_TASK_FEED_ROWS`, LGI `TASKFEED_WINDOW`); placeholders: `waiting for the status room…` / `no task events — Polaris is calm`
- **Status line** `N ACTIVE · HH:mm` — seeded by the `active_tasks` join snapshot, then ±1 on `task_started` / `task_completed`+`task_failed`
- **Chips strip** under the feed: **SPD** (mirrors the slider live) + **MIC** (IDLE/LISTENING); the **VOICE** chip lives in the ⚙ drawer row and mirrors the active Kokoro voice on connect + change

**Status-room plumbing (`PolarisWebSocketClient`, additive — chat paths untouched):** every (re)connect emits `join_status_room` (`room: "status"`); on connect the client registers `active_tasks`, `tool_execution`, and `chat_tool_report` listeners plus one per `task_*` kind (5), with symmetric `off()`s on disconnect. The gateway broadcasts task lifecycle/tool runs ONLY to room-`status` members (`join_status_room` / `join_tasks` / `leave_status_room`); `tool_execution` + `chat_tool_report` render as the ⚙ CHAT_TOOL rows.

**LGI chat tinting (`rebuildChatUI`):** user bubbles keep the reserved deep-navy `bubble_user` with `#f8fafc` text (`lgi_you_text`); Polaris bubbles run bright ice-blue `#9ae6ff` text (`lgi_polaris_text`); sender labels `YOU` in ledtag grey `#64748b` / `POLARIS` in title cyan `#00e5ff`.

**Enabling assets:**

| Asset | Where |
|-------|-------|
| 23 `lgi_*` strings (HUD title, LED tags, kebab/drawer labels, ops titles, task-feed lines, chips, mic/TTS states) | `res/values/strings.xml` |
| LGI QSS-token colors transcribed from `hud_ui.py build_hud_qss` (glass `lgi_bg #0a0e14f0`, `lgi_deep`, `lgi_panel`, `lgi_border*`, `lgi_text`/`lgi_input_text`/`lgi_you_text`/`lgi_polaris_text`, `lgi_slider_groove`, alert trio) + 6 task-kind colors + LED trio `lgi_led_on/linking/dim` | `res/values/colors.xml` |
| `led_on` / `led_off` dots + `lgi_panel_bg` / `lgi_input_bg` / `lgi_ops_bg` surfaces | `res/drawable/` |
| `hudKebab`, `opsToggle`, `opsContainer`, `taskFeedContainer`, `taskStatusLine`, `pol/tts/mic/feedLed`, `voiceChip`/`speedChip`/`micChip` ids | `res/layout/activity_main.xml` |

**Retired by this look:** the `PolarisAvatarView` Eve-face overlay left the layout (class retained, unwired — the HUD chrome replaced it).

---

## Android App Components

All paths relative to `android/app/src/main/java/com/freeroam/tactical/`:

| Component | Path | Role |
|-----------|------|------|
| `MainActivity` | `MainActivity.kt` | UI, chat orchestration, voice push-to-talk toggle (`toggleVoiceCapture()`), auto-speak gating, bubble playback, speed control, LGI HUD host (header LEDs, ⋯ drawer, ▼ TACTICAL OPERATIONS feed, chips) |
| `KokoroTTS` | `voice/KokoroTTS.kt` | On-device TTS engine (sherpa-onnx, 24 kHz mono PCM16) |
| `PolarisAudioStore` | `media/PolarisAudioStore.kt` | Legacy WAV cache (`polaris_<id>.wav`) via MediaStore, app-dir fallback — demoted by v3.6's text-only playback (chat replay re-synthesizes from text) |
| `VoiceCaptureController` | `voice/VoiceCaptureController.kt` | Push-to-talk STT (`SpeechRecognizer` wrapper, PARTIAL wake lock, safety timeouts) |
| `MediaSessionManager` | `media/MediaSessionManager.kt` | ExoPlayer/Media3 session playback + watch AVRCP media-button routing (v3.3: PLAY/PAUSE = push-to-talk toggle, STOP = cancel/halt, BACK/NEXT disabled) + the always-on paused-session tether + watch-HUD pushes (pre-Kokoro accumulator, 3-pass marquee loop → standing-by, v3.7.x) |
| `PolarisWebSocketClient` | `network/PolarisWebSocketClient.kt` | Socket.IO-style chat transport, stateful reconnection, status-room task-lifecycle plumbing (additive listeners + `join_status_room` per connect) |
| `PolarisWebSocketListener` | `network/PolarisWebSocketListener.kt` | Callback interface (onChatMessage, onChatHistory, onMessageSent, …) |
| `NetworkRepository` | `network/NetworkRepository.kt` | Gateway REST endpoint layer |
| `PolarisGateway` | `network/PolarisGateway.kt` | Gateway API definitions |
| `ChatRepository` | `data/ChatRepository.kt` | "Dumb terminal" wrapper over the gateway chat API |
| `ChatMessage` | `data/ChatMessage.kt` | Message model (user, text, timestamp, isUser) |
| `ConversationDatabase` | `database/ConversationDatabase.kt` | Room database (`chat_db`) |
| `ChatArchiverWorker` | `worker/ChatArchiverWorker.kt` | WorkManager periodic transcript archival |
| `ClipboardMonitorService` | `service/ClipboardMonitorService.kt` | Clipboard → TTS readout |
| `TacticalBackgroundService` | `service/TacticalBackgroundService.kt` | Mic-capable foreground service (`dataSync\|microphone`, notification ID 1001), started by `MainActivity.onStart()` |
| `GpsForegroundService` | `service/GpsForegroundService.kt` | Location-typed FGS (notification ID 1003, `gev_gps_channel`) that owns `GpsReporter`'s lifecycle so the feed survives screen-lock (v3.8) |
| `GpsReporter` | `gps/GpsReporter.kt` | GPS live-feed worker: 15 s fix POSTs to the Burst Receiver (LAN primary, WireGuard-hub fallback per-POST) |
| `PolarisAvatarView` | `PolarisAvatarView.kt` | Eve-style AI face overlay (hidden/active/paused states) — **unwired since the LGI look (Oct 5, 2026)**: the class remains but the overlay left `activity_main.xml` |
| `PolarisMiniPlayerView` | `ui/PolarisMiniPlayerView.kt` | Mini player overlay (play/pause/stop/close/seek) |
| `AppConfig` | `config/AppConfig.kt` | Central configuration constants |

---

## Component Details

### MainActivity UI Layout — LGI Tactical HUD (v3.10)

`activity_main.xml` is a vertical glass stack (`lgi_bg`) with weights **45 / 37 / 16** — chat / ops / input, mirroring the dashboard Holodeck split-view:

- **Header strip:** `◈ LGI — LOOKING GLASS` title → **POL** LED + connection text → processing sphere → **TTS** LED → **MIC** LED → **⋯ kebab**. LEDs are 9dp `led_on` (green `#22c55e`) / `led_off` (dim)
- **⚙ CONTROLS drawer** (hidden until ⋯): voice spinner → speed slider (0–100 ≡ 1.0x–2.0x, live `x.x` value + SPD chip) → identity person button + VOICE chip
- **Chat area (`lgi_deep`):** rolling 10-message window (5-per-side seed); LGI tinting — user bubbles deep-navy with `#f8fafc` text, Polaris bubbles with bright `#9ae6ff` text; sender labels `YOU` (grey) / `POLARIS` (cyan); per-bubble ▶ replay
- **▼ TACTICAL OPERATIONS pane (`lgi_ops_bg`):** `▼/▶` toggle + status line (`N ACTIVE · HH:mm`) + FEED LED; status-room task feed; SPD/MIC chip strip
- **Input row (`lgi_deep`):** text field (`lgi_input_bg`, `#e0f7ff`) + mic button + send; sent messages toast `✓ Sent` and clear the input
- **Mini player** floats at the bottom (hidden until playback)
- Header LEDs, the drawer, the ops pane, and the task feed are detailed in [§12 — LGI Tactical HUD](#12-lgi-tactical-hud--the-unified-look-oct-5-2026)

### KokoroTTS (`voice/KokoroTTS.kt`)

On-device neural TTS powered by **sherpa-onnx** (Kokoro model, espeak-based phonemization, int8 quantized inference). Output is **24 kHz mono 16-bit PCM**.

**Public API**

| Function | Purpose |
|----------|---------|
| `setVoice(voiceId)` | Select speaker (`af_emma` default; `af_heart` / `af_sarah` / `af_bella`) |
| `setSpeed(speed)` | Set synthesis speed (1.0–2.0) |
| `synthesize(text): ByteArray` | Generate PCM16 audio for the current voice + speed |
| `playAudio(pcmData, onComplete)` | AudioTrack playback (USAGE_MEDIA routing) with amplitude tracking and a completion callback |
| `pause()` / `resume()` / `stop()` | Playback control; `stop()` halts audio, releases the AudioTrack, and clears state |
| `setAmplitudeCallback` / `isCurrentlyPlaying()` | Visualizer support / state query |

**v3.1 fix — the static-noise bug:** PCM16 conversion (`floatToPcm`) now uses `.order(ByteOrder.nativeOrder())` on its ByteBuffer. Previously the buffer defaulted to BIG_ENDIAN, byte-swapping every 16-bit sample on little-endian ARM — speech played as static that followed the speech envelope. The fix restores true audio output.

**Robustness:** `isValidModelFile()` validates model blobs (size floor + ONNX magic byte) before native init, preventing SIGSEGV crashes from corrupt model files.

### PolarisAudioStore (`media/PolarisAudioStore.kt`)

Persistent WAV cache keyed by message ID — audio is synthesized once per message, then replayed from cache:

- `savePcmAsWav(context, pcmData, messageId, sampleRate): Uri?` — writes `polaris_<messageId>.wav` into the MediaStore `Polaris` directory (app-dir fallback)
- `findExistingWav(context, messageId): Uri?` — cache lookup (fallback path validates file length > 44-byte header)
- `buildWav()` constructs the 44-byte RIFF header (LITTLE_ENDIAN) around the PCM payload

### MediaSessionManager (`media/MediaSessionManager.kt`)

Dual-role component:

1. **Playback** — owns the shared ExoPlayer + MediaSession; `playUri(uri, speed)` respects the current speed setting; pause/resume/stop/seek exposed to the UI and mini player
2. **Wearable media buttons + watch tether (v3.3)** — `WatchSessionPlayer` (a Media3 `ForwardingPlayer`) intercepts AVRCP commands from the paired Garmin before they reach ExoPlayer, so watch buttons drive the app even when no audio is loaded. `handleWatchCommand()` maps them (500 ms debounce): PLAY/PAUSE → `onWatchPlay()` (push-to-talk toggle), STOP → `onWatchStop()` (cancel capture / tethered halt); BACK/NEXT are stripped from `getAvailableCommands()` and no-op'd. The tether keeps the session permanently AVRCP-visible: `activateSession()` seeds a silent 1 s WAV paused at 0:00, `onPlaybackStateChanged(ENDED)` rewinds to 0:00 and holds paused after 600 ms, and `stop()` pauses + rewinds instead of idling the player — her latest reply (≤ 180 chars) stays loaded as the scrolling track title. **v3.7.1/v3.7.2 HUD model:** re-seeds (STOP halt, ENDED rewind) title from `lastPolarisTitle` — the seed carries the current HUD (her live streaming text mid-speech, her full reply at completion) instead of stomping it; during `chat_stream` a pre-Kokoro accumulator (`streamHudText`) is fed at WS-arrival and each accumulated chunk is pushed as the track title (fresh mediaId, ≥ 1.5 s throttle) **before** that chunk enters Kokoro (`player.feed()`); the accumulator retires at stream end; completion runs the 3-marquee-pass loop into `restoreStandbyTitle()` ("Polaris standing by - press PLAY to talk", fresh mediaId)

### VoiceCaptureController (`voice/VoiceCaptureController.kt`)

Push-to-talk mic capture wrapping Android's `SpeechRecognizer` behind a small PTT-shaped API:

- **API:** `startListening()`, `stopListening()` (graceful flush of the final result), `cancel()` (hard abort, no result), `isListening()`, `destroy()`
- **Callbacks** (all delivered on the main thread): `onCaptureStarted()` → mic button tinted red, `onPartialResult(text)` → partials stream into the input field, `onCaptureComplete(text)` → transcript routed through `sendMessage()`, `onCaptureError(reason)` → capture UI dismissed
- **Engine:** on-device recognition preferred (API 31+, fully offline — no audio ever leaves the phone); system recognition service as fallback; no service at all → error callback, UI degrades to text input
- **Power:** holds a `PARTIAL_WAKE_LOCK` (`freeroam:voice_capture`) for the duration of a capture, released in `finishCapture()`; the lock auto-expires after `MAX_CAPTURE_DURATION + 5 s` so a missed release can never pin the CPU
- **Safety nets:** 30 s hard capture ceiling (`MAX_CAPTURE_DURATION_MS`) and a 1.5 s stop-flush timeout (`STOP_FLUSH_TIMEOUT_MS`) for OEM recognizers that never deliver a final result after `stopListening()`

### TacticalBackgroundService (`service/TacticalBackgroundService.kt`)

Microphone-typed foreground service that promotes the Free Roam session so speech captures survive **screen-lock**:

- Manifest: `android:foregroundServiceType="dataSync|microphone"` plus `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` permissions — the **microphone FGS type** is what lets `SpeechRecognizer` hold the mic while the display is off (runtime proof on the Redmagic: `dumpsys` reports `types=0x00000081` = dataSync + microphone)
- Started by `MainActivity.onStart()` via `startForegroundService()`; `onStartCommand()` re-asserts `startForeground()` idempotently on every start (API 31+ demands a matching `startForeground()` per start call)
- Silent, ongoing, low-importance notification on `tactical_service_channel`, ID 1001: "Free Roam Active — Voice capture + tactical monitoring active"
- Returns `START_STICKY`; a denied FGS start (sticky restart while fully backgrounded) is caught and logged instead of crashing
- Watch media buttons do **not** route through this service — they arrive through the Media3 `MediaSession` (`WatchSessionPlayer` in `MediaSessionManager`)

### GpsForegroundService (`service/GpsForegroundService.kt`) + GpsReporter (`gps/GpsReporter.kt`)

The GPS live-feed pipeline (v3.8) — the phone is the Burst Receiver's **only** source of position fixes, so feed survival is lifecycle work:

- `GpsForegroundService`: **location-typed FGS** promoted *while the app is foregrounded* (from `MainActivity.onStart()` + the runtime-grant callback, both foregrounded by construction) with the while-in-use location grant — on API 30+/34+ this authorizes location access continuing with the screen locked **without an `ACCESS_BACKGROUND_LOCATION` prompt** (the speech FGS is dataSync|microphone and does not carry background location on its own)
- Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` + the location-typed `<service>` entry; persistent notification **ID 1003** on `gev_gps_channel` ("Polaris GPS Feed — Operator position streaming (screen-off safe)"); `START_STICKY`; a denied promotion (fresh-install grant-dialog race) is caught and logged — degrade-don't-crash
- **Sole lifecycle owner** of `GpsReporter` (start in `onStartCommand`, stop in `onDestroy`) — the v3.5.1 foreground-only `stop()`s in `onPause`/`onDestroy` are gone
- Runtime proof: `dumpsys activity services GpsForegroundService` → `isForeground=true`, `types=0x8` (location); speech FGS unaffected (`0x81`)
- `GpsReporter` (singleton): 15 s cadence (`GPS_REPORT_INTERVAL_MS` = `GPS_LISTENER_MIN_MS` = 15 000 ms in `AppConfig`), 5 m min-distance updates, one POST in flight, freshest fix reused across cycles
- Payload: `{lat, lng, alt, speed, heading, accuracy}` (Gson; `lat`/`lng` required server-side) — **no device key**; the receiver resolves the reporter from the source IP, so the same build works for any operator
- Endpoints (per-POST flip): **LAN primary** `192.168.50.51:8083/api/gps`; on any failure `usingFallback` flips to the **WireGuard hub** `10.20.30.1:8083/api/gps` (`POLARIS_WG_SERVER_IP`); any success flips back — off-LAN roamers keep the feed over the tunnel
- Fossil guard: fixes older than `STALE_FIX_MS` (**90 s**) are skipped, never replayed — a dead provider surfaces as feed staleness, not a replayed position

### PolarisAvatarView (`PolarisAvatarView.kt`)

Eve-style AI face overlay in three states: `HIDDEN` (idle), `ACTIVE` (speaking, speech-synced), `PAUSED` (breathing pulse). Fades out when playback completes.

### Local Storage

- **Room database** `chat_db` (`ConversationDatabase`) — local message cache only; the **authoritative chat history is server-side**
- **`ChatArchiverWorker`** — WorkManager periodic job that archives transcripts server-side
- **Gateway master timeline (verified Oct 1, 2026):** the gateway holds chat histories in memory, loaded at boot from `/data/freeroam/chat_history.json` and saved per-event (boot log: `Loaded N user histories from disk`). The compose bind source for `/data/freeroam` is **`/mnt/containers/freeroam/data`** (root-owned) — NOT the `/home/causeiam/docker-containers/freeroam/data` tree, which holds only a stale Sep-11 copy that is never read at runtime. Because the timeline is server-side, wiping the phone's app data (uninstall / clear-storage) loses nothing: the app re-announces identity, rejoins its rooms, and the opening seed restores the latest 5-per-side. Saves are per-event rather than on a timer, so a hard container crash can drop only messages since the most recent write

### Backend User Context (Gateway)

The gateway maps each identity to a per-user context tree under `/data/freeroam/<user>/` (profile, transcripts, behavior libraries). See [`Polaris-gateway.md`](Polaris-gateway.md) for the authoritative service architecture.

---

## Garmin Watch Push-to-Talk (PTT) Integration

Garmin watch buttons drive the app over standard AVRCP media-button routing — no screen interaction required. **PLAY/PAUSE is the push-to-talk mic toggle and works with the screen locked** (the mic-typed `TacticalBackgroundService` keeps captures alive through screen-off). The **watch tether (v3.3)** keeps the AVRCP session alive indefinitely: the player is never parked in ENDED or IDLE, so the watch media screen permanently lists "Polaris AI" and marquee-scrolls her latest response as the track title until the next reply replaces it. **Field-verified Sep 26, 2026** — the operator's physical Garmin retest passed end-to-end: locked-screen PTT via PLAY, the tether hold minutes after a reply, and both STOP behaviors; the `REPEAT_MODE_ONE` reserve lever was not needed (the Garmin retains paused sessions indefinitely). **HUD pipeline (Oct 2026 field series, v3.7 → v3.7.2):** during `chat_stream` playback the accumulating reply retitles the track every 1.5 s with fresh mediaIds, and since v3.7.2 the HUD is fed **before** synthesis — deltas accumulate (`streamHudText`) and push first, so her first live words reach the watch inside her first spoken sentence; replays push their full text ~1–2 s before `speakText()` starts (Kokoro lead); after any streamed/replayed reply the full text runs **3 marquee passes** (length-scaled cadence; cancels on PTT / new stream-replay / STOP) and the watch returns to "Polaris standing by - press PLAY to talk". v3.7.2 (versionCode 8) deployed Oct 11, 2026 — cert-gated `adb install -r`, data lineage intact (`firstInstallTime=2026-10-04`); HUD live-words / replay-lead / drain-loop field checks await the operator's report.

### Button Mapping

| Watch Button | Command | Effect |
|--------------|---------|--------|
| PLAY / PAUSE | `PLAY` / `PAUSE` | **Push-to-talk toggle** — 1st press starts capture, 2nd press stops + sends (AVRCP sends whichever is opposite the reported state; both land in `onWatchPlay()`) |
| STOP | `STOP` | **Cancel capture** if mid-PTT (hard abort, nothing sent; `cancel()` fires no listener callback so the stop handler resets the mic tint) — otherwise **halt playback tethered** (pause + rewind to 0:00; the session stays listed) |
| BACK | `BACK` | Disabled (v3.3) — stripped from advertised commands; logged and dropped |
| NEXT | `NEXT` | Disabled (v3.3) — stripped from advertised commands; logged and dropped |

Button events are debounced at **500 ms** to prevent duplicate triggers. A silent 1 s seed WAV is loaded paused at session activation, so the watch sees a live (paused) player from launch; finished responses rewind to 0:00 and hold paused — the tether. Title metadata carries up to 180 chars of her reply for the Garmin marquee.

### Architecture

```
Garmin watch button press
  → AVRCP media-button event → Media3 MediaSession
  → WatchSessionPlayer (ForwardingPlayer) intercepts in MediaSessionManager
  → handleWatchCommand() (500 ms debounce)
  → MainActivity.watchMediaListener → onWatchPlay() → toggleVoiceCapture()
  → duck her TTS → VoiceCaptureController start / stop → transcript → sendMessage()

Watch tether (always-on AVRCP presence)
  → launch: silent 1 s seed WAV loaded paused at 0:00, title = current HUD
  → her words stream: WS delta → streamHudText accumulate → push HUD title
    (fresh mediaId, ≥ 1.5 s throttle) → THEN feed the chunk into Kokoro
  → replay: full reply pushed as the title BEFORE speakText() (~1-2 s lead)
  → response plays: her live text is the scrolling title while she talks
  → natural end: STATE_ENDED → 600 ms → pause() + seekTo(0) → READY-paused
    → completion retitle = marquee pass 1 → passes 2 + 3 (fresh mediaId each,
      ~60 ms/char cadence) → restoreStandbyTitle() = standing-by, fresh id
  → STOP: pause() + seekTo(0) — tethered halt, standing-by immediately
```

Because the Media3 `MediaSession` is owned by `MediaSessionManager`, watch events are received while the app is backgrounded — the watch does not need the app open to trigger a capture. With no listener attached, commands fall back to plain ExoPlayer control.

### Testing

All three watch-side checks below passed the operator's physical Garmin retest on Sep 26, 2026 — v3.3 is verified end-to-end.

1. ✅ Pair the watch; then lock the screen, press PLAY, speak, press PLAY again — the transcript is sent to Polaris and answered aloud
2. ✅ After the reply finishes, raise the wrist minutes later — the watch still lists "Polaris AI" with her reply marquee-scrolling as the title (the tether hold)
3. ✅ Press STOP mid-capture — the mic tint resets on the phone and nothing is sent; press STOP during playback — audio halts but the player stays listed on the watch
4. In-app, confirm the mic tint + live partials in the input field, and inspect logcat:

```bash
adb logcat | grep -E "MediaSessionManager|VoiceCaptureController|TacticalBackgroundService"
```

Expected: `MediaSession activated - wearable AVRCP commands live` on launch, `Watch command … routed to listener` per press, and `VoiceCaptureController` listening/stop logs for PLAY/PAUSE toggles.

**Redmagic logcat caveat:** vendor sensor spam rotates the logcat buffer within seconds, flushing app logs out almost immediately — an empty grep does **not** mean the session is dead. Grow the buffer before a capture window (`adb logcat -G 16M`), or skip logs entirely and use the authoritative log-free check:

```bash
adb shell dumpsys media_session
```

A healthy tether shows our Media3 session as the active media-button session for `com.freeroam.tactical`: `state=PAUSED(2)`, `position=0`, metadata **"Polaris standing by - press PLAY to talk"**. Ignore any stale `ERROR(7) "Bluetooth audio disconnected"` record with null metadata — it belongs to another (inactive, pre-install) session, not ours.

---

## Build System

### Native Gradle build (host path — **the operative pipeline** for the device's host-key lineage; the Oct-10/11 v3.6.3 → v3.7.2 series shipped through this path — see the keystore lineage notes at the top)

The Gradle project lives under `android/`; the Android SDK is at `/home/causeiam/android-sdk` (wired via `android/local.properties`).

```bash
cd /home/causeiam/docker-containers/freeroam/android

./build-apk.sh      # wraps: ./gradlew :app:assembleDebug --no-daemon

# Output
ls -lh app/build/outputs/apk/debug/app-debug.apk   # ~234 MB (unminified debug)

# Pre-install gates — version + signature; never skip (v3.7.2 deploy recipe)
BT=/home/causeiam/android-sdk/build-tools/35.0.0
$BT/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep package:
#   -> versionCode='8' versionName='1.0.5'
$BT/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
#   -> cert SHA-256 must equal the device lineage (7c02562a49c8da3f…583658e50a)
#   (env vars do NOT persist across separately-run commands — hardcode $BT)

# Install over the existing install (in-place upgrade, data preserved)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Matt's phone over WiFi (no cable): 5555 while tcpip is armed, or the mDNS
# TLS serial after a phone reboot (run rearm_phones.sh to re-establish)
#   ~/platform-tools/adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
# -r preserves the ACCESS_FINE_LOCATION grant; verify the identity chip shows Matt
```

- **Toolchain:** Gradle 9.2 wrapper, `compileSdk 35`, JDK 17 toolchain, `minSdk 26`, `targetSdk 34`
- **Package:** `com.freeroam.tactical`, `versionName 1.0.5` (`versionCode 8`) — doc version **v3.11** (Oct 11, 2026: full doc sync to the current architecture — HUD-first speech pipeline, host-key lineage operative, versionCode 8 deployed via this host path; see Version History)
- **Signature-mismatch reinstall (field-verified Oct 1, 2026):** `adb install -r` over the previously installed Sep 28 build failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — that build was signed by a different keystore (container-built: committed `freeroam-debug.keystore`; host builds sign with `~/.android/debug.keystore` — see the "two signing worlds" note at the top). Recovery: full `adb uninstall` + fresh install. The wipe is harmless by design — only the on-device Room cache is lost; the app re-announces identity, rejoins its rooms, and the Phase-2 opening seed restores the recent timeline from the gateway. The phone then held the host-signed build (host rebuilds would have upgraded in place) — **superseded Oct 3, 2026:** that host key was regenerated/lost in the interim, and the v1.0.5 fresh install re-pinned the device to the committed container keystore; **superseded again Oct 11, 2026:** the device lineage is now the **host** key (operative since the Oct 4, 2026 first install — the "Android build signing" line at the top carries the authoritative key lineage), so the committed-key container path is the mismatch world today
- **Size note:** the debug APK is unminified and bundles sherpa-onnx JNI libraries for every ABI → ~234 MB. The release build type already has ProGuard enabled (`isMinifyEnabled = true`); a minified release build will shrink it considerably when a daily-driver APK is wanted.
- **Crash logs:** `./get_crash_log.sh` (repo root)
- Build logs land in `android/build/` intermediates (`build.log` / `build-output/` at the repo root belong to the legacy container build)

### Container Docker build (legacy v1.0.5 pinned pipeline — no longer matches the device lineage)

The Docker path is fully containerized (Dockerfile-baked Gradle + SDK). Since Oct 3, 2026 it is the pinned-signature pipeline **(legacy era: this matching worked only while the device held the container pin — the phone re-pinned to the host-key lineage with its Oct 4, 2026 first install, so today a container-built APK will NOT upgrade it in place)**: the Dockerfile compiles the APK inside the image build (~6.5 min) and bakes the committed `android/app/freeroam-debug.keystore` to `/root/.android/debug.keystore` (Dockerfile line 63), so every container build signs with the SAME key — cert-verified Oct 3: the v1.0.5 build cert (SHA-1 `acf51847…`) matches the committed keystore exactly. `./build.sh` wraps it (`docker build -t freeroam-android:latest .`, log `/tmp/freeroam-build.log`); extract the baked APK via a one-shot container and install:

```bash
cd /home/causeiam/docker-containers/freeroam
./build.sh   # bakes freeroam-android:latest with the pinned keystore inside
# extract the baked APK (one-shot container), then install:
docker run -d --name freeroam-tmp freeroam-android:latest sleep Infinity
docker cp freeroam-tmp:/app/android/app/build/outputs/apk/debug/app-debug.apk \
  /tmp/freeroam-v105.apk
docker rm -f freeroam-tmp
adb install /tmp/freeroam-v105.apk           # fresh install
adb install -r /tmp/freeroam-v105.apk        # in-place upgrade from any pinned-signed install
```

Stage the artifact into the repo as `freeroam/freeroam-debug.apk` so the built app travels with the workspace. **Lineage warning (updated Oct 11, 2026):** with the device on the **host-key lineage**, artifacts from this container path (and from `./docker-build.sh`, a disposable SDK container that pins nothing) will never in-place-upgrade (or be upgraded by) the phone without a full uninstall + reinstall — the mismatch world has moved to the container side. Only host Gradle builds via `./build-apk.sh` upgrade the phone in place today; keep the committed container keystore for archive reproducibility of the v1.0.5-6 era only.

## Version History

| Version | Date | Changes |
|---------|------|---------|
| doc v3.12 / APK v3.8 | Oct 11, 2026 | **GPS feed never sleeps — dedicated location FGS (versionCode 9, v1.0.6):** operator: "Polaris said my GPS is 17 min old?" — root cause: the v3.5.1 foreground-only design stopped the GPS feed at every `onPause`/`onDestroy`, so the passive Burst Receiver kept aging a fossil fix whenever the screen locked; the gateway has no independent GPS source (phone POSTs are the only feed) and Polaris reported the true age. Fix: **`.service.GpsForegroundService`** (location-typed FGS, notification id 1003 on `gev_gps_channel`, `START_STICKY`, degrade-don't-crash promotion) now **owns `GpsReporter`'s lifecycle** (start in `onStartCommand`, stop in `onDestroy`); promoted from `MainActivity.onStart()` + the runtime-grant callback while the app is foregrounded with while-in-use location — which on API 30+/34+ authorizes background location continuation **without an `ACCESS_BACKGROUND_LOCATION` prompt**; the foreground-only `stop()`s in `onPause`/`onDestroy` are gone; manifest adds `FOREGROUND_SERVICE_LOCATION` + the location-typed `<service>` entry. 15 s POST cadence unchanged (fresher than a 30 s pull). Deployed via host Gradle + pinned gates: `aapt` versionCode 9 ✓, `apksigner` SHA-256 `7c02562a…e50a` ✓, `adb install -r` Success in place (`firstInstallTime=2026-10-04` preserved, grants intact); dumpsys: `GpsForegroundService` isForeground=true `types=0x8` (location), speech FGS unaffected (0x81); gateway `age_seconds` 6.7 / `stale:false` on relaunch. Operator soak **"so far so good"** held all evening; decisive **WireGuard walk test PASSED** (Oct 11, late): back of the property, Wi-Fi off, tunnel on — Polaris pulled the operator's live coordinates; LAN → WG-hub endpoint flip + IP-identity resolution verified end-to-end in the field |
| doc v3.11 | Oct 11, 2026 | **Full doc sync — no APK change (versionCode 8 stands):** freeroam.md rewritten to the current architecture — (1) status header + Build System re-based on the **host Gradle pipeline** (`./build-apk.sh` + build-tools 35.0.0 aapt/apksigner gates) with the device lineage now the **host `~/.android/debug.keystore`** (SHA-256 `7c02562a…e50a`, operative since the Oct 4, 2026 first install; committed `freeroam-debug.keystore` = legacy v1.0.5 container pin, no longer upgrade-safe); (2) the HUD-first Kokoro-second speech pipeline documented across Overview / tether bullet / MediaSessionManager / Garmin sections (`streamHudText` accumulate → push → feed, 1.5 s streaming retitles, replay full-text lead, post-playback 3-pass marquee → `restoreStandbyTitle()` — mirrors the v3.7 / v3.7.1 / v3.7.2 rows below); (3) field reports logged Oct 11: GPS live-feed verified accurate end-to-end (Polaris noticed the operator walking away from the phone), double-tap PTT voice loss recurred once — a same-press self-heal escape — **parked** by operator ruling; HUD live-words / replay-lead / drain-loop checks await the operator's report |
| v3.7.2 | Oct 11, 2026 | **v3.7.2 HUD-first, Kokoro-second: her words hit the watch before synthesis (operator field-report fix)** — the watch held **"Polaris is speaking..." while she talked**: on the live-stream path the placeholder and the first live slice were stamped as two AVRCP "new track" changes inside one frame and the watch coalesced the burst onto the placeholder, which then stuck for the whole speech (deltas stop arriving seconds before her voice drains — nothing re-pushes until completion); on the replay path the placeholder was pushed once at `speakPolarisText` start and no mid-speech HUD push ever ran. Fix (operator ruling: "feed the text to the watch before we send it through kokoro so the display has time to start streaming her response as she starts speaking"): a **pre-Kokoro accumulator** (`streamHudText`) fed at WS-arrival — per delta the order is now accumulate → **push HUD title** → `player.feed()` into Kokoro, so the HUD lags her typing, never the synthesis pipeline; the **first thing the watch sees is her actual first words** (placeholder push deleted — race dead); replays title with their **full text before `speakText()`** (~1–2 s of Kokoro lead before her first sentence speaks, matching the "whole-text replays titled at play start" behavior v3.6.3 had made false); `startStreamingPlayback` guards stale/superseded deltas off the accumulator (`isActiveFor`); accumulator + throttle retire at stream end; `pushWatchHudTitle` reads the accumulator (player param dropped). Completion loop → standing-by ruling unchanged. `versionCode 8` |
| v3.7.1 | Oct 11, 2026 | **v3.6.4 HUD close-out: post-playback reply loop → standing by (operator field-report fix)** — the watch "flashed then reverted" and sat at "Polaris standing by almost all the time": the silent-tether seed **hardcoded** its track title, so every re-seed (STOP halt, ENDED rewind) stomped the streaming pushes. The seed now titles from `lastPolarisTitle` (the current watch HUD: her live text mid-speech, her reply at completion) — code finally matches the v3.5.1/v3.5.2 comments that always claimed re-seeds carry her latest reply. New **post-playback loop** (operator ruling: "once it plays a response, have it loop the latest response 3 times then go to Polaris standing by"): when a streamed (or bubble-replay) reply finishes speaking, the full response gets **3 marquee passes** on the Garmin — the completion retitle is pass one, then each pass re-pushes as a fresh track (fresh mediaId → the watch reliably re-scrolls) at a length-scaled cadence (~60 ms/char, clamped 3–12 s), after which the new `restoreStandbyTitle()` pushes "Polaris standing by - press PLAY to talk" with a fresh mediaId: idle = deterministic PLAY-under-standby, ready for the next PTT. The loop cancels on any PTT press, new stream/replay, or STOP (STOP = standing-by immediately), and on teardown. `versionCode 7` |
| v3.7 | Oct 11, 2026 | **Same-press voice self-heal + live watch HUD streaming (operator field-triggered: a double-tap PTT press lost its speech-to-text once, second press worked)** — diagnosis: NOT the v3.6 echo guard (the 2 s window correctly collapses the double-tap) but the documented RedMagic on-device engine wedge ("no callbacks at all"): the capture ran, heard nothing, and the v1.0.4 self-heal deliberately cost one failed press before hopping to the cloud recognizer. v3.6.3 removes the lost press: a **2 s warmup watchdog** — zero callbacks of any kind (ready-for-speech / RMS / beginning-of-speech) since `startListening()` means the engine never engaged — hops engines and restarts the capture **within the same press** (red "Listening..." never drops, budget 2 per press); timer-driven blank captures (silence watchdog / 30 s cap / stop-flush) and retryable recognizer errors with no words yet auto-retry on the other engine the same way (ERROR_INSUFFICIENT_PERMISSIONS/ERROR_AUDIO excluded — permanent/hardware); a wedge that hits mid-speech folds the lost **live partial** back into the running transcript so the restart continues the operator's sentence; a manual stop on an empty press still reports honestly. **Watch HUD streaming** — the pre-v3.5.2 "no mid-speech retitles" ban is retired (the churn predates fresh-mediaId retitles): during `chat_stream` playback the accumulating reply retitles the track every 1.5 s (tail-sliced ≤180 chars, skipped when identical, fresh mediaId per push = a "new track" the Garmin refreshes), so her words type across the watch marquee WHILE she generates; "Polaris is speaking..." holds until the first delta and the full reply takes the title at completion as before |
| v3.10 | Oct 5, 2026 | **Unified LGI look (app v1.0.5 rebuilt + redeployed, operator-approved "LOOKS AWESOME!")** — `activity_main.xml` rebuilt as a port of the LGI HUD (`hud_ui.py` v1.4 QSS + `gateway_feed.py` TASK_ROW_STYLES): ◈ LGI — LOOKING GLASS header (cyan `lgi_title` 12sp) with POL/TTS/MIC 9dp dot LEDs (`led_on` green #22c55e / `led_off` dim; POL+FEED follow the gateway link state, TTS fires on speak/stream-begin and drains on stream-end/STOP/replay-halt/natural-end, MIC flips with the capture + IDLE↔LISTENING chip), ⋯ kebab → hidden ⚙ CONTROLS drawer (voice spinner, 0–100 speed slider ≡ 1.0x–2.0x with live SPD-chip mirror, identity person button), split-view weights 45/37/16 (chat / TACTICAL OPERATIONS / input) per the dashboard Holodeck, ▼/▶ toggle collapses the ops pane, and the gateway **status-room task feed** (newest-first monospace one-liners `icon title[ · pct] HH:mm:ss`; ◇ registered #93c5fd / ▶ started #22d3ee / ⟳ progress #f59e0b / ✓ completed #22c55e / ✗ failed #ef4444 / ⚙ chat_tool #c084fc — TASK_ROW_STYLES parity, unknown kinds fall back to `•`; 50-row rolling cap; `N ACTIVE · HH:mm` status line; `active_tasks` join snapshot; `tool_execution`/`chat_tool_report` ⚙ CHAT_TOOL rows). **PolarisWebSocketClient plumbing is additive** (chat paths untouched): connect emits `join_status_room`, registers `active_tasks` + `tool_execution` + `chat_tool_report` + one listener per `task_*` kind; `disconnect()` offs symmetrically. **LGI chat tinting:** user `#f8fafc` on deep-navy `bubble_user`, Polaris bright `#9ae6ff`, sender labels YOU ledtag-grey / POLARIS title-cyan; VOICE/SPD/MIC chips; 23 `lgi_*` strings, QSS-transcribed `lgi_*` token colors, `led_on/led_off` + `lgi_panel_bg/input_bg/ops_bg` drawables; Eve avatar overlay retired from the layout. **Deploy:** rebuilt + installed over USB (wireless mDNS proved unreachable with the screen off — this ROM advertises `_adb-tls-connect` only while awake), in-place `install -r` Success on the pinned cert (data + grants preserved), `tcpip 5555` re-armed → gateway watchdog reconnected automatically at 192.168.50.42:5555; on-device verified `com.freeroam.tactical` 1.0.5 / versionCode 6 (lastUpdateTime 2026-10-05), live process; **same binary fresh-installed on Matt that evening** (`install -r` blocked by cert mismatch on his old v1.0.4 — uninstall + reinstall, grants re-applied pre-launch, `user_id='matt'` resolved live from 192.168.50.98) |
| v3.9 | Oct 3, 2026 | **Wake-reconnect chat polarity fix + keystore lineage closed (app v1.0.5, operator wake-tested)** — history rows replayed from the gateway (WS `chat_history` on every wake/reconnect + the REST seed on open) were classified with an exact case-sensitive sender match; the master timeline stores "Polaris" with a capital P, rows missed the whitelist and fell through to the legacy user-side default, so every wake-reconnect re-seed rendered the whole window as **You** (operator-observed). `ChatRepository` (REST) and `PolarisWebSocketClient` (WS) now share `parseHistorySenderPolarity(role, sender)`: role authoritative when present, then sender case-insensitively; bare legacy rows keep the user-side legacy default; unknown senders fail-safe to the AI side. Built container-side in 6m22s (image `freeroam-android:latest` c4b92c69ccdc, `/tmp/freeroam-v105.apk` 233 MB → staged `freeroam/freeroam-debug.apk`); build-tools 35.0.0 cert archaeology proved the installed Oct-1 v1.0.4 cert (SHA-1 `86b8c88a…`) belonged to a since-regenerated host keystore → one final forced fresh install (Oct 3 14:18 device-time, install log "Success"); v1.0.5's cert (SHA-1 `acf51847…`) matches the committed `freeroam-debug.keystore` (Dockerfile pin) byte-for-byte — from now on container builds upgrade in place, the signature saga is permanently closed. Chat content survived the wipe (gateway-side history re-seeds on open); operator wake-test passed — labels stay correct across screen off/on reconnects |
| v3.8 | Oct 2, 2026 | **Dashboard reconnect hardening (LGI Phase 3 tie-off)** — the radar, vision, and task-feed Socket.IO clients on the Polaris dashboard were still capped at `reconnectionAttempts: 10` (≈10 reconnect ticks): any outage longer than that (gateway worker recycle, gateway reboot, LLM hang) killed those feeds silently FOREVER — the chat socket got the Infinity fix in v1.0.4 but the other three kept the bug. All three now reconnect indefinitely (1 s initial delay, 10 s delay max, matching the chat socket). Also scrubbed the stale Sep 11 flat-format `chat_history.json` from the workspace `freeroam/data/` dir (runtime persists via SQLite; the real bind root is `/mnt/containers/freeroam/data`); only the `polaris-dashboard` service was rebuilt — the other 13 compose services untouched |
| v3.7 | Oct 1, 2026 | **Looking-Glass chat unification, Phases 1+2 (operator live-fire verified: "Works awesome!")** — outgoing replies and `chat_stream` deltas now broadcast to a persistent `room=user_identity` (identity-keyed) alongside the legacy IP room (demoted to sender-only error emits), making phone / dashboard / Looking-Glass delivery push-instant with the 3-second history polls demoted to fallback; fresh installs and chat openings reseed the latest 5 messages per side from the gateway master timeline ("Looking-Glass seed v2") so one-sided stretches no longer open half-blank. Verified in the rebuilt container: 14 identity-room emissions, 1 client-IP error emit, seed marker live; only the `polaris-gateway` service was recreated (the other 13 compose services untouched). Host-Gradle rebuild of v1.0.4 reinstalled on the RedMagic after `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (the installed Sep 28 build was signed by a different keystore) via full uninstall/reinstall — local data wipe benign. Chat persistence clarified: the real bind root is `/mnt/containers/freeroam/data` (79K master `chat_history.json`, last write Oct 1 12:16, with a `.pre-scrub` backup from Oct 1 01:37); the `/home/.../freeroam/data` copy (Sep 11) is stale and unused at runtime; saves are per-event, so a hard container crash can drop only messages since the last write |
| v3.5.2 | Sep 28, 2026 | **Watch play-state model (operator field feedback)** — the v3.5.1 always-PLAYING keep-alive loop held the connection but left the watch showing a PAUSE button at idle ("caught out of sync"). New model: the silent seed (now 60 s, was 1 s — the 1 s loop's per-second track-end churn destabilized AVRCP title display) rests PAUSED at launch and after every exchange; `ensureTetherLooping()` engages it only while she speaks (pause affordance — pressing it stops her speech and starts the mic; both PLAY and PAUSE route to the PTT toggle), and `pauseTetherLoop()` parks it the moment her voice drains — the watch flips to PLAY exactly as she finishes. Every retitle now stamps a fresh mediaId (`updateMetadata`) so legacy AVRCP stacks treat it as a new track and reliably refresh the title — her full reply sticks on the display instead of flashing back to standby. Mid-speech text updates removed: the Garmin ignores in-place retitles anyway, and the metadata churn destabilized the display |
| v3.6 | Sep 28, 2026 | **Text-only playback (operator decision)** — live replies already streamed text→Kokoro→AudioTrack with no artifacts (v3.5); now the artifact layer is gone entirely: no WAV is ever written, bubble replay re-synthesizes from text through the streaming pipeline via `speakText()` (first word ~1-2 s; tap speaks, tap again stops), and a startup sweep (`PolarisAudioStore.pruneAllWavs`) deletes the legacy cache (56 MB / 79 files at cutover) — with nothing new written, the sweep IS the long-term pruning. `PolarisTextStore` persists reply text for cross-restart replays; watch HUD and tether behavior unchanged |
| v3.5.1 | Sep 28, 2026 | **Streamed-tail clip fix** — `AudioTrack.write` only QUEUES PCM (up to ~1 s of speech still buffered at the last chunk); the playback thread now waits for the playback head to reach the final written frame before teardown, mirroring KokoroTTS's completion wait — no more clipped last words. **Live text on the watch HUD** — her reply streams onto the Garmin title the moment it generates (the LLM writes ~3x faster than she speaks, so the watch runs well ahead of the voice); AVRCP metadata updates throttled to a 1.5 s cadence so watch firmware is never flooded; the marquee's pixel scroll rate itself is Garmin-side firmware and not commandable. **Keep-alive loop (the v3.3 reserve lever, engaged)** — the silent seed now plays forever on `REPEAT_MODE_ONE` from launch, so the session reads PLAYING instead of paused-idle and the AVRCP connection never ages out; real playback (bubble replay) clears the loop, and tethered STOP + natural completion both re-seed it, re-titled with her latest reply. Mic hygiene still pauses the loop during captures (`ensureTetherLooping()` resumes it at stream start/finish) |
| v3.5 | Sep 28, 2026 | **Speak-while-she-writes streaming playback** — the gateway has streamed her replies as sentence-safe `chat_stream` deltas since v3.3, but the phone never registered the "message" socket event, so chunks were dropped and the app waited for the whole reply then synthesized it end-to-end before a single word. v3.5 opens the ear: `PolarisWebSocketClient` dispatches `chat_stream` deltas (plus `user_chat` for cross-device messages), new `StreamingTtsPlayer` turns arriving text into continuous speech — sentences synthesize on the shared Kokoro executor while earlier ones are already audible through one persistent 24 kHz mono AudioTrack (blocking write = natural backpressure), monster sentences split at clause boundaries, first words land seconds after the reply starts generating. On completion the concatenated PCM persists as the standard bubble WAV; mic capture / watch STOP abort mid-stream (aborted streams re-synthesize whole on the final broadcast); TTS-unavailable devices keep the whole-text path. **Watch HUD:** "Polaris is speaking..." marquee-scrolls during the stream; her full reply replaces it at completion |
| v3.4 | Sep 28, 2026 | **bf_emma default** — voice spinner reordered so af_emma leads (the position-0 `onItemSelected` at layout time had been overwriting the af_emma default with af_heart before Kokoro init); `VOICE_DEFAULT_SID` 0 → 7 so unknown names also fall back to bf_emma. **End-of-speech patience (2.5 s)** — the recognizer's own ~1 s VAD no longer auto-sends mid-pause messages: service-finalized segments fold into a running transcript and the recognizer restarts; the capture finalizes only on a manual stop, 2.5 s of true silence (silence watchdog), or the 30 s cap; quiet-segment errors deliver the accumulated transcript instead of discarding it; live partials show the full running transcript. **Lock-screen audio** — `onPause()` no longer pauses Polaris playback (it was cutting replies mid-sentence at every screen-lock; the ExoPlayer wake lock + TacticalBackgroundService FGS were already in place). **Capture visibility** — a live capture swaps the input field to a red-bordered "Listening..." state (`input_field_bg_capture`) with a focused caret and the IME suppressed, so a watch PLAY press is obvious without tapping the field first. **User bubble restyle** — `bubble_user.xml` now uses the reserved deep-navy `@color/bubble_user` (#1e3a5f) instead of `electric_blue`; bubble text 14sp → 16sp |
| v3.3 | Sep 26, 2026 | **Watch tether** — the AVRCP media session never drops: silent 1 s seed WAV loaded paused at launch (`seedSilentTetherIfEmpty`), natural completion rewinds to 0:00 and holds paused 600 ms after `STATE_ENDED`, `stop()` pauses + rewinds instead of idling the player; her latest reply (≤ 180 chars, up from 100) marquee-scrolls on the Garmin as the track title until the next response. **Button remap:** PLAY/PAUSE → push-to-talk toggle; STOP → cancel capture mid-PTT (hard abort — `cancel()` fires no listener callback, so the stop handler resets the mic tint itself) / tethered halt; BACK/NEXT disabled (stripped from `getAvailableCommands()` + no-op overrides). **Mic hygiene:** starting a capture pauses any playing TTS. **Honest state reporting:** READY/BUFFERING map to PAUSED when `playWhenReady == false`. Reserve lever if a stack drops paused players: play the seed on `REPEAT_MODE_ONE`. **Field-verified Sep 26, 2026:** built in 1m 21s (233 MB debug APK), installed over the air on the Redmagic (FY24031100E6), `dumpsys media_session` confirmed the paused seed live at launch (`state=PAUSED(2)`, position 0, "Polaris standing by"), and the operator's physical Garmin retest passed end-to-end — PTT works screen-locked, the tether holds, both STOP behaviors correct; REPEAT_MODE_ONE reserve not needed |
| v3.2 | Sep 26, 2026 | Voice push-to-talk: watch NEXT rewired as the mic toggle (press = start capture, press again = stop + send); in-app mic button tap-to-toggle with live partials in the input field (`alert_red` tint); `VoiceCaptureController` (PARTIAL wake lock, 30 s cap, 1.5 s stop-flush timeout); `TacticalBackgroundService` converted to a mic-typed FGS (`dataSync\|microphone`, ID 1001) started from `MainActivity.onStart()` so captures survive screen-lock; `WatchTriggerReceiver` removed — watch buttons now route through the Media3 `MediaSession` (`WatchSessionPlayer`) |
| v3.1 | Sep 25, 2026 | KokoroTTS static-noise fix — `nativeOrder()` PCM16 conversion (the default BIG_ENDIAN ByteBuffer byte-swapped every sample, producing static that followed the speech envelope); completion-tracked `playAudio()` + explicit `stop()`; WAV caching (`polaris_<id>.wav`) with replayable audio bubbles; mini player; `freeroam.md` rewritten to match the current architecture |
| v3.0 | Sep 14, 2026 | ChatArchiverWorker Room timing fix; APK size optimization 193 MB → 74 MB (61% reduction) |
| v2.9 | Sep 7, 2026 | Security hardening: strict text-chat operation (text in / text out only) |
| v2.8 | Sep 5, 2026 | Rolling 10-message window + auto-speak freshness gating |
| v2.7 | Sep 5, 2026 | Multi-user chat sync (per-identity server-side histories) |
| v2.6 | Sep 5, 2026 | Garmin watch PTT (PLAY/NEXT media-button integration) |
| v2.5 | Sep 4, 2026 | Automatic IP-based identity detection |

## License

Private use only. Not for commercial distribution.
