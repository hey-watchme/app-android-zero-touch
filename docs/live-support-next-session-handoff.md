# Live Support Next Session Handoff

更新日: `2026-04-29 (JST, session close v2)`
対象リポジトリ: `app/android-zero-touch`
目的: 次セッションでゼロから再開しても迷わないように、現状と起動条件を一本化する

---

## 1. 現在のステータス（事実）

- backend 最終デプロイ済みコミット: `9d04dda`  
  `chore(backend): default realtime ASR to gpt-4o-transcribe`
- backend 直近デプロイ成功 Run:
  - `25092066256` (`9d04dda`)
  - `25088087826` (`a41678f`: translate endpoint の SDK 互換修正)
  - `25087855766` (`a349e21`: realtime translation 導入)
- 本番 API ヘルス: `200 OK`（確認日時: `2026-04-29 JST`）
- 本番 OpenAPI に Live API が存在:
  - `/api/live-sessions`
  - `/api/transcribe/realtime`
  - `/api/translate/realtime`
- 本番 translate API 疎通: `200 OK`  
  `POST /api/translate/realtime` で `translated_text` を返却確認済み
- Supabase 側 migration 017 相当の Live テーブル適用済み:
  - `zerotouch_live_sessions`
  - `zerotouch_live_transcripts`
  - `zerotouch_live_keypoints`

### 1.1 Android 側の直近実装

- `a349e21` realtime translation パイプライン導入
  - `AmbientRecordingService` で句単位抽出 -> `/api/translate/realtime`
  - 英訳履歴を `SharedPreferences` へ保持
  - UI に English Translation セクション追加
- `45e70da` 翻訳投入のしきい値緩和
  - 短い完結文も翻訳に回るよう `MIN_TRANSLATION_*` を調整
- `94d6d5b` `MemoLiveHomeScreen` Live 表示レイアウト更新
  - 上 `2/3` 日本語、下 `1/3` 英語の独立スクロール
  - 下部の Listening バーを削除し、上部セクションへ統合

---

## 2. 動作に必要なもの（Androidだけでは不可）

Live / Conversation が動くには、次の3つが揃っている必要がある。

1. Android アプリ（UI + 録音 + API 呼び出し）
2. ZeroTouch backend（`/api/live-sessions`, `/api/transcribe/realtime` を提供）
3. Supabase migration（Live テーブル群）

つまり、**Android 単体デプロイでは不十分**。  
バックエンドと DB が古いと、Live Transcript は 404/失敗/停止に見える。

---

## 3. 現在の実装経路（混乱しやすい点の整理）

### Live Transcript / Translation 経路（新規）

- Android `AmbientRecordingService` が Live セッション作成:
  - `createLiveSession(...)`
- 録音チャンクごとに新規 API を呼ぶ:
  - `transcribeRealtimeChunk(...)` -> `POST /api/transcribe/realtime`
- 結果を `AmbientStatus.liveTranscriptLatest/liveTranscriptHistory` に反映し、`MeMo Live` UI に表示
- 翻訳対象句を抽出して新規 API を呼ぶ:
  - `translateRealtime(...)` -> `POST /api/translate/realtime`
- 結果を `AmbientStatus.liveTranslationLatest/liveTranslationHistory` に反映し、UI の English 枠に表示

### 既存 Conversation 経路（従来）

- 既存の録音アップロード + バッチASR（Speechmatics/Deepgram/Azure）系
- ただし現時点の実装では、`AmbientRecordingService` で  
  `ENABLE_LEGACY_BATCH_PIPELINE = false`  
  が入っており、Live焦点のため既存経路は一時的に停止中

### 重要: 今回の不具合と修正

- 不具合: `POST /api/translate/realtime` が `500`  
  `OpenAI object has no attribute responses`
- 原因: `openai==1.59.4` 環境で `client.responses.create(...)` を使用
- 修正: `client.chat.completions.create(...)` へ変更（`a41678f`）
- 現在: 本番で `200` 応答を確認済み

### VAD について

- 音声検知は既存の Ambient/VAD 系を共有
- `WebRTC` は未実装で、選択時は `Silero` へフォールバックする実装

---

## 4. 次セッション開始時の最短チェックリスト

1. まず本番 API を確認
   - `curl -i https://api.hey-watch.me/zerotouch/health`
   - `curl -s https://api.hey-watch.me/zerotouch/openapi.json | rg '"/api/live-sessions"|"/api/transcribe/realtime"|"/api/translate/realtime"'`
2. translate API 単体疎通
   - `curl -X POST https://api.hey-watch.me/zerotouch/api/translate/realtime -F live_session_id=dummy -F chunk_index=0 -F text='テストです。' -F target_language=en`
3. Android を起動して Live Transcript / Translation を検証
   - Home (`MeMo Live`) で録音開始
   - 日本語枠と英語枠の両方が増加することを確認
4. 失敗時は Android Logcat で次を確認
   - live session 作成の HTTP ステータス
   - realtime transcribe の HTTP ステータス/レスポンス
   - realtime translate の HTTP ステータス/レスポンス
   - `Translation updated:*` / `Realtime translation failed*` のログ
   - VAD fallback ログ（WebRTC -> Silero）

---

## 5. 次セッションで優先する実装順

1. 実機で 2分割レイアウトの UX 検証（日本語 2/3、英語 1/3）
2. ASR `gpt-4o-transcribe` での精度・遅延を観測
3. 翻訳の文脈強化（前後句を translate request に追加）を検討
4. 用語集注入（glossary）を translate prompt に追加検討

---

## 6. 参照ドキュメント

- Live仕様（正本）: `docs/live-support-pivot-spec.md`
- docs入口: `docs/README.md`
- 既存引き継ぎ（旧系）: `docs/amical-longterm-memory-handoff.md`
- Ambient再開メモ: `docs/ambient-pipeline-refactor-handoff.md`
