# Testing Kestrel offline (Airplane Mode protocol)

The goal is a demonstration anyone can repeat: the app answers research questions, with local
citations, on a phone that has no network at all, and it could not use the network even if the
radios were on.

## A. Static proof: the APK cannot open network connections

On the computer:

```sh
scripts/verify_offline.sh kestrel-*-arm64.apk
```

The script checks with `aapt2` that the APK requests none of `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE` or `CHANGE_WIFI_STATE`. It also checks that no Google
Play Services or Firebase component is declared and that no networking native library (curl,
OpenSSL) is packaged. CI runs it on every build.

On the phone:

```sh
adb shell dumpsys package io.kestrel.research | grep -A20 "requested permissions"
```

Expected: no `android.permission.INTERNET`. On Android, a process without this permission gets
`EACCES` for every socket. On GrapheneOS the per-app *Network* toggle for Kestrel shows it as not
requested.

In the app, **Settings → Offline guarantee** lists the requested permissions as reported by the
package manager, and the top bar shows the green **Offline** badge. The badge is computed from the
missing INTERNET grant, not from connectivity.

## B. Airplane Mode run (record this for the demo)

1. Install the APK and copy the models and pack ([INSTALL.md](INSTALL.md)). Open the app once and
   check that Library lists them.
2. **Force-stop the app**: Settings → Apps → Kestrel → Force stop. This way nothing survives from a
   session that had connectivity.
3. **Enable Airplane Mode.** Also turn off Wi-Fi and Bluetooth if the phone keeps them on in
   Airplane Mode. Show the status bar on camera.
4. Optionally reboot, to show there are no cached network sessions.
5. Open Kestrel. The top bar shows **Offline · airplane**.
6. Ask the demo questions ([DEMO_PLAN.md](DEMO_PLAN.md)) in each mode (Quick, Research, Deep):
   * expand **Research steps** to show classification, planning, per-hop searches and evidence
     selection, each with a timing;
   * tap a citation `[n]` to show the exact source passage;
   * point out the **Grounding** line (claims supported by their cited sources);
   * ask one question whose answer is not in the library, and show the refusal.
7. Open **Benchmark → Run runtime benchmark** to show prompt and generation speed on this phone,
   still in Airplane Mode.
8. Open **Settings → Offline guarantee** to show the permission list.

## C. What to capture for the bounty submission

| Evidence | How |
|---|---|
| Screen recording of steps 2-8 | `adb shell screenrecord /sdcard/kestrel-demo.mp4` (3-minute limit per file; run it several times) or the phone's built-in recorder |
| Proof of Airplane Mode | status bar visible in the recording; `adb shell settings get global airplane_mode_on` returns `1` |
| Device and build | Settings → About in the app (device, SoC, Android version, llama.cpp CPU features); `adb shell getprop ro.build.fingerprint` |
| Permissions | `scripts/verify_offline.sh` output plus the `dumpsys package` excerpt |
| Performance | the runtime benchmark file and the research benchmark JSONL: `scripts/pull_bench.sh` |
| Memory | the peak RSS column in the benchmark summary; `adb shell dumpsys meminfo io.kestrel.research` during an answer |
| Thermal (optional) | `adb shell dumpsys thermalservice` before and after a benchmark run |
| Battery (optional) | `adb shell dumpsys batterystats --reset`, run the benchmark on battery, then `adb shell dumpsys batterystats io.kestrel.research` |

Notes:
* `adb` over USB works in Airplane Mode (USB is not a radio). Collecting logs does not break the
  offline condition.
* Do not use Wi-Fi adb for the demo.
