# 07 — OTA アップデート設計

対象: `app.tsunagi.e46m3.launcher` / FF-5000 (MediaTek MT8227L, API 27)
記法は他の docs と同じ — **[V]** 実機で確認済 / **[I]** 静的調査で確認済 / **[U]** 未検証

---

## 1. なぜ作ったか

これまでこのランチャーを更新する手段は `tools/deploy.sh` だけだった。成立条件が 3 つ同時に要る:

1. PC を持った人間がいる
2. 車と同じサブネットの WiFi にいる（`adb connect 192.168.11.14:5555`、DHCP なので IP は動く）
3. イグニッションが入っている（切れた瞬間 `device offline`）

ヘッドユニットがインターネットに出られるなら、車の側から取りに行けるはず。そうすれば更新は「ガレージで PC を開く作業」ではなく「エンジンを掛けたらコンソールに UPDATE と出るので押す」になる。

**決定事項**（2026-09-18、利用者と合意）:

| 項目 | 決定 | 理由 |
|---|---|---|
| 権限モデル | `REQUEST_INSTALL_PACKAGES` + システム確認ダイアログ | Device Owner による無音更新は Google アカウント削除が前提。§2.3 |
| 配信元 | GitHub Releases | `mushitaro/E46M3-Launcher` は既に public。トークン不要、新規インフラ不要 |
| 適用 | 自動ダウンロード → ワンタップ適用 | HOME アプリの自己更新が無人で走らない |
| 範囲 | ランチャー APK のみ | TUNER は APK ではない。§7 |

---

## 2. 設計を決めている実機の事実

### 2.1 RTC が狂う [V]

`device-extract/bugreport.zip` のファイル名が `bugreport-FF-5000-O11019-2006-12-18-16-42-48.txt`、`sdcard/TsCrash/` に `crash-2006-02-03-…` と `crash-2006-06-22-…` がある（本物の 2021–2023 のものと混在）。

時計が 2006 年を指した状態で `HttpsURLConnection` を開くと、ペイロードに触れる前に `CertificateNotYetValidException` で落ちる。**したがって完全性の根拠を TLS に置けない。**

対応は 2 段:

- **独自署名。** RSA-2048/SHA-256 でマニフェストに署名し、公開鍵を APK に焼き込んで検証する（`ManifestVerifier`）。APK 本体は SHA-256 と署名証明書で二重に検証する。TLS は輸送路でしかなくなり、時計の狂いは可用性の問題であって完全性の問題ではなくなる。
- **時計が異常なら接続しない。** 下限は `BuildConfig.BUILD_EPOCH`（= そのビルドのコミット時刻）。端末が「自分で動かしているビルドより前」を指すことは有り得ない。固定値と違って自動的に更新される。異常時は待機し、`HomeActivity` が既に受けている `ACTION_TIME_CHANGED`（NTP 同期後に `AlarmManagerService.setTime()` が投げる）で再開する。**新しい receiver は要らない。**

> Ed25519 は `java.security` では **API 33** 以降。この端末は 27 なので使えない。`SHA256withRSA` は API 1 から在り、検証は小さい指数の modexp 1 回で in-order Cortex-A7 でもサブミリ秒。ECDSA も使えるが DER エンコーディングの失敗モードが増えるだけで、得られるのは 184 バイト。

### 2.2 更新対象が HOME アプリ [V]

`docs/04` §12 の警告どおり、HOME を失うと `com.android.settings/.FallbackHome` に落ち、復帰には車両の電源と正しい WiFi と ADB が要る。設計上の帰結:

- ランチャーは **絶対に uninstall→install しない。** 同一署名の in-place 置換のみ。`ConfirmInstaller.uninstall` は自パッケージを構造的に拒否する。
- **`HomeActivity` のクラス名と HOME intent-filter は変更禁止。** preferred activity の保持がそこに懸かっている。`proguard-rules.pro` の `-keep` は既にこの理由で在り、`tools/release.sh` の GATE-1 が publish 前にも検査する。
- 置換後の確認は `resolveActivity` ではなく `PackageManager.getPreferredActivities()`（`HomeGuard`）。`dumpsys` が印字し `set-home-activity` が書くテーブルそのものなので、`deploy.sh` の ADB 越しの確認と同じ答えになる。
- `commit()` の直前に `SharedPreferences.commit()`（`apply()` ではない）で pending を記録する。パッケージ置換は SIGKILL であり、`apply()` の `QueuedWork` フラッシュは走らない。

### 2.3 Google アカウントが存在する [I]

`device-extract/bugreport.zip` の account ダンプ:

```
User UserInfo{0:所有者:13}:
  Accounts: 1
    Account {name=kazuhiro.mushi@gmail.com, type=com.google}
```

API 27 の `dpm set-device-owner` はアカウントが 1 つでも残っていると `CODE_ACCOUNTS_NOT_EMPTY` で拒否する。したがって **Device Owner による無音更新は Play ストア用アカウントの削除とのトレードオフ**。今回は採らなかったが、`Installer` インターフェースは差し替えられる形で残してある（§6）。

Device Owner が買えるのは「チューザ無しで黙って HOME を切り替える」ことであって、HOME を手放す能力そのものではない — §8 参照。

### 2.4 root は取れない [V]

`docs/01` §3.2: `adb root` → `cxj said not suport, 88`。ベンダーが `adbd` にパッチを当てている。`/system/priv-app` に置いて `INSTALL_PACKAGES` 特権を得る道は無い。

### 2.5 壁時計で throttle できない

6 時間間隔のような throttle は壁時計を要求するが、その時計こそ §2.1 で信用できないと結論したもの。時計が 2037 年を指せば数年チェックが止まり、2006 年なら毎回走る。**`SystemClock.elapsedRealtime()` + プロセス内フラグのみ**で throttle する。この端末はキーサイクルごとにコールドブートするので、実質「起動ごとに 1 回」になる — 壊れた時計に左右されない経路でそこに到達する。

---

## 3. 配信パイプライン

`tools/release.sh`。各段がゲートで、最後の 2 つは**我々が作ったバイト列ではなく、公開 URL から返ってきたバイト列**を検査する。

`docs/04` §10.5 に、ビルドもアップロードもデプロイも成功と報告しながら公開サイトが 404 を返していた事例がある（`upload-pages-artifact@v5` がドット始まりを黙って落とす）。どの段も嘘はついておらず、どの段も肝心なものを検査していなかった。

| 段 | 主張すること |
|---|---|
| 0 preflight | tree clean、`gh auth status`、鍵が読める、`apksigner`/`aapt2`/`openssl`/`curl`/`python` が在る |
| 1 version | `versionCode` が**公開済み最新より厳密に大きい**。ネットワーク障害を「まだリリースが無い」と読まない（serial を 1 に戻すと、既に高い serial を見た端末が永久に拒否する） |
| 2 build | `./gradlew :app:assembleRelease` |
| 3 **GATE-1** | `aapt2 dump xmltree` で `.HomeActivity` が MAIN+HOME+DEFAULT を持つこと、versionCode/versionName が一致すること。**HOME フィルタを失った APK を車に絶対に届かせない** |
| 4 **GATE-2** | `apksigner` が **v1: true かつ v2: true**、署名者 SHA-256 が一致 |
| 5 **GATE-3** | OTA 公開鍵が APK に載っていること。**パスではなく内容ハッシュで照合**（§3.1） |
| 6 manifest | `serial = 前回 + 1`、`openssl dgst -sha256 -sign` |
| 7 **GATE-4** | **公開鍵**で `-verify`。鍵ペアの取り違えを車ではなくここで捕まえる |
| 8 publish | `gh release create --prerelease`（§3.2） |
| 9 **GATE-5a** | 空の一時ディレクトリに公開 URL から `curl` → 署名検証 → sha256 → `apksigner` → **GATE-1 を再実行** → `Range: bytes=0-1023` が **206** を返すこと |
| 10 promote | `gh release edit --prerelease=false --latest` |
| 11 **GATE-5b** | `releases/latest/download/…` が今の serial を返すこと |

### 3.1 GATE-3 がパスではなく内容で照合する理由 [V]

AGP のリリースビルドは `aapt2 optimize --shorten-resource-paths` を走らせる。`res/raw/ota_public_key.der` は実際には **`res/j5.der`** になっていた。`openRawResource()` は気にしないが、パスで探す検査は黙って何も検査しなくなる — §10.5 と同じ形。だから全エントリをハッシュして照合する。これは同時に「鍵が**今の**鍵であって前の keygen の残りではない」ことも証明する。

### 3.2 publish が 2 段階な理由

車が読むのは `releases/latest/download/ota-manifest.json`。この URL は prerelease に解決しないので、まず prerelease として作り、直接 URL で全部検証してから昇格する。**検証に落ちたビルドが、車の見る場所に一度も現れない。**

失敗時に release/tag を自動削除はしない。コマンドを印字して止まる — 既に別の理由で存在していた tag を自動で消す方が、誰も配信していない prerelease が残るより悪い。

### 3.3 API 27 が v3 署名を解さない [I]

`PackageParser` は v1/v2 は解するが v3 は解さない。**v3 単独の APK はインストールできても `getPackageArchiveInfo(GET_SIGNATURES)` が null を返す。** だから GATE-2 は v1/v2 両方を要求し、端末側は `signatures == null || isEmpty()` を**スキップではなくハード失敗**として扱う。

---

## 4. マニフェスト形式

```json
{
  "schema": 1,
  "serial": 7,
  "packages": [
    { "kind": "apk", "packageName": "app.tsunagi.e46m3.launcher",
      "versionCode": 3, "versionName": "0.3.0",
      "url": "...", "size": 3114250, "sha256": "...",
      "signerSha256": "8E529141...BCFDB580",
      "minSdk": 21, "maxSdk": null,
      "isHome": true, "mandatory": false, "releaseNotes": ["..."] }
  ]
}
```

分離署名 `ota-manifest.json.sig` は **JSON ファイルの正確なバイト列**に対する RSA 署名の base64。

- **`serial` はタイムスタンプではない。** 鮮度判定が RTC に依存してはならない（§2.1）。端末は `maxSerialSeen` を持ち、それ**未満**を拒否する。等号は許す — 毎回同じマニフェストを取り直すので、厳密不等号だと 2 回目のチェックで保留中の更新が消える。
- 不良リリースの撤回は「より大きい serial でそれを含まないマニフェストを出す」。serial を下げると、既に高い serial を見た端末＝まさに撤回対象の端末が無視する。
- **`signerSha256` はエントリごと。** 署名対象なので権威があり、別の鍵で署名された 2 つ目のアプリが現れても定数を再コンパイルせずに済む。
- **`isHome`** で「最後にインストールし最も強く守る」対象をデータとして示す。コードに自分自身を推論させない。
- `kind` は前方互換のために持ち、**`"apk"` 以外は黙って無視**する。新しい kind が増えても古いビルドは自分を更新し続ける。

---

## 5. 端末側の流れ

```
起動 → consumeStartupOutcome()      前回の適用結果を読む(消費してから判定)
      ↓ 初回フレーム後 +6s
      checkIfDue()                  throttle: elapsedRealtime のみ
      ↓
      時計は妥当か? ──no→ WAITING(CLOCK)  … ACTION_TIME_CHANGED で再開
      ↓yes
      回線はあるか? ──no→ WAITING(OFFLINE / UNVALIDATED)
      ↓yes
      マニフェスト + 署名を GET (各数 KB)
      ↓
      バイト列を検証 → それから parse   ← 逆順は構造的に不可能
      ↓
      自分より新しい? ──no→ UP TO DATE
      ↓yes
      従量制? ──yes→ AVAILABLE（DOWNLOAD を押すまで通信しない）
      ↓no
      ダウンロード（Range 再開 + ストリーミング SHA-256）
      ↓
      ApkInspector: 署名証明書 == 自分の証明書、pkg/versionCode 一致
      ↓
      READY → LCD に "UPDATE 0.3.0" → LCD タップ → INSTALL
      ↓
      SelfUpdateGuard → beginInstall(commit!) → PackageInstaller セッション
      ↓
      システム確認ダイアログ → SIGKILL → 新しいプロセス
```

### 5.1 スレッド

| 仕事 | スレッド | 理由 |
|---|---|---|
| チェック | 既存の `"catalogue"` | 数 KB、connect/read とも 8s。最悪でもアプリ一覧の更新を 16s 遅らせるだけ |
| ダウンロード / インストール | 専用の `"ota"`（MIN_PRIORITY） | 3MB の転送がカタログ再読込の前に居座るのは目に見えるバグ。しかもそのカタログ再読込を引き起こすのはこのインストール自身 |
| 状態の公開 | `ui` Handler | OTA パッケージは View に触れない |

`snapshot()` は volatile フィールドだけから作る。ペインを開くのに `SharedPreferences` を読まないため — この画面のコールドスタートは毎回のブートで払われ、予算は 800ms。

### 5.2 foreground Service は作っていない

現ビルドの APK は約 3.1MB、ガレージ WiFi で数秒。ダウンロードは `HomeActivity` が RESUMED の間だけ走る＝自プロセスが前景なので LMK は触らない。pause したら読むのを止めて `.part` を残し、Range 再開が残りを賄う。

Service が買えるのは「TUNER を使っている間もダウンロードを続ける」だけで、それは WebUSB のデータログ中に 1.1GB の空きと同じ回線を奪い合う最も避けたい瞬間。ステータスバー通知も運転者に見える。

**追加する基準**: ガレージの回線でダウンロードがコンソール 1 回の滞在で終わらないことが実際にログで観測されたら。判断ではなく数字。

### 5.3 VALIDATED を要求しつつ 3 回で諦める

`NET_CAPABILITY_VALIDATED` は正しい信号だが、captive portal プローブ（`connectivitycheck.gstatic.com`）成功後にしか立たない。ベンダー MTK ビルドでそれが無効化・遮断されていると、**正常なガレージ WiFi で OTA が永久に沈黙し、ログは「validated を待っています」と言い続ける** — §10.5 と同じ形。

だから VALIDATED は優先するが必須にしない。「接続済みだが未 VALIDATED」を **3 回**数えたら行く。カウンタは `SharedPreferences` に持つ — プロセス内だとキーサイクルごとの再起動で 3 に到達せず、静かな停滞を救うはずの仕組みがそれ自体静かな停滞になる。

実機で `adb shell dumpsys connectivity | grep -i valid` を一度見ておくこと [U]。

---

## 6. インストーラ

`Installer` インターフェースに 2 実装を想定し、1 つだけ作ってある。

- **`ConfirmInstaller`（実装済）** — `PackageInstaller` セッションに APK を流し込み、システムが確認ダイアログを出す。`FileProvider` も `content://` も使わない（`<provider>` 一式が不要になり、ステータスコールバックも得られる）。ステータス受信は**ランタイム登録**の receiver + 明示パッケージ付き `PendingIntent` — `Ds2Link` の USB 権限と同じ形。
- **Device Owner 版（未実装）** — 無音コミットと `addPersistentPreferredActivity` による HOME の設定。§2.3 のアカウント削除が前提。インターフェースは残してあるが、スタブは置いていない — 何もしないスタブは機能のふりをするだけ。

### 6.1 一度だけ必要な app-op

`REQUEST_INSTALL_PACKAGES` は manifest に書いても API 26+ では app-op が未許可で始まる。許可されるまで `canRequestPackageInstalls()` は false で、インストーラは自分を「利用不可」と報告する（完了できないセッションをコミットしない）。

```bash
adb shell appops set app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES allow
```

`tools/deploy.sh` が毎回これを実行する。端末からは 設定 → アプリ → 特別なアクセス → 不明なアプリのインストール。アプリ内ショートカット（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`）は MtkSettings で解決するか **[U]**。

### 6.2 自己更新のガード

`SelfUpdateGuard` は純関数で、`SelfUpdateGuardTest` が全分岐を通している（車が要らない唯一の検証手段）。順序も検査対象 — 複数該当したとき、**利用者が最も簡単に解消できるもの**を名指しする。

| 順 | 条件 | 判定材料 |
|---|---|---|
| 1 | 画面が前面にない | ライフサイクル |
| 2 | M コンソールが開いている | `modeSwitch.isMOpen` |
| 3 | resume カウントダウン中 | `resumeTarget != null` |
| 4 | アプリ一覧が開いている | `appList?.isVisible` |
| 5 | K+DCAN ケーブルが挿さっている | `usb.isCableAttached` |
| 6 | DS2 が生きている | `onEngineSample` の到着時刻（**`Ds2Link` は無改造**） |
| 7 | エンジン始動中 | `tach.rpm > 0` |

ケーブルの規則は意図的に鈍い方。ケーブルが挿さっている＝誰かが車を触っている。煩わしいと分かってから緩める方が、データログを中断するより安い。

### 6.3 毒入り版

同じ versionCode で 2 回続けて「適用したのに起動しているのは別の版」になったら、その versionCode は永久に提示しない。1 回はタイミングの悪い電源断かもしれないが、2 回はビルド。ループが起きる場所が車のホーム画面なので。

---

## 7. TUNER は対象外

TUNER は APK ではない。`https://mss54hp-csl-convert-tuner.tsunagi.app/` を TWA で開いている PWA で、更新機構は service worker 側に既に在る（`docs/04` §10.12）。今回は利用者の判断でランチャー APK のみを対象にした。

`deploy.sh` の「盲目タップで SW を温める → Chrome を force-stop して waiting worker を起動」は従来どおり残っている。

将来統合するなら `kind: "web"` エントリと `killBackgroundProcesses("com.android.chrome")` で実現できるが、**ランチャーは更新が反映されたと主張してはならない** — その API は `void` を返し、foreground service を持つ Chrome は生き残る。SW の `waiting → active` は Chrome の領分。

---

## 8. 削除（HOME の返上）

`Relinquish` + `RemoveLayer`。更新ペインの下部（版数行）の**長押し**で入る — 意図的に発見しにくくしてある。`dumpMatrix` が表示窓の長押しなのと同じ流儀。

1. 全画面警告 + 復旧 2 行を常時表示
2. `pm.clearPackagePreferredActivities(packageName)` — **自パッケージの記録を消すのに権限は要らない**。`PackageManagerService` は `pkg.applicationInfo.uid == Binder.getCallingUid()` のとき `SET_PREFERRED_APPLICATIONS` の検査を短絡する。これは `cmd package set-home-activity` が書いた記録そのもの
3. `ACTION_HOME_SETTINGS`、無ければ HOME intent でリゾルバのチューザ。文言は逐語で「**「ランチャー」(com.android.launcher) を選び、「常時」をタップ**」
4. `Relinquish.verify()` — 自分が preferred で**ない**こと、**かつ** `com.android.launcher` が preferred で**ある**こと。前者だけでは「HOME が無い端末」も該当してしまう
5. そこまで通って初めて自己削除を提示

`com.android.launcher` が入っていなければ、そもそも手順を始めない。

復旧は常時画面に出ている:

```
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
adb uninstall app.tsunagi.e46m3.launcher
```

段 2–4 はこのベンダービルドでは **[U]**。ADB を繋ぎ復旧コマンドを別ターミナルに打ち込んだ状態で予行すること。

---

## 9. ロールバック

**第一の手段は「戻す」ではなく「前に進める」。** v3 が駄目なら v2 のソースを **v4 として publish** する（`tools/release.sh --bump patch`）。`versionCode` は単調増加のまま、downgrade フラグも要らず、経路は通常更新と同一で既に実証済みのものになる。HOME アプリでは追加リリース 1 回分の手間に十分見合う。

**第二の手段**は端末内ロールバック。インストール直前に `applicationInfo.sourceDir`（＝自分の APK、権限不要で読める）を `ota/prev/` にコピーしておき、`Downgrade`（`setAllowDowngrade` / `installFlags` へのリフレクション）が使えるときだけ ROLLBACK ボタンを出す。使えないプラットフォームではボタンを出さない — 押せば必ず失敗する控えは「動作できない操作」。

API 27 は hidden API 制限（API 28 導入）より前なのでリフレクションは合法。それでも**実機確認が要る** [U]: `PackageInstallerService` が非 shell 呼び出し元に対してフラグを落とす可能性がある。駄目なら `Downgrade.kt` を 1 コミットで削除する。

> ブリーフ段階では `Build.IS_DEBUGGABLE`（この端末は `ro.debuggable=1`）を根拠にしていたが、**`IS_DEBUGGABLE` による downgrade 許可は API 29 の追加**で API 27 には無い。結論（試す価値はある／実機確認必須）は変わらないが根拠は別。

`adb install -r -d` はどちらの場合も恒久的な脱出口。

---

## 10. UI

**既存のコンソールキーを 1 つも動かしていない。** `deploy.sh` は座標を直打ちしている（920,419 = HOME の M キー、307,339 = M の TUNER）ので、キーの追加や移動は deploy を黙って壊す。

入口は LCD:

- 適用可能な更新が待機中 → 版数行に `UPDATE 0.3.0`
- **表示窓をタップ → 更新ペイン**（常時。長押しは従来どおり `dumpMatrix`）

これでホームモードから 1 タップで到達でき、ジオメトリ変更ゼロ、DIAG(2,0)/整備履歴(3,0) の予約枠も温存。M コンソールへのキー追加は後から `ConsoleKeys.M_MODE` に 1 行足すだけででき、`(4,0)` なら予約枠を消費しない。

文字幅は実測して決めてある: 版数行の `dot=2/gap=1`（pitch 3）で `"UPDATE 0.2.0"` は 171px。LCD の内容枠は 440 − 2×18 = 404px、日付 `"08.09 SAT"` が左から約 132px を占めるので残りは約 272px。`"READY"` を足すと約 281px で衝突するため**付けていない**。

ペインが守る規則（`docs/04` §9 由来）:

- **捏造しない。** 経過時間は `elapsedRealtime` 由来なので起動ごとにリセットされる。跨いだ古い値を出す代わりに `NOT CHECKED THIS BOOT` と言う
- **動作できない操作を出さない。** グレーアウトではなく非表示にし、その場所に理由を出す（`CABLE IN` / `CLOCK WRONG` / `METERED` / app-op 未許可）。コンソールが死んだキーを出さずソケットを塞ぐのと同じ
- `AppListLayer` と同型: 透明ルート + `console.visibility = INVISIBLE`、160ms `m3fade`、`d_al_close` 再利用、新フォント・新依存・新色なし、px 指定

**HOME を失ったとき**だけは別で、`lost_home.xml` は不透明・赤見出し・**等幅・選択可能**。ダッシュボードからキーボードへコマンドを転記ミスなく運ぶことだけが仕事なので、意匠より可読性を採る。消せない。通知にも同じコマンドを出す（画面自体に到達できない可能性があるため）。

---

## 11. 検証

### オフ車両で検証済 [V]

| 何を | どう |
|---|---|
| 署名検証（改竄・別鍵・stale serial・不正 JSON・未知 kind・SDK 範囲） | `ManifestVerifierTest` 15 件。毎回 RSA-2048 鍵ペアを生成する |
| 自己更新ガードの全分岐と**優先順位** | `SelfUpdateGuardTest` 12 件 |
| テストが実際に落ちること | 署名検査と serial 検査を無効化 → 6 件 FAILED、復帰後 63 件 PASS |
| GATE-1 の 5 パターン（正常 / versionCode 違い / versionName 違い / HOME フィルタ無し / 存在しない activity） | 実 APK に対して実行 |
| GATE-3 が内容で照合すること | `res/j5.der` にリネームされた実 APK で検出 |
| GATE-4 が改竄を検出すること | JSON の 1 文字を書き換え → 検証失敗、無改変 → 成功 |
| マニフェスト生成・署名・全ローカルゲート | `tools/release.sh --dry-run` 通過 |

### 実機で要検証 [U]

1. 非 shell 呼び出し元の `PackageInstaller` セッションがこのベンダービルドで動くか（このアプリは何かをインストールしたことが一度も無い）
2. `INSTALL_ALLOW_DOWNGRADE` が O-MR1 のサニタイズを生き延びるか（§9）
3. `ACTION_MANAGE_UNKNOWN_APP_SOURCES` / `ACTION_HOME_SETTINGS` が MtkSettings で解決するか
4. ガレージ WiFi で `NET_CAPABILITY_VALIDATED` が立つか（§5.3）
5. GitHub のアセットホストが `latest/download` のリダイレクト越しに `Range` を尊重するか — **GATE-5a が毎リリース検査するので黙って腐ることはない**
6. **同一署名 in-place 置換で preferred HOME 記録が保持されるか。** AOSP は保持すると言っている。本設計最大の賭けであり、だからこそ GATE-1・`HomeGuard`・バナー・通知・印字される ADB 行が互いに独立して存在する
7. `getPackageArchiveInfo(GET_SIGNATURES)` が AGP 8.7 既定の署名出力に対して非 null を返すか（GATE-2 の v1/v2 が PC 側の証明、端末側の「null はハード失敗」が backstop）
8. **NTP が RTC を実際に補正するか** = WiFi 起動後に `ACTION_TIME_CHANGED` が本当に飛ぶか。飛ばないなら延期したチェックはキーサイクル内に再開せず、平文 HTTP ミラー（自前ホスト + 独自署名で完全性は担保済み、`network_security_config` でそのホストのみ cleartext 許可）が任意から必須に格上げされる
9. 削除フローの段 2–4（§8）

### 実機手順

```bash
# 0. ブートストラップ（従来どおり。app-op もここで付く）
tools/deploy.sh

# 1. 版数が載っているか（デバイス上で dumpsys を grep にパイプすると Broken pipe）
adb shell "dumpsys package app.tsunagi.e46m3.launcher > /sdcard/p.txt"
adb shell "grep -E 'versionCode|versionName' /sdcard/p.txt"

# 2. VALIDATED が立つ世界かどうか（一度だけ見ておく）
adb shell "dumpsys connectivity > /sdcard/c.txt"; adb shell "grep -i valid /sdcard/c.txt"

# 3. チェックが走ったか
adb logcat -d | grep -E "^.*Ota\b"

# 4. 落ちてきた APK が publish したものと同じか
adb pull /data/data/app.tsunagi.e46m3.launcher/files/ota/ /tmp/ota-pull
sha256sum /tmp/ota-pull/*.apk        # マニフェストの sha256 と一致すること

# 5. 自己更新 ★ 先にこれを別ターミナルに打ち込んでおく
adb shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity

# 6. 適用後、HOME が戻っているか
adb shell "dumpsys package app.tsunagi.e46m3.launcher > /sdcard/p.txt"
adb shell "grep -A 4 'Preferred Activities' /sdcard/p.txt"

# HOME を失った場合は logcat に固有タグが出る
adb logcat -d | grep OTA_LOST_HOME
```

HOME フィルタを落とした APK での復旧バナー検証は**車ではなくエミュレータで**。GATE-1 が在るので車では起こり得ない。

---

## 12. 鍵の管理

| 鍵 | 置き場所 | 用途 |
|---|---|---|
| APK 署名鍵 | `app-launcher/keystore.properties` が指すリポジトリ外のキーストア | APK 署名。SHA-256 `8e529141…bcfdb580` は TUNER の `assetlinks.json` に焼かれているので**回せない**（回すと TWA 検証が黙って壊れる） |
| OTA マニフェスト署名鍵 | `~/.e46m3/ota-rsa2048-private.pem`（`tools/ota-keygen.sh` が生成、上書き拒否） | マニフェスト署名。公開側は `tools/ota_public_key.pem` と `app/src/main/res/raw/ota_public_key.der` としてコミット |

**OTA 秘密鍵を失うと、現場の端末は新しいマニフェストを一切受け付けなくなり、ADB での再導入以外に道が無くなる。** このマシン以外にバックアップを取ること。

`.gitignore` は `*-private.pem` / `*private-key*.pem` を弾く。**公開側は弾かない** — それが入っていないと誰も何も検証できない。

---

## 13. 今後

- 実機検証 §11 の [U] を潰す
- M コンソールに UPDATE キーを足すか決める（`(4,0)` なら予約枠を消費しない）
- DIAG が出たら `packages[]` に 2 件目を足すだけ。`signerSha256` がエントリごとなので別鍵でもコード変更不要
- Device Owner 版を検討するなら §2.3 のアカウント削除から
- R8 を有効にするとき、`Downgrade` がリフレクトする `SessionParams` メンバに `-keep` が要る
