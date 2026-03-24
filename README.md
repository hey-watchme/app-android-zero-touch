# ZeroTouch Android

`ZeroTouch` は、Android タブレットを現場に置くだけで、会話をメモ、ナレッジ、
将来的にはエージェント行動へ変えていくためのアプリです。

## リポジトリと作業対象（重要）

このREADMEが指す実体のリポジトリは **ZeroTouch専用リポジトリ** です。  
現在の作業場所: `/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch`

`watchme/` 直下は **Gitリポジトリではありません**（複数リポジトリを並列配置したワークスペース）。  
そのため、以下の混同が起きやすい点に注意してください。

- `watchme/` 全体の話と **ZeroTouchリポジトリの話は別** です
- GitHub Actions やCI/CDは **このリポジトリ（ZeroTouch）側** に定義されています

必要に応じて、このリポジトリのGitHub Actionsは  
`.github/workflows/` 配下を確認してください。

## 現在の実装（MVP）

**パイプライン**: アンビエント録音 → S3アップロード → ASR文字起こし（Speechmatics / Deepgram）→ アプリ表示  
※ **LLM カード生成は一旦停止中**（`/api/generate-cards` は未使用）

**ASRプロバイダー**: Speechmatics / Deepgram（アプリの設定から選択）

> 重要: ZeroTouch は WatchMe のインフラ（同一 Supabase / S3 / EC2）を「間借り」していますが、  
> **DB は `zerotouch_sessions`（発言）と `conversation_topics`（会話グループ）**のみを使用する POC です。WatchMe 本家の既存テーブル/パイプラインには触れません。

## 対象タブレット

- 端末: Xiaomi Redmi Pad SE
- MIUI/OS: `1.0.7.0.UMUMIXM`
- Android: `14 (UK91.231003.002)`

### アーキテクチャ

```
Android App (Kotlin/Compose)
  ├ AmbientService (VAD / RingBuffer)
  └ Upload (m4a)
S3 (watchme-vault/zerotouch/...)
  ↓
Backend API (FastAPI :8061)
  ↓ ASR (Speechmatics / Deepgram)
Transcription
  ↓
Supabase (zerotouch_sessions / conversation_topics)
  ↓ Polling (5s)
Android App (Card Display)
```

### Android アプリ（Notion風UI）

- **TopAppBar**: 中央 `ZeroTouch`、左にアンビエントステータスドット（パルスアニメーション）、右に設定アイコン
- **アンビエント制御**: トグルチップ（Listening / Off）+ ミニマルバナー（録音中はパルスドット + レベルバー）
- **検索バー**: モック（将来の全文検索用プレースホルダー）
- **カードリスト**: 日付ヘッダー（Today / Yesterday / 日付）でグルーピング、角丸カード + ソフトシャドウ
- **カード詳細**: タップで BottomSheet 展開（全文選択可、Copy / Save / Remove アクション）
- **ローディング**: シマーアニメーション付きスケルトンカード
- **BottomNav**: Material Icons 付き（Timeline / Saved）
- **設定**: BottomSheet（感度、最小録音長、自動文字起こし等のモックコントロール）
- **テーマ**: Notion風ウォームニュートラル + ブルーアクセント（ライト固定）

**録音ルール（現在値）**
- 先頭 2 秒のリングバッファを含めて保存
- 無音 5 秒でセッション終了
- 最小 3 秒未満は破棄

### バックエンド API

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/health` | GET | ヘルスチェック |
| `/api/upload` | POST | 音声をS3にアップロード、セッション作成 |
| `/api/transcribe/{id}` | POST | 文字起こし開始（202 Accepted） |
| `/api/generate-cards/{id}` | POST | カード生成開始（202 Accepted / 現在は未使用） |
| `/api/sessions/{id}` | GET | セッション詳細取得 |
| `/api/sessions` | GET | セッション一覧 |
| `/api/models` | GET | 利用可能なLLMモデル一覧 |

※ `/api/transcribe/{id}` は `provider` / `model` のクエリ指定に対応（例: `?provider=deepgram&model=nova-3`）
※ `/api/transcribe/{id}` は `language` のクエリ指定に対応（例: `?language=en`）。`ja`, `en` をサポート。

### データベース

- テーブル:
  - `zerotouch_sessions`（発言単位）
  - `conversation_topics`（発言を束ねるトピック単位）
- ステータス遷移: `recording → uploaded → transcribing → transcribed → generating → completed / failed`
  - ※ 現在は `transcribed` までを利用

## 技術スタック

| レイヤー | 技術 |
|---------|------|
| Android | Kotlin / Jetpack Compose / Material3 / OkHttp |
| Backend | FastAPI (Python 3.11) / uvicorn |
| STT | Speechmatics Batch API / Deepgram Nova（選択式） |
| LLM | **一旦停止中**（将来は GPT-5.4 を想定） |
| DB | Supabase (`zerotouch_` prefix) |
| Storage | S3 (`watchme-vault`) |
| Deploy | Docker / EC2 / GitHub Actions |

## セットアップ

### バックエンド

```bash
cd backend
cp .env.example .env
# .env を編集（APIキーを設定）
# 重要: バックエンドは `SUPABASE_SERVICE_ROLE_KEY` を使用します（Android 側には絶対に持たせない）
# ASR:
# - Speechmatics: SPEECHMATICS_API_KEY
# - Deepgram: DEEPGRAM_API_KEY
# Optional:
# - ASR_PROVIDER / ASR_MODEL / ASR_LANGUAGE（デフォルト設定）

python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

python3 app.py  # localhost:8061
```

### データベース

Supabase SQL Editorで以下を順に実行:

1. `backend/migrations/001_create_zerotouch_sessions.sql`
2. `backend/migrations/002_lockdown_zerotouch_sessions_rls.sql`（推奨: anon/authenticated からの直接アクセスを遮断）
3. `backend/migrations/003_add_conversation_topics.sql`

### Android

Android Studio でプロジェクトを開いてビルド。  
Foreground Service を使用するため、通知許可が必要（Android 13+）。

## ディレクトリ構造

```
android-zero-touch/
├── app/                          # Android アプリ (Kotlin/Compose)
│   └── src/main/java/.../
│       ├── MainActivity.kt       # Scaffold + TopAppBar + BottomNav
│       ├── api/
│       │   ├── ZeroTouchApi.kt   # API クライアント
│       │   └── DeviceIdProvider.kt
│       ├── audio/
│       │   └── ambient/           # アンビエント録音
│       │       ├── AmbientRecordingService.kt
│       │       ├── AmbientRecorder.kt
│       │       ├── Mp4AudioWriter.kt
│       │       ├── SileroVadDetector.kt
│       │       ├── VadDetector.kt
│       │       ├── WebRtcVadDetector.kt
│       │       ├── PcmRingBuffer.kt
│       │       └── AmbientStatus.kt
│       └── ui/
│           ├── VoiceMemoScreen.kt         # メイン画面（カードリスト + 検索 + アンビエント制御）
│           ├── SettingsScreen.kt          # 設定 BottomSheet（モック）
│           ├── ZeroTouchViewModel.kt      # 状態管理
│           ├── SessionListScreen.kt       # セッション一覧（未使用）
│           ├── components/
│           │   ├── AmbientIndicator.kt    # パルスドット + ステータスバナー
│           │   ├── TranscriptCardView.kt  # Notion風カードUI
│           │   ├── CardDetailSheet.kt     # カード詳細 BottomSheet
│           │   └── ShimmerEffect.kt       # シマーローディング
│           └── theme/
│               ├── Color.kt              # カラーパレット
│               ├── Type.kt               # タイポグラフィ
│               └── Theme.kt              # Material3テーマ
├── backend/                      # FastAPI バックエンド
│   ├── app.py                   # メインAPI
│   ├── services/
│   │   ├── llm_providers.py     # OpenAI/Gemini 抽象化
│   │   ├── llm_models.py       # モデルカタログ
│   │   ├── prompts.py          # カード生成プロンプト
│   │   ├── background_tasks.py  # 非同期処理
│   │   └── asr_providers/
│   │       └── speechmatics_provider.py
│   ├── migrations/
│   │   ├── 001_create_zerotouch_sessions.sql
│   │   ├── 002_lockdown_zerotouch_sessions_rls.sql
│   │   └── 003_add_conversation_topics.sql
│   ├── Dockerfile
│   ├── docker-compose.prod.yml
│   └── requirements.txt
└── docs/
    └── ambient-agent-spec.md    # 企画書
```

## 今後の拡張（Phase 2+）

- ファクト抽出 → アノテーション → ナレッジ化パイプライン
- Lambda + SQS による自動パイプライン
- 現場固有の用語・ルール学習
- エージェント行動（予約登録、リマインド等）

## 参考

- 企画書: `docs/ambient-agent-spec.md`
- モデルプロジェクト: `/Users/kaya.matsumoto/projects/watchme/business`
- WatchMe インフラ: `/Users/kaya.matsumoto/projects/watchme/server-configs`

## 最近の変更（運用メモ / 2026-03-24）

### Listeningの可視化（2軸）

- `SND`: 環境音レベル（物音なども含む）
- `VOC`: VADが「声」と判定した度合い

実装: `app/src/main/java/com/example/zero_touch/ui/components/AmbientIndicator.kt`

### VAD関連（オンデバイス）

- 設定で `VAD engine` / `Audio source` / `High-pass filter` を切り替え可能
- `Silero VAD` を実装（声の判定はこれがメイン）
- `WebRTC VAD` は現状未実装（選択しても `supported=false` 扱い）

### 英語会議の再トランスクライブ

- カード詳細に `Re-Transcribe EN` ボタンを追加（事後的に英語で再文字起こし）
- API: `POST /api/transcribe/{id}?language=en`（`ja|en`）
- `transcription_metadata.language` に実際に使った言語を保存
