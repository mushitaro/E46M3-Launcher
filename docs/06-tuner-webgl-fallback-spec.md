# TUNER 側 実装指示書 — WebGL が無いヘッドユニットでの 3D マップ

**日付**: 2026-08-09
**相手**: `E46M3CSL_TuningTool`（`mss54hp-csl-convert-tuner.tsunagi.app`）
**ランチャー側**: 変更なし。これは TUNER 内部だけの話

証拠ラベル: **[V]** 実機／コードで確認　**[I]** 推論　**[U]** 未確認

---

## 1. 症状

車載機（FF-5000 / MT8227L, Android 8.1, Chrome の TWA）で GRAPH ペインが白くなり:

```
WebGL is not supported by your browser - visit https://get.webgl.org for more info
```

この文言は plotly.js 自身の `showNoWebGlMsg` **[V]**。ブラウザのエラーでもアプリのエラーでもなく、**plotly が WebGL トレースを描けなかったときに出す代替表示**。

---

## 2. WebGL を使っているのは1箇所だけ

| 場所 | trace | WebGL |
|---|---|---|
| `MapVisualizer.tsx` | `type: 'surface'` | **要る** |
| `LogTimeSeriesChart.tsx` | `type: 'scatter'`（コメントにも "SVG, for hover/click precision"） | 要らない **[V]** |

つまり**時系列ログのグラフは無関係で、落ちているのは 3D マップだけ**。

### なぜ「GRAPH」タブで出るのか

狭幅レイアウトの MAP / GRAPH / DASH は**ペインの切替**であって「時系列グラフ」という意味ではない。GRAPH は右ペイン（Visualization & Inputs）で、その中身は `activeTab` で決まる **[V]**:

- `activeTab` が current / new / diff / lambda / warmup / wot → `MapVisualizer`（3D・WebGL）
- `activeTab` が log → `LogTimeSeriesChart`（SVG）

写真はマップ系タブを選んだ状態なので、GRAPH に 3D が入って落ちた。ログを開いていれば同じ GRAPH タブでも正常に描けるはず **[I]**。

---

## 3. 直し方 — 3D を諦めるのではなく、2D に落とす

`surface` の z グリッドは、そのまま `heatmap` に渡せる。同じ配列・同じカラースケールで、**WebGL を一切使わない**（plotly の `heatmap` は 2D canvas を SVG に貼る実装。WebGL 版の `heatmapgl` は plotly 3 で削除済みなので**使わないこと**）。

そして 1024×600 のダッシュ画面では、指でぐるぐる回すタービンより **2D ヒートマップの方が読みやすい** **[I]**。左ペインの `MapEditor` が同じ格子を数値で出しているので、色と数字が1対1で並ぶ。

### 3.1 検出

```ts
// module scope。呼ぶたびに canvas を作らないこと
let webglOk: boolean | null = null;
export const hasWebGL = () => {
    if (webglOk !== null) return webglOk;
    try {
        const c = document.createElement('canvas');
        webglOk = !!(c.getContext('webgl') || c.getContext('experimental-webgl'));
    } catch { webglOk = false; }
    return webglOk;
};
```

SSR で走らせないこと。`MapVisualizer` は既に `dynamic(..., { ssr: false })` なので置き場所はそこで良い。

### 3.2 trace

```ts
const data: Data[] = useMemo(() => [
    hasWebGL()
        ? { type: 'surface', z: mapData.data, x: indexX, y: indexY, colorscale, ...cmid, showscale: false }
        : { type: 'heatmap', z: mapData.data, x: indexX, y: indexY, colorscale, ...cmid,
            showscale: false,
            zsmooth: false },   // セル単位で読むマップなので補間しない
    ...
```

`cmid` は heatmap でも効く。`SCALE_DEVIATION` / `SCALE_MAGNITUDE` も共通で使える。

### 3.3 layout — ここが唯一の落とし穴

3D の軸設定は `layout.scene.{x,y,z}axis` の下にある。2D では `layout.{x,y}axis` に**移す**。`scene` の下に置いたままだと plotly は黙って無視し、軸が index の 0..n-1 のまま出る。

`tickmode: 'array'` / `tickvals` / `ticktext` はそのまま流用でよい（間引きの `thin()` も含めて）。

**そして `yaxis: { autorange: 'reversed' }` を付けること。**

`MapEditor` は `mapData.yAxis` を自然順で上から描いている **[V]**（`yAxis.map((load, rowIdx) => ...)`）。一方 plotly の heatmap は index 0 を**下**に置く。反転しないと、**左ペインと右ペインで負荷軸の上下が逆さまになる** — 同じ画面に並べる以上これは事故になる。3D では視点を回せたので問題にならなかった。

`zaxis` に相当するものは 2D には無い。`zAxisLabel`（"RF %" / "Diff %" / "Lambda"）はカラーバーか、ペインのどこかに小さく出す。`showscale: false` を外してカラーバーを出すなら `colorbar: { title: { text: zAxisLabel } }`。

### 3.4 3D 専用の仕掛けは 2D では不要

- `cameraNonce` / リセットボタン / `scene.camera` — 回す視点が無いので出す意味が無い
- `ChartLoading` の二段 `requestAnimationFrame` — 366,561 頂点を組む前にプレースホルダを塗るための仕掛け。20×24 のヒートマップには要らない（残しても害は無い）
- `touch-pan-y` は **2D でも残すこと**。plotly の dragmode が縦スワイプを取ってペインをスクロールできなくする

---

## 4. 車載機側でも一応やってみること（TUNER 側の作業ではない）

MT8227L の GPU 自体は Android の UI 合成を GLES でこなしているので、**ハードが WebGL を出せないとは限らない** **[I]**。Chrome が GPU をブロックリストで弾いているだけの可能性がある。車で 2 分:

1. Chrome を開いて `chrome://gpu` — "WebGL: Disabled" の**理由**が書いてある
2. `chrome://flags` → **Override software rendering list**（`#ignore-gpu-blocklist`）を Enabled → Relaunch
3. もう一度 `chrome://gpu`

TWA は Chrome 本体の上で動くので、ここで有効になれば TUNER にもそのまま効く **[I]**。

ただし**これは代替案にならない**:

- Android の Chrome には SwiftShader（ソフトウェア WebGL）が入っていない。ドライバ側が本当に出せないなら、フラグでは何も変わらない **[I]**
- フラグは端末に置いた設定でしかなく、Chrome のデータを消せば戻る
- 通ったとしても、この GPU で 3D サーフェスが実用速度で回る保証は無い **[U]**

**§3 は、フラグが通っても通っても通らなくても入れる価値がある。** 通れば 3D、通らなければ 2D、どちらでも画面が成立する。

---

## 5. 確認

- **PC**（WebGL あり）: 今までどおり 3D。回転もリセットも変化なし
- **PC で WebGL を切る**: `chrome://flags` → `#disable-webgl`、または DevTools の Rendering から。2D ヒートマップが出て、**負荷軸の向きが左の格子と一致**していること
- **車載機**: マップ系タブ → GRAPH。白いエラー箱ではなくヒートマップ
- diff / lambda（`scale="deviation"`）で中心色が正しく「変化なし」に乗っていること
