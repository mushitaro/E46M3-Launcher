# E46M3 /// Launcher

*[English version](README.md)*

BMW E46 M3 に載せた Android ヘッドユニット用の、ホーム画面置き換えアプリです。

純正ランチャーを、車両のスイッチ類を模したコンソールに差し替えます。表示窓とキーの格子、そして表示窓がタコメーターに変わる「M」モード — 回転数は K+DCAN ケーブル経由で DME から直接読んでいます。

**コールドスタートは 790〜1780ms。純正ランチャーは 5966ms でした。**

---

## 入れる前に読んでください

**これは汎用の Android ランチャーではありません。** 特定のヘッドユニット 1 機種のために書かれていて、いくつもの要素が実行時に適応するのではなくハードウェアに直結しています。他の機種にもインストールはできますが、たいていは表示が崩れ、できることも減ります。

危険な点を 2 つ、先に書きます。

> ### ⚠️ HOME に設定したままアンインストールしないでください
>
> Android は `com.android.settings/.FallbackHome` に落ちます。これは何もない画面で、ユニット側からは操作のしようがありません。復旧には車両の電源、PC が同じ WiFi にいること、そして ADB が要ります。**下の復旧コマンドは、入れた後ではなく入れる前に手元で試してください。**
>
> アプリには HOME を先に返してから削除する手順が組み込まれていて、別のランチャーが HOME を引き継いだことをプラットフォームが認めるまで削除に進みません。設定アプリからではなくそちらを使ってください。

> ### ⚠️ 配布 APK は *このリポジトリ* から自動更新します
>
> 更新元 URL はバイナリに焼き込まれています:
> `https://github.com/mushitaro/E46M3-Launcher/releases/latest/download/ota-manifest.json`
>
> 配布 APK を入れると、あなたのヘッドユニットはこのリポジトリを見に行き、見つけたものを（このプロジェクトの鍵で署名された状態で）適用しようとします。公開プロジェクトとして意図した挙動ですが、信頼関係が発生することは承知したうえで入れてください。更新を自分で管理したい場合は[自分でビルドしてください](#自分でビルドする)。

---

## 対象機種

**箱のブランド名ではなく、ユニット自身が名乗る値で判定してください。**

この種のユニットはホワイトレーベル品です。ODM が 1 社で作ったものを、多数の販売元が自社ブランドで売っています。このプロジェクトの開発に使ったのは **EONON GA9450B** ですが、**ファームウェアにはその名前が一切残っていません**。抽出した端末データ全体（システムプロパティ、`/sdcard`、ベンダー APK 23 本）を「EONON」でも「GA9450B」でも検索して 0 件でした。小売ブランドはシールの話で、端末が名乗るのは ODM の識別子です。照合すべきは後者です。

つまり、**同じ中身を別ブランドで買っていても動きますし、EONON の別モデルだからといって動くとは限りません。**

判定はこのコマンドで。

```bash
adb shell getprop ro.product.model         # FF-5000
adb shell getprop ro.product.brand         # FFKJ
adb shell getprop ro.product.manufacturer  # alps     (MediaTek のリファレンス名)
adb shell getprop ro.build.version.sdk     # 27       <- 本当の Android バージョン
adb shell getprop ro.build.display.id      # FF_8227L_10
adb shell wm size                          # Physical size: 1024x600
```

> **信じるべきは `ro.build.version.sdk` です。** このユニットは設定画面で「Android 10」と表示し、`ro.build.version.release` も `10` を返しますが、どちらもベンダーが文字列を書き換えただけの見た目です。実体は Android **8.1、API 27**。このプロジェクトはすべて 27 基準で考えています。

### 検証済みの基準機

| | |
|---|---|
| 販売名 | **EONON GA9450B**（所有者の申告）。**ファームウェアには一切記録されていません** — 機種判定の根拠には使わないでください |
| model / device / name | **`FF-5000`** |
| ブランド | **`FFKJ`** — ODM の名前であって小売ブランドではありません |
| 製造元 | `alps`（MediaTek リファレンス） |
| ビルド ID | `FF_8227L_10` |
| fingerprint | `alps/full_8227L_demo/8227L_demo:8.1.0/O11019/1571038753:userdebug/test-keys` |
| HMI 版数 | `XRCH.D.Q.F.3.04_1.2019.11.29.16.00` — ベンダー UI のクラッシュログ由来。`getprop` では読めません |
| SoC | MediaTek **MT8227L** / AutoChips AC8227L、Cortex-A7 ×4、**32bit のみ**（`armeabi-v7a`） |
| Android | **8.1 Oreo、API 27** |
| 画面 | **1024 × 600**、240dpi |
| RAM | 2GB（空き約 1.1GB） |
| ビルド型 | `userdebug` / `test-keys`。ネットワーク ADB がポート 5555 で既定で開いています |
| ベンダー UI | `com.ts.MainUI`（MTK カーオーディオの「TS」系ファームウェア） |

### あなたのユニットはどこまで近いか

| ユニット | 期待できること |
|---|---|
| `FF-5000` / `FFKJ`、API 27、1024×600 | **基準機**（EONON GA9450B）。ここに書いてあることがそのまま当てはまります |
| 他の MTK **8227L** 機で `com.ts.MainUI` あり、API 27、**1024×600** | ほぼ問題ないはずです。コンソールもアプリ一覧も更新も車両系キーも、このファミリが共有するものに乗っています |
| 同じ系統だが**解像度が違う**（800×480、1280×720 など） | **コンソールの配置が崩れます。** 座標は意図的に **px** 直打ちで（理由は `res/values/design.xml`）、密度スケーリングもありません。動きはしますが、見た目は成立しません |
| API 23〜26 | インストールも起動も更新もできます。ただし `targetSdk 27` なので、未テストの互換シムがフレームワーク側でかかります |
| **API 23 未満** | 更新機能がそもそも動きません。captive portal と生きている回線を区別するのに `NetworkCapabilities` が必要だからです。`minSdk` は 21 なので、それ以外は入ります |
| 8227L でない / `com.ts.MainUI` が無い | ホーム画面・時計・アプリ一覧・更新は動きます。RADIO / BT / VIDEO / EQ / CARPLAY / DROID と外気温は死にます |

### この機種に依存している部分

| 機能 | 必要なもの | 無い場合 |
|---|---|---|
| コンソールのレイアウト | **1024 × 600** のパネル | キーの位置がずれます。座標は意図的に **px** 直打ちで（`res/values/design.xml` 参照）、密度によるスケーリングはありません |
| M キー、タコメーター、水温・油温 | **FTDI (`0x0403`) か CH340 (`0x1A86`) の K+DCAN ケーブル**が USB ホストバスに刺さっていること | **M キーがそもそも出ません。** ソケットは塞がれたままで、TUNER に到達できません → [トラブルシューティング](#m-キーが出てこない) |
| RADIO / BT / VIDEO / EQ / 外気温 | `com.ts.MainUI`（ベンダーの CAN・ラジオ基盤） | これらのキーは淡色になり、押しても何も起きません |
| CARPLAY / DROID | `com.ts.carplayapp`、`net.easyconn` | 同上 |
| TUNER（Web ツール） | **Google Chrome** が入っていること | キーが失敗し、その旨が画面に出ます |
| TUNER の全画面化 | Chrome に加えて署名の一致（後述） | ブラウザのツールバーが付いた状態で開きます。**エラーは出ません** |
| アプリ一覧 | 標準の `CATEGORY_LAUNCHER` と、ベンダー私有の `MYLAUNCHER` | どこでも動きます。ベンダーアプリが一覧に出ないだけです |

いずれも落ちずに degrade します。上記が全部無いユニットでも、ホーム画面・アプリ一覧・時計・更新機能は動きます。

---

## インストール

PC に `adb` が必要です。ヘッドユニットの電源が入っていて、PC と同じ WiFi サブネットにいる必要があります。**ADB の接続は車両の電源が入っている間しか存在しません** — キーを切ると即座に切断されます。

### 1. 先に「戻し方」を用意する

```bash
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
```

これは FF-5000 の純正ランチャーです。別の機種の場合は次で調べてください。

```bash
adb shell "dumpsys package > /sdcard/p.txt"
adb shell "grep -B 2 -A 6 'android.intent.category.HOME' /sdcard/p.txt"
```

この行を別のターミナルに出したまま、以下の作業を進めてください。

> `dumpsys` を**デバイス上で**直接 `grep` にパイプすると `Broken pipe` になるため、ファイル経由にしています。好みの問題ではなく、そうしないと動きません。

### 2. APK を入手する

[Releases](https://github.com/mushitaro/E46M3-Launcher/releases/latest) の `app-launcher-<version>-<code>.apk` を落とします。

入れる前に検証してください。

```bash
apksigner verify --min-sdk-version 21 --print-certs app-launcher-0.2.0-2.apk
```

証明書の SHA-256 が次と一致すること:

```
8e529141ef09abdb50d95930a25263153834ac3b385c9f7603b15fe8bcfdb580
```

加えて **v1 scheme: true** かつ **v2 scheme: true** であること。API 27 は v3 署名を解釈できません。

### 3. 接続してインストール

```bash
adb connect <ナビのIP>:5555
adb install -r app-launcher-0.2.0-2.apk
```

インストールしただけでは HOME になりません。既存の設定は `mAlways=true` で固定されているため、明示的に置き換える必要があります。

### 4. HOME に設定する

```bash
adb shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity
```

反映を確認します。**`cmd package resolve-activity` は使わないでください** — 古い値をキャッシュから返します。

```bash
adb shell "dumpsys package app.tsunagi.e46m3.launcher > /sdcard/p.txt"
adb shell "grep -A 4 'Preferred Activities' /sdcard/p.txt"
```

出力にこのアプリの `HomeActivity` が出ていれば成功です。

### 5. 更新のインストールを許可する

```bash
adb shell appops set app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES allow
adb shell appops get app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES
```

**ここは飛ばさないでください。** `REQUEST_INSTALL_PACKAGES` はマニフェストに書いてありますが、API 26 以降は app-op に紐付いていて未許可で始まります。これが無いと、ランチャーは更新を見つけてダウンロードして検証までするのに、**適用だけができません**。その旨は画面に出ますが、直し方はこのコマンドです。

ユニット側からやる場合は 設定 → アプリ → 特別なアクセス → 不明なアプリのインストール です（ベンダーの設定アプリにその画面があれば）。

### 6. ホーム画面を起動し直す

```bash
adb shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
```

---

## アップデート

最初の 1 回さえ入れてしまえば、以後はヘッドユニットが自分で面倒を見ます。

| 手順 | 誰が | 自動か |
|---|---|---|
| 新しい版があるか確認 | ヘッドユニット | **自動** — ホーム画面が出て数秒後 |
| ダウンロードと検証 | ヘッドユニット | **自動**（従量制回線の場合のみ DOWNLOAD を押すまで待ちます） |
| 適用 | あなた | **ワンタップ**、その後システムの確認ダイアログ |

**表示窓（上部の LCD パネル）をタップ**すると更新画面が開きます。更新が待機しているときは LCD の右下に `UPDATE <版数>` と出ます。

### 適用前に検査していること

1. 更新マニフェストには、このリポジトリに**入っていない**鍵による **RSA-2048/SHA-256 署名**が付いています。公開鍵は APK に埋め込まれています。バイト列を検証してから parse します（逆順はしません）。
2. APK の **SHA-256** が署名済みマニフェストと一致すること。
3. APK の**署名証明書**がマニフェストと一致し、かつ**今動いているアプリの証明書と同一**であること。
4. マニフェストの `serial` は後戻りできません。古い署名済みマニフェストを再生して押し付けることはできません。

これらは意図的に HTTPS から独立させてあります。このヘッドユニットは起動時に内蔵時計が **2006 年**を指していることが頻繁にあり、そうなるとすべての証明書が「まだ有効でない」扱いになって、データが届く前に TLS ハンドシェイクが失敗します。そのときランチャーは `CLOCK WRONG` と表示して接続せず、時計が直り次第ひとりでに再開します。

### 更新が拒否される条件

自己更新はホーム画面を再起動させるので、次のいずれかに当てはまる間は実行しません。それぞれ固有のメッセージが出ます。

- M コンソール（タコメーター）が開いている
- アプリ一覧が開いている
- 復帰カウントダウン中
- **K+DCAN ケーブルが刺さっている** — 誰かが車を触っている
- DME と通信中
- **エンジンが掛かっている**

コンソールを閉じる、ケーブルを抜く、エンジンを止める、のいずれかをしてから INSTALL を押し直してください。

---

## 削除する

**組み込みの手順を使ってください。** 更新画面を開き（表示窓をタップ）、**一番下の版数行を長押し**します。4 段階が順に進み、各段が次の段のゲートになっていて、復旧コマンドは常時画面に出ています。

1. このアプリの HOME 割り当てを解除する
2. 元のランチャーを「**常時**」で選ぶ
3. HOME が実際に移ったことを確認する
4. アンインストール

段 3 が「別のランチャーが HOME を持っている」と確認するまで、段 4 は提示されません。

うまくいかない場合、あるいは PC からやりたい場合:

```bash
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
adb uninstall app.tsunagi.e46m3.launcher
```

必ずこの順で両方。1 行目なしに 2 行目だけ実行することが、この手順全体が防ごうとしている事故そのものです。

---

## 自分でビルドする

自分で更新を配信したい、自分の署名鍵を使いたい、改造したい場合はこちら。

**必要なもの:** JDK 17、Android SDK（build-tools 35.0.0）。Gradle 8.9 と AGP 8.7.3 は wrapper に含まれます。

```bash
git clone https://github.com/mushitaro/E46M3-Launcher
cd E46M3-Launcher/app-launcher
```

### 1. 自分の署名鍵

キーストアを作り、`app-launcher/keystore.properties`（gitignore 済み）を置きます。

```properties
storeFile=/絶対パス/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

同じ証明書を**すべてのビルドタイプ**に適用しているのは意図的です。debug ビルドが自動生成の debug 鍵で署名されると Digital Asset Links の検証に落ち、TUNER がツールバー付きの Custom Tab に劣化します。しかも**どこにもエラーが出ません**。

### 2. 自分の OTA 署名鍵

```bash
tools/ota-keygen.sh
```

秘密鍵は `~/.e46m3/ota-rsa2048-private.pem` に作られ（リポジトリには絶対に入りません）、公開側の 2 ファイルは**コミットが必要**です: `tools/ota_public_key.pem` と `app/src/main/res/raw/ota_public_key.der`。

> 秘密鍵を失うと、現場のユニットは新しいマニフェストを一切受け付けなくなり、ADB での再導入以外に手がなくなります。作ったマシン以外にバックアップしてください。

### 3. 更新元を自分のリポジトリに向ける

ビルド時に指定するか、

```bash
./gradlew assembleRelease -PotaManifestUrl=https://github.com/<あなた>/<repo>/releases/latest/download/ota-manifest.json
```

`app/build.gradle.kts` の既定値を書き換えます。

### 4. TUNER の全画面化（任意）

TUNER は APK ではなく Trusted Web Activity です。全画面化には両側からの Digital Asset Links 宣言が要ります。アプリ側は `res/values/strings.xml` でオリジンを宣言し、サイト側は `/.well-known/assetlinks.json` であなたのパッケージ名と**あなたの**証明書 SHA-256 を宣言します。どちらかが間違っていても Chrome は何も報告せず、ただツールバーを出します。

### 5. リリースを公開する

```bash
tools/release.sh --bump patch
```

ビルド → 検証 → **prerelease として公開** → 公開 URL から全部落とし直して返ってきたバイト列を検査 → 通ったら `latest` に昇格、という順で進みます。`--dry-run` は公開以外をすべて実行します。

---

## トラブルシューティング

### ホーム画面が消えた / 真っ暗な画面になった

HOME を失っています。車両の電源を入れ、同じ WiFi に入って:

```bash
adb connect <IP>:5555
adb shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity
```

ランチャーはこのコマンドを通知にも出し、`OTA_LOST_HOME` というタグでログにも残します。

```bash
adb logcat -d | grep OTA_LOST_HOME
```

### M キーが出てこない

診断ケーブルが無ければそれが正常です。M キーは **FTDI (`0x0403`) か CH340 (`0x1A86`)** のデバイスが USB ホストバスにいる間だけ装着されます。それがランチャーにとっての「K+DCAN ケーブルが刺さっている」の判定だからです。ベンダー ID 全体で照合していて、特定の製品 ID は見ていません。

TUNER は M コンソール上にあるので、ケーブル無しでは到達できません。更新画面はそうではなく、ホーム画面で表示窓をタップすれば開きます。

### TUNER がツールバー付きで開く

Digital Asset Links の検証に失敗しています。原因はどれも無言です: Chrome が未インストール、初回起動時にネットワークが無い、サイトが `/.well-known/assetlinks.json` を返していない、APK がそのファイルに書かれた鍵と別の鍵で署名されている。

### 更新は見つかるのに INSTALL を押しても何も起きない

`REQUEST_INSTALL_PACKAGES` の app-op が未許可です。[手順 5](#5-更新のインストールを許可する) を実行してください。

### 更新画面に CLOCK WRONG と出る

ヘッドユニットの時計が異常な値なので、接続を試みていません。まだ有効でない証明書ではどのみちハンドシェイクが失敗します。時刻が同期されれば自動で解消します。何もする必要はありません。

### キーが淡色で押しても反応しない

そのキーが指すアプリがユニットに入っていません。淡色は「解決できなかった＝不在」の表示で、FF-5000 以外のハードウェアでは普通に起きます。

---

## ドキュメント

すべて [`docs/`](docs/) 以下にあります。主張には **[V]** 実機で確認済 / **[I]** 静的調査で確認済 / **[U]** 未検証 のラベルが付いています。

| | |
|---|---|
| [`01-device-investigation.md`](docs/01-device-investigation.md) | ヘッドユニットの正体、root が取れない理由、Android のバージョン表示が偽である理由、WebView を使えない理由 |
| [`03-mainui-recon.md`](docs/03-mainui-recon.md) | `com.ts.MainUI` の解析 — 車両 binder API |
| [`04-launcher-design.md`](docs/04-launcher-design.md) | ランチャー本体: アーキテクチャ、コンソール、タコメーター、実車で出たバグとその原因 |
| [`05-tuner-resume-spec.md`](docs/05-tuner-resume-spec.md) | 電源断からの Web ツール復帰 |
| [`06-tuner-webgl-fallback-spec.md`](docs/06-tuner-webgl-fallback-spec.md) | このユニットに WebGL が無いことへの対処 |
| [`07-ota-design.md`](docs/07-ota-design.md) | 更新機構: 脅威モデル、公開時のゲート、未検証事項 |

---

## 使用しているサードパーティ

| | |
|---|---|
| `androidx.constraintlayout` 2.1.4、`androidx.recyclerview` 1.3.2、`androidx.browser` 1.8.0 | Apache-2.0 |
| [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) 3.9.0 | MIT |

意図的に使っていないもの: Compose、AppCompat、Material Components。この画面は毎回のブート・`KILL_APPS`・低メモリ kill のたびにコールドスタートします。`speed-profile` の dexopt は API 28 以降のため使えず、in-order の Cortex-A7 上で 1 万個規模のメソッドが毎回 JIT されることになるからです。

このリポジトリにはまだライセンスを定めていません。コードを再利用したい場合は issue を立てて聞いてください。
