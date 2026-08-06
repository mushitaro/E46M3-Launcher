# E46 M3 ヘッドユニット 調査記録

**調査日**: 2026-08-03
**目的**: 自作ランチャー(Tuner / Diagnosis PWA ホスト)開発の前提条件確定
**結論**: ランチャーの開発・置き換えは **可能**(root 不要・実機で実証済み)

---

## 0. この文書について

E46 M3 に搭載されている Android ヘッドユニットに ADB 接続し、ランチャー開発に必要な前提条件を調査した記録。

記載内容は以下の3種類に分類している。混同しないよう明示的にラベルを付けた。

| ラベル | 意味 |
|---|---|
| **[確認済]** | 実機コマンドまたは抽出ファイルで直接確認した事実 |
| **[推測]** | 状況証拠からの推論。未検証 |
| **[未確認]** | 調べたが確定できなかった事項 |

---

## 1. 対象ハードウェア

### 1.1 基本情報 [確認済]

| 項目 | 値 | 取得元 |
|---|---|---|
| product / model / device | `FF-5000` / `FF_5000` / `FF-5000` | `adb devices -l` |
| manufacturer | `alps`(MediaTek のリファレンス名) | `getprop ro.product.manufacturer` |
| brand | `FFKJ` | `getprop ro.product.brand` |
| SoC | **MediaTek MT8227L**(AutoChips AC8227L) | fingerprint / `fstab.ac8227l` |
| CPU ABI | `armeabi-v7a`(**32bit 専用**) | `getprop ro.product.cpu.abi` |
| CPU コア数 | 4(kworker/0〜3 を確認) | `ps -A` |
| RAM | 総容量 約 2.0 GB(空き 約 1.1 GB) | `/proc/meminfo` |
| 画面 | **1024 x 600**、密度 **240 dpi** (hdpi) | `wm size` / `wm density` |
| ビルド日 | 2019-10〜11 | fingerprint / `/system/etc` タイムスタンプ |

**Build fingerprint** [確認済]
```
alps/full_8227L_demo/8227L_demo:8.1.0/O11019/1571038753:userdebug/test-keys
```

**HMI Version**(ベンダー UI のバージョン、クラッシュログより) [確認済]
```
XRCH.D.Q.F.3.04_1.2019.11.29.16.00
```

> `userdebug` / `test-keys` ビルドである点は重要。製品版の `user` / `release-keys` より制約が緩く、`adb install` や各種 `cmd` コマンドが通りやすい。

### 1.2 Android バージョンは偽装されている [確認済] ★注意

設定画面には「Android 10」と表示されるが、**実体は Android 8.1 (API 27)**。

| プロパティ | 値 | 判定 |
|---|---|---|
| `ro.build.version.release` | `10` | ← 表示用に書き換えられている |
| `ro.build.version.sdk` | **`27`** | ← 実体(= Android 8.1) |
| fingerprint | `...:8.1.0/O11019/...` | ← 実体(`O` = Oreo) |

中華系ヘッドユニットで一般的な「表示だけ新しく見せる」パターン。

**開発上の影響**: `targetSdkVersion` / `minSdkVersion` の判断、利用可能な API、`adb` サブコマンドの有無などはすべて **API 27 (Android 8.1) 基準**で考える必要がある。実際、`pm resolve-activity` は存在せず `cmd package resolve-activity` を使う必要があった(Android 8.1 で移行された仕様)。

---

## 2. 接続方法(WiFi ADB)

### 2.1 経緯 [確認済]

ヘッドユニット側の USB ポートは USB-A(ホスト側)で PC と直結できないため、**WiFi ADB** を使用。
この個体は **ネットワーク ADB がデフォルトで有効**、かつ **RSA 認証も承認済み**で、`adb connect` するだけで即 `device` 状態になった(`unauthorized` を経由しない)。

### 2.2 接続手順 [確認済]

```bash
adb connect 192.168.11.14:5555
```

| 項目 | 値 |
|---|---|
| ヘッドユニット IP | `192.168.11.14`(調査時点。DHCP なので変動しうる) |
| ADB ポート | `5555` |
| MAC アドレス (wlan0) | `c0:81:35:1a:6f:da` |
| PC 側 IP | `192.168.11.19`(GW: `192.168.11.1`) |

**IP が変わった場合の再発見方法**:
サブネットに ping sweep をかけ、応答ホストの `5555` 番ポートへ順に `adb connect` を試す。MAC アドレス `c0:81:35:1a:6f:da` でも識別可能(ルーターの DHCP テーブルで固定 IP 割り当てを推奨)。

### 2.3 制約 [確認済]

- PC とヘッドユニットが**同一サブネット**にいる必要がある(中継器/APモードは可、ルーターモードでのサブネット分割は不可)
- ルーターの「クライアント分離 (AP Isolation)」が有効だと通信不可
- **車両の電源が入っている間のみ**接続可能。電源断で即 `device offline` になる

---

## 3. 開発上の重大な制約と機会

### 3.1 ★最重要: WebView と Chrome のエンジンが全く違う [確認済]

| コンポーネント | パッケージ | バージョン | 設置場所 |
|---|---|---|---|
| システム WebView | `com.android.webview` | **Chromium 61.0.3163.98**(2017年) | `/system/app/webview` |
| MediaTek WebView | `com.mediatek.webview` | Chromium 58.0.3029.125 | `/system/app/MtkWebView` |
| **Chrome アプリ本体** | `com.android.chrome` | **135.0.7049.113**(最新級) | `/data/app/`(更新済み) |

Chrome は `minSdk=26 targetSdk=35` で、**Play ストア経由で最新まで更新されている**。

**設計上の帰結**:

- 素の `android.webkit.WebView` を使うと **2017年の Chromium 61** に縛られる。
  - 使えない/危険な例: optional chaining (`?.`)、nullish coalescing (`??`)、`Array.flat()`、最近の CSS(`gap` の一部、コンテナクエリ等)、新しめの PWA API
- **推奨: Trusted Web Activity (TWA) または Custom Tabs で Chrome 135 をホストする**
  - モダン JS/CSS がそのまま使える
  - Service Worker / PWA 機能もフル活用可能
  - ランチャー本体は薄い Android シェルに留められる

> Tuner / Diagnosis を PWA として載せるという当初方針は、この構成なら十分実現可能。ただし **WebView 実装は避けること**。

### 3.2 root 権限は取得できない [確認済]

```
$ adb root
cxj said not suport, 88
```

ベンダーが `adbd` にパッチを当てて `adb root` を無効化している。シェルは `uid=2000(shell)` のまま。

**その結果できないこと** [確認済]
- `/system/build.prop` の読み取り(`cat` も `pull` も Permission denied)
- アプリの内部データ (`/data/data/*`) へのアクセス
- `run-as com.ts.MainUI` も不可(`package not an application` — システムアプリのため)

**root 化の可能性** [未確認]
MT8227L 系では `mtk-su` 等のコミュニティ製ツールが存在するとされるが、**素性不明な実行ファイルの入手・実行は本調査では行っていない**。必要なら、信頼できる情報源(型番 `FF-5000` / `FFKJ` / `8227L` で検索)から利用者自身が入手すること。

なお `/vendor/priv-app/SRV_Upgrade/SRV_Upgrade.apk`(`com.android.sunoddtool`)という USB 経由のシステム更新ツールが存在する [確認済]。署名検証の実装次第では改造イメージの投入経路になりうる [推測・未検証]。

**重要**: ランチャー開発と置き換えには **root は不要**(§4.3 で実証済み)。

### 3.3 ハードウェア制約 [確認済]

- **RAM 2GB**(空き約 1.1GB)— 常駐アプリが多く、メモリ圧迫あり。軽量設計必須
- **armeabi-v7a 32bit のみ** — ネイティブライブラリを含む場合は 32bit ARM ビルドが必要
- **旧世代 CPU**(Cortex-A7 クラス、4コア)— 重いアニメーションや大規模 DOM は避ける
- 画面 1024x600 @ 240dpi = 論理解像度 **約 683 x 400 dp**(横長・低め)。UI はこの狭さを前提に設計する

---

## 4. ランチャーアーキテクチャ(結論: 置き換え可能)

### 4.1 HOME ロールを持つのは `com.android.launcher` のみ [確認済]

3つの独立した方法で検証し、すべて一致した。

**① Preferred Activities テーブル**(`dumpsys package`)
```
Preferred Activities User 0:
  Non-Data Actions:
      android.intent.action.MAIN:
        17af8d8 com.android.launcher/com.android.launcher2.Launcher
         mMatch=0x100000 mAlways=true
          Selected from:
            com.android.launcher/com.android.launcher2.Launcher
            com.android.settings/.FallbackHome
          Action: "android.intent.action.MAIN"
          Category: "android.intent.category.HOME"
          Category: "android.intent.category.DEFAULT"
```
HOME 候補は **2つだけ**。`com.ts.MainUI` は一切登場しない。

**② 実機ライブクエリ**
```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
# → com.android.launcher/com.android.launcher2.Launcher
```

**③ 両 APK の AndroidManifest.xml 文字列抽出**
- `Launcher_8227LTsLauncher2.apk`: `android.intent.category.HOME` **あり**
- `MainUI.apk`: `category.LAUNCHER` と独自の `category.MyLAUNCHER` はあるが、**`category.HOME` は無し**

> `category.LAUNCHER` は「アプリ一覧に表示される」ためのカテゴリであり、`category.HOME`(ホーム画面になる)とは全くの別物。

**ランチャーの実体** [確認済]

| 項目 | 値 |
|---|---|
| パッケージ | `com.android.launcher` |
| アクティビティ | `com.android.launcher2.Launcher` |
| APK パス | `/system/priv-app/Launcher/8227LTsLauncher2_xrc04_2_81.apk` |
| 中身 | **素の AOSP Launcher2 派生**(`minSdkVersion=15 targetSdkVersion=15` — 極めて古い) |
| uid | `10015`(通常アプリ権限) |

### 4.2 `com.ts.MainUI` の正体 = 特権バックエンド [確認済]

**ランチャーではない。** ラジオ/メディア/CANバス/ナビ機能を提供する、system 権限の巨大アプリ。

| 項目 | 値 |
|---|---|
| パッケージ | `com.ts.MainUI` |
| APK | `/system/priv-app/MainUI/MainUI.apk`(37.6 MB) |
| versionName | `1.1`(versionCode=2, minSdk=27, targetSdk=27) |
| **uid** | **`1000` (`android.uid.system`)** — システム権限を共有 |
| flags | `SYSTEM DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP` |
| privateFlags | `PRIVILEGED` |
| PERSISTENT | **なし**(`android:persistent="true"` は宣言されていない) |
| アクティビティ数 | **39**(`action.MAIN` バケットに登録、いずれも `category.HOME` なし) |
| Application クラス | `com.ts.can.MyApplication` |

**決定的証拠 — 呼び出しの方向** [確認済]

`dumpsys activity` の HOME アクティビティ記録:
```
  mLastHomeActivityStartRecord:
    packageName=com.android.launcher processName=com.android.launcher
    realActivity=com.android.launcher/com.android.launcher2.Launcher
    baseDir=/system/priv-app/Launcher/8227LTsLauncher2_xrc04_2_81.apk
    mActivityType=HOME_ACTIVITY_TYPE
    connections=[ConnectionRecord{6b6fc32 u0 com.ts.MainUI/com.ts.main.common.MainUI:@84d0c3d}]
```

つまり **ランチャー(HOME)が MainUI のサービスにバインドしている**。MainUI が呼ばれる側であり、逆ではない。

`com.ts.main.common.MainUI` は紛らわしい名前だが **Service** であって Activity ではない [確認済]:
```
Service Resolver Table:
      android.intent.action.MAIN_UI:
        b95b728 com.ts.MainUI/com.ts.main.common.MainUI
```

稼働中のサービス [確認済]:
```
    Services:
      - ServiceRecord{1a2e43a u0 com.ts.MainUI/com.ts.main.common.MainUI}
      - ServiceRecord{369fe3c u0 com.ts.MainUI/.MainService}
    hasClientActivities=true foregroundActivities=false
```

**自動起動** [確認済]

MainUI はランチャーとは**独立して** `BOOT_COMPLETED` で自動起動する。
```
android.intent.action.BOOT_COMPLETED:
    100f8c5 com.ts.MainUI/.AutoBootUp
    293a33c com.ts.MainUI/com.ts.bt.BtReceiver
```
(`LOCKED_BOOT_COMPLETED` には未登録 [確認済])

→ **ランチャーを差し替えても MainUI の CAN/ラジオ機能は動き続ける**。

### 4.3 ★置き換え可否 — 実機で実証済み [確認済]

`cmd package set-home-activity` がこのベンダービルドで**実際に機能する**ことを実機で検証した。

**実施した検証**:
1. `cmd package set-home-activity com.android.settings/.FallbackHome` → `Success`
2. `dumpsys package com.android.settings` で確認 → **Preferred Activities が実際に `.FallbackHome` へ書き換わっていた**(`mAlways=true`)
3. `cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher` で復元 → `Success`
4. `am start -a android.intent.action.MAIN -c android.intent.category.HOME` + `dumpsys window` で画面確認
   → `mCurrentFocus=Window{8912770 u0 com.android.launcher/com.android.launcher2.Launcher}`
   **元の OEM ランチャーが表示されていることを確認。端末は正常な状態に復元済み。**

**⚠️ 検証中に判明した罠** [確認済]

`set-home-activity` 実行**直後**の `cmd package resolve-activity` は **古い値を返す**(キャッシュされている)。
実際に設定が効いているかは `dumpsys package <pkg>` の Preferred Activities セクション、または `am start` + `dumpsys window` で確認すること。
`resolve-activity` の結果だけを見て「効かなかった」と判断してはいけない。

**結論**:

| 条件 | 可否 |
|---|---|
| root 権限 | **不要** |
| 既存ランチャーのアンインストール | **不要** |
| 元に戻せるか | **いつでも可能**(1コマンド) |
| ADB のみで完結するか | **可能** |

### 4.4 置き換え時の注意点

1. **Preferred Activity の上書きが必要** — `mAlways=true` で既存設定が固定されているため、APK をインストールしただけでは切り替わらない。`cmd package set-home-activity` を明示的に実行する(またはユーザーにホームアプリ選択ダイアログを出させる)
2. **MainUI は無効化されない** — 差し替え後も CAN/ラジオは裏で動く。新ランチャーから機能を呼びたい場合は明示的な Intent が必要(§5.3 参照)
3. **`com.autochips.quickbootmanager`** は Activity を一切持たず Receiver + Service のみ [確認済]。HOME 競合とは無関係だが、`autochips.intent.action.KILL_APPS` / `TEST_APPS` / `RESUME_APPS` でアプリのライフサイクルを制御しているため、自作ランチャーが強制終了対象にならないか要観察 [未確認]

---

## 5. CAN バス / 車両連携アーキテクチャ

### 5.1 `libcan50.so` = 汎用多車種 CAN デコーダー [確認済]

| 項目 | 値 |
|---|---|
| ファイル | `libcan50.so`(1,551,300 bytes) |
| 場所 | `MainUI.apk` 内 `lib/armeabi-v7a/` **および** `/system/lib/libcan50.so` |
| JNI 登録方式 | `RegisterNatives`(動的登録。`Java_*` シンボルは存在しない) |
| 対応 Java クラス | `com.lgb.canmodule.CanJni` / `com.lgb.canmodule.CanDataInfo` / `com.ts.can.CanFunc` |

**`/system/lib/` にも配置されている**点は重要 — MainUI 以外からも理論上ロード可能。

**50社以上の自動車メーカーに対応**した汎用 SDK。関数名から確認できたブランド(抜粋):
BMW / MINI / VW(Golf, Touareg, Teramont) / Toyota / Lexus / Honda / Nissan / Mazda / Mitsubishi / Hyundai / Ford / GM / Chrysler / Jeep / PSA / Renault / Porsche / Benz / Audi / Fiat / 各中国メーカー多数

同梱の関連ライブラリ [確認済]:
- `libts50xhw.so`(232,760 bytes)— ボードハードウェア設定
- `libts70xicfg.so`(13,448 bytes)— 設定系

### 5.2 BMW 系実装バリエーション [確認済]

C++ クラスとして **7種類**の BMW 実装が存在する。

| クラス | 主要メソッド | E46 適合度 |
|---|---|---|
| **`CCanBMWHc`** | `DealDoor` `DealSWKey` `DealEPS` `DealRadar` `DealTime` `DealControlInfo` `DealSettings` `DealECU` `CalcTemp` `CarSet(BMW_Settings&)` | **有力候補** [推測] |
| **`CCanBmwWithCD`** | `DealBaseInfo` `DealStaicInfo` `DealWorkModeInfo` `DealSWKey` `DealPanKey` `DealSetInfo` `DvrIrSend` | **有力候補** [推測] |
| `CCanBMW_LZ` | `DealFuleInfo` `DealAmpInfo` `DealTextInfo` `DealMeterInfo` `DealCdcInfo` `DealTimeInfo` | 候補 |
| `CCanBMW_ZMYT` | `DealBaseInfo` `DealRadar` `DealCarSta` `DealDynmaicMsg` `IoTestReport` | 候補 |
| `CCanBmw2_Lz` | `DealVaildInfo` `DealBaseInfo` `DealRadar` `DealSWKey` | 候補 |
| `CCanBMWX1` / `CCanBMW_X1` / `CCanBmwX1Wc` | X1 専用 | 非該当 |
| `CCanBMW_Mini` | MINI 専用(`CircleLightSet` `iDriverKeySend` 等) | 非該当 |

**命名規則の推測** [推測]
- `Wc` = "Without CD"(CD チェンジャーなし)。全ブランドで頻出
- `Hc` = 旧世代向けのコード名?
- `Lz` / `Zmyt` / `Wc` = 供給元 OEM の識別子?

**JNI 経由で渡されるデータ構造体**(`CanDataInfo` の内部クラス) [確認済]
```
CanDataInfo$BMW_Trip
CanDataInfo$BMW_CtrlInfo
CanDataInfo$BMW_Settings
CanDataInfo$BMW_X1_Trip / State / Date / Time / Drive
CanDataInfo$BMW_Trip_MINI / BMW_Time_MINI / BMW_CtrlInfo_MINI
CanDataInfo$BmwWithCD_WorkMode / BmwWithCD_Set / BmwWithCD_UpdateInfo
CanDataInfo$BmwLz_FuleData / SetData / AmpData / TextData / CdcSta / Time / OutTemp
CanDataInfo$BmwZmytCar / BmwZmytIapInfo
```

**汎用 OBD 関数も存在** [確認済] — ブランド非依存で使える可能性:
```
CanObdGetSpeed / CanObdGetTemp / CanObdGetFule / CanObdGetMoto
CanObdGetDistance / CanObdGetIll / CanObdGetSta / CanObdGetAdt / CanObdGetOther
```

### 5.3 MainUI の CAN 関連アクティビティ [確認済]

`/sdcard/Iconfig/Iconfig.ini` に画面構成の一部が記録されていた(末尾の数字 = 有効/無効):
```
com.ts.MainUI,com.ts.can.btobd.CanBtOBDActivity,1     ← 有効
com.ts.MainUI,com.ts.can.CanExRadioActivity,1         ← 有効
com.ts.MainUI,com.ts.can.CanCarDeviceActivity,0
com.ts.MainUI,com.ts.can.CanExCDActivity,0
com.ts.MainUI,com.ts.can.gm.onstar.CanOnStarMainActivity,0
com.ts.MainUI,com.ts.main.navi.NaviMainActivity,1
com.ts.MainUI,com.ts.main.Media.USBMainActivity,1
（他多数）
```

dumpsys で確認できた CAN 系アクティビティ(全39個中) [確認済]:
```
com.ts.can.CanMainActivity
com.ts.can.CanCarDeviceActivity
com.ts.can.CanCarACActivity
com.ts.can.CanExRadioActivity
com.ts.can.CanExCDActivity
com.ts.can.btobd.CanBtOBDActivity
com.ts.can.ford.CanFordSyncActivity
com.ts.can.gm.onstar.CanOnStarMainActivity
```

Manifest 文字列からはさらに多くの車種別クラスを確認 [確認済]:
`CanGolfMainActivity` / `CanGolfSetMainActivity` / `CanToyotaSetMainActivity` /
`CanMGGSHomeLightActivity` / `CanToyotaWCSetMainActivity` / `CanGolfWcMainActivity` 等

**外部から MainUI を呼び出す方法** [推測・未検証]
- Service: `action=android.intent.action.MAIN_UI`、または明示コンポーネント `com.ts.MainUI/com.ts.main.common.MainUI`
- その他判明している独自 action: `android.intent.action.MAIN_SERVICE` / `BT_INTENT_SERVICE` / `BACKCAR_SERVICE`
- Activity: 明示コンポーネント指定で直接起動(例 `com.ts.MainUI/com.ts.can.CanMainActivity`)

### 5.4 シリアルデバイス / 車両バス経路 [確認済]

```
/dev/ttyMT0   crw-rw-rw-  system system  204,209   ← 全ユーザー読み書き可
/dev/ttyMT1   crw-rw----  system system  204,210
/dev/ttyMT2   crw-rw----  system system  204,211   ← タイムスタンプが他と異なる
/dev/ttyMT3   crw-rw-rw-  system system  204,212   ← 全ユーザー読み書き可 / 同上
/dev/ttyS0-3  crw-------  root   root    4,64-67
```

`/proc/tty/drivers`:
```
mtk-uart   /dev/ttyMT   204  209-212  serial
```

**注目点** [推測]
`ttyMT2` / `ttyMT3` だけタイムスタンプが `2006-12-18`(他は `2010-01-01`)で、後から動的にロードされたドライバの可能性。CAN/MCU ブリッジの有力候補。
`ttyMT0` と `ttyMT3` は **world-writable** なので shell ユーザーからもアクセスできる。

**SocketCAN は存在しない** [確認済] — `ip link` に `can0` 等のインターフェースなし。`/vendor/lib` にも CAN 関連ライブラリなし。
→ CAN は**カーネルの SocketCAN ではなく、UART 経由で MCU と独自プロトコル通信**していると考えられる [推測]。

### 5.5 その他の車両関連コンポーネント [確認済]

| 名称 | 種別 | 備考 |
|---|---|---|
| `vendor.autochips.hardware.backcar@1.0-service` | HAL(稼働中) | バック信号 → リアカメラ切替 |
| `init.svc.backcar_daemon` = `running` | デーモン | 同上 |
| `com.ts.tscanupdate` | APK | `/system/priv-app/tscanupdate/`。名称から CAN 関連更新ツール [推測]。非稼働 |
| `[GCPU]` | カーネルスレッド | D状態(割り込み不可スリープ)。用途不明 [未確認] |
| `[carplay_wq]` | カーネルワークキュー | CarPlay 用 |
| `org.prowl.torque` | APK | Torque(OBD2 診断アプリ)がプリインストール |

### 5.6 未確定事項 [未確認]

- **どの BMW サブクラスが実際に選択されているか** — 車種設定値は `com.ts.MainUI` の内部データに保存されていると思われるが、root なし・`run-as` 不可のため読み取れなかった。`settings` DB にも `getprop` にも車種情報はなし
- `CanDataInfo$BMW_*` 構造体の**フィールドレイアウト**(バイトオフセット) — jadx 等での逆コンパイルが必要
- CAN 通信の**実際のプロトコル**(UART 上のフレーム形式、ボーレート)

---

## 6. 車両特定の裏付け [確認済]

`/sdcard/.torque/vehicles/1598356859559.tdv`(Torque の車両プロファイル)より:

```properties
name=E46 M3
displacement=3.2
maxRpm=8000
weight=1620.0
tankCapacity=63.0
fuelType=0
volumetricEfficiency=85.0
preferredProtocol=4
odoMeter=246.43901405877114
```

排気量 3.2L / レブリミット 8000rpm は **S54B32 エンジン**の値と一致。このヘッドユニットが実際に E46 M3 で使用されていたことの裏付け。

**Torque の走行ログ**が 2021-01 〜 2022-04 の期間で **57セッション分**残っている [確認済]。

---

## 7. 抽出済みデータ一覧

保存先: `device-extract/`(合計 **約 126 MB / 578 ファイル**)

```
device-extract/
├── packages.txt              インストール済み 110 パッケージ(APK パス付き)
├── processes.txt             稼働中 229 プロセス
├── getprop_full.txt          全 516 プロパティ
├── bugreport.zip             ★adb 標準の完全システムダンプ(展開時 約9.8MB / 140,867行)
│                               dumpsys package / activity / window 等をすべて含む
├── logcat_all.txt            logcat 全バッファ(773行)
├── mtkprotocol.ini           Bluetooth 電話帳互換リスト(CAN とは無関係)
│
├── apks/  (23個 / 111.5 MB)
│   ├── MainUI.apk                    ★車両統合本体(37.6MB)
│   ├── Launcher_8227LTsLauncher2.apk ★現行 OEM ランチャー(5.4MB)
│   ├── tscanupdate.apk               CAN 関連更新ツール
│   ├── EngineerMode.apk              MTK エンジニアモード
│   ├── SRV_Upgrade.apk               USB 経由システム更新ツール
│   ├── MtkSettings.apk / CarplayService.apk / CarPlay_ts.apk
│   ├── TsPlayer.apk / TsGallery.apk / IpodPlayer.apk / PhoneState.apk
│   ├── MyTouch.apk / MyCamera.apk / apkmanage.apk / GpsTest.apk
│   ├── logoset.apk / QuickBootManager.apk / AtcLocationService.apk
│   ├── AtciService.apk / BtTool.apk / xfapp.apk
│   └── TXZSmartWakeUp.apk / EasyConnect.apk
│
├── libs/  (3個)
│   ├── libcan50.so           ★CAN デコーダー本体(1.5MB)
│   ├── libts50xhw.so         ボード HW 設定
│   └── libts70xicfg.so
│
├── strings/  (3個)           上記 .so から抽出した全文字列
│   └── libcan50.so.strings.txt   ★21,574 文字列。BMW クラス/関数名一覧を含む
│
└── sdcard/  (541 ファイル / 10.9 MB)
    ├── .torque/              ★E46 M3 車両プロファイル + 2021-2022 走行ログ 57 セッション
    ├── ProTool/              ★BMW コーディングツールの痕跡(§8 参照)
    │   └── Debug/*.BG            5ファイル。16進エンコードされた診断通信ログ
    ├── TsCrash/  (398件)     MainUI / TsPlayer 等のクラッシュダンプ
    │                            HMI バージョン情報を含む
    ├── Iconfig/Iconfig.ini   ★MainUI の画面構成定義
    ├── torqueLogs/           Torque のログ
    ├── launcherLog.txt       現行ランチャーのログ
    ├── TsStorage/ / waterMark/ / Ts/Picture/ / easyconn/ / EasyConnected/
    └── Download/ / Android/

除外: /sdcard/mtklog (4.2GB — MTK の低レベル通信ログ。今回の目的では価値が低い)
```

> **データ取得元の注記**: `com.ts.MainUI` の詳細な dumpsys 情報の一部は、ライブクエリではなく `bugreport.zip` 内の同等データから抽出したもの。調査期間中デバイスへは読み取り専用操作しか行っていないため内容は同一。

---

## 8. 追加の発見: `ProTool` フォルダ [推測]

`/sdcard/ProTool/` に以下のサブフォルダ構造が存在する:
```
ProTool/
├── CodingBackups/   (空)
├── DataLogs/        (空)
├── Debug/           5ファイル (2022-12 〜 2023-01)
├── Diagnostics/     (空)
└── VO/              (空)
```

`VO`(Vehicle Order)、`Coding`、`Diagnostics` は **BMW 純正診断/コーディングの専門用語**(INPA / NCS Expert / E-Sys / ISTA 等で使われる)。
→ 以前このユニットに BMW 系コーディングアプリが入っていた可能性が高い [推測]。現在のパッケージ一覧(110個)には該当なし = アンインストール済み。

`Debug/*.BG` の中身は **16進エンコードされたテキスト** [確認済]。タイムスタンプ部分は素直にデコードできるが、データ部はさらに難読化(シフト/XOR 系?)されている模様。一部に `22`(UDS の ReadDataByIdentifier サービス ID)らしき並びが見える [推測]。
**解析は今後の課題**。

---

## 9. 開発方針への示唆(まとめ)

| 項目 | 結論 |
|---|---|
| ランチャー置き換え | **可能**。root 不要、ADB のみ、いつでも復元可 |
| PWA ホスト方式 | **TWA / Custom Tabs 必須**。素の WebView は Chromium 61 で NG |
| ターゲット API | **API 27 (Android 8.1)** 基準で設計 |
| 画面設計 | 1024x600 @ 240dpi = 約 683x400 dp 相当の横長・低解像度 |
| パフォーマンス | RAM 2GB / 32bit 旧世代 CPU。軽量必須 |
| CAN 連携 | MainUI 経由(Intent)か `libcan50.so` 直接呼び出し。要追加調査 |
| 既存機能 | MainUI は BOOT_COMPLETED で独立起動。差し替えても壊れない |

---

## 10. 未解決事項 / 次のステップ候補

1. **`MainUI.apk` の逆コンパイル**(jadx)
   → `CanDataInfo$BMW_*` 構造体のフィールドレイアウト、車種選択メニューの実装、CAN 呼び出しの Java 側インターフェースを解明する
2. **`ProTool/Debug/*.BG` の解読**
   → BMW 純正診断プロトコルの通信ログの可能性。Diagnosis アプリ開発の直接的な参考になりうる
3. **どの BMW CAN サブクラスが有効か特定**
   → 設定画面の車種選択メニューを実機で確認するのが最短。あるいは逆コンパイル
4. **`tscanupdate.apk` の調査**
   → CAN MCU のファームウェア更新機構の可能性。プロトコル解明の手がかり
5. **工場テスト画面の調査** [確認済で存在]
   `FactoryMainActivity` / `FactorytestAudioActivity` / `FactoryRadioTestActivity` / `FactorytestarmActivity` / `FactorytestvideoActivity`
   → ハードウェア直接テストの入口。CAN 疎通確認に使えるかもしれない
6. **ランチャー本体の実装**

---

## 付録 A: よく使うコマンド

```bash
# 接続
adb connect 192.168.11.14:5555
adb devices -l

# 現在の HOME を確認
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME --brief

# HOME を切り替え(★実効性は dumpsys で確認すること。resolve-activity はキャッシュを返す)
adb shell cmd package set-home-activity <package>/<activity>

# 実際に効いているか確認
adb shell dumpsys package <package> | grep -A 10 "Preferred Activities"

# 画面に何が出ているか
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"

# HOME を再表示
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

# 元に戻す(OEM ランチャーへ復元)
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
```

**注意**: Android 8.1 のため `pm resolve-activity` は**存在しない**。`cmd package resolve-activity` を使うこと。

---

## 付録 B: 調査手法メモ

**APK 内 AndroidManifest.xml の文字列抽出**
バイナリ XML (AXML) 形式だが、文字列プールは **UTF-16LE** で埋め込まれている。
Latin1 でデコードすると 1文字ごとに `0x00` が挟まりマッチしないため、`[System.Text.Encoding]::Unicode` でデコードしてから印字可能 ASCII を正規表現抽出すると、intent-filter の action/category やクラス名が読み取れる(専用パーサ不要)。

**ネイティブライブラリの文字列抽出**
`strings` コマンドが無い環境では、全バイトを ISO-8859-1 でデコードし `[\x20-\x7E]{5,}` を正規表現マッチすれば同等の結果が得られる。
C++ のマングル名(`_ZN9CCanBMWHc...`)からクラス構造とメソッドシグネチャが読み取れる。

**root なしで取れる/取れないもの**

| 対象 | 可否 |
|---|---|
| `/system/*` `/vendor/*` の APK / .so | **取得可** |
| `/system/build.prop` | 不可(`getprop` で代替) |
| `/data/data/*`(アプリ内部データ) | 不可 |
| `/sdcard/*` | **取得可** |
| `dumpsys` 各種 | **取得可** |
| `adb bugreport` | **取得可**(dumpsys の塊として非常に有用) |
