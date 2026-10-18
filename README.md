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
<br>

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
- Dedicated VD Start without MediaProjection consent and local ADB/Helper fallback preflight
- Normal screen mirroring stops on screen-off or MediaProjection revocation
- 480 × 234 XMAX dashboard layout with configurable crop and margins
- Right-top clamped rendering for the usable dashboard area
- Automatic Bluetooth reconnect and recovery after ignition OFF / ON
- Dedicated VD unlock handling returns the VD to HOME via authenticated Helper HOME key event
- DEMOTE does not relaunch a remembered navigation app when its task is already absent
- One-shot recovery after a real phone sleep during the 3-second PROMOTE settle window (rapid POWER presses)
- Yahoo! Car Navigation restart-on-VD handling for rendering and DPI stability
- OCR overlays for arrival information and map scale
- XMAX stick up/down input support with separate NAV / DRIVE zoom positions
- Optional Yahoo! Car Navigation Auto DRIVE mode based on OCR Area 1 after PROMOTE
- DRIVE-only upward image shift, mode-specific stick coordinates and input suppression while the initial mode is undecided
- First-run Yahoo! Car Navigation preset on clean installations (existing settings preserved)
- Compact, collapsible NAV / DRIVE / OCR settings and XMAX Custom Fork v38 link in About
- Pixel SecureCamera detection and physical display wake handling
- Start mirroring race / duplicate-start prevention
- Reset Pillion and Save & Restart workflows
- Authenticated DASH/OCR and restart-helper local control channels, bounded wireless ADB TCP/IP acknowledgement wait, and safer Stop handling
- Diagnostic flight recorder and diagnostic export
- Five UI languages: Japanese, English, Korean, Spanish and Malay (plus automatic system language)\n- Additional recovery and stability improvements for long-running use
<br>

- Yahoo!カーナビ向け Virtual Display キャスト
- VD専用Startで画面共有許可を省略し、Helper/ローカルADBによる事前判定と通常ミラーへの切り替えに対応
- 通常ミラーは画面ロック・共有終了時に自動停止
- XMAXメーター 480 × 234 に合わせたクロップ・余白調整
- 使用可能領域への右上クランプ描画
- Bluetooth再接続、およびキーOFF / ON後の自動復帰
- VD中のスマホロック解除でHelper経由のHOMEキー入力によりVDをホームへ復帰
- DEMOTE時にナビの既存タスクが無ければ、意図しない再起動を抑止
- PROMOTE直後3秒以内の電源ボタン連打でAndroidがスリープした場合に、単発確認でDEMOTE・復帰
- Yahoo!カーナビの描画・DPI安定化を目的としたVD移動時のアプリ再起動
- 到着情報・地図縮尺のOCRオーバーレイ
- XMAXスティック上下入力対応（NAV/DRIVE別のズーム座標）
- OCRエリア1の判定によるYahoo!カーナビのAuto DRIVEモード
- DRIVE時限定の映像上方向シフト、モード別スティック座標、モード判定中の誤タップ防止
- 初回クリーンインストール時のYahoo!カーナビ向け設定プリセット（既存設定は維持）
- NAV/DRIVE/OCR設定の折りたたみ表示、About内のXMAX Custom Fork v38リンク
- Pixel SecureCamera検出と物理ディスプレイ復帰処理
- Start mirroring の二重発火・レース対策
- Reset Pillion / Save & Restart
- DASH/OCR・再起動Helperのローカル通信認証、ADB TCP/IP応答待ちの上限設定、Stop処理の安全化
- 診断用フライトレコーダーと診断ログExport
- 日本語・英語・韓国語・スペイン語・マレー語の5言語対応（システム言語に合わせる自動設定あり）\n- 長時間運用向けの復帰処理・安定性改善

---

## Modes / 利用モード

**VD ON:** Start mirroring, then lock the phone. The bike may connect later; navigation resumes on reconnection or after using and re-locking the phone. If neither Helper nor loopback ADB is available, Start falls back to normal mirroring.

**VD OFF:** Turn the phone to landscape and allow screen capture after Start. Keep the phone screen on; locking it ends normal mirroring. The bike may connect later.

**VD ON：** Startして画面ロック。キーONは後からでもよく、再接続やスマホ操作後の再ロックでナビが自動復帰します。Helper・ローカルADBが両方利用できない場合は通常ミラーへ切り替えます。

**VD OFF：** スマホを横向きにしてStart、画面共有を許可します。キーONは後からでも可能。画面ロックでミラーは終了します。

---

## Branches and milestones / ブランチとバージョン履歴

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

- `v35` (release commit [`ea564aa`](https://github.com/waiasu/pillion/commit/ea564aa9b513f8553b002450ca34f15e6d0fd5db))  
  Tested NAV / DRIVE behavior, Yahoo! Navi clean-install preset, settings updates, and About link. This is a release commit, not a tag.  
  NAV/DRIVE動作、初回プリセット、設定UI、Aboutリンクを実車確認して反映したコミット（タグではありません）。

---

- `v36` (2026-10-13)  
  Token-authenticated helper channels, ADB bootstrap timeout handling, Stop correction, and safe stick input during Auto DRIVE mode checking. The XMAX v36 release retains the feature/fix commits in Git history.  
  Helper通信認証、ADB開始処理の待ち対策、Stop修正、Auto DRIVE判定中のスティック誤タップ防止。個別修正コミットを履歴に残してリリース。

---

- `v37` (2026-10-14)  
  VD-only Start without MediaProjection consent, correct loopback ADB reuse, normal-mirror automatic shutdown, and redesigned mode-aware Settings/Home. Bench and XMAX tests passed; test commits kept in history.  
  VD専用Start、ADB接続済み判定、通常ミラーの自動終了、設定・ホームUIの刷新。机上・実車確認済み、テストコミット履歴も保持。

---

- `v38` (2026-10-17)  
  Five-language UI, refined Japanese/English wording, notification labels, and About version. Squash release; no casting logic changes.  
  5言語UI、日本語・英語の文言整理、通知表示とAboutバージョンの更新。スカッシュで反映、投影処理は維持。

---

## Upstream / オリジナル

This project is based on the original Pillion project by alexandrevega. Many thanks to the original author for making the project available.
For the original general-purpose project, documentation, and upstream development, please refer to the original Pillion repository.

本プロジェクトは、alexandrevega氏によるオリジナルのPillionプロジェクトをベースにしています。プロジェクトを公開してくださった原作者に深く感謝いたします。
オリジナルの汎用プロジェクト、ドキュメント、およびアップストリームでの開発状況については、オリジナルのPillionリポジトリをご参照ください。

---

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
