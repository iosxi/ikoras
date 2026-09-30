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

## 画面

設定は 1 つの画面で、上のタブで 3 つを行き来する（v6 から。前回開いていたタブを覚える）。
どのタブも、先頭にそのタブだけの ON/OFF スイッチがある。

| タブ | 元のアプリ | 先頭のスイッチ | 中身 |
|---|---|---|---|
| イコライザ | ikora-lite | イコライザを使う | 効き具合、出力機器、プリセット、7 バンド、BASS、動作の設定、診断 |
| 音量キー | volzz | 音量キーを使う | ユーザー補助の状態、長押し・超長押しの動作と時間、振動、動作確認 |
| 音量段階 | voom（のちに volzz に統合） | 細かい音量を使う | 音量キーのサービスの状態、段階数、減衰の手段と今の音量 |

ikora-lite・volzz が入っているときの警告は、どのタブにも関わるのでタブのすぐ下に出す。
v5 までの「音量キー」は別の画面で、左上の「＜」でメイン画面に戻っていた。

## 使い方

1. `ikoras.apk` を入れて一度開く
2. **ikora-lite と volzz は止める**（アンインストールするか、volzz はユーザー補助を OFF に）。
   同時に動かすと、同じ再生に EQ が二重に掛かり、音量キーは 1 回の押しに 2 回反応する。入っていると画面に警告が出る
3. イコライザ: 今までの ikora-lite とほぼ同じ。ただしフェーダーは**つまみの近く（上下 28dp）を触ったときだけ**動く（v5 から）。
   ikora-lite は列のどこでも掴めたが、画面を下へスクロールしようとした指で EQ を動かしてしまうことが多かった。YouTube にも効かせるなら、画面上の案内から「通知へのアクセス」で ikoras を ON
4. 音量キー: 「音量キー」タブ（と細かい音量の「音量段階」タブ）から、ユーザー補助で ikoras を ON
5. Android 13 以降でストア以外から入れた場合、ユーザー補助と通知へのアクセスのスイッチが押せない（「制限付き設定」）。
   設定 → アプリ → ikoras → 右上の ︙ →「制限付き設定を許可」のあとで ON にする

個々の機能の細かい説明は、元のアプリの README にある（ikora-lite: 出力機器ごとの設定・BASS・診断など、
volzz: 長押しと超長押し・画面が消えているとき・細かい音量の仕組みなど）。

## Poweramp で使う

Poweramp は、再生しているだけでは再生の番号（音声セッション）を知らせない（Xperia で、再生中に何も届かないことを確認）。
Poweramp（build-1031）のコードを追うと、知らせを送るのは次の 2 つがそろったときだけだった:

1. 設定の「**MusicFX**」（キー `allow_platform_fx`、初期値 OFF）が ON。設定の検索で「FX」と打つと出る
2. 再生中に **Tone/Vol 画面の「MusicFX」ボタン**を押す。このとき `OPEN_AUDIO_EFFECT_CONTROL_SESSION` を送り、
   続けて番号付きで「端末のイコライザ画面」（`DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`）を開く

ikoras はその画面として呼ばれるので、開くアプリに ikoras を選べば、YT Music と同じく Poweramp の再生だけに効く。
Xperia で、この呼び出しで開くと Poweramp の再生に ikoras の DynamicsProcessing（有効・制御権あり）が付き、
「全体 −12 dB」で「イコライザを使う」を ON/OFF すると、曲の揺れを含めて約 10 dB ずつ上下した（マイクで測定）。

- Poweramp を終了すると番号が変わる。通知へのアクセスが ON なら、Poweramp のメディアセッションが消えたときに
  古い番号を手放し、次の再生では「✗ Poweramp に効いていません」とボタンからの開き方を出す
- Poweramp が入っていてまだ一度も付いたことがなければ、イコライザのタブの上に使い方を出す（「分かった」で消える）。
  「動作の設定」にも同じ説明を置く
- 実際のボタンを押す操作は、ボタンを自動操作で読めず、同じ呼び出しを adb から送って代わりにした
- Xperia では、開くアプリの候補は Android の MusicFX、Sony のオーディオ設定、ikoras の 3 つだった

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
