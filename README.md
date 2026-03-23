# ZeroTouch Android

`ZeroTouch` は、Android タブレットを現場に置くだけで、会話をメモ、ナレッジ、
将来的にはエージェント行動へ変えていくためのアプリです。

## 現在の実装（MVP）

**パイプライン**: アンビエント録音 → S3アップロード → Speechmatics文字起こし → アプリ表示  
※ **LLM カード生成は一旦停止中**（`/api/generate-cards` は未使用）

> 重要: ZeroTouch は WatchMe のインフラ（同一 Supabase / S3 / EC2）を「間借り」していますが、  
> **DB は `zerotouch_sessions` のみ**を使用する POC です。WatchMe 本家の既存テーブル/パイプラインには触れません。

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
  ↓ Speechmatics Batch API
Transcription
  ↓
Supabase (zerotouch_sessions)
  ↓ Polling (5s)
Android App (Card Display)
```

### Android アプリ

- **ヘッダー**: 左上 `ZeroTouch`、右上アンビエント録音トグル
- **ステータス表示**: Recording / VAD / レベルメーター / 経過時間
- **カードリスト**: 直近の録音が上に積み上がる（自動ポーリング）
- **フッター**: 「タイムライン」「保存済み」タブ
- **カード操作**: ☆→★でお気に入り、×で非表示（ローカルのみ）

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

### データベース

- テーブル: `zerotouch_sessions`（Supabase、同一プロジェクト）
- ステータス遷移: `recording → uploaded → transcribing → transcribed → generating → completed / failed`
  - ※ 現在は `transcribed` までを利用

## 技術スタック

| レイヤー | 技術 |
|---------|------|
| Android | Kotlin / Jetpack Compose / OkHttp |
| Backend | FastAPI (Python 3.11) / uvicorn |
| STT | Speechmatics Batch API（話者分離対応） |
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

python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

python3 app.py  # localhost:8061
```

### データベース

Supabase SQL Editorで以下を順に実行:

1. `backend/migrations/001_create_zerotouch_sessions.sql`
2. `backend/migrations/002_lockdown_zerotouch_sessions_rls.sql`（推奨: anon/authenticated からの直接アクセスを遮断）

### Android

Android Studio でプロジェクトを開いてビルド。  
Foreground Service を使用するため、通知許可が必要（Android 13+）。

## ディレクトリ構造

```
android-zero-touch/
├── app/                          # Android アプリ (Kotlin/Compose)
│   └── src/main/java/.../
│       ├── MainActivity.kt       # 1画面 + フッターナビ
│       ├── api/
│       │   ├── ZeroTouchApi.kt   # API クライアント
│       │   └── DeviceIdProvider.kt
│       ├── audio/
│       │   └── ambient/           # アンビエント録音
│       │       ├── AmbientRecordingService.kt
│       │       ├── AmbientRecorder.kt
│       │       ├── Mp4AudioWriter.kt
│       │       ├── VadDetector.kt
│       │       ├── PcmRingBuffer.kt
│       │       └── AmbientStatus.kt
│       └── ui/
│           ├── VoiceMemoScreen.kt  # 状態表示 + カードリスト
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
