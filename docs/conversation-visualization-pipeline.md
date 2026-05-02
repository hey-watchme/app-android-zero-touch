# Conversation Visualization Pipeline

更新日: `2026-05-03`
状態: `draft`
位置づけ: Home / Dashboard をまたぐ **会話可視化パイプラインの正本**

この文書は、ZeroTouch における

- `Card がどんどんできる`
- `Card が Topic にまとまる`
- `Topic が Fact / Wiki へ流れる`

という最重要パイプラインを、ゼロベースで再定義するための設計文書である。

Live Transcript / Translation のような presentation 機能はここでは従属扱いにする。
本体はあくまで **Conversation pipeline** である。

---

## 1. 目的

過去の実装では、録音、リアルタイム表示、upload、ASR、Topic 化、UI 反映が
複数の状態源にまたがっており、

- 何が source of truth なのか分かりにくい
- 画面遷移しないと反映されない
- processing のトリガーが UI refresh に依存して見える
- Live 表示と本体 pipeline の責務が混ざる

という問題が起きていた。

この文書の目的は、会話可視化に必要な責務を最小構成へ戻し、
**状態遷移、トリガー、表示契約を明文化すること** である。

---

## 2. 結論

Home の `Conversation` は、次の 3 レイヤーだけで構成する。

1. `Capture`
   Android が現在録音している会話断片
2. `Card`
   録音完了後に backend へ保存された発話単位
3. `Topic`
   複数 Card を束ねた会話区間

そして、パイプラインは常に次の順で進む。

```text
Speech detected
  -> local capture indicator
  -> recording finalized
  -> Card created
  -> Card transcribed
  -> Card assigned to active Topic
  -> Topic finalized
  -> Fact
  -> Wiki
```

重要:

- `Live Transcript / Translation` はこの pipeline を置き換えない
- `Conversation` は backend に保存される Card / Topic の進行を見せる
- UI は backend の処理を mutate しない
- UI refresh は表示更新だけを担い、処理の開始条件にならない

---

## 3. 用語

### 3.1 Capture

Android 端末上で、まだ backend に保存されていない録音中の断片。

- source of truth: Android memory
- 役割: 「今話している」ことを即時表示する
- 永続 ID: まだ持たない

### 3.2 Card

`zerotouch_sessions` に保存される発話単位。

- source of truth: Supabase `zerotouch_sessions`
- 1 録音セッション = 1 Card
- status を持つ
- transcription が入る

### 3.3 Topic

複数 Card を束ねた会話区間。

- source of truth: Supabase `zerotouch_conversation_topics`
- active -> finalized のライフサイクルを持つ
- title / summary / description を持つ

### 3.4 Fact / Wiki

Topic finalize 後の知識化レイヤー。

- Fact: Topic から抽出された構造化知識
- Wiki: 長期記憶として統合されたページ

---

## 4. 非目標

この文書では次を扱わない。

- Share / QR / Take-Home ページの UX 詳細
- Action Candidate / Connector の設計詳細
- LLM プロンプトの詳細最適化
- VAD アルゴリズム改善の詳細

---

## 5. Source Of Truth

会話可視化で使う状態源は明示的に 3 つだけに制限する。

### 5.1 Android local state

対象:

- `isRecording`
- `speech`
- `recordingElapsedMs`
- local pending recordings

用途:

- 録音中インジケータ
- backend 保存前の temporary placeholder

### 5.2 `zerotouch_sessions`

対象:

- `pending`
- `uploaded`
- `transcribing`
- `transcribed`
- `failed`

用途:

- Card の本体
- processing 状態の本体

### 5.3 `zerotouch_conversation_topics`

対象:

- `active`
- `finalized`
- title / summary / utterances

用途:

- Conversation のまとまり
- Home / Dashboard の主表示対象

禁止:

- UI 専用の擬似 Topic を長期的に source of truth にしない
- 複数の state を join した結果を再び mutate しない

---

## 6. 状態遷移

### 6.1 Capture state

```text
idle
  -> speech_detected
  -> recording
  -> finalized_local
  -> cleared
```

これは Android のみが持つ一時状態であり、backend には保存しない。

### 6.2 Card state

```text
pending
  -> uploaded
  -> transcribing
  -> transcribed
  -> failed
```

補足:

- `pending`: local finalize 済み、まだ upload 応答待ち
- `uploaded`: backend 保存済み、ASR 開始待ち
- `transcribing`: ASR 実行中
- `transcribed`: transcription 済み、Topic へ紐付け可能
- `failed`: upload / ASR / topic assign のいずれかで失敗

### 6.3 Topic state

```text
none
  -> active
  -> finalized
```

Topic には `processing` のような UI 専用 status を持ち込まない。
processing 中に見せたいものは Card 側の状態として表示する。

---

## 7. トリガー設計

この文書で最も重要なポイント。

### 7.1 原則

- backend 処理の開始は **録音イベント** または **backend 内イベント** のみで発火する
- 画面遷移、タブ切り替え、pull-to-refresh は **一切トリガーにならない**

### 7.2 正しいトリガー

1. `speech_detected`
   - Android が local placeholder を出す
2. `recording finalized`
   - Android が local pending Card を作る
   - Android が `/api/upload` を呼ぶ
3. `upload completed`
   - backend session id を local pending Card に紐付ける
   - Android が `/api/transcribe/{session_id}` を呼ぶ
4. `transcription completed`
   - backend が Card を `transcribed` に更新
   - backend が active Topic へ assign
5. `topic assignment completed`
   - `/api/topics` に反映
6. `topic idle timeout or ambient off`
   - backend / explicit trigger で Topic finalize
7. `topic finalized`
   - Fact / Wiki pipeline 開始

### 7.3 誤ったトリガー

次は禁止する。

- `loadSessions()` で processing を開始する
- `refreshSessions()` で finalize を起こす
- 画面遷移でしか local state が反映されない設計
- UI 側が「多分 transcribing のはず」と optimistic に状態遷移し続ける設計

optimistic UI は許可するが、source of truth の代替にはしない。

---

## 8. Home Conversation の表示契約

`Conversation` 列は、次の順に表示する。

### 8.1 録音中

表示対象:

- local capture placeholder

表示内容:

- `Listening`
- 経過秒数
- `Recording...`

まだ Card ではないので、Topic のふりをしない。

### 8.2 録音終了後、Topic 未反映

表示対象:

- pending Card rows (`pending/uploaded/transcribing/transcribed`)

表示内容:

- 各 Card の処理状態
- 必要なら transcription preview

ここが「以前は動いていた processing 表示」に相当する。
Topic がまだ見えなくても、Card の列として見える必要がある。

### 8.3 Topic 反映後

表示対象:

- backend から取得した `active` / `finalized` Topic

表示内容:

- title
- summary
- updated time
- utterance count

### 8.4 優先順位

同一会話に対しては次の優先順位で 1 回だけ表示する。

1. local capture
2. pending Card
3. active / finalized Topic

同一データが二重に見えないことを invariant にする。

---

## 9. シンプルな実装方針

Home `Conversation` を次の 2 配列だけで組み立てる。

1. `localConversationItems`
   - Android local state から生成
   - capture / pending card を表す
2. `remoteConversationItems`
   - backend `sessions + topics` から生成
   - transcribed card / active topic / finalized topic を表す

最終表示は:

```text
displayConversationItems =
  dedupe(localConversationItems + remoteConversationItems)
  .sortedBy(updatedAt desc)
```

ただし `dedupe` は見た目のためではなく、

- local recording id
- session id
- topic id

のいずれかで同一性を厳密に判定する。

---

## 10. Invariants

この設計が守るべき不変条件。

1. 話し始めたら 1 秒以内に Home へ local placeholder が出る
2. 録音終了後、placeholder は pending Card に置き換わる
3. pending Card は upload / transcribe のどこで止まっているかが分かる
4. Topic 生成後は、同じ会話が pending Card と Topic の両方で二重表示されない
5. 画面遷移しなくても UI は更新される
6. `refresh` は表示同期であり、処理トリガーではない
7. Topic finalize は UI 読み込みに依存しない

---

## 11. エラー設計

各段階で失敗点を明示する。

### 11.1 Upload failure

表示:

- `Upload failed`
- retry available

### 11.2 Transcribe failure

表示:

- `Transcription failed`
- provider / error summary
- retry available

### 11.3 Topic assignment failure

表示:

- `Topic assignment failed`
- Card は残る
- Topic には出なくても Card 列では追える

重要:

- 失敗しても会話は消さない
- `Conversation に出ない = 処理されていない` を避ける

---

## 12. Observability

最低限、次のログが必要。

### Android

- `speech_detected`
- `recording_started`
- `recording_finalized`
- `upload_started`
- `upload_completed`
- `transcribe_started`
- `transcribe_trigger_failed`
- `processing_monitor_tick`

### Backend

- `session_created`
- `transcription_started`
- `transcription_completed`
- `topic_assigned`
- `topic_finalize_started`
- `topic_finalized`
- `fact_started`
- `wiki_ingest_started`

運用では、1 会話を `localRecordingId -> session_id -> topic_id` で追えることを必須にする。

---

## 13. 実装順

この文書の後は、次の順でやる。

1. `Conversation` 表示モデルを `Capture / Card / Topic` に再定義
2. Home 右カラムをそのモデルだけで描画
3. local placeholder -> pending Card -> Topic の切り替えを実装
4. pending Card の失敗表示と retry を実装
5. Fact / Wiki 反映の downstream 状態を補助表示する

---

## 14. 実装上の既知制約

Conversation パイプラインを再稼働させる際に必ず確認する事項。

### 14.1 DB migration の適用確認

`015_add_session_upload_idempotency.sql` が Supabase に適用済みか確認する。

- `zerotouch_sessions.local_recording_id` カラムと `(device_id, local_recording_id)` の unique index が対象
- これが未適用でも upload 自体は動くが、upload idempotency（重複送信防止）が成立しない
- Android 側は録音ごとに `localRecordingId` を生成して `/api/upload` に送信している

### 14.2 Topic finalize scheduler の運用注意

本番では `TOPIC_FINALIZE_SCHEDULER_ENABLED=true` を **1 process のみ** に設定する。

- scheduler は FastAPI process 内 thread で動く
- 複数 worker（`uvicorn -w 4` 等）では worker 数だけ scheduler が立ち、同一 device / topic に対して重複 finalize が走る
- 重複実行は LLM 課金と DB 整合性の両方でリスクになる
- 将来的には Lambda / EventBridge への外部化が本命（`roadmap.md` 参照）

### 14.3 VAD の実装状態

- Silero VAD が稼働中（メインの音声検知）
- WebRTC VAD は**未実装**。設定で選択しても Silero にフォールバックする

### 14.4 Failed card の retry UI

- API 層のリトライ（IOException / 408 / 429 / 5xx）は実装済み
- 最終的に `failed` になった Card を UI から再送する導線は**未実装**
- 必要な導線:
  - upload 済み → `retry transcribe`
  - upload 前 → ローカルファイルが残っている場合のみ再 upload

---

## 15. ドキュメント上の扱い

以後、会話可視化についてはこの文書を正本にする。

他文書との関係:

- プロダクト全体方針: `conversation-action-platform.md`
- Wiki / 長期記憶: `knowledge-pipeline-v2.md`
- Live presentation: `live-support.md`
- 将来の TODO: `roadmap.md`

もし handoff 文書や README にこの文書と矛盾する記述があれば、
**この文書を優先して修正する**。
