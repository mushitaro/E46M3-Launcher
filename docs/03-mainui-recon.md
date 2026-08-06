# Phase 0 — 逆コンパイル偵察の結果

**日付**: 2026-08-03
**対象**: `device-extract/apks/Launcher_8227LTsLauncher2.apk`（424ファイル）、`device-extract/apks/MainUI.apk`（2550ファイル）
**ツール**: jadx 1.5.6
**出力**: `device-extract/decompiled/{oem-launcher,mainui}/`（gitignore 済み）

証拠ラベル: **[V]** 逆コンパイル済みソースで直接確認　**[I]** 推論　**[U]** 未確認

---

## 1. ★ 車両データの binder API が判明 — `ITsCommon`

**`com.ts.main.common.ITsCommon`**（55トランザクション）。純正ランチャー `com.android.launcher`（**uid 10015 = ごく普通のアプリ**）がこれを使っている **[V]** ため、**system 権限も root も不要**であることが証明された。

### 使えるメソッド（車両関連の抜粋）

| メソッド | 戻り値 | 内容 |
|---|---|---|
| **`GetTemp()`** | `String` | **外気温**（書式は §2） |
| `GetSpeed()` | `float` | 車速 |
| `GetReverState()` | `int` | リバース状態 |
| `GetBrakeState()` | `int` | ブレーキ状態 |
| `GetCog()` | `float` | 進行方位 |
| `GetMcuPowerState()` | `int` | MCU 電源状態 |
| `nGetWorkMode()` | `int` | 現在のワークモード |
| `IsNightMode()` | `boolean` | ナイトモード |
| `GetSongName()` / `GetId3*()` / `GetFreq()` / `GetBand()` | `String` | メディア・ラジオ情報 |
| `VolInc()` / `VolDec()` / `VolSet(int)` / `Mute()` | — | 音量制御 |
| `SendMcuKey(int)` / `EnterMode(int)` / `EnterActivity(int)` | — | 画面遷移・キー送出 |
| `getSpecialBinder(String)` | `IBinder` | 追加インターフェース（§4） |

**回転数のメソッドは存在しない。** これは §3 で詳述する通り、データが無いからではなく**プロセス境界を越えて公開されていない**ため。

### bind の作法

純正ランチャー `FirstView.java` **[V]**:
```java
Intent intent = new Intent();
intent.setAction("android.intent.action.MAIN_UI");
m_Context.bindService(intent, sconn, 0);
// onServiceConnected:
m_CommService = ITsCommon.Stub.asInterface(binder);
```

**⚠️ そのまま真似してはいけない点が2つある:**

1. **暗黙 Intent での `bindService` は API 21 以降は違法**で `IllegalArgumentException` を投げる。純正が通っているのは `targetSdk=15` だから。**`setPackage("com.ts.MainUI")` の明示が必須**（`LibWorkspace.startCommService` の方はちゃんとそうしている **[V]**）
2. binder 呼び出しは**同期 IPC**。MainUI が詰まると呼び出し側がブロックする。HOME アプリでこれは画面凍結を意味するので、**必ずワーカースレッドから呼ぶ**

実装は `app-launcher/app/src/main/java/app/tsunagi/e46m3/launcher/vehicle/VehicleLink.kt`。
インターフェース定義は逆コンパイル結果をそのままコピーした `app/src/main/java/com/ts/main/common/ITsCommon.java`。**`.aidl` を書き直してはいけない** — AIDL のトランザクションコードは宣言順で決まるため、1つ順序を間違えると黙って別のメソッドを呼ぶ。コピーしたファイルはコードが `static final int` リテラルとして埋まっているので確実に一致する **[V]**。

---

## 2. `GetTemp()` の実装 — 外気温は解決

`MainUI.java:2552` **[V]**:
```java
public String GetTemp() throws RemoteException {
    mOutTemp = Can.mOutTemp;                       // CAN層の構造体
    if (mOutTemp.UpdateOnce != 0) {                // 一度でも値が来ていれば
        StrTemp = <接頭辞> + mOutTemp.Val + <単位>;
        return StrTemp;
    }
    return null;                                   // 未受信なら null
}
```

**判明した性質:**

- **`null` は「データ無し」の正規の戻り値** — エラーではない。`VehicleLink` はこれを空表示に落とす
- **文字列は接頭辞＋数値＋単位**。リソースから確認できた実際の文字列 **[V]**:
  ```
  "Out temp: %d℃"  /  "車外溫度: %d℃"  /  "车外温度: %d℃"
  単位リソース: °C / °F / ℃ / ℉
  ```
- **⚠️ `DW` フラグで摂氏/華氏が切り替わる** **[V]**。数値だけ抜くと**華氏を摂氏として表示する**危険がある。`VehicleLink.readTemp()` は単位記号を見て判定・換算する必要がある（実装済み要確認）
- 値は `int`（`%d`）なので小数は出ない

---

## 3. ★ 回転数 — 「デコードされていない」のではなく「公開されていない」

**以前の私の結論を訂正する。** `.so` のシンボル走査で `DealRpm` という*メソッド名*が BMW 系クラスに無いことから「BMW ではデコードされていない」と述べたが、**これは誤りだった**。

`CanDataInfo.CAN_Msg`（**汎用**構造体）に `Rpm` が存在し **[V]**、BMW LZ 系のビューが現に表示している **[V]**:

```java
// CanBMWLzYbxxView.java
CanJni.GetCarInfo(this.mCanMsg);                    // mCanMsg は CanDataInfo.CAN_Msg
updateItem(0, mCanMsg.Lqywd, ...);                  // 冷却水温
updateItem(1, mCanMsg.Speed, ...);                  // 車速
updateItem(2, mCanMsg.EndurOil, ...);               // 航続距離
updateItem(3, mCanMsg.Rpm, ...);                    // ★ 回転数
updateItem(4, mCanMsg.Distance, ...);               // 距離
```

つまり Rpm はブランド別ゲッターではなく**汎用の `GetCarInfo()` が返す構造体のフィールド**だった。私は BMW 接頭辞のゲッターばかり探していたので見落とした。

**しかし結論は変わらない。理由が変わるだけ:**

| 経路 | 状態 |
|---|---|
| `CanJni.GetCarInfo()` | **MainUI のプロセス内**。外部アプリから呼べない |
| `ITsCommon` | **RPM のメソッドが無い** **[V]** |
| `getSpecialBinder(String)` | Radio / Bt / Tbox_App のみ。CAN 系は返さない **[V]**（§4） |
| ブロードキャスト | CAN 系の送信はメニューキー通知のみでペイロード無し **[V]** |

→ **回転数は MainUI 内部には存在するが、プロセス境界を越えて取り出す正規の口が無い。** root 無しで vendor スタックから取るのは不可能。

**残る現実的な経路は OBD 系のみ**（プランのタスク #8 参照）:
- BT ELM327 ドングル（OBD-II PID `010C`）
- USB ホスト → 既存の FTDI K+DCAN ケーブル → DS2 で MSS54 の live block 3 offset 0（`n`, uint16, ×1, rpm）。**ユーザーの DIAG プロジェクトで検証済みの値**であり、同じブロックから冷却水温・油温・負荷・スロットルも同時に取れる

**未確認 [U]**: この個体で選択されている CAN バリアントが `Rpm` を実際に埋めているか。`CanJni.GetCanType()` が数値定数（152=Audi系, 176=BmwZmytWithCD, 276=LexusH_ZMYT 等 **[V]**）を返すが、これも MainUI プロセス内。外部から読める保存場所は見つからなかった **[V]**。

---

## 4. `getSpecialBinder(String)` — 期待外れ

`MainUI.java:2865` **[V]**:
```java
public IBinder getSpecialBinder(String name) {
    if (name.equalsIgnoreCase("Radio")) return mRadioCommon;
    if (name.equalsIgnoreCase("Bt"))    return mBTCommon;
    if (name.startsWith("Tbox") && name.equals("Tbox_App")) return mAppInterface;
    return null;
}
```
CAN / 車両データ用のインターフェースは無い。`Tbox_App` は `IAppInterface`（テレマティクス系）で車両テレメトリとは無関係。

---

## 5. ★ `mem.ini` — 以前の解釈は誤り。R-01 は取り下げ

**当初「kill 除外リストではないか」と推測したが、実際は「最後に前面にいたアプリ」の記憶であり、電源復帰時のアプリ復元に使われる。** **[V]**

`Evc.java` が読み書きし **[V]**、
```java
static final String MeM_FILE = "/mnt/sdcard/mem.ini";
public String ReadMem()  { return TsFile.readFileSdcardFile(MeM_FILE); }
public void   WriteMem(String str) { ... }
```

呼び出し側 **[V]**:
- `AmapAuto.java:534` — 任意のアプリの `onResume` でそのパッケージ名を書き込む
- `MainUI.java:1005` — 電源オフ時に `WriteMem(null)`
- `WinShow.java:379` — **電源オン時に読み出し**、ナビでも `com.ts.MainUI` でもなければ `openApplication(MemStr)` でそのアプリを復帰させる。それ以外は `BackToLauncher()`。復帰後 `WriteMem("test")` でクリア

中身が `com.android.launcher`（20バイト）だったのは、単に**最後に前面にいたのが純正ランチャーだった**というだけ。

**→ 自作ランチャーが kill される危険を示すものではない。R-01 のリスクは取り下げる。**

**ただし別の観点で注意が要る [I]**: `WinShow` は復帰対象から `com.ts.MainUI` とナビだけを除外しており、`com.android.launcher` は除外していない。ただし `getLaunchIntentForPackage()` は `CATEGORY_LAUNCHER` を要求し、純正ランチャーはそれを宣言していない **[V]** ので `null` が返り `BackToLauncher()` に落ちるはず。**自作ランチャーも意図的に `CATEGORY_LAUNCHER` を宣言していない**ので同じ経路になる。**実機での電源サイクル検証で確認すること [U]**。

---

## 6. プランへの反映

| 項目 | 変更 |
|---|---|
| **R-01**（mem.ini = kill 除外リスト） | **取り下げ**。実体は前面アプリ復元。代わりに電源復帰経路の確認項目を残す |
| **R-09**（外気温の binder 経路が未解読） | **解決**。`ITsCommon.GetTemp()`。残るは実機での動作確認のみ |
| **回転数** | 「BMW ではデコードされない」→「**内部には存在するが公開されていない**」に訂正。結論（OBD 経路が必要）は不変 |
| 新規 | `GetTemp()` の**摂氏/華氏判定**が必要。単位記号を見て換算すること |
| 新規 | `GetSpeed()` / `GetReverState()` / `GetBrakeState()` も同じ binder で取れる。将来の拡張余地 |

---

## 7. 未実施

- `CanJni.GetCanType()` の値をこの個体で確認する手段（外部から読める保存先は見つからず）
- `IAppInterface`（Tbox）の中身
- MainUI の `Settings.System` / `Global` への書き込み調査（T1.1 — binder が解決したので優先度低下）
