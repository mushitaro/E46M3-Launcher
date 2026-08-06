# E46M3 ///M Launcher — 設計メモ

**日付**: 2026-08-04
**対象**: `app-launcher/`（`app.tsunagi.e46m3.launcher`）
**状態**: 実機に導入済み・HOME 置き換え済み。8ボタン全て到達確認済み。回転数ライブ表示動作。

証拠ラベル: **[V]** 実機または実物で直接確認　**[I]** 証拠からの推論　**[U]** 未確認

この文書は `docs/01-device-investigation.md`（端末調査）と `docs/03-mainui-recon.md`（逆コンパイル偵察）の続きで、**実装の設計判断**と、**実車で確定した事実**、および**そこで判明した誤りの訂正**を記録する。

---

## 1. 現在の状態

| 領域 | 状態 |
|---|---|
| HOME 置き換え | **[V]** 動作。`preferred-activities` XML に永続化を確認 |
| ホーム画面（カーボン／10キー／LCD／昼夜） | **[V]** 実機動作 |
| M モード（キー入れ替え＋窓モーフ） | **[V]** 実機動作 |
| アプリリスト | **[V]** 実機動作。19件列挙 |
| 8ボタンの到達先 | **[V]** 全て実測（§5.1） |
| 回転数 | **[V]** DS2 経由でライブ表示。5チャンネル裏取り済み（§6.2） |
| 外気温 | **[V] 取得できた。DME のライブブロック3・オフセット15**（§6.1.7）。実車 `ambient=21C`。**K+DCAN ケーブル接続時のみ** |
| M モードの温度4連（水温／油温／吸気温／外気温） | **[V]** 実車で動作。`WTR 54°C OIL 54°C IAT 48°C OUT --°C`（§6.2） |
| ボタンのインジケータ | **[V]** 実車で3つとも正しく消灯（未接続）。点灯側は未検証 |
| TUNER | **[V] 全画面 TWA で動作。** ステータスバーも消えている（§10.5） |
| DIAG / 整備履歴 | 未配信・未実装。**M コンソールでは背景プレート**（§10.8）。位置は確保済み |
| 昼間視認性 | **[U]** 実車確認は**まだできていない** — 投入時刻が 22:40 で夜モードだったため（§10.1） |
| 起動中の壁紙 | **[V]** 実車で適用済み。次回キーオン時に見えるはず（§10.10） |
| R8 難読化 | 無効のまま |

**未達の予算**: 起動時間。実測 790〜1780ms に対し予算 800ms。ただし置き換え前の純正ランチャーは **5966ms** **[V]** で、6分の1以下にはなっている。

---

## 2. 実装アーキテクチャ

### 2.1 単一 Activity・3レイヤ

`HomeActivity` ひとつ。ホームコンソール・M コンソール・アプリリストはいずれも Activity ではなく、同一 Window 内のレイヤである。

```
FrameLayout root
├ ImageView carbon              ← アプリリストを開いても触らない
├ FrameLayout console
│   ├ WindowFrameView           ← 表示窓の枠（描画で解決）
│   ├ FrameLayout lcd           ← 時刻/日付/外気温/ソース
│   ├ TachView                  ← M モードの計器
│   ├ FrameLayout sockets / plates / keys / keys_m
└ ViewStub app_list             ← 初回タップで inflate
```

カーボンが `console` の**外**にあるのが要点。アプリリストを開くときは `console.visibility = INVISIBLE` にするだけで、背景の View オブジェクトは一度も触らない。「背景固定」の要件を、2枚の背景を一致させるのではなく**同一オブジェクトが動かない**という構造で満たしている。

### 2.2 依存

`constraintlayout` / `recyclerview` / `androidx.browser` / `usb-serial-for-android`。

Compose・AppCompat・Material Components は意図的に不採用。理由は API 27 固有で、Baseline Profile を消費する `speed-profile` dexopt が **API 28+** であるため、この端末では Compose ランタイムの約1万メソッドがインオーダーの Cortex-A7 上で JIT される。HOME アプリは起動・`KILL_APPS`・LMK kill のたびにコールドスタートするので、この組み合わせは最悪になる。

**`usb-serial-for-android` だけは例外的に入れた。** FTDI の2つの性質が、失敗を静かにするからである。

- 読み取りパケットの先頭2バイトがモデムステータスで、剥がし忘れると全体が2バイトずれ、チェックサムが合わなくなって「車が応答しない」ように見える
- ボーレート分周比の符号化がチップ世代ごとに違い、間違えるとリンクは正常に開いて何も返さない

DS2 のバイト列は自前（`Ds2.kt`）、USB シリアルのバイト列は他人、という切り分け。JitPack は `com.github.mik3y` グループのみにスコープしてある。

### 2.3 座標を px で持つ

`res/values/design.xml` の寸法は全て px。デザインは 1024×600 で描かれており、それがこのパネルの物理解像度と**完全に一致する**ため、px なら 1:1 で再現され丸め誤差が出ない。dp にすると全値が 1.5 で割られ、148px → 98.67dp のように端数になる。

密度非依存はここでは何も買わない（パネルは1種類・固定・他機種に出荷しない）ので、**意図的で境界の明確な例外**として扱う。

### 2.4 レイアウトではなく描画で解いたもの

| クラス | なぜレイアウトで解かないか |
|---|---|
| `WindowFrameView` | デザインは表示窓の `left/top/width/height` をトランジションさせる。LayoutParams でやると 500ms のアニメ中ずっと measure+layout が走り、インオーダー CPU で確実にガタつく。画面全面の1 View が角丸矩形を1つ描き、4つの float を動かすだけにした |
| `TachView` | 29セグメント＋数字＋シフトランプを子 View 化すると30数個。1つの display list 約40オペにした |
| `ModeSwitch` | キーの沈み込みは `translateZ` ではなく **scale**。CSS の `perspective(520px) translateZ(-300px)` は要素自身の transform-origin を消失点にするので、中心基準の一様スケール 520/(520+300)=0.6341 と**数学的に等価**。scale は RenderNode プロパティなので measure も layout も display list 再記録も起きない |
| 減光 | `filter: brightness(.05)` は不透明なキー面の上では「黒を alpha 0.95 で重ねる」と厳密に同一（brightness(k) = dst·k、黒 alpha (1−k) の合成も dst·k）。子 View の alpha アニメで済み、オフスクリーンバッファが不要 |

M モード遷移のタイミングで効いている非自明な点：**去る側が 390ms、来る側が 400ms で opacity を切り替える**（0.01s トランジション＝カット）。この 10ms の隙間により2つのコンソールが同時に画面に乗らないので、中央3列で重なっているのに z 順の入れ替えが不要。

### 2.5 タコメーターの形状

デザインは各セグメントを「軸沿いの角丸矩形を、それぞれの中心で回転」させている。隣り合うセグメントの回転角が違うので**向かい合う辺が平行にならず、物理的に接することができない**（左端 −33°、右端 −0.1°）。鱗のように割れる。

同じ4式が帯（リボン）も厳密に記述している。`rot = atan2(-372·e^(-6t), 568)` は中心線の接線角であり、`h` は法線方向の厚み。セグメントの**中心**ではなく**境界**をサンプリングし、中心線 C ± (h/2)·N で2本の縁を出せば、辺を完全に共有する29枚の四角形になる。デザインの数値は不変で、分割の仕方だけが変わる。

切り口は**垂直**に統一（オーナー指定）。上下の縁は曲線が急なところで x 方向にずれるため、最初と最後の切り口は**両方の縁が存在する範囲**で取る。端に細い破片が残らないのはこのため。

---

## 3. 起動可能性の判定 — 2つの機構が別々の問いに答える

`TargetLauncher` は**事前解決**と**実行時 catch** の両方を持つ。これは冗長ではなく、答えている問いが違う。

| 機構 | 答える問い | 用途 |
|---|---|---|
| `resolveActivity` | インストールされているか | 事前の減光（位置は動かさない） |
| `startActivity` の catch | **起動を許可されているか** | 押した瞬間の真実 |

`resolveActivity` が非 null でも起動できる保証はない。**解決は `exported` も権限も見ない。** この区別は実車で実証された（§5.1 の EQ）。

そして **ADB からはこの問いに答えられない** — shell は `START_ANY_ACTIVITY` を持つため。ただし `adb shell input tap` は**アプリ自身にタッチイベントを配るだけ**で、そこから先の `startActivity` は我々の権限で走る。これがリモートからターゲット行列を実測できた理由である。

---

## 4. 実車で決着した Phase 0 の未確認事項

| # | 事項 | 結果 |
|---|---|---|
| **R-06** | ナビバー／物理 Back キーの有無 | **[V]** どちらも**無い**。`qemu.hw.mainkeys=1`、窓一覧に NavigationBar 無し、キーパッドは `VOLUMEDOWN`/`VOLUMEUP`/`POWER` のみ。→ イマーシブで隠すものが（ステータスバー以外に）存在しない |
| **R-07** | 物理 HOME キーが `category.HOME` を発火するか | **[V]** 発火する。ランチャーが正しく前面化する |
| **R-10** | 純正ランチャー置き換えで MainUI 機能が壊れないか | **[V]** バックカメラ・ステアリングリモコン共に正常 |
| **R-12** | CJK フォントの有無 | **[V]** `NotoSansCJK-Regular.ttc` あり。日本語アプリ名は正しく描画される |
| **R-03** | MainUI のアクティビティが実際に起動できるか | **[V]** §5.1 で全数実測 |

`/data` 空き 20.4GB **[V]**。

---

## 5. 実測されたターゲット行列

### 5.1 到達先（全て [V]）

| キー | 勝ったステップ | 備考 |
|---|---|---|
| RADIO | `com.ts.main.radio.RadioMainActivity` | §7.1 で順序を訂正 |
| VIDEO | `com.ts.main.Media.USBMainActivity` | → `com.ts.dvdplayer/USBActivity` に連鎖 |
| EQ | `com.ts.set.SettingSoundActivity` | → `com.ts.set.dsp.SetDspMainActivity` に着地（§7.2） |
| MAP | `com.google.android.apps.maps` | |
| BT | `com.ts.MainUI/com.ts.bt.BtConnectActivity` | |
| Android接続 | `net.easyconn` | |
| CarPlay | `com.ts.carplayapp/com.autochips.carplayapp.LoadingActivity` | §5.2 |
| 設定 | `com.android.settings/.Settings` | |

`com.ts.set.dsp.SetDspMainActivity` を**直接**指定した場合は **SECURITY** **[V]**。Phase 0 の「`action.MAIN` バケット39個が exported ホワイトリスト」という推論 **[I]** は的中したが、結論は半分だけ正しかった（§7.2）。

### 5.2 ★ `category.MYLAUNCHER` — このメーカー固有の落とし穴

このメーカーのアプリは `android.intent.category.LAUNCHER` ではなく、独自の **`android.intent.category.MYLAUNCHER`** を宣言する **[V]**。

```
com.ts.carplayapp/com.autochips.carplayapp.LoadingActivity
  Action:   "android.intent.action.MAIN"
  Category: "android.intent.category.DEFAULT"
  Category: "android.intent.category.MYLAUNCHER"     ← LAUNCHER ではない
```

`getLaunchIntentForPackage` は MAIN+LAUNCHER（または MAIN+INFO）を探すので **null を返す**。ステップは起動を試みることすらできず、キーは自分で減光して `NO_INTENT` を記録していた。

影響は CarPlay ボタンだけではない。**アプリリストも同じ問い合わせをしていた**ため、以下が丸ごと欠落していた **[V]**。

| パッケージ | LAUNCHER | MYLAUNCHER |
|---|---|---|
| `com.ts.logoset`（起動ロゴ設定） | 0 | 1 |
| `com.ts.carplayapp` | 0 | 1 |
| `com.ts.tscanupdate` | 0 | 1 |
| `com.ts.MainUI` | 1 | **35** |

対処は「MYLAUNCHER も見る」ではなく「**LAUNCHER を1つも持たないパッケージだけを補う**」。MainUI の35個はアプリではなく**同一アプリの画面**であり、そのまま混ぜると一覧が1アプリの内部ページで埋まる。結果、列挙は 16件 → 19件になった **[V]**。

---

## 6. 車両テレメトリ

### 6.1 外気温 — ★ 一度誤った結論を出した。訂正済み

> **この節は 2026-08-04 に全面的に書き直した。** 初版は「OEM 経路は存在しない」と結論していたが、**間違っていた。** 見ていた関数が違った。誤りの記録として §6.1.0 を残す。

#### 6.1.0 何を間違えたか

初版の根拠は3点で、うち**1点は証拠として無効**、**2点は別系統の話**だった。

| 初版の根拠 | 評価 |
|---|---|
| `ITsCommon.GetTemp()` が null | **[V] 事実。ただし `Can.mOutTemp` が空であることしか証明していない** |
| `getprop forfan.user.info` が空 | **[V] 事実。同じ `mOutTemp` 系** |
| `UpdateOutTemp` のログが0行 | **無効。** この端末のログリングは数秒で溢れる。後に自分の `Ds2Link` の行すら `logcat -d \| grep` で拾えず `logcat -s TAG -m N` に切り替えている。同じ方法で「出ていない」と結論していた |

そして決定的に、**MainUI には車両データの store が2つある**のに、片方しか見ていなかった。

```
ITsCommon.GetTemp()            -> Can.mOutTemp      (CanDataInfo.CAN_OutTmp)
                                    ← CanJni.GetOutTemp()
ICarInfoService.requestCarBaseInfo() -> CanFunc.mCarInfo (CanDataInfo.CAN_Msg)
                                    ← CanJni.GetCarInfoAidl()
```

**片方が空であることは、もう片方について何も言わない。** ネイティブの取得関数からして別である。

以前 BMW の回転数を追ったとき `CanDataInfo.CAN_Msg.Rpm` に辿り着いていたにもかかわらず、外気温では同じ構造体を見に行かなかった。同じ `CAN_Msg` に `OutTemp` が並んでいる。

#### 6.1.1 実際の経路 — `ICarInfoService`

**`com.ts.can.carinfo.CarInfoService` は明示的に exported されている** **[V]**（MainUI のマニフェストを `aapt2 dump xmltree` で確認）。

```xml
<service android:name="com.ts.can.carinfo.CarInfoService" android:exported="true">
  <intent-filter>
    <action android:name="com.ts.can.carinfo.CarInfoService" />
    <category android:name="android.intent.category.DEFAULT" />
  </intent-filter>
</service>
```

`requestCarBaseInfo()`（transaction code **6**）が `int[69]` を返し、MainUI 自身の詰め込みコードによれば **[V]**：

| index | 内容 | index | 内容 |
|---|---|---|---|
| 0 | `Avalid` | 15 | `BatV` |
| 2 | `Speed` | 42..58 | `Vin[17]` |
| 3 | **`Rpm`** | 61..66 | `DoorSta[6]` |
| 4 | `WaterTemp` | **67** | **`OutTemp`** |
| 6..9 | `Tpms[4]` | 68 | `OilTemp` |

`OutTemp` のスケールは **0.1℃**（MainUI 自身の消費側が `OutTemp * 0.1` で℃、`*0.1*1.8+32` で℉に整形している **[V]**）。

MainUI の **BMW 専用ビュー** `com/ts/can/bmw/lz/CanBMWLzYbxxView.java` がこの `mCanMsg.OutTemp` を読んでいる **[V]**。BMW のデータはこちらの経路を通る。

#### 6.1.2 実装

`ICarInfoService.java` は逆コンパイル結果を**逐語コピー**した（`ITsCommon.java` と同じ理由）。この interface のトランザクションコードは**宣言順ではない**：

```
requestCarAirInfo   = 1     requestCarDoorInfo  = 4
requestCarAirLtTemp = 2     requestCarIllInfo   = 5
requestCarAirRtTemp = 3     requestCarBaseInfo  = 6   ← 4番目に宣言、コードは6
```

手書き `.aidl` から再生成すると `requestCarBaseInfo` にコード4が割り当てられ、**呼び出しは黙って `requestCarDoorInfo` に化ける**。

`VehicleLink` が両サービスを bind し、外気温は `ICarInfoService` を優先、`ITsCommon.GetTemp()` をフォールバックとする。`int[69]` の生ダンプを15秒に1行ログする——69スロットのうち5つしか読んでおらず、**この車が実際にどのスロットを埋めるかを知る手段が他にない**ため。

#### 6.1.3 未検証

**[U] この経路が実際に値を返すかは未確認。** 実装した時点でヘッドユニットが電源断になったため。`UpdateCarInfo()` には `mCanInit != 0 && CanJni.GetCanFsTp() != 0` というゲートがある。次回接続時に生ダンプを見れば、外気温だけでなく回転数・水温・電圧・速度・VIN まで一度に判明する。

`date[3] = Rpm` が生きていれば、**K+DCAN ケーブル無しで回転数が取れる**ことになる（DS2 経路の代替またはフォールバック）。以前「BMW の回転数は内部でデコードされるが外に出ていない」と訂正したが、**それも誤りで、ここから出ている**可能性が高い。

#### 6.1.4 旧・別経路の調査結果（参考として保持）

- **MSS54（DME）に ambient は無い** **[V]**。`mss54.telegrams.json` を全文検索して該当なし。吸気温はあるが、それを外気温と称するのは嘘になる
- **IKE は持っている**が、DIAG のカタログにあるのは MSS54・SMG2・DSC MK60 の3つのみ **[V]**。IKE を取り込むには `IKE*.prg` が要る

これらは `ICarInfoService` が空振りした場合の次の手として有効。

---

### 6.1.7 ★★★ 結局、最初から手の中にあった

**外気温は MSS54 のライブブロック3、オフセット15 にある。** `tumg` / `Umgebungstemperatur`、`uint8 - 48`。実車で `ambient=21C` **[V]**。

```
{ symbol: "tumg", name: "Ambient temperature CAN", ja: "外気温（CAN）",
  offset: 15, format: 'uint8', scale: 1, add: -48, unit: "°C" }
```

生成テーブルの名前が **"Ambient temperature CAN"** であることが要点で、DME はこれを測っていない — **バスから受け取っている**。つまりこれは I-Bus の外気温であり、バスにタップを立てるのではなく DME に訊けば手に入る。

**この探索全体が不要だった。** ブロック3は**回転数のために毎秒11回読まれていた**。オフセット 0・10・11・12・16 を取り出しながら、15 は隣で読まれずに捨てられていた。そこから、

1. `ITsCommon.GetTemp()` を調べ（null）
2. 「見ている関数が違う」と訂正して `ICarInfoService` を実装し（全ゼロ・§6.1.6）
3. I-Bus に物理タップを立てる話を始めた

**3回とも、自分が所有しているバッファに既に届いているものを探しに行っていた。** テーブルは `packages/ds2-mss54/src/liveValueBlocks.generated.ts` にあり、5フィールドを実装した時点で目の前にあった。**実装したフィールドの隣を読まなかった。**

§6.1.0 の教訓は「見ている関数が違うのでは、と疑え」だった。今回はその一段手前で、**既に読んでいるデータの残りを読め**、である。

#### ケーブルが必要になる

出所が DME なので、**K+DCAN ケーブルを抜くと外気温は消える。** ヘッドユニット側の2経路はこの車では死んでいる（§6.1.6）のでフォールバックが無い。抜いたときは古い値を残さず `--.-` に戻す。

そのため `Ds2Link` は **M モードを閉じても止まらなくなった**。ホーム画面の外気温スロットが同じブロックから来るので、11Hz（ゲージ表示中）と30秒間隔（それ以外）の2段階になっている。`onPause` では従来どおり完全に止める — TUNER が WebUSB で DME に届くようになった以上、USB デバイスを握ったままにはできない。

### 6.1.8 ★ なぜヘッドユニット側が空なのか — 車種が未設定だった [V]

「ケーブル無しで常時表示したい」という要求に対し、ヘッドユニット経由が復活しうる唯一の筋。

**ゲート**（`CanFunc.java:968`）:

```java
if (mCanInit != 0 && CanJni.GetCanFsTp() != 0) { … UpdateCarInfo() … }
```

**車種は `FtSet.GetCanTp()` で決まる**（`CanFunc.CanInit` → `CanJni.CanStart(mFsCanTp, SubType)`）。`FtSet` は全てネイティブで、プロパティにもファイルにも出ない。そこで `libts50xhw.so` を逆アセンブルした **[V]**:

```
GetCanTp     ldr.w r0, [r3, #0xa24]
GetCanSubT   ldr.w r0, [r3, #0x6c8]
GetCanTpms   ldr.w r0, [r3, #0x6cc]
```

設定の実体は **`/dev/block/mmcblk0p15`**（`by-name/forfanzone`、先頭に `andfactory`、**world-readable**）。実車で読むと **0xA24 も 0x6C8 も 0** **[V]**。

**`CanTp == 0` ならゲートは開かず、`CAN_Msg` は全ゼロのまま** — 観測（§6.1.6）と完全に一致する。工場ゾーンが概ね未設定であることの傍証もある：`forfan.serial.number` と `forfan.device.model` がどちらも**空** **[V]**。

#### 起動ログで確定した **[V]**

```
08-04 23:55:00.120  1375  1375 D CanFunc : Init can tp = 0, sub = 0
```

**`CanTp = 0`、`SubT = 0`。** パーティション読み（0xA24=0, 0x6C8=0）と完全に一致したので、**逆アセンブルで得たオフセットと、構造体ベースがパーティション先頭に 1:1 で乗るという仮定の両方が裏取りされた**。

したがって `GetCanFsTp() != 0` は偽、`UpdateCarInfo()` は一度も走らず、`CAN_Msg` は全ゼロのまま。**§6.1.6 の観測は故障ではなく設定の帰結である。**

##### 取り逃しと、その対処

最初のキーサイクルでは取れなかった。`main` リングが **256KB** しかなく、ブート中のログ密度で MainUI の init 行が数十秒で流れるため。**`logcat -G 4M` と `setprop persist.logd.size 4M`**（再起動を跨いで効く）を入れて2回目で確保した。この端末でブート時のログを追う作業には、これが前提条件になる。

#### 確認方法

MainUI は起動時に1行だけ吐く：

```
CanFunc: Init can tp = N, sub = M
```

ログリングは数秒で溢れるので**キーオフ→オンの瞬間を捕まえる**必要がある。`FsCanActivity.onResume()` はこの値を画面に表示するが、**exported ではない**（shell からも `SecurityException` **[V]**）ので純正 UI 側からしか開けない。

#### ⚠ `SetCanTp` を勝手に書かないこと

`FtSet.SetCanTp(index)` は存在する（`FsCanActivity.java:487`）。**書かない。** 車種設定はバックカメラのトリガとステアリモコンを含むデコーダ全体の構成であり、誤った変種を入れると動いているものを壊す。選ぶのはオーナーの判断。

#### 意味すること

正しい BMW 変種を入れれば `CAN_Msg` が埋まり、**外気温は既に実装済みの `ICarInfoService` 経路から降ってくる**（§6.1.1）。ケーブル不要・常時・ホーム画面。**こちらのコード変更はゼロ。**

**ただし前提が1つ残る：デコーダボックスが物理的に付いているかどうか。** `CanTp=0` である以上そのハードは一度も使われておらず、付いていなければ種別を設定しても何も出ない。「ステアリモコンが効くからボックスは生きている」という以前の推論は**根拠として弱い** — MFL は MCU の抵抗入力にも来うるし、`CanTp=0` の状態でも現に効いている。

### 6.1.9 車種を設定してみた結果 — 設定は入った、データは来ない [V]

**2026-08-05、工場設定で `274. Bmw E46（LZ）` を選択して再起動。**

| | |
|---|---|
| 工場ブロブ | `CanTp @0xA24 = 274`、`CanSubT @0x6C8 = 1` **[V]** |
| MainUI 起動ログ | `CanFunc: Init can tp = 274, sub = 1` **[V]** |
| `carBaseInfo` | **69スロット全ゼロのまま** |
| `GetTemp()` | **null のまま** |
| CAN 関連ログ | **初期化とJNI登録の2行のみ。** デコード活動が皆無 |
| MainUI の tty | **1つも開いていない**（`/proc/<pid>/fd` に tty 無し） |

```
08-05 00:18:39.753  1376  1376 D CanFunc : Init can tp = 274, sub = 1
08-05 00:18:39.770  1376  1376 E [lgb]CanNative: RegisterNativeMethods com/lgb/canmodule/CanJni
（以降 CAN 関連は一切なし）
```

**設定は正しく入り、正しい変種で初期化された。それでも何も来ない。**

#### 番号の出所

`can_auto_array`（284件、`MainUI.apk`）。一覧の表示は `"<index>.<名前>"` で、`setSubSel()` が `FtSet.SetCanTp(index)` を呼ぶので **index がそのまま CanTp**。BMW は8件あり、E46 は1件だけ：

| CanTp | 名前 |
|---|---|
| 42 | BMW E90(Hc,Hz,Lz，Od) |
| 65 / 85 | BMW Mini / BMW MINI (FSTT) |
| 138 | BMW (with Host) |
| 163 | BMW X1（WC） |
| 176 | BMW (Zmyt) |
| **274** | **Bmw E46（LZ）** |
| 277 | Bmw（LZ） |

`LZ` は `com/ts/can/bmw/lz/CanBMWLzYbxxView.java:53` — **`mCanMsg.OutTemp` を読む唯一のクラス**の系統。42（E90）は K-CAN で E46 の I/K-Bus とは物理層が別なので不可。

#### 残る解釈

1. **デコーダボックスが物理的に無い。** 端末情報画面に `CAN :` の行が出ていなかったことと整合する
2. バスが寝ていた（エンジン停止・車両スリープ）。I-Bus は ACC/イグニッションと車両の起床状態に依存する

**1が有力。** 設定は正しくなったので、判定は「起こした状態で `CAN :` にバージョンが出るか」に絞られた。出なければボックス無しが確定し、I-Bus タップだけが残る。

**274 は入れたままで無害。** 誤りではなく、単に受け手がいない。

### 6.1.5 参考：`mOutTemp` 系が空であること自体は事実

以下は依然として正しい観測であり、**`ITsCommon.GetTemp()` を当てにしてはいけない**理由である。ただしこれは `CAN_OutTmp` store についての話であって、車両が外気温を送っていない証拠ではない。

```java
// MainUI.java — ITsCommon の実装
public String GetTemp() {
    if (MainUI.this.mOutTemp.UpdateOnce != 0) { ... }
    return null;                              // ← 常にここ [V]
}

// CanFunc.java — CAN ループから10ティックごとに実行される [V]
public static int UpdateOutTemp() {
    Can.updateOutTemp();                      // CanJni.GetOutTemp(mOutTemp)
    if (Can.mOutTemp.Update != 0) {           // ← 一度も真にならない
        SystemProperties.set("forfan.user.info", str);
        Log.d(TAG, "forfan.user.info, ...");  // ← このログが1行も出ない [V]
    }
    if (CanJni.GetCanType() == 274 && FtSet.Getyw8() == 0) {   // BMW LZ 用の別経路
        CanJni.BmwLzGetOutTempData(nBmwLz_OutTemp);
        ...
    }
}
```

| 観測 | 結果 |
|---|---|
| `ITsCommon.GetTemp()` | `null` **[V]** |
| `getprop forfan.user.info` | 空 **[V]** |
| `UpdateOutTemp` のログ | 0行 **[V]** |

`UpdateOutTemp()` は呼ばれているのに `Can.mOutTemp.Update` が立たない。**ネイティブ CAN スタックは、この store には外気温を書かない。** §6.1.1 の通り、BMW のデータは `CAN_Msg` 側を通るので、これは矛盾ではなく別チャンネルの話である。

（binder の到達可能性は `bound to MainUI: true` **[V]** で解決済み。）

---

### 6.2 回転数 — DS2 経路、実車で検証済み

```
USB host → FTDI FT232R (0403:6001, product "K+DCAN") [V]
        → 9600 8E1 K-line
        → DS2  12 05 0B 03 1F        (READ_IO_STATUS, block 3)
        → MSS54 live block 3 (35 bytes)
```

| クラス | 役割 |
|---|---|
| `Ds2.kt` | フレームコーデック。**読み取り専用（構造上）** — 発行できる制御バイトは `READ_IO_STATUS` と keep-alive のみ |
| `Ds2Framer.kt` | K-line のフレーム再同期。純粋ロジック、テスト10件 |
| `Ds2Port.kt` | USB シリアル。9600 **8E1**、レイテンシタイマ短縮、パージ、送受信 |
| `Ds2Link.kt` | ワーカースレッド、90ms ポーリング、USB 権限、無応答時のバックオフ |

**設計判断: DS2 のポーリングは M モードが開いている間だけ動く。** K-line は共有の単線であり、誰も見ていないゲージのために常時喋るのは読み手のいないバス負荷になる。また TUNER と DIAG のために DME を空けておく必要がある（ケーブルが挿さっている理由はそちらである）。

**Ds2Framer が解いている2つの問題**

1. **エコー** — K-line は単線で、K+DCAN ケーブルの送信は自分の受信に返る。エコーは**構造的に正当な DS2 フレーム**（同じアドレス、正しいチェックサム）なので検証では弾けず、認識するしかない。送信内容を渡して1回だけバイト一致で捨てる
2. **再同期** — 素朴な「先頭を見て、長さが足りなければ待つ」実装は**デッドロックする**。長さバイトが 0xFF に化けると来ない255バイトを永遠に待ち、本物のフレームがその3バイト後ろで腐る。**この不具合はユニットテストが捕まえた。** 全オフセット走査に書き換え、未完成候補が走査を止められないようにした。完全なフレームがどこにも無い場合のみ、**まだ完成しうる最も早いオフセット**までバッファを詰める（分割されたフレームを捨てないため）

**実車検証結果** **[V]** — 暖機アイドル

```
rpm=888  coolant=75C  oil=75C  intake=50C  batt=13.5V
rpm=886  coolant=75C  oil=75C  intake=50C  batt=13.4V
rpm=885  coolant=75C  oil=75C  intake=50C  batt=13.4V
```

回転数が 885〜888 とアイドルらしく揺れ、油温が水温に追従し、吸気温が停車時の熱ダレとして妥当な値、電圧が充電中の値。**`MSS54DS0.prg` の逆コンパイル由来で「走っている車では未検証」だったオフセット表 5つ全てが、桁も符号も向きも正しいと確認された。** これは逆コンパイルした表だけでは分からないことである。

`134 * 0.1f` が `13.400001` になる浮動小数点の粗を発見し、`/ 10f` に修正（元データは 0.1V 単位の整数）。

### 6.3 K+DCAN ケーブルの検出

`KdcanLink` が `UsbManager` を見て、FTDI（VID `0x0403`）または CH340（`0x1A86`）があれば M ボタンを出す。無ければ M キーをホームコンソールから外し、**そのソケットにカーボンのブランクプレートを立てる**。

**VID のみで PID を見ないのは意図的。** 失敗の非対称性による：取りこぼすと**ケーブルを挿しているのに M メニューが出ず、理由も分からない**。逆に別の FTDI 機器で誤って出ても、リンクの無いメニューが開くだけで害がない。ユニットテスト `KdcanLinkTest` で固定。

判定が主張しているのは**ケーブルの存在だけ**であり、車が起きているとも DME が応答するとも言っていない。

---

## 7. 誤っていた推論の訂正

### 7.1 `Iconfig.ini` のフラグ — 推論が実験に負けた

`/sdcard/Iconfig/Iconfig.ini` の

```
com.ts.main.radio.RadioMainActivity,0
com.ts.can.CanExRadioActivity,1
```

を「0=無効 / 1=有効」と読み **[I]**、RADIO の第一候補を `CanExRadioActivity` に入れ替えていた。

**実車では逆だった [V]。** `CanExRadioActivity` は**例外を投げずに起動し、そして何もしない**（画面も音も出ない）。例外が出ないので「成功」として記録され、本物のチューナーに二度と辿り着かなくなる。`RadioMainActivity` が実際の FM/AM チューナーである。

**`CanExRadioActivity` はフォールバックとしても残していない。** 成功を報告しながら何もしないステップは、失敗するステップより有害である（勝者として記憶され、本当の失敗を隠す）。

### 7.2 `SetDspMainActivity` — 到達不能という結論は半分だけ正しかった

Phase 0 の推論「`com.ts.set.dsp.SetDspMainActivity` は 39個の exported バケットに無いので到達不能」**[I]** は、**直接指定に関しては的中**した（SECURITY **[V]**）。しかしこのアクティビティはこの個体に**そもそも登録されていない** **[V]**（マニフェストの文字列プールにあるだけ）。

一方 `com.ts.set.SettingSoundActivity` を指定すると、**着地するのは `SetDspMainActivity`** **[V]**。前者は後者へのエイリアス（か直接の入口）だった。**別の名前からなら入れる。** 順序付きフォールバックを積んでおいたことが効いた例。

---

## 8. 実車で見つかった不具合と根本原因

### 8.1 USB 接続で Torque Pro が勝手に起動する

**原因**: 既定設定ではなく（Torque の「デフォルトで開く」は**設定なし** **[V]**）、FTDI に一致する `device_filter` を持つアプリが Torque しか無かったため、Android が候補1つとして選択肢を出さずに直行していた。

**対処**: `UsbAttachActivity` — UI を持たない受け口でアタッチインテントを取得。

ホーム画面にフィルタを付けなかったのは、そうするとケーブルを挿すたびにホーム画面が前面に飛び出すため。診断ケーブルを挿しただけでラジオから叩き出されるのは違う。この Activity は窓を持たず、composed される前に finish し、Recents にも残らない。ランチャーへの通知も無い（`KdcanLink` が同じブロードキャストを聞いている）。

**副次的な利得**: アタッチインテントを処理したパッケージには、そのデバイスへのアクセス権限が**永続付与**される。毎回の USB 権限ダイアログが消える。運転者が画面で質問に答えさせられる状況が1つ減る。

### 8.2 M キーが見えているのに押せない

**原因**: 状態設定が非対称だった。ケーブル無しのとき `isClickable = false` にする一方、挿さって再構築されたときに**戻す処理がどこにも無かった**。`visibility` は `applyInstant` が副作用で戻していたため、**見えているのに死んでいる**キーになった。例外も出ないので、コードを読むまで手掛かりがゼロ。

**対処**: 両状態を毎回明示的に設定する。「片方だけ書いて、もう片方は他の処理が戻してくれる」に頼らない。

### 8.3 ★ M モードが数秒で勝手に畳まれる — `IdleScreen`

**原因**: このユニットには `IdleScreen` というベンダーサービスがあり、**無操作が続くと HOME インテントを再投入する** **[V]**。

```
IdleScreen: activityIdleScreen: idleIntent: Intent { act=android.intent.action.MAIN
  cat=[android.intent.category.HOME] flg=0x10800000
  cmp=app.tsunagi.e46m3.launcher/.HomeActivity }
```

`onNewIntent` は「HOME が来たら綺麗なホームに戻す」設計だったため、そのたびにタコメーターが畳まれていた。**まさに人が見つめている（＝触っていない）ときに消える**という挙動になる。

**対処**: `onStop` を経た場合だけリセットする。ラジオや設定から戻ってきたなら綺麗なホームに戻すべきだが、こちらが可視のまま届いた小突きは、戻るべき場所が無いのだからリセットしない。

**一般化**: このユニットは HOME インテントを**ナビゲーション以外の目的でも**送ってくる。`onNewIntent` を「ユーザーが HOME を押した」と等価に扱ってはいけない。

---

## 9. 計器としての規律（実装に落ちている規則）

- **でっち上げない。** データ源が無いチャンネルは `--.-` / `----` を出す。もっともらしい数字は出さない
- **予約スロットは初日から存在する。** 値が後から繋がってもレイアウトは1pxも動かない
- **プレースホルダは測定値の色を着ない。** `LcdReadout` がこれを強制する
- **消灯ランプは「無い」ではなく「暗い窓」。** ランプを持つキーは、状態によらずランプを持つキーに見える。消灯色は黒60%で、琥珀色の低アルファは使わない（「半分点いている」に見えるのは、ステータスランプが絶対にやってはいけない見え方）
- **ランプは押下ではなく起動成功に従う。** 実車で CarPlay が起動失敗しながらランプを点けていた
- **効果を持てないスイッチを ON に見せない。** DIAG と整備履歴は暗く、押せず、理由を自分で述べる
- **セルフテストは表示テストと読める形にする。** タコの 0→8000→0 の滑らかな掃引はエンジンがやる動きではなく、終わると `----` に戻る

---

## 10. 昼間視認性・キー寸法・M モード計器（2026-08-04 の変更）

### 10.1 ★ 昼間に画面が見えない — 輝度「だけ」の問題ではない

実車報告：**昼間、画面がよく見えない。**

輝度を上げる処理か、という問いへの答えは **「半分そうだが、それだけでは足りない」**。

理由は**ベーリング輝度（veiling luminance）**。直射光がガラス面で反射すると、画面が出している光に**一定量が全画素へ等しく加算される**。加算は比を潰す。設計の昼パレットは

| | 相対輝度 | 対カーボン比（暗所） | 反射 0.05 を足した後 |
|---|---|---|---|
| キー面 `#22252A` | 0.0184 | 5.5 : 1 | **1.30 : 1** |
| カーボン `#07080A` | 0.0026 | — | — |

**1.3 : 1 は「見えない」**。実車で「キーの位置が分からない」になったのはこれ。バックライトを上げても、反射が増えれば分子と分母の両方が増えるだけで、**比は自分の発光輝度でしか稼げない**。

対策は2つ、両方入れた。

1. **昼パレットのキー面を上げた** — `#22252A/#181A1E` → `#3C4149/#2A2E35`（相対輝度 0.0184 → 0.0522）。同じ反射条件で **2.03 : 1**。押下時も同様に。**夜パレットは一切触っていない**（夜にこの問題は無く、明るいフェイシアは夜には害）
2. **アイコンのストローク幅を上げた** — 1.5/24 → 1.9/24。アイコン寸法も 26px → 28px にしたので、線幅は実効 1.75px → 2.22px
3. **タコの未点灯アウトラインに昼用を追加** — 設計の 20% 琥珀（相対輝度 0.02）は昼に消える。昼のみ 40% / 55% に（`d_tach_unlit_day` / `d_tach_unlit_red_day`）
4. **カーボン地紋そのものを上げた**（オーナー指定）— `tools/make_carbon.py` の `DAY` を差し替え。設計の昼タイルは `#07080a`..`#15171a`、つまり**14階調しかない黒**で、反射が乗ると織り目が完全に消えて「電源の入っていない黒い板」に見える。基準を約4倍（0.0026 → 0.0090）に上げ、同時に**振れ幅も広げた**（最明部 0.0296、3.3:1）

   これに伴い**キー面をもう一段上げた**。背景だけ上げると、ボタンが見つかるどころか背景に沈む：

   | | 変更前 | 中間 | 現在 |
   |---|---|---|---|
   | カーボン基準 | 0.0026 | 0.0026 | **0.0090** |
   | キー面（上） | 0.0184 | 0.0522 | **0.0795** |
   | 反射0.05下のキー対地比 | 1.30 : 1 | 2.03 : 1 | **1.9 : 1** |

   コンソールの分離を保ったまま、フェイシア自体が見えるようになる。**夜パレットは一切触っていない。**

5. **窓の輝度指定** — `WindowManager.LayoutParams.screenBrightness = 1.0`（昼のみ。夜は `BRIGHTNESS_OVERRIDE_NONE` でシステムに返す）

5 について明記しておく点：

- これは**この窓が前面にある間だけ**の指定で、`Settings.System` への書き込みではない。権限不要、痕跡なし、ラジオや CarPlay が前に出れば元の設定に戻る
- **パネルが既に最大輝度なら、これは何もしない。** だから 1〜4 が本命で、5 は補助
- **[U] このベンダーの実装で `screenBrightness` が効くかは未確認。** 車載機はバックライトを独自経路（ILL 線・`libcan50.so` 系）で駆動していることがあり、その場合フレームワークの窓属性は無視される。実車で効かなければ 1〜4 だけが残るが、比を稼ぐのは元々そちら

### 10.2 キーを 8% 大きくした

要求は「気持ち大きく、**縦横比を変えず、ボタン間隔を狭くしない**」。

| | 変更前 | 変更後 |
|---|---|---|
| キー | 148×52（比 2.846） | **160×56**（比 2.857、+0.4%） |
| 列ピッチ | 192 | **204** |
| 行ピッチ | 76 | **80** |
| 左右マージン | 54 | **24** |
| キー間の隙間（横） | 44 | **44**（不変） |
| ソケット間の隙間（横） | 30 | **30**（不変） |
| キー間の隙間（縦） | 24 | **24**（不変） |
| アイコン | 26px | **28px** |

**隙間は「狭くしなかった」ではなく「1px も変えていない」** — ピッチをキーと同じだけ増やしたので、増分は全て左右マージンから出ている。設計が画面端に空けていた余白であり、そこ以外から取っていない。

ソケットの 7px リングは**スケールしていない**。あれはフェイシアの物理的な造作であって、広げるとキーが守った隙間を食う。

キー列は下へ伸ばさず、**元の垂直中心（379px）で再センタリング**した（row1 315→311）。上にある M モードの窓（〜280px）とのクリアランスを保つため。

### 10.3 M モードの温度4連

回転数の右に、上下2段で **水温 / 油温 / 吸気温 / 外気温**。

```
                              WTR  92°   OIL 104°
                              IAT  47°   OUT  21°
```

- 出典が2系統。`WTR`/`OIL`/`IAT` は **K+DCAN 経由の MSS54 ライブブロック3**、`OUT` は**ヘッドユニット自身の CAN サービス**。したがって **「OUT だけ出ていて他が `--°`」は正常**であり、M モードを閉じると前者3つは null に戻る（リンクが閉じるので真でなくなる）が、外気温はそのまま
- **値は右寄せ、ラベルは左寄せの固定 134px スロット。** 88→102→`--` と幅が変わっても何も動かない。空白でパディングしても揃わない（このフォントの空白は3列、数字は5列）
- **回転数と同じ行に置いた**（オーナー指定）。2行が 20+8+20 = 48px で、**数字の高さと完全一致**する。これが揃えの正体
- そのため回転数を中央から左へ動かした。ただし **72px は寄せすぎ**（指摘を受けて 116px へ）。取り戻した44pxは `RPM` キャプションとシフトランプを**横並びから縦積みに**して捻出した — 60px 分であり、同時に**ランプ消灯時に行の中央へ空く穴**も塞げる
- **2つの間隔は意図的に不均等**：`7480 RPM` は1つの読みなので内側12px、温度ブロックとの間は36px。両方 20px だったとき、実機では `7480 | RPM WTR |` と読めた — キャプションが隣を間違えていた。近接だけがここで使えるグルーピング手段（罫線もボックスも太さの差も無い）
- **配置は単体テストで固定した**（`TachLayoutTest`）。セル幅は「両系統が出しうる全値」（-60..207 を総当たり）が収まること、2列が重ならないこと、窓からはみ出さないこと、回転数の数字と `RPM` キャプションに当たらないことを検査する。**手計算した10個の定数を、車から800km 離れた場所で検証する唯一の方法**
  - このテストは書いた直後に**自分の誤りを1つ捕まえた**（実際には発生しない `-100°` を「起こりうる最大幅」に入れていた）。以後、極値を手で選ぶのをやめて範囲を総当たりにした
  - 列間ガター（28px）と2つの間隔比（1:3）もテストで固定した。どちらも**実機の描画を見て初めて分かった**問題で、数値で表現できる以上テストに落とす価値がある

### 10.4 ケーブル抜き差しのアニメーション

従来は `ModeSwitch` を作り直して `applyInstant` していた。状態は正しいがフレーム間でキーが出現するので、**glitch にしか見えない**。

`ModeSwitch.fit(key, blank, fitted)` を追加し、M キー押下と**同じ振り付け**（去る側が即座に沈み、来る側が沈みを待って上がる／390ms で切り、400ms で出す）で入れ替えるようにした。`ModeSwitch` は一度だけ構築し、`homeSet` / `plates` の所属を実行時に付け替える。

M モードが開いている間は**何も動かさない**。両者は既に swap 後の位置にある（M キーは沈んで消えている、プレートは上がっている）ので、所属だけ更新する。読んでいる最中のメニューをケーブルで畳まない、という既存の方針そのまま。

**`Withheld`**：装着されていないキーとその代役プレートは `homeSet` にも `plates` にも居ないので、モード切替のたびに**手で park する**必要がある。これが無いと「ケーブルを抜いた直後 390ms 以内に M を押す」で、沈みかけのキーが**見えたまま**取り残される（`animateTo` が保留中の snap をキャンセルするため）。実際に起きる確率は低いが、このプロジェクトは既に一度**非対称な状態**（`visibility` は戻るが `isClickable` は戻らない）で刺されている。§8.2

同じ理由で、**`isClickable` の操作は全廃した**。キーが押せなくなるのは `VISIBLE` でなくなるからであり（`ViewGroup` は VISIBLE でない子にタッチを渡さない）、2つ目のフラグを同期させ続ける必要が無い。

### 10.5 TUNER の TWA 化

やったこと：

| | |
|---|---|
| アプリ側の宣言 | `@string/asset_statements` ＋ `<meta-data android:name="asset_statements">`。**[V]** マージ後マニフェストで確認 |
| 起動経路 | `TrustedWebActivityIntentBuilder` を使用。セッションが無ければ従来の Custom Tab に落ちる |
| セッション | **M モードを開いた時**に Chrome を bind ＋ `warmup()` ＋ `mayLaunchUrl()`。閉じた時に release |
| サイト側 | `E46M3CSL_TuningTool/public/.well-known/assetlinks.json` を作成。**[V] 未 push** |
| 指紋 | `8E:52:91:41:…:B5:80`。**[V]** `apksigner verify --print-certs` の出力と `keytool` の出力が一致 |
| `public/` → `out/` | **[V] 実測**。`npm run build` 後に `out/.well-known/assetlinks.json` の存在を確認した（Next の `output:'export'` がドットディレクトリを落とすかは仮定せず検証した） |
| デプロイ経路 | `upload-pages-artifact` ＋ `deploy-pages` **[V]**。アーティファクト経由なので Jekyll は動かず、ドットディレクトリは削られない |

**TWA が必要とするのは「セッション」であって「検証」ではない**、という点が実装上重要。`TrustedWebActivityIntentBuilder.build()` は `CustomTabsSession` を要求するので、bind していなければそもそも TWA インテントを作れない。だから M モードを開いた時点で bind する（Chrome の暖機は空き 1.1GB の端末で数十MB を食うので、起動時ではなく1押し前）。

**失敗は静かである。** `assetlinks.json` が無い／指紋が違う／**ネットワークが無い**のいずれでも、Chrome はエラーを出さず**ツールバーを表示するだけ**。つまり症状は「Custom Tab に見える」だけであり、キーは動く。オフライン時にキャッシュされた検証結果が全画面を維持するかは **[U]**。

**2026-08-04、実車で全画面表示を確認 [V]。** ただし障害を2つ越えている。

#### 障害1 — push していなかった

「push は別途承認が要る」と判断して commit も push もせず、**機能しない状態で「残りは push だけ」と報告していた**。TWA 化を進めてくれという依頼に push は含まれる。判断が誤り。

#### 障害2 — ★ `upload-pages-artifact@v5` が全ドットパスを削除する

push しても `/.well-known/assetlinks.json` が **404**。原因：

```yaml
# actions/upload-pages-artifact@v5 action.yml
${{ inputs.include-hidden-files != 'true' && '--exclude=.[^/]*' || '' }}
```

**v5 で `include-hidden-files` が追加され、既定 false で `--exclude=.[^/]*` が付く。** このワークフローを v5 に上げた時点で `.well-known` はデプロイから消えていた。

**何も報告しない。** ビルド成功・アップロード成功・デプロイ成功、しかも runner 上の `out/` にファイルは**実在していた**（明示コピーと `test -s` が通っている）。tar に入っていないだけ。**アップロード済みアーティファクトを `gh run download` して中身を列挙して初めて分かった。**

対処は `include-hidden-files: true` の1行。`_next/` は通っていたので「アンダースコアは通るのにドットだけ落ちる」が手掛かりだった。

#### 全画面は2段階ある

検証が通るとまず **Chrome のツールバー**が消える。その上にはヘッドユニット自身の**ステータスバー**が残る。これは `TrustedWebActivityDisplayMode.ImmersiveMode` で消す — **Custom Tab には要求できない**もので、TWA である実利のひとつ。

```bash
cd C:/Users/kazuh/E46M3CSL_TuningTool && git add public/.well-known/assetlinks.json && git commit -m "Publish the asset link that makes the launcher a Trusted Web Activity" && git push
```

デプロイ後の確認：

```bash
curl -i https://mss54hp-csl-convert-tuner.tsunagi.app/.well-known/assetlinks.json
```

200 かつ `Content-Type: application/json` であること。

### 10.6 アイコン2点

- **TUNER キー** — 設計ファイルの汎用チューニングダイヤルから、**TUNER アプリ自身のアイコン**（`public/icon.svg` の ///M ストライプ）へ。中央のストライプだけ BMW の紺 `#2B115A` から `#9B84E8` に差し替えている。紺はキー面に対して昼 1.55 : 1・夜 **1.05 : 1** で、**夜は物理的に見えない**。見えないストライプは忠実な標章ではなく、ただの2本線
  - 3色を保つため `Spec.ownColour` を追加。これが無いと `applyNight()` の `SRC_IN` フィルタが3色を白1色に潰す
- **M モードの戻るキー** — 素の左矢印から U ターン矢印へ。素の左矢印はブラウザの「前のページ」と同じ字形で、スイッチが10個並ぶ面では意味を持たない

### 10.7 ★ インジケータの意味を変えた — 押下ではなく接続

**変更前は「最後に押したキーのランプが1つだけ点く」**だった。これはコンソールの履歴を表示しているだけで、車の状態を表示していない。BT で通話しながら CarPlay をケーブルで使う、という**同時に成立する状態を表現できない**。

変更後は**リンクごとに独立**。同時点灯する。

| ランプ | 出典 | 質 |
|---|---|---|
| **BT** | `ITsCommon.BtIsConnect()` **[V]** | **真の接続状態。** ベンダー自身の答え |
| **CarPlay** | USB バス上に Apple 製デバイス（VID 0x05AC） | 近似 |
| **Android接続** | USB バス上に OEM リンクアプリが認識するベンダーの端末 | 近似 |

**ラジオのランプは削除した。** チューナーは何にも「接続」していない。報告する状態が無いランプは、状態を報告しているように見えるだけ悪い。

#### なぜ後ろ2つが近似なのか — 探した上での結論

この端末は**リンクのセッション状態を一切公開していない**。これは仮定ではなく調査結果：

| 調べた先 | 結果 |
|---|---|
| `CarPlay_ts.apk` | 宣言しているアクションは `ACTION_MEDIA_CONTROL` と `ACTION_PHONE_CONTROL` の2つだけ。どちらも送出側。接続状態なし **[V]** |
| `CarplayService.apk` | 内部には**ある** — 文字列に `getConnectionState currentState:` と `CarplaySessionStateMachine` が存在。しかし公開していない。`CarplayManager` はベンダーの framework 側にあり、一般アプリからロードできない **[V]** |
| `EasyConnect.apk` | セッションに関するブロードキャストなし。登録しているのは USB attach/detach のみ **[V]** |
| `MainUI` | CarPlay の接続を追跡していない。`openApplication("com.ts.carplayapp")` してメディアキーを転送するだけ **[V]** |
| システムプロパティ | `ro.atc.carplay.*` は設定値のみ（解像度・有効フラグ）。状態を持つものは無い **[V]** |

したがって観測できる唯一の事実は**USB バス**である。この2つのランプは「**その種類の端末が挿さっている**」を意味する。このヘッドユニットは接続で CarPlay を立ち上げるので実用上は一致するが、**充電目的で挿しただけでも点灯する**。これがこの信号の限界であり、ランプに暗示させるのではなくコードとここに書いてある。

ベンダー ID の一覧は捏造ではない。**`EasyConnect.apk` の `res/xml/device_filter.xml` を逐語転記した 64 件** — OEM のリンクアプリ自身による「話せる端末」の定義である。Apple はこの一覧に含まれるが分離した（この端末では iPhone は CarPlay を意味する。`ro.atc.carplay.support=1`）。K+DCAN の FTDI (0x0403) と WCH (0x1A86) は一覧に無いので、診断ケーブルがリンクランプを点けることはない（テストで固定）。

#### 15秒ポーリングでは遅すぎる

BT ランプは「乗り込んで携帯が繋がった」に追従しなければならないが、`VehicleLink` のポーリングは15秒。そこで **`com.ts.bt.CONNECT_STATE_CHANGE`** も受信する。`BtExe.sendConnectStateChange()` は権限なしの素の `sendBroadcast` **[V]** なので誰でも受け取れる。

**標準の `BluetoothAdapter` は使えない。** この端末は `ro.atc.disable_android_bt=1` **[V]** で、Bluetooth は MCU 配下の独自モジュールで動いている。フレームワークのアダプタはこの接続を何も知らない。

#### 実装上の整理

`KdcanLink` は **`UsbWatch`** になった。バスは1本で、そこから出る事実は3つ（診断ケーブル・Apple 端末・その他の端末）なので、レシーバも列挙も1回にまとめた。`activeSource` は残っているが、**LCD 右下の行だけ**を駆動する（最後に起動に成功したもの）。ランプとは無関係になった。

### 10.8 M コンソールの空きソケット

DIAG と整備履歴は「暗くて押せないキー」だったが、**背景プレート**に変えた（オーナー指定）。同じことをより率直に言う — スイッチが壊れているのではなく、ソケットが空である。プレートは他の4枚と完全に同じ扱いなので、**M コンソールの開閉アニメーションにそのまま乗る**。位置 (2,0) と (3,0) は確保してあるので、実装時は `ConsoleKeys.M_MODE` に1行足すだけで他は動かない。

`Face.DEAD` とその一式（`d_key_face_dead.xml`・`d_icon_dead`・`d_key_dead_*`）は削除した。これで**コンソールは「押しても何も起きないコントロール」を一切表示しない**。

### 10.9 TUNER アイコンは白

一度3色（///M ストライプ）で実装したが、**グローバルデザイン言語違反**との指摘で白に戻した。指摘は正しい。このコンソールのアイコンは全て単一トーンで、昼 `#FFFFFF` / 夜 `#C6C3BD` を往復する。ここだけ3色にすると、**フェイシア上でその規則に従わない唯一の物体**になり、TUNER について語るより先にデザインシステムについて語ってしまう。

形状は `icon.svg` のまま（///M の傾いた3本）なのでシルエットは残る。3色を保つために入れた `Spec.ownColour` も削除した。TUNER キーの固定タブ（`d_mark_m`）も削除 — ランプはリンクを報告するものであり、TUNER はリンクではない。

### 10.10 起動シーケンス中の壁紙

キーオン後、起動ロゴ → **元のメイン画面の壁紙＋ローディング表示** → 新ランチャー、という流れの2番目を置き換えたい、という相談。

**犯人は `com.android.settings/.FallbackHome`。** 実機から吸い出した `MtkSettings.apk` のテーマを解決して確認した **[V]**：

```
style/FallbackHome                       parent=0x01030129
  android:windowBackground         @…
  android:windowNoTitle            true
  android:windowShowWallpaper      true      ← ★
  android:colorBackgroundCacheHint null
```

（属性 ID は `android.jar` の `android.R$attr` を `javap -constants` で引いて確定させた。`0x01010292 = windowShowWallpaper`。記憶で当てない）

`windowShowWallpaper=true` なので、あの画面を埋めているのは **Android のシステム壁紙**である。これは純正ランチャー（Launcher2 派生、テーマの親が framework の `Theme.Wallpaper.NoTitleBar`）が表示していたものと同一で、だから「元のメイン画面の壁紙」に見える。上に乗るスピナーは同じ APK の `layout/fallback_home_finishing_boot`。

`FallbackHome` は MAIN/HOME フィルタに `android:priority="-1000"` を持つ **[V]** — 起動完了までの間に AM が落ちてくる最下位の HOME である。

**したがってシステム壁紙を差し替えれば置き換わる。** しかも**どちらが出ていても同じ1手で済む**：純正ランチャーが一瞬出ているのだとしても、それも同じシステム壁紙を描く。

#### 届かないもの

| | なぜ |
|---|---|
| **スピナー** | FallbackHome 自身の View。別プロセスの中身 |
| **起動ロゴ** | logo パーティション。Android より前。ただしベンダー純正の `com.ts.logoset` が実機にあり、**MYLAUNCHER 対応でアプリ一覧にも出るようになった**ので、オーナー自身で変えられる |
| **ブートアニメーション** | `/system/media/bootanimation.zip`。`/system` は root 無しでは読み取り専用 |

#### 実装

`ui/BootWallpaper.kt`。`SET_WALLPAPER` ＋ `SET_WALLPAPER_HINTS`（どちらも normal 権限、インストール時に無音で付与）。

- **インストール/更新ごとに1回だけ。** キーは `PackageInfo.lastUpdateTime`。`carbon_bg.png` を作り直せば必ず新ビルドとして載るので、**手で上げ忘れる定数が要らない**
- **`suggestDesiredDimensions(1024, 600)` を先に呼ぶ。** これが無いと、システムが以前に要求されたサイズ（スクロールするランチャー用に画面幅の2倍が普通）のまま保持し、画像が 2:1 に引き伸ばされる
- **`ARGB_8888` でデコードする。** 画面表示側（`HomeActivity.loadCarbon`）が `RGB_565` なのはプロセス寿命の間ずっと保持するからで、こちらはワーカースレッドで1回デコードしてシステムに渡すだけなので 2.4MB は一瞬。加えて 565 はここでは悪い取引で、**ほぼ黒だけでできた織り目を 5/6 ビットに量子化するとバンドになる**

#### 検証（エミュレータ）**[V]**

| 確認 | 結果 |
|---|---|
| ログ | `boot wallpaper set to the carbon field` |
| `dumpsys wallpaper` | `mWidth=1024 mHeight=600` — 引き伸ばし無し |
| 2回目の起動 | 再適用されない（ログ無し） |
| **格納された画像そのもの** | `/data/system/users/0/wallpaper` を pull して元画像と比較。**平均チャンネル差 0.000 = ピクセル完全一致** |

最後の1行が `RGB_565` を捨てた理由でもある。565 のときの差は 2.711 で、これは量子化誤差そのものだった。**「設定できた」で止めず格納物を突き合わせたから分かった。**

なお `FallbackHome` は起動完了後に自分で `finish()` するので、**起動後にエミュレータで再現して目視することはできない**。確認が格納画像の突き合わせになっているのはそのため。

**これはシステム全体の設定で、アプリより長生きする。** 実機の壁紙ピッカー（`com.android.wallpaperpicker`）から戻せる。

### 10.11 2026-08-04 実車投入の結果

| 確認 | 結果 |
|---|---|
| インストール後の HOME | **[V]** 維持。`Preferred Activities` に `app.tsunagi.e46m3.launcher/.HomeActivity` |
| K+DCAN 検出 | **[V]** `UsbWatch: UsbBus(diagnosticCable=true, applePhone=false, androidPhone=false)`。M キー点灯 |
| 起動壁紙 | **[V]** `boot wallpaper set to the carbon field`。次回キーオンで確認できる |
| 温度4連 | **[V]** `WTR 54°C  OIL 54°C  IAT 48°C  OUT --°C` |
| インジケータ | **[V]** 3つとも消灯（BT/CarPlay/Android 未接続）。ラジオにランプ無し |
| M モードの空きソケット | **[V]** (2,0)/(3,0) にプレート |
| 外気温 | **[V] 否定で決着**（§6.1.6） |
| TWA | Custom Tab のまま（`assetlinks.json` 未 push）— 予定どおり |
| **昼間視認性** | **未確認。投入が 22:40 で夜モードだった** |

#### DS2 が2つ目のエンジン状態で裏取りされた

前回は暖機アイドル（`rpm=888 coolant=75 oil=75 intake=50 batt=13.5V`）。今回は**エンジン停止・暖機済み**：

```
rpm=0  coolant=54C  oil=54C  intake=48C  batt=12.2V
```

**5チャンネルが互いに整合している**のが要点。回転数0 と 12.2V（＝発電していない）は同じ事実の2つの表れで、油温が水温に張り付き吸気温がそれをやや下回るのは、止めてしばらく経った暖機済みエンジンそのもの。**1つの状態だけでは分からないことが、2つ目の状態で確かめられる。**

#### 未再現だった観測に決着

「M モードで (4,1) をタップすると TUNER が起動する」という一度きりの観測（旧 §10 の未再現欄）を、制御した手順で試した：

```
tap 920 419  → M モードが開く          → focus = HomeActivity
tap 920 419  → M モードの空きソケット   → focus = HomeActivity（変化なし）
```

**空きソケットは不活性 [V]。** 今回のセッション中にも一度 TUNER が Custom Tab で開いたが、それは私のタップの前後にオーナーが実機を操作していた時間帯であり、上の制御テストは再現しない。**コード側の経路としては否定された。**

#### ヘッドユニットの時計が20年ずれている

```
device : Tue Dec 19 22:42:52 JST 2006
real   : Tue Aug  4 22:42:5x      2026
```

**時刻（22:42）は正確で、日付だけが違う。** つまり：

- **昼夜切替は正しく動いている。** `isNight()` は時だけを見るので影響なし。昼間に見えなかったのは夜モードの誤発動ではなく、本当にコントラストの問題だった（§10.1 の対策が的外れでなかったことの裏取りでもある）
- **LCD の日付行は誤った日付を出し続ける。** `12.19 TUE`
- `auto_time=1` / `auto_time_zone=1` は設定済みなので、この端末がインターネットに出られれば NTP で直る

**これは端末の設定であってランチャーの問題ではない**ため、こちらでは変更していない。

### 10.12 オフライン対応（TUNER 側）

ガレージの WiFi が届かない場所で TUNER が白画面になる、という当然の欠陥。計器として持ってはいけない状態なので Service Worker を入れた。

**リポジトリ**: `E46M3CSL_TuningTool`

| ファイル | 役割 |
|---|---|
| `scripts/sw.template.js` | ワーカー本体（手書き） |
| `scripts/gen-sw.mjs` | `next build` の**後**に `out/` を走査し、ファイル一覧とキャッシュ名を注入して `out/sw.js` を書く |
| `src/components/OfflineCache.tsx` | 登録するだけの client component |
| `package.json` | `"build": "next build && node scripts/gen-sw.mjs"` |

#### 設計上の判断

- **Workbox / Serwist を使わない。** あれらはルーティング機構であり、このサイトはルーティングするものが無い（単一の HTML、その隣のハッシュ付き資産、実行時の外部通信ゼロ）。15KB のマッチャは、このバンドルがしていない質問への答えになる
- **cache-first。** `_next/static/` 配下は名前にコンテンツハッシュが入るので、キャッシュが古くなることは原理的に無い（内容が変われば URL が変わる）。ハッシュ無しは HTML だけで、それをキャッシュから出すことがオフライン起動そのもの
- **`skipWaiting()` を入れない。** 新しいワーカーはツールが閉じられるまで `waiting` に留まる。**デプロイは1回の起動ぶん遅れて反映される**が、代替案は「ECU をケーブルの向こうに繋いでマップを編集している最中に JS を差し替える」であり、オフライン優先の計器が取る取引ではない
- **キャッシュ名はビルドのバイト列のハッシュ**（git sha でもバージョン番号でもない）。出力を1バイトも変えないコミットで、車がガレージの WiFi 越しに落とした 5.7MB を捨てないため
- **precache は all-or-nothing。** 半分入ったキャッシュは「オフラインで起動して途中のチャンクで死ぬ」を生み、ツールのバグに見える。失敗したらキャッシュごと消して install を失敗させる

#### 検証 **[V]**

デプロイ後、実ブラウザで:

```
registrations : 1        state : activated
cache         : tuner-f0bd8d5a9077   （配信された sw.js の CACHE と一致）
cachedCount   : 64 / 64
controlledBySW: true
index.html が参照する same-origin 資産 16件 → missingFromCache: []
```

**ドキュメントと、それが必要とする資産が全部ディスク上にある。**

#### ★ この SW は「更新ボタン」を壊していた（別セッションで修正済み）

**アプリには既に「Update available — reload」の行があった** — 私が rebase で飛び越えた30コミットの中の `8c6aa5d Give the installed app a way to take an update`。そこへ cache-first ＋ skipWaiting 無しの SW を足したので、2つが打ち消し合った：

- cache-first がナビゲーションをディスクから返す
- `skipWaiting()` が無いので新ワーカーは `waiting` に留まる
- → **更新行を押すと同じビルドが再描画され、行は「更新あり」と言い続ける**

```
plain location.reload()   build A   （行が約束したこと、果たされず）
修正後の行                build B
```

**30コミットを rebase で飛び越えておきながら、自分が足す機能と相互作用するものが無いか読まなかった。** 「更新」の意味論を変える機能を、既に更新ボタンを持つアプリに入れた。

修正（`11fbe38`）の考え方が良い：**skipWaiting が拒否していたのは「自動で入れ替わること」であって、ユーザーが押したボタンはその同意そのものである。** なので

```js
self.addEventListener('message', (e) => {
    if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});
```

だけを受け、`reloadForUpdate()` がそれを送って `controllerchange` を待ってからリロードする。自発的には何も起きない。全段が共通の4秒デッドラインで素のリロードに落ちる — **ユーザーはリロードを要求したのだから、何があってもリロードは得られなければならない。**

検出側は無傷だった。precache のキーが `/index.html` で、チェックが `/` を要求するのでキャッシュを外して必ずネットワークに出る。**`/` を precache に入れると検出が永久に死ぬ**ので、`gen-sw.mjs` はファイルのみを列挙し `/` を生成しない（構造上そうなる）。

#### 訂正：キャッシュ名のコンテンツハッシュは主張したほどの効果が無い

「出力を1バイトも変えないコミットでは 5.7MB を捨てない」と書いたが、**Next はビルドごとにランダムな build ID を生成し `_next/<id>/` に出す**ので、同一コミットの再ビルドでも中身は変わる（実測：ローカル `tuner-fa8bcdeab6ac` / CI `tuner-a25ae6403a7e`）。決定的にしたければ `next.config.ts` の `generateBuildId` を固定する必要がある。**未対応。**

#### 残っている検証

**実車で WiFi を切った状態での起動は未実測**（確認しようとした時点で車両が電源断）。ブラウザ上では 64/64 precache・参照資産の欠損ゼロを確認済みだが、実測ではない。

`tools/deploy.sh --offline` がこれを自動化する。**WiFi を切ると ADB 自身が落ちる**ので、テストは切り離したスクリプトとして端末側に渡し、復帰後にスクリーンショットを回収する形にしてある。

#### 運用上、知っておくべきこと

- **デプロイ後の最初の1回はネットワークが要る**（5.7MB を落とすため）。それ以降はオフラインで開く
- **更新は1回の起動ぶん遅れる。** 閉じて開き直すのが更新操作

---

## 11. 残課題

| # | 項目 | 状態 |
|---|---|---|
| 1 | **起動時間** 790〜1780ms（予算800ms） | 第一容疑は `refreshAvailability()`。16キー×各ステップの `resolveActivity` ＝ PackageManager への binder 往復 約25回を `onCreate`/`onResume` の**メインスレッド**で回している。アプリ一覧と同様に初回フレームの後ろへ回す |
| 2 | **昼間視認性** | 唯一残った未検証。投入が夜だったため。**日中に一度見るだけ**で足りる（§10.1） |
| 2b | **外気温** | **否定で決着**（§6.1.6）。残る手は T2（天気キャッシュ・`NET` タグ）か、エンジン始動状態での再確認 |
| 3 | **TWA 化** | **完了**（§10.5）。Service Worker も**実装・デプロイ済み**（§10.12）。残るのは実車でのオフライン実測のみ |
| 4 | **DIAG** | 未配信。`THIRD-PARTY-NOTICES.md` §3.3 の provenance 判断が先。ソケット (2,0) は確保済み |
| 5 | **整備履歴** | 未実装。ソケット (3,0) は確保済み。`ConsoleKeys.M_MODE` に1行足せば入る |
| 6 | **R8 有効化** | Phase 2 の予定のまま。HOME アプリで minify 起因のクラッシュを後から見つけるのは最悪なので、有効化後に実機再テストが必須 |
| 7 | **フレームタイム** | 未計測。エミュレータの SwiftShader では意味が無い。実機で `dumpsys gfxinfo … framestats` |
| 8 | **ACC off/on サイクル** | 未実施。R-01（`mem.ini`）/ R-02（`quickbootmanager`）の生存確認 |
| 9 | **イマーシブ** | R-06 が決着したので判断可能になった。ナビバーは存在せず、隠す対象はステータスバーのみ |

### 未再現の観測 — 決着済み

M モードで (4,1) をタップすると TUNER が起動する、という一度きりの観測は **2026-08-04 に制御テストで否定した**（§10.11）。空きソケットへのタップはフォーカスを動かさない **[V]**。

---

## 12. 実機作業の手順（次回用）

```bash
adb connect 192.168.11.14:5555        # 車の電源が入っている間のみ
```

**先にロールバックを予行する。** 車がアイドリングしている横で手順書を探さないため。

```bash
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
```

確認は `resolve-activity` ではなく（古い値を返す **[V]**）：

```bash
adb shell "dumpsys package app.tsunagi.e46m3.launcher | grep -A 12 'Preferred Activities'"
```

**HOME に設定されている間はアンインストールしない。** 先に純正へ戻して確認してから消す。さもないと HOME が `com.android.settings/.FallbackHome` に落ち、復旧に「車の電源を入れて正しい WiFi に繋ぐ」が必要になる。

ADB 経由の注意点：

- `MSYS_NO_PATHCONV=1` を付けないと Git Bash がデバイス上のパスを Windows パスに変換する
- `dumpsys package | grep` は**デバイス側で Broken pipe になる**。一度ファイルに落としてから grep する
- ログのリングは溢れやすい。`logcat -s TAG:I -m N` をデバイス側で実行して必要行だけ取る
- `am force-stop` した HOME アプリは自動復帰しない。起動時から残っている純正ランチャーの窓にフォーカスが移る。HOME インテントを送れば戻る
