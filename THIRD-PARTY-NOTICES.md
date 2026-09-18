# Third-party notices / 第三者に関する表示

`LICENSE` covers the code written for this project. Not everything in this
repository was written for this project, and this file says which parts and on
what terms.

`LICENSE`（MIT）が適用されるのは、このプロジェクトのために書かれたコードです。リポジトリの中身はそれだけではないので、どの部分がどういう扱いなのかをここに書きます。

---

## 1. This project's own code — MIT

Everything under `app-launcher/app/src/`, `tools/` and `docs/` **except** the
items named in §3 below.

§3 に挙げたもの**以外**の `app-launcher/app/src/`、`tools/`、`docs/` の全て。

---

## 2. Dependencies

Pulled at build time, not vendored into this repository.

ビルド時に取得されるもので、このリポジトリには同梱していません。

| Component | Version | Licence |
|---|---|---|
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Apache-2.0 |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Apache-2.0 |
| `androidx.browser:browser` | 1.8.0 | Apache-2.0 |
| [`com.github.mik3y:usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) | 3.9.0 | MIT |
| `junit:junit` (tests only) | 4.13.2 | EPL-1.0 |
| `org.json:json` (tests only) | 20240303 | Public Domain |
| Gradle wrapper (`gradle-wrapper.jar`) | 8.9 | Apache-2.0 |

The two test-only entries ship in nothing: they exist so that the OTA signature
verifier and the self-update guard can be proven off-vehicle.

テスト専用の 2 つは配布物に一切入りません。OTA の署名検証と自己更新ガードを実車なしで検証するために存在します。

---

## 3. Vendor-derived material — **no rights claimed**

### 3.1 Recovered binder interfaces

| File | Origin |
|---|---|
| `app-launcher/app/src/main/java/com/ts/main/common/ITsCommon.java` | Decompiled from `com.ts.MainUI` (the head unit's vendor firmware) with jadx |
| `app-launcher/app/src/main/java/com/ts/can/carinfo/ICarInfoService.java` | Same |

These are AIDL-shaped interface declarations recovered from the head unit's own
pre-installed software. They are **not** this project's work, they carry their
original vendor's copyright, and they are included here **solely so that this
launcher can talk to software that is already on the device** — the radio, the
Bluetooth stack, the CAN service and the outside-air temperature all live behind
them. They are declarations, not implementations: nothing of the vendor's logic
is reproduced.

これらはヘッドユニットに元から入っているソフトウェアから復元した、AIDL 相当のインターフェース宣言です。**このプロジェクトの著作物ではなく**、元のベンダーの著作権下にあります。**端末に既に存在するソフトウェアと会話するためだけ**に含めています — ラジオ、Bluetooth、CAN サービス、外気温はいずれもこの向こう側にあります。含まれるのは宣言だけで、ベンダーの実装ロジックは再現していません。

**The MIT licence in `LICENSE` does not extend to these two files.** If you
redistribute this repository, you are redistributing them too, and that is your
decision to make.

**`LICENSE` の MIT はこの 2 ファイルには及びません。** リポジトリを再配布するならこれらも一緒に配ることになり、その判断は再配布する側のものです。

### 3.2 Analysis notes

`docs/03-mainui-recon.md` describes what was found inside `com.ts.MainUI` and
quotes small fragments of it. Same standing as §3.1: observations about somebody
else's software, recorded so the conclusions can be checked rather than trusted.

`docs/03-mainui-recon.md` は `com.ts.MainUI` の中身についての記録で、断片を引用しています。位置づけは §3.1 と同じです。他人のソフトウェアについての観察であり、結論を信用ではなく検証できるように残してあります。

### 3.3 Provenance, still open

`docs/04-launcher-design.md` §11 records that the **DIAG** feature is not
shipped, pending a provenance decision about material it would need. That
decision has not been made, and nothing relating to it is in this repository.
This section exists so the question is visible rather than forgotten.

`docs/04-launcher-design.md` §11 に、**DIAG** が未配信であり、それが必要とする素材の出所判断が先である旨が記録されています。その判断はまだ下されておらず、関連するものはこのリポジトリに入っていません。この節は、その問いが忘れられずに見えているようにするために置いてあります。

### 3.4 Not distributed at all

`device-extract/` — the head unit's own system properties, bug report, `/sdcard`
contents and 23 vendor APKs — is **excluded by `.gitignore` and has never been
committed**. It is roughly 126 MB of somebody else's firmware and one owner's
personal storage. The documents cite it as provenance; nothing in the build,
the tests or the release scripts reads it.

`device-extract/`（ヘッドユニットのシステムプロパティ、bugreport、`/sdcard` の中身、ベンダー APK 23 本）は **`.gitignore` で除外されており、一度もコミットされていません**。約 126MB の他人のファームウェアと、所有者個人のストレージだからです。ドキュメントは根拠として引用していますが、ビルドもテストもリリーススクリプトも読みません。

---

## 4. Trademarks

This project is **not affiliated with, endorsed by, or connected to** any of the
following, and claims no rights in their marks:

このプロジェクトは以下のいずれとも**無関係であり、承認も提携も受けていません**。それらの標章に対する権利も主張しません。

- **BMW**, **M**, the **///M** stripe motif, **E46**, **M3** — Bayerische
  Motoren Werke AG. This is an aftermarket project for a privately owned car,
  and the console is styled after that car's own switchgear.
- **EONON** — the retailer the head unit used in development was bought from.
  Named only to describe which hardware this was tested on. See the README on
  why the device's own identifiers (`FFKJ` / `FF-5000`) are what actually
  matter.
- **Android**, **Google**, **Chrome**, **Android Auto** — Google LLC.
- **Apple**, **CarPlay** — Apple Inc.
- **MediaTek**, **AutoChips** — their respective owners.

Marks appear in this repository for identification and interoperability: to say
which car, which head unit and which software this runs with. They are not used
as branding for the project itself.

標章は識別と相互運用のために出てきます — どの車の、どのヘッドユニットの、どのソフトウェアと動くのかを述べるためです。プロジェクト自身のブランドとして使ってはいません。

---

## 5. Signing keys

Neither the APK signing key nor the OTA manifest signing key is in this
repository, and neither has ever been committed. The **public** halves are:

APK 署名鍵も OTA マニフェスト署名鍵も、このリポジトリには入っておらず、一度もコミットされていません。**公開側**は次のとおりです。

- `tools/ota_public_key.pem` and `app-launcher/app/src/main/res/raw/ota_public_key.der`
  — the same RSA-2048 public key in two encodings. Committed on purpose:
  without it no device can verify anything.
- The APK signing certificate's SHA-256 is published in the README so a
  downloaded release can be checked against it.

A build made from a fork will be signed with a different key and will therefore
**not** be accepted as an update by a device running the published binaries, and
vice versa. That is the intended behaviour, not a limitation to work around.

fork からのビルドは別の鍵で署名されるため、配布バイナリを入れた端末は更新として**受け付けません**。逆も同様です。これは回避すべき制限ではなく、意図した挙動です。
