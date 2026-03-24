# ZeroTouch VAD改善企画

## 背景

ZeroTouch の現行アンビエント録音は、物音だけで録音が始まる誤検知が多い。
現状の `VadDetector` は「人の声かどうか」を学習済みモデルで判定しているわけではなく、
RMS と ZCR を使った閾値ベースの自作検出である。

対象実装:

- `app/src/main/java/com/example/zero_touch/audio/ambient/VadDetector.kt`
- `app/src/main/java/com/example/zero_touch/audio/ambient/AmbientRecorder.kt`

## 現状の実装

### 判定方式

`VadDetector` は以下を使って音声判定している。

- RMS: 音量
- ZCR: ゼロ交差率
- noise floor ratio: 環境ノイズに対する相対音量

発話開始条件:

- `ratio >= 2.4`
- `rms >= 700`
- `zcr in 0.02..0.25`

発話継続条件:

- `ratio >= 1.4`
- `rms >= 450`
- `zcr in 0.01..0.30`

### 録音フロー

`AmbientRecorder` は以下で動いている。

- 音声ソース: `MediaRecorder.AudioSource.MIC`
- サンプリング: `16kHz / mono / PCM16`
- フレーム: `20ms`
- 開始デバウンス: `200ms`
- プリロール: `2秒`
- 停止条件: `5秒` 無音
- 最短録音長: `3秒`

## 問題の本質

現行方式は VAD というより「音の変化検出」に近い。
そのため以下を区別しにくい。

- 机に物を置く音
- 衣擦れ
- 咳
- ドアや椅子の軋み
- タイピング

これらは RMS と ZCR の条件を満たしうるため、閾値調整だけでは根本改善が難しい。
今の調整は「誤検知を減らす」と「声の取りこぼしを減らす」のトレードオフになっている。

## 方針

### 結論

採用方針は **ハイブリッド3段構え** とする。

1. 軽量なエネルギーゲートで完全無音時の計算を抑える
2. MLベースのVADで「人の声」を判定する
3. 短時間の連続性判定で録音開始を確定する

### 推奨アーキテクチャ

#### Stage 0: Energy Gate

目的:

- 無音時に ML モデルを起動しない
- バッテリー消費を抑える

実装:

- 低域の振動ノイズを減らすため、必要に応じて Stage 0 の前段に
  `100-150Hz` 付近の HPF を入れる
- 現行の RMS / noise floor の一部を残す
- 「何か音がした」フレームだけ次段に渡す

補足:

- ZeroTouch は机上設置前提なので、低域の振動や空調ノイズを拾いやすい
- HPF は ML VAD の前処理という位置づけで使う

#### Stage 1: ML VAD

第一候補:

- **Silero VAD (TFLite もしくは ONNX Runtime)**

理由:

- 音の大きさではなく音声パターンで判定できる
- 物音への誤検知を大きく減らせる
- 16kHz モノラルとの相性が良く、現行フローに載せやすい

第二候補:

- **WebRTC VAD**

理由:

- Silero より導入が軽い
- 現行の自作VADよりは確実に改善する
- ただし非音声音の誤検知耐性は Silero に劣る

#### Stage 2: Confirmation Buffer

目的:

- 咳、くしゃみ、短い衝撃音で録音開始しない

実装:

- `speech probability` もしくは `speech=true` が `200-300ms` 続いたら開始
- 停止は `1.5-2.0秒` の連続 non-speech を基本に再設計
- UI の `speech` 表示と録音開始を分離する

補足:

- 冒頭欠け対策として、現行の `2秒` プリロールは維持する
- 確定バッファの遅延はプリロールで吸収する

## API利用案の評価

### クラウドVAD / ライブAPI

今回は非推奨。

理由:

- 常時接続が必要
- ネットワーク遅延が録音開始精度に直結する
- 音声前段を都度外部送信する設計はコストとプライバシーの負担が大きい
- 「置きっぱなし端末の常時待受」にはオンデバイスが適している

結論:

- VAD はオンデバイスで完結
- ASR は従来どおりサーバー側で実施

## 実装計画

### Phase 1: 計測基盤

目的:

- 改善前後を比較できるようにする

実装:

- VAD判定ログを追加
- `AudioSource.MIC` と `AudioSource.VOICE_RECOGNITION` を比較検証する
- 以下をフレーム単位または集約単位で保存
  - rms
  - ratio
  - zcr
  - speech判定
  - 録音開始理由
  - 録音停止理由
- AudioSource 種別
- HPF 適用有無
- 誤検知サンプルを 30-50 件収集

成功条件:

- 「どの音で録音開始したか」を後から説明できる状態になる
- `MIC` と `VOICE_RECOGNITION` の差分を実機で比較できる

### Phase 2: VAD抽象化

目的:

- 自作VADから差し替え可能な構造にする

実装:

- `VadDetector` をインターフェース化
- 実装を3種類に分離
  - `ThresholdVadDetector`
  - `WebRtcVadDetector`
  - `SileroVadDetector`
- 前処理レイヤーを分離
  - `NoOpAudioPreprocessor`
  - `HighPassAudioPreprocessor`
- 設定で切替可能にする

成功条件:

- 同じ録音フローで検出器のみ差し替え可能
- AudioSource と前処理も比較可能

### Phase 3: Silero導入

目的:

- 本命の ML VAD を導入する

実装:

- TFLite 版を優先
- 入力は 16kHz mono のまま利用
- 20ms フレームをモデル入力要件に合わせてバッファリング
- 出力を `speech probability` として扱う

成功条件:

- 物音の誤検知が現行比で大幅に減る
- 発話の取りこぼしが許容範囲に収まる

### Phase 4: 連続性チューニング

目的:

- UXを安定させる

実装:

- 開始条件:
  - `speech probability >= threshold` が `200-300ms` 続く
- 継続条件:
  - 短い non-speech は保持
- 停止条件:
  - `1.5-2.0秒` 連続 non-speech

成功条件:

- 録音開始が頻発しない
- 会話中の短い間で録音が切れない

## KPI

最低限、以下で評価する。

- 誤検知率: 物音で録音開始した割合
- 取りこぼし率: 実際に声があったのに録音されなかった割合
- 平均録音長
- 日次セッション数
- 端末のバッテリー消費
- 1時間あたりのCPU使用傾向

補助指標:

- `MIC` と `VOICE_RECOGNITION` の誤検知差分
- HPF 有無による誤検知差分

目標値の初期案:

- 誤検知率: 現行比 70%以上削減
- 取りこぼし率: 10%未満
- 平均録音長: 断片化を減らして安定化

## リスク

### Silero導入リスク

- Android 実装コストが現行VADより高い
- モデル配布サイズが増える
- 低遅延処理のための入力窓設計が必要

### 回避策

- まず抽象化レイヤーを入れる
- WebRTC VAD を暫定比較対象として並行導入する
- A/B テスト可能な形で切り替えられるようにする

## 最終提案

ZeroTouch の用途では、現行の `RMS + ZCR` を延命するより、
**Silero VAD を本命として導入し、Energy Gate + Confirmation Buffer を組み合わせる**
のが最も妥当である。

短期的には以下で進める。

1. VAD の抽象化
2. ログと評価データの収集
3. WebRTC VAD を比較用に導入
4. Silero VAD を本命実装として導入
5. 現場音で評価して閾値・連続時間を調整

この順序なら、いきなり大きく壊さずに改善できる。
