---
作成日: 2026-05-02
ステータス: 実装済み・本番稼働中
位置づけ: Live Support 機能（リアルタイム文字起こし + リアルタイム翻訳）の現状仕様
---

# Live Support

Home（`MeMo Live`）画面で、会話をリアルタイムに文字起こしし、英訳して表示するための表示専用パイプライン。

**この機能は完成していて本番稼働中**。これ以上の機能追加（QR 持ち帰り、Take-Home ページ、要点抽出など）は `roadmap.md` を参照。

---

## 1. 機能スコープ

Live Support は次の 2 つだけを担当する。

1. **Live Transcription**: 会話音声を句単位で文字起こしして日本語テキストとして表示
2. **Live Translation**: 文字起こし結果を英訳して並列表示

それ以外（Card / Topic / Fact / Wiki / Action Candidate などの会話処理）は ZeroTouch 本体の Conversation パイプラインの担当で、Live Support の責務ではない。

---

## 2. 技術スタック

| レイヤー | 技術 |
|---------|------|
| クライアント | Android (Kotlin / Jetpack Compose) |
| 録音 | `AmbientRecordingService` + `AmbientRecorder`（既存 VAD / RingBuffer 共有） |
| 文字起こし | OpenAI `gpt-4o-transcribe`（デフォルト） |
| 翻訳 | OpenAI `chat.completions`（`gpt-4o-mini` 系） |
| バックエンド | FastAPI (Python 3.11) on EC2 Docker |
| DB | Supabase（`zerotouch_live_*` テーブル） |
| ストレージ | S3 不使用（チャンクは API リクエストに直接含める） |

---

## 3. API エンドポイント

すべて `https://api.hey-watch.me/zerotouch` 配下。

| エンドポイント | メソッド | 役割 |
|-------------|---------|------|
| `/api/live-sessions` | POST | Live セッション作成 |
| `/api/transcribe/realtime` | POST | チャンク単位の文字起こし |
| `/api/translate/realtime` | POST | 句単位の英訳 |

### 疎通確認コマンド

```bash
# ヘルスチェック
curl -i https://api.hey-watch.me/zerotouch/health

# Live API が公開されているか
curl -s https://api.hey-watch.me/zerotouch/openapi.json \
  | rg '"/api/live-sessions"|"/api/transcribe/realtime"|"/api/translate/realtime"'

# 翻訳単体疎通
curl -X POST https://api.hey-watch.me/zerotouch/api/translate/realtime \
  -F live_session_id=dummy \
  -F chunk_index=0 \
  -F text='テストです。' \
  -F target_language=en
```

---

## 4. データベース

Supabase に以下のテーブルが適用済み。

| テーブル | 役割 |
|---------|------|
| `zerotouch_live_sessions` | Live セッション本体 |
| `zerotouch_live_transcripts` | チャンク単位の文字起こし結果 |
| `zerotouch_live_keypoints` | （将来の要点抽出用に予約済み・現状未使用） |

該当 migration は `backend/migrations/` 配下の Live 関連ファイル。

---

## 5. Android 側の実装

### 関連ファイル

- `app/src/main/java/.../audio/ambient/AmbientRecordingService.kt`
  - Live セッション作成（`createLiveSession(...)`）
  - チャンクごとに `transcribeRealtimeChunk(...)` を呼び出し
  - 句抽出後に `translateRealtime(...)` を呼び出し
- `app/src/main/java/.../audio/ambient/AmbientStatus.kt`
  - `liveTranscriptLatest / liveTranscriptHistory`
  - `liveTranslationLatest / liveTranslationHistory`
- `app/src/main/java/.../ui/MemoLiveHomeScreen.kt`
  - 上 2/3 = 日本語、下 1/3 = 英語の独立スクロールレイアウト

### 翻訳投入のしきい値

短い完結文も翻訳に回るよう `MIN_TRANSLATION_*` 系の定数を緩和済み（`AmbientRecordingService.kt`）。

---

## 6. 動作要件

Live Support が動くには以下 3 つが揃っている必要がある。Android 単体デプロイでは不十分。

1. Android アプリ（最新ビルドが端末に入っている）
2. ZeroTouch backend が `/api/live-sessions`, `/api/transcribe/realtime`, `/api/translate/realtime` を返せる状態
3. Supabase に Live テーブル群が適用済み

---

## 7. 過去の不具合と修正履歴

| 日付 | 症状 | 原因 | 修正 |
|------|------|------|------|
| 2026-04-29 | `POST /api/translate/realtime` が `500` | `openai==1.59.4` で `client.responses.create` を使用 | `client.chat.completions.create` に変更（commit `a41678f`） |

---

## 8. ZeroTouch 本体パイプラインとの関係

Live Support は **表示専用の並列機能** であり、ZeroTouch の Conversation パイプライン（Card → Topic → Fact → Wiki → Action Candidate）を **置き換えない / 干渉しない**。

Home 画面では本来、Live Support と Conversation パイプラインを並列で走らせる設計になっている。ただし現時点で Conversation パイプラインは止まっており、再稼働させることが次の作業（`docs/README.md` の現状ステータスを参照）。
