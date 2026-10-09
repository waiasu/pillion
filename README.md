# Pillion – XMAX / Pixel 10a Fork

> Personal fork of Pillion optimized for Yamaha XMAX, Google Pixel 10a, and Yahoo! Car Navigation.
>
> This branch is my daily-use build and contains changes focused on stable navigation display, automatic recovery, and practical use with the Yamaha XMAX dashboard.
>
> This is not an official Pillion build.
> Issues or questions related to modifications in this fork should be reported here, not to the upstream Pillion repository.

> Yamaha XMAX / Google Pixel 10a / Yahoo!カーナビ向けに調整した Pillion の個人forkです。
>
> このブランチは実際に日常使用しているビルドで、Yamaha XMAX メーターでの安定したナビ表示、自動復帰、実運用時の扱いやすさを重視して変更しています。
>
> Pillion公式版ではありません。
> このfork独自の変更に関する不具合・質問は、オリジナルPillionではなくこちらへお願いします。

---

## Target environment / 想定環境

- Yamaha XMAX with Garmin StreetCross / NaviLite compatible dashboard
- Google Pixel 10a
- Yahoo! Car Navigation
- Android Virtual Display mode

- Garmin StreetCross / NaviLite 対応メーターを搭載した Yamaha XMAX
- Google Pixel 10a
- Yahoo!カーナビ
- Android Virtual Display モード

This fork is primarily developed and tested with the environment above.
Compatibility with other Android devices, Yamaha models, or navigation apps is not guaranteed.

このforkは主に上記環境で開発・実機確認しています。
他のAndroid端末、Yamaha車種、ナビアプリでの動作は保証していません。

---

## Main changes from upstream / 主な変更点

- Virtual Display based casting optimized for Yahoo! Car Navigation
- 480 × 234 XMAX dashboard layout with configurable crop and margins
- Right-top clamped rendering for the usable dashboard area
- Automatic Bluetooth reconnect and recovery after ignition OFF / ON
- Yahoo!カーナビ restart-on-VD handling for rendering and DPI stability
- OCR overlays for arrival information and map scale
- XMAX stick up/down input support
- Pixel SecureCamera detection and physical display wake handling
- Start mirroring race / duplicate-start prevention
- Reset Pillion and Save & Restart workflows
- Diagnostic flight recorder and diagnostic export
- Additional recovery and stability improvements for long-running use

- Yahoo!カーナビ向け Virtual Display キャスト
- XMAXメーター 480 × 234 に合わせたクロップ・余白調整
- 使用可能領域への右上クランプ描画
- Bluetooth再接続、およびキーOFF / ON後の自動復帰
- Yahoo!カーナビの描画・DPI安定化を目的としたVD移動時のアプリ再起動
- 到着情報・地図縮尺のOCRオーバーレイ
- XMAXスティック上下入力対応
- Pixel SecureCamera検出と物理ディスプレイ復帰処理
- Start mirroring の二重発火・レース対策
- Reset Pillion / Save & Restart
- 診断用フライトレコーダーと診断ログExport
- 長時間運用向けの復帰処理・安定性改善

---

## Branches and tags / ブランチとタグ

- `xmax-pillion`  
  Daily-use XMAX / Pixel build.  
  XMAX / Pixel向けの実運用版。

- `main`  
  Kept close to the upstream Pillion repository.  
  オリジナルPillion追従用。

- `legacy-base`  
  Tag marking the upstream commit used as the original base of this fork.  
  このforkの開発を開始したオリジナル側の起点を示すタグ。

- `xmax-v34`  
  Stable working snapshot of the v34 build used on the actual bike.  
  実車で運用確認済みのv34を保存した固定タグ。

---

## Upstream / オリジナル

This project is based on Pillion by alexandrevega.
For the original general-purpose project, documentation, and upstream development, please refer to the original Pillion repository.

このプロジェクトは alexandrevega 氏の Pillion をベースにしています。
汎用版Pillion、オリジナルのドキュメント、およびオリジナル側の開発についてはオリジナルのPillionリポジトリを参照してください。

---

# Original Pillion README / オリジナルREADME


<p align="center">
  <img src="art/icon.png" alt="Pillion logo" width="150" height="150">
</p>

<h1 align="center">Pillion</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="alpha">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84" alt="Android">
  <img src="https://img.shields.io/badge/iOS-planned-lightgrey" alt="iOS planned">
  <img src="https://img.shields.io/badge/license-PolyForm%20NC-blue" alt="PolyForm Noncommercial">
  <a href="https://discord.gg/mxNV97QUnB"><img src="https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white" alt="Join the Discord"></a>
  <a href="https://buymeacoffee.com/alexandrevega"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black" alt="Buy me a coffee"></a>
</p>

> **🚧 Alpha.** Android-only for now and under active development — it works on my MT-07 (2025) but is
> rough, and the protocol/UI may still change. **iOS support is planned.** Expect bugs, and please
> [report them](../../issues) (especially compatibility on other bikes).

**Cast your phone's screen to a Yamaha motorcycle TFT dash over Bluetooth** — so you can run
Waze, Google Maps, or anything else on the bike's built-in display instead of being limited to
Garmin StreetCross.

Pillion is an independent, reverse-engineered implementation of the **NaviLite** protocol (the same
protocol Garmin StreetCross uses to project navigation to the dash), built for interoperability. It
is **not** affiliated with, authorized by, or endorsed by Yamaha or Garmin.

> **Measured:** ~**14–15 fps** at 480×240 over Bluetooth on a Yamaha MT-07 (2025). Smooth enough to
> follow a moving map.

---

## ⚠️ Safety first

This puts arbitrary content on a **moving motorcycle's dashboard**. Read **[SAFETY.md](SAFETY.md)**
before using it. Short version: **do not watch video or anything distracting while riding.** Use a
glanceable navigation view, or use it parked. You are responsible for riding safely and for any
local laws about screens/displays while riding.

---

## What it does

- Mirrors your **Android** phone screen to the bike's TFT at ~480×240, ~5–15 fps (tunable).
- Works with **any** app on your phone (Waze, Google Maps, Organic Maps, music, etc.) — it casts the
  screen, so it isn't tied to a specific nav app.
- Connects directly over Bluetooth; **no internet required** while riding.

## Supported bikes

Any Yamaha that uses the **Garmin "Communication Control Unit" (CCU)** / works with the Garmin
StreetCross app — confirmed so far on the MT-07, MT-09, R9, and XSR900, and very likely on other
models with the same CCU platform.

See **[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)** for the full list of confirmed bikes, fps
reports, and how to add yours. **More reports welcome!**

## Install (Android)

1. Download the latest `Pillion.apk` from [**Releases**](../../releases).
2. Sideload it (enable "install unknown apps" for your browser/file manager).
3. Pair your phone to the bike's Bluetooth as you normally would.
4. On the bike, **select the navigation screen** on the TFT.
5. Open **Pillion**, grant the screen-capture prompt, and tap **Start**. Then open Waze/Maps in
   **landscape**.

> Note: only run **one** projection app at a time — close Garmin StreetCross / Yamaha MyRide first,
> or they'll fight Pillion for the connection.

## Updates

Pillion checks **[GitHub Releases](../../releases)** once on launch and shows an in-app **"Update
available"** banner (with the changelog) when a newer build exists — tap **Get** to download the new
APK. Each release also lists what changed; see **[CHANGELOG.md](CHANGELOG.md)**.

## How it works

Pillion speaks **NaviLite** over Bluetooth Classic RFCOMM (SPP UUID `0x7220`): a small framed
protocol with a CRC-32/MPEG-2 checksum, a trivial de-obfuscate-and-echo handshake, and a JPEG image
channel. Your screen is captured, scaled to 480×240, JPEG-encoded, and streamed frame-by-frame.

The full wire spec is documented in **[docs/PROTOCOL.md](docs/PROTOCOL.md)**.

## Build from source

A Kotlin Multiplatform + Compose Multiplatform project; the Android app module is `composeApp`.
Requires the Android SDK + JDK 17 (or just Android Studio).

```bash
git clone https://github.com/alexandrevega/pillion.git
cd pillion
./gradlew :composeApp:assembleDebug   # -> composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:testDebugUnitTest   # protocol unit tests (CRC + auth vectors)
```

Or open the project in Android Studio and Run. The shared protocol/engine lives in
`composeApp/src/commonMain` (ready for an iOS target later); Android specifics are in
`composeApp/src/androidMain`.

## License

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** — free to use, modify, and share for **non-commercial**
purposes. Commercial use requires a separate license. This is a hobby / interoperability project.

## Legal / disclaimer

- Independent interoperability project. **Not affiliated with Yamaha or Garmin.** "Yamaha", "Garmin",
  "StreetCross", and "Motorize" are trademarks of their respective owners, used here only descriptively;
  Pillion is not endorsed by or sponsored by them.
- Contains **no** Garmin/Yamaha code or binaries — only an original, independent implementation of an
  observed protocol and original documentation of it.
- Provided **as-is, with no warranty.** Use at your own risk; you are responsible for safe and lawful
  use. See [LICENSE.md](LICENSE.md) and [SAFETY.md](SAFETY.md).

## Community

Questions, setup help, or a compatibility report? **[Join the Discord](https://discord.gg/mxNV97QUnB)** 💬
— there's a support forum, bike-specific roles, and release announcements.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports, bike-compatibility reports, and PRs welcome.
