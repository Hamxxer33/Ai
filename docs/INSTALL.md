# Installing Kestrel on an Android phone

Everything below runs on a computer (Windows, macOS or Linux) with a USB cable. The phone only
receives files; it never downloads anything, and the app has no permission to do so.

**Requirements:** an arm64 Android phone with Android 12+ (GrapheneOS works; no Google Play
Services needed). The target configuration is a 12 GB Pixel (Tensor G3/G4/G5). 8 GB phones can
run the fast and strong tiers but not the deep tier. You need 5-45 GB of free storage, depending on
the knowledge pack and models you choose (see the table in step 3).

## 1. Get the APK

Pick one:

* **GitHub Releases:** download `kestrel-<version>-arm64.apk` and `SHA256SUMS` from the
  repository's Releases page, then check the file:
  `sha256sum -c SHA256SUMS` (Linux/macOS) or `Get-FileHash kestrel-*.apk` (PowerShell).
* **CI artifact:** every push builds `kestrel-apk` in the Actions tab.
* **Build it yourself:** see [BUILDING.md](BUILDING.md).

## 2. Install the APK

With USB debugging enabled (Settings → About phone → tap *Build number* 7 times → Developer
options → USB debugging):

```sh
adb install -r kestrel-*-arm64.apk
```

Or copy the APK to the phone and open it in the Files app. Android asks you to allow installs from
that app.

## 3. Get the models and a knowledge pack onto your computer

**Models** (GGUF, verified by SHA-256):

```sh
python tools/fetch_models.py --out models                      # Qwen3.5-2B (fast), Qwen3.5-4B (strong), nomic-embed (vectors)
python tools/fetch_models.py --out models --with gemma4-e4b    # optional alternative strong model
```

The deep tier (optional, 12 GB phones only) is a 2- to 3-bit Qwen3.6-35B-A3B from Hugging Face.
[MODELS.md](MODELS.md) has the files. Download one with:

```sh
python tools/fetch_models.py --out models --hf unsloth/Qwen3.6-35B-A3B-GGUF/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
```

**Knowledge pack.** Build it from the English Wikipedia dump. [KNOWLEDGE_PACKS.md](KNOWLEDGE_PACKS.md)
covers the options (full text vs. lead sections, vectors or not) and the build times:

```sh
pip install pyarrow numpy requests huggingface_hub
huggingface-cli download wikimedia/wikipedia --repo-type dataset --include "20231101.en/*" --local-dir data/wikipedia
python tools/build_pack.py --format parquet --input "data/wikipedia/20231101.en/*.parquet" \
    --out packs/enwiki --id enwiki-20231101 --name "English Wikipedia (2023-11-01)"
```

Typical storage:

| Item | Size |
|---|---|
| APK | ~40-90 MB |
| Qwen3.5-2B Q4_K_M (fast) | 1.3 GB |
| Qwen3.5-4B Q4_K_M or Gemma-4-E4B Q4_K_M (strong) | 2.7 / 5.0 GB |
| nomic-embed-text-v1.5 (vectors, optional) | 0.15-0.27 GB |
| Qwen3.6-35B-A3B 2-bit (deep, optional) | ~12.3 GB |
| English Wikipedia pack, full text + FTS5 | ~25 GB [estimate from the 3 GB dev build: 1.2x the input text] |
| English Wikipedia pack, lead sections only | ~6 GB [estimate] |
| Vectors for 2M lead passages (256-d) | ~0.6 GB [estimate: 290 bytes per passage] |

## 4. Copy the files to the phone

The app reads from its own folder. No permission is needed for it, and `adb push` can write to it:

```sh
# Linux / macOS
scripts/push_to_phone.sh models packs/enwiki
```

```powershell
# Windows (PowerShell), adb from Android platform-tools on PATH
$dst = "/sdcard/Android/data/io.kestrel.research/files"
adb shell mkdir -p $dst/models $dst/packs
adb push models\Qwen3.5-2B-Q4_K_M.gguf models\Qwen3.5-4B-Q4_K_M.gguf models\nomic-embed-text-v1.5.f16.gguf $dst/models/
adb push packs\enwiki $dst/packs/
```

Open the app once before pushing so that Android creates its folder.

**Without adb:** in the app, go to Library → *Allow /sdcard/Kestrel* (grants all-files access).
Then copy `models/` and `packs/` into the `Kestrel` folder of the phone's storage with any USB
file manager.

## 5. Check the setup in the app

1. Open **Library** and tap **Rescan**.
2. Check that each model has the right role (fast / strong / deep / embed). Tap a role to change it.
3. Check that the pack shows its article and passage counts, and whether it has vectors.
4. Open **Settings → Offline guarantee**. It must say the app has no INTERNET permission.
5. Open **Benchmark → Run runtime benchmark** to measure prompt and generation speed on this phone.

Then follow [OFFLINE_TESTING.md](OFFLINE_TESTING.md).

## Signing and updates

Release APKs are signed with the project key when the repository secrets `KESTREL_KEYSTORE_B64`,
`KESTREL_KEYSTORE_PASSWORD`, `KESTREL_KEY_ALIAS` and `KESTREL_KEY_PASSWORD` are set. Otherwise
they are signed with a per-build debug key. Installing a build signed with a different key requires
uninstalling first, which deletes `/sdcard/Android/data/io.kestrel.research`. Keep your models in
`/sdcard/Kestrel` if you expect to switch builds.
