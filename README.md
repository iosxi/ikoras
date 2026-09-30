# ikoras（いこらす）

**ikora-lite（7 バンドのイコライザ）と volzz（音量キーの長押しで曲送り・ハード段より細かい音量）を 1 つにしたアプリ。**
2 つのアプリのままでは、全体に掛ける音声エフェクト（DynamicsProcessing）を取り合って、
YouTube にイコライザが効かない端末があった。1 つのアプリにして、同じエフェクトを分け合う。

- 対応: Android 9 (API 28) 〜 Android 16 (API 36)。YouTube への効き目は Android 11 以降
- 出来上がり: `ikoras.apk`（約 150 KB）
- 元にした版: ikora-lite v21、volzz v16（どちらもこの先は変えない。ikoras で続ける）
- 依存ライブラリ: 無し

## なぜ 1 つにしたか

Android は、全体（セッション 0）の DynamicsProcessing をエフェクトの種類ごとに 1 つしか作らず、
**同じ優先度なら後から加わった側が制御権を取る**（AQUOS SH-M06 で確認）。

- volzz は細かい音量のために、全体の DynamicsProcessing の入力ゲインを使っていた
- ikora-lite は YouTube（再生を知らせないアプリ）のために、再生中だけ全体に EQ を掛けていた

2 つのアプリだと、後から来た側が相手の設定（volzz なら音量）を消してしまう。そこで ikora-lite は
全体を volzz に譲り、端末標準の Equalizer で代わりに掛けていた。ところが Android は、
**どれかの再生にアプリごとの効果が付いていると、全体の効果を黙って一時停止する**
（AOSP `ThreadBase::checkSuspendOnEffectEnabled`。Android 11 以降は DynamicsProcessing だけが対象外）。
その結果、画面は「効いています」なのに音は変わらなかった（テスターの AQUOS R8）。

ikoras では、全体の DynamicsProcessing を **1 つだけ作って、段ごとに役目を分ける**（`Mix`）。

| 段 | 使うもの |
|---|---|
| 入力ゲイン | 音量キーの細かい音量（元 volzz） |
| 前段 EQ（7 バンド） | イコライザ。YouTube の再生中と全体モードのときだけ。それ以外はフラット |
| 圧縮（2 帯域）・リミッター | BASS。前段 EQ と同じとき |

作るときに全部の段を用意しておくので、片方が値を変えても、もう片方の値は消えない。
YT Music など再生を知らせるアプリには、今までどおりアプリごとの効果（別の DynamicsProcessing）で掛ける。

## 実測（AQUOS sense4 plus SH-M16 / Android 12、本体スピーカー、1 kHz を流して本体マイクで測った）

| 全体に付けたもの | 頼んだ量 | 実測 | 再生ごとの効果も付けたとき |
|---|---|---|---|
| 端末標準の Equalizer | -15 dB | -0.03 dB（効かない） | -0.30 dB |
| DynamicsProcessing の前段 EQ | -12 dB | -11.87 dB | -12.12 dB |
| DynamicsProcessing 1 つに入力ゲイン -6 dB ＋ 前段 EQ -6 dB | -12 dB | -11.89 dB | -12.14 dB |

ikoras そのもの（全体モード、トーンを流したまま操作）:

| 操作 | 変化 |
|---|---|
| 音量ダウン ×4（細かい音量 53 → 49 段） | -2.3 dB（1 回目は、測定アプリが変えたハード段との合わせ直しを含む） |
| 「全体」フェーダーを -12 dB | **-11.9 dB** |
| 音量アップ ×4（49 → 53 段） | **+3.8 dB**（1 段 0.95 dB。EQ の -12 dB は残ったまま） |
| 「フラット」 | **+11.8 dB**（EQ だけ外れ、音量の位置は残った） |

YouTube の再生中は、全体の効果は ikoras の DynamicsProcessing 1 つだけで、EQ と入力ゲインが両方載っていた。
YouTube を止めると EQ だけが外れ、音量用の効果は残った（`dumpsys media.audio_flinger` と診断の読み戻しで確認）。

## 使い方

1. `ikoras.apk` を入れて一度開く
2. **ikora-lite と volzz は止める**（アンインストールするか、volzz はユーザー補助を OFF に）。
   同時に動かすと、同じ再生に EQ が二重に掛かり、音量キーは 1 回の押しに 2 回反応する。入っていると画面に警告が出る
3. イコライザ: 今までの ikora-lite と同じ。YouTube にも効かせるなら、画面上の案内から「通知へのアクセス」で ikoras を ON
4. 音量キー: 「音量キー（長押し・細かい音量）…」から、今までの volzz と同じ設定画面を開き、ユーザー補助で ikoras を ON
5. Android 13 以降でストア以外から入れた場合、ユーザー補助と通知へのアクセスのスイッチが押せない（「制限付き設定」）。
   設定 → アプリ → ikoras → 右上の ︙ →「制限付き設定を許可」のあとで ON にする

個々の機能の細かい説明は、元のアプリの README にある（ikora-lite: 出力機器ごとの設定・BASS・診断など、
volzz: 長押しと超長押し・画面が消えているとき・細かい音量の仕組みなど）。

## 権限

インストール時に自動で付くもの: `MODIFY_AUDIO_SETTINGS`、`VIBRATE`、`FOREGROUND_SERVICE`、
`FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`。
使うときだけ求めるもの: 通知の表示（常駐・全体モード）、付近のデバイス（機器プリセット）。
利用者が設定で ON にするもの: ユーザー補助（音量キー）、通知へのアクセス。
通知へのアクセスは通知を読むためではなく、YouTube が再生中かをメディアセッションで読むために要る。
ほかのアプリのメディアセッションを読む `MediaSessionManager.getActiveSessions()` は、呼び出し元が
`MEDIA_CONTENT_CONTROL`（保護レベル signature|privileged で、一般のアプリには付かない）を持つか、
**有効な通知リスナーであること**を条件にしている（AOSP の `MediaSessionManager` の説明）。ikoras は後者の資格のためだけに
通知リスナーを置き、`onNotificationPosted` は持たない。
なお、メディアセッションで分かるのは「再生中か」までで、イコライザを付けるのに要る音声セッションの番号は含まれない
（`MediaController.PlaybackInfo` にあるのは再生の種類・音量・AudioAttributes・音量操作の ID だけ）。
そのため YouTube の再生中は全体に効かせる。
通知の表示の許可（`POST_NOTIFICATIONS`）は別物で、常駐の表示を見せるためだけ。許可しなくても常駐は動く。
`DUMP` は宣言だけで、`adb shell pm grant com.ikoras android.permission.DUMP` をしたときだけ有効（詳しい状態の表示と、YouTube の再生そのものへの付け方）。

## 限界

- **Android 10 以前**では、全体の DynamicsProcessing も一時停止される（SH-M06 で確認）。
  YouTube への効き目は使わない。アプリごとの EQ が付いている間は、細かい音量も効かないことがある
- 全体の DynamicsProcessing を**ほかのアプリ**（メーカーの音響アプリなど）が持っている端末では、
  YouTube の再生中の EQ は掛けられない（画面に理由が出る）。細かい音量は LoudnessEnhancer に落ちる（効くかは端末次第）
- 試験のメモ: `adb shell input keyevent` の音量キーはユーザー補助に届かない。`hid` コマンドで仮想の音量キー
  （USB の consumer control）を作ると、本物の押下と同じ道を通る。`uiautomator dump` のたびにユーザー補助はつなぎ直される
