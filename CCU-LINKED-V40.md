# v40 CCU連動常駐モード — 現行仕様・運用メモ

## 目的と責務の分離

Yamaha XMAX + Pixel 10a + Yahoo!カーナビ向けの任意機能。CCU連動の「スタート」を押すと独立した `:ccu_watcher` プロセスがAndroidのBT ACLイベントを待ち、**既存の**VD Start/Stopへ依頼する。常駐サービスは映像処理・RFCOMM通信・VDのPROMOTE/DEMOTEを独自実装しない。通常の手動VD、通常ミラーは従来どおり。

常駐を開始するには、NaviLite設定で専用VDがON、Bluetooth/通知の許可、端末のローカルADBまたはHelperの準備が必要。VD無効時に通常の画面共有へ自動フォールバックはしない。接続の有無はAndroidのペアリング済みCCUのACL接続通知で判断するもので、NaviLite通信成立と同義ではない。CCU接続済みの後から常駐を起動した場合、過去のACL接続イベントは届かず、次の接続まで自動起動しないことがある。

## 自動開始

1. 対象デバイス名（YCCUで始まる／CCUを含む）の初回 `ACTION_ACL_CONNECTED` で1度だけStart。スキャン・ペアリング・独自の接続試行は行わない。
2. 既存ループバックADBで `KEYCODE_WAKEUP (224)` し、ワンタイムトークン付きでMainActivityを呼ぶ。トークンは私有AtomicFileで1回消費。Activityが起動済みでも `onNewIntent` で受ける。
3. 保存済み設定に基づきCaptureServiceをStart。画面OFFの受信準備が整ったことを常駐側へ通知。
4. Wake前に端末が**明確に非interactive**だった場合だけ、`KEYCODE_SLEEP (223)` して従来のSCREEN_OFF → PROMOTEへ。元から画面ON/操作中、または判定不能なら勝手に消灯しない。ユーザーが後でロックすると従来のVD切替が動く。

## 自動停止・HOME

1. 対象CCU ACL切断／BT OFFで5秒猶予開始。同じCCUの再接続は取消。
2. 猶予満了でCCU接続状態を「検索中」に更新し、CaptureServiceへ既存の明示的STOPを要求。Helperは再利用のため維持する。
3. 停止中のDEMOTEがAndroidのWAKEUPを発行する場合がある。常駐側は停止を確認し、約1.2秒待ち、元が暗転またはロック中で現在もロック中なら再SLEEPを発行。Stopが失敗したら勝手にHOMEや再SLEEPを進めない。
4. **自動Stop開始時点でロック中かつCaptureService実行中**なら次のHOMEを1回予約。Stop完了／Androidの `ACTION_USER_PRESENT` のどちらのイベントでも共通判定する。Stop完了かつ解除状態ならADBで `KEYCODE_HOME (3)` を1回だけ送る。順序が前後しても取りこぼさない。次の自動開始・常駐停止時には予約破棄。手動Stopは変更しない。

## 操作・終了

- CCU連動「ストップ」（Homeまたは常駐通知）で、常駐および動作中のキャストを停止。キャスト自身の通知のStopはそのキャストだけを停止する。
- Reset / Save & Restartは常駐を停止してから既存手順を実行。再起動後の常駐再開は手動。
- Androidの強制停止では正常終了やHelperの終了順序まで保証されない。
- 通知はAndroid標準の低重要度チャンネル。サイレント/デフォルト、ロック画面表示、ステータスバーのアイコンは端末設定に依存する。

## タイミングと確認事項

- メーターの画面やキーレスマークの消灯は、Bluetooth ACL切断を意味しない。車体キーOFFからAndroidに切断が届くまで時間差があり、その後さらに5秒で自動Stop。
- VDが物理画面を暗転していてもAndroidはinteractive=trueのことがある。Stop・DEMOTE・Wake・再Sleepの途中で電源ボタンを押すと、1回目がOFFとして扱われて2回目で点灯することがある。これは独立したHOME予約の問題ではない。
- 診断Exportには独立したccu-watcher.logを含む。注目ログは `AUTO_START_TRIGGER`, `TRIAL_DISPLAY_BEFORE_WAKE`, `TRIAL_SLEEP_REQUEST`, `AUTO_STOP_PENDING`, `CCU_OUT_OF_RANGE`, `AUTO_STOP_BEGIN`, `AUTO_STOP_CAPTURE_EXIT`, `AUTO_STOP_SLEEP_REQUEST`, `AUTO_STOP_HOME_REQUEST`, `AUTO_STOP_HOME_RESULT`。
- libadb-android 3.1.1は第三者コードの原著権利表示を保持してローカル管理。`third_party/libadb-android/PATCHES.md`を参照。通常のPROMOTE/DEMOTE、OCR、JPEGやRFCOMM再接続には変更を加えない。

## 実機で確認された範囲

CCU連動の自動開始・停止、後からの初回ロック解除でHOMEへ戻ること、CCU切断から停止までの待機、画面ON/OFFが重なる場合のPOWER挙動を確認済み。長期運用、機種差、Android将来版の通知/バックグラウンド起動挙動は保証できない。

**試行錯誤の履歴**：`test/ccu-watcher-v40` とそのコミット群。30秒起動試験、接続イベント2回/6秒判定、カスタムRemoteViews通知は最終仕様ではない。安定版にはこの文書の採用事項を適用する。
