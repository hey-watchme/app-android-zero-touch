# Live Support Next Session Handoff

更新日: `2026-04-29 (JST, session close)`
対象リポジトリ: `app/android-zero-touch`
目的: 次セッションでゼロから再開しても迷わないように、現状と起動条件を一本化する

---

## 1. 現在のステータス（事実）

- backend 最終デプロイ済みコミット: `2d8cc98`  
  `fix: stream live transcript chunks and disable realtime persistence by default`
- GitHub Actions デプロイ: 成功  
  Run: `25064282756`  
  URL: `https://github.com/hey-watchme/app-android-zero-touch/actions/runs/25064282756`
- 本番 API ヘルス: `200 OK`（確認日時: `2026-04-29 JST`）
- 本番 OpenAPI に Live API が存在:
  - `/api/live-sessions`
  - `/api/transcribe/realtime`
- Supabase 側 migration 017 相当の Live テーブル適用済み:
  - `zerotouch_live_sessions`
  - `zerotouch_live_transcripts`
  - `zerotouch_live_keypoints`

### 1.1 Android 側の直近実装（backend 再デプロイ不要）

- `c343b9b` Live transcript UX（準備中表示、右ペイン分離）
- `cae49c8` 新着発言で自動スクロール
- `26bf913` 参照画像寄せの 2 ペインデザイン
- `c7ecdee` サイドバー開閉時も同じ 2 ペイン構造維持
- `671c41e` transcript を連続テキスト表示へ変更（下方向に追加）
- `dae9732` Live 画面に ASR model 表示
- `2eff751` Live transcript をローカル保持（上限 50）
- `f03aca9` 左ナビ開閉状態をローカル保持（既定は閉じる）

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

### Live Transcript 経路（新規）

- Android `AmbientRecordingService` が Live セッション作成:
  - `createLiveSession(...)`
- 録音チャンクごとに新規 API を呼ぶ:
  - `transcribeRealtimeChunk(...)` -> `POST /api/transcribe/realtime`
- 結果を `AmbientStatus.liveTranscriptLatest/liveTranscriptHistory` に反映し、`MeMo Live` UI に表示

### 既存 Conversation 経路（従来）

- 既存の録音アップロード + バッチASR（Speechmatics/Deepgram/Azure）系
- ただし現時点の実装では、`AmbientRecordingService` で  
  `ENABLE_LEGACY_BATCH_PIPELINE = false`  
  が入っており、Live焦点のため既存経路は一時的に停止中

### VAD について

- 音声検知は既存の Ambient/VAD 系を共有
- `WebRTC` は未実装で、選択時は `Silero` へフォールバックする実装

---

## 4. 次セッション開始時の最短チェックリスト

1. まず本番 API を確認
   - `curl -i https://api.hey-watch.me/zerotouch/health`
   - `curl -s https://api.hey-watch.me/zerotouch/openapi.json | rg '"/api/live-sessions"|"/api/transcribe/realtime"'`
2. Android を起動して Live Transcript だけ検証
   - Home (`MeMo Live`) で録音開始
   - `Listening` 表示と transcript の増加を確認
3. 失敗時は Android Logcat で次を確認
   - live session 作成の HTTP ステータス
   - realtime transcribe の HTTP ステータス/レスポンス
   - VAD fallback ログ（WebRTC -> Silero）

---

## 5. 次セッションで優先する実装順（Live Transcriptフォーカス）

1. 実機で transcript 表示の最終微調整（タイポ/余白/英訳ダミーの見え方）
2. モデル切替検証（`ASR Model` 表示を見ながら実験）
3. 必要なら `MAX_LIVE_TRANSCRIPT_LINES` の保持件数を 30/50 で調整
4. その後に必要なら既存バッチ経路の再有効化を検討

---

## 6. 参照ドキュメント

- Live仕様（正本）: `docs/live-support-pivot-spec.md`
- docs入口: `docs/README.md`
- 既存引き継ぎ（旧系）: `docs/amical-longterm-memory-handoff.md`
- Ambient再開メモ: `docs/ambient-pipeline-refactor-handoff.md`
