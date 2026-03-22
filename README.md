# ZeroTouch Android

`ZeroTouch` は、Android タブレットを現場に置くだけで、会話をメモ、ナレッジ、
将来的にはエージェント行動へ変えていくためのアプリです。

## 現在の実装（MVP）

**パイプライン**: 録音 → S3アップロード → Speechmatics文字起こし → GPT-5.4カード生成

### アーキテクチャ

```
Android App (Kotlin/Compose)
  ↓ Upload (m4a)
S3 (watchme-vault/zerotouch/...)
  ↓
Backend API (FastAPI :8060)
  ↓ Speechmatics Batch API
Transcription
  ↓ GPT-5.4
Cards (task/memo/schedule/contact/issue)
  ↓
Supabase (zerotouch_sessions)
  ↓ Polling (5s)
Android App (Card Display)
```

### Android アプリ

- **Record タブ**: 録音 → 停止 → アップロード
- **Sessions タブ**: セッション一覧（ステータス表示）
- **Detail タブ**: 文字起こし + 生成カード表示

### バックエンド API

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/health` | GET | ヘルスチェック |
| `/api/upload` | POST | 音声をS3にアップロード、セッション作成 |
| `/api/transcribe/{id}` | POST | 文字起こし開始（202 Accepted） |
| `/api/generate-cards/{id}` | POST | カード生成開始（202 Accepted） |
| `/api/sessions/{id}` | GET | セッション詳細取得 |
| `/api/sessions` | GET | セッション一覧 |
| `/api/models` | GET | 利用可能なLLMモデル一覧 |

### データベース

- テーブル: `zerotouch_sessions`（Supabase、同一プロジェクト）
- ステータス遷移: `recording → uploaded → transcribing → transcribed → generating → completed / failed`

## 技術スタック

| レイヤー | 技術 |
|---------|------|
| Android | Kotlin / Jetpack Compose / OkHttp |
| Backend | FastAPI (Python 3.11) / uvicorn |
| STT | Speechmatics Batch API（話者分離対応） |
| LLM | GPT-5.4 (`gpt-5.4-2026-03-05`) |
| DB | Supabase (`zerotouch_` prefix) |
| Storage | S3 (`watchme-vault`) |
| Deploy | Docker / EC2 / GitHub Actions |

## セットアップ

### バックエンド

```bash
cd backend
cp .env.example .env
# .env を編集（APIキーを設定）

python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

python3 app.py  # localhost:8060
```

### データベース

Supabase SQL Editorで `backend/migrations/001_create_zerotouch_sessions.sql` を実行。

### Android

Android Studio でプロジェクトを開いてビルド。

## ディレクトリ構造

```
android-zero-touch/
├── app/                          # Android アプリ (Kotlin/Compose)
│   └── src/main/java/.../
│       ├── MainActivity.kt       # 3タブナビゲーション
│       ├── api/
│       │   ├── ZeroTouchApi.kt   # API クライアント
│       │   └── DeviceIdProvider.kt
│       ├── audio/
│       │   └── VoiceMemoEngine.kt # 録音/再生エンジン
│       └── ui/
│           ├── VoiceMemoScreen.kt  # 録音 + アップロード
│           ├── SessionListScreen.kt # セッション一覧
│           ├── CardDetailScreen.kt  # カード表示
│           └── ZeroTouchViewModel.kt
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
│   │   └── 001_create_zerotouch_sessions.sql
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
