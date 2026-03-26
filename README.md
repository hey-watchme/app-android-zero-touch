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

`HANDOFF.md` は廃止済みです。次の担当者は README と `docs/conversation-pipeline-processes.md` を起点に状況を把握してください。

## 現在の方針

**ソースオブトゥルース**: `docs/conversation-pipeline-processes.md`

ZeroTouch の会話可視化パイプラインは、現在以下のモデルへ整理し直しています。

- `Card` は発言の最小単位で、ASR 完了後に必ず生成される
- `Topic` は Card を受ける live な箱で、Card と同時に必ず存在する
- Topic の境界は `60秒無発言` または `Ambient Off` の機械ルールで決まる
- LLM は Topic を分割せず、Topic 確定時にタイトル、要約、説明文を整える

**目標パイプライン**: アンビエント録音 → S3アップロード → ASR文字起こし（Speechmatics / Deepgram）→ Card生成 → active Topic へ即時紐付け → アプリ表示 → idle / ambient stop で Topic finalize → LLM で Topic 要約

※ **LLM カード生成は一旦停止中**（`/api/generate-cards` は未使用）  
※ コードベースには旧 `pending -> batch grouping` モデルの実装が一部残っており、現在はこの README と `docs/conversation-pipeline-processes.md` を基準に整理中です。

**ASRプロバイダー**: Speechmatics / Deepgram（アプリの設定から選択）

> 重要: ZeroTouch は WatchMe のインフラ（同一 Supabase / S3 / EC2）を「間借り」していますが、  
> **DB は `zerotouch_sessions`（Card）と `zerotouch_conversation_topics`（Topic）を中心に使う POC** です。WatchMe 本家の既存テーブル/パイプラインには触れません。

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
Supabase (zerotouch_sessions / zerotouch_conversation_topics)
  ↓ Polling (5s)
Android App (Card-first Display)
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
| `/api/topics` | GET | トピック一覧取得 |
| `/api/topics/{topic_id}` | GET | トピック詳細取得 |
| `/api/topics/evaluate-pending` | POST | active topic の finalize トリガー（エンドポイント名は旧名を踏襲） |
| `/api/device-settings/{device_id}` | GET | デバイス設定（LLM）取得 |
| `/api/device-settings/{device_id}` | POST | デバイス設定（LLM）更新 |
| `/api/sessions/{id}` | GET | セッション詳細取得 |
| `/api/sessions` | GET | セッション一覧 |
| `/api/models` | GET | 利用可能なLLMモデル一覧 |

※ `/api/transcribe/{id}` は `provider` / `model` のクエリ指定に対応（例: `?provider=deepgram&model=nova-3`）
※ `/api/transcribe/{id}` は `language` のクエリ指定に対応（例: `?language=en`）。`ja`, `en` をサポート。

### データベース

- テーブル:
  - `zerotouch_sessions`（Card / 発言単位）
  - `zerotouch_conversation_topics`（Topic / Card を束ねる会話区間）
  - `zerotouch_topic_evaluation_runs`（旧 Process 2 の評価バッチ管理。整理対象）
  - `zerotouch_device_settings`（デバイスごとの LLM 設定）
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
# - TOPIC_PIPELINE_MODE は旧トピック実装の切り替え用。新モデルへ移行後は整理予定

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
4. `backend/migrations/004_process2_topic_pipeline.sql`
5. `backend/migrations/005_add_device_settings.sql`
6. `backend/migrations/006_live_topic_container_constraints.sql`（1 device 1 active topic 制約 + topic description/boundary metadata）

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
│   │   ├── topic_manager_process2.py # 旧 Process 2 実装。live-topic モデルへ置換予定
│   │   └── asr_providers/
│   │       └── speechmatics_provider.py
│   ├── migrations/
│   │   ├── 001_create_zerotouch_sessions.sql
│   │   ├── 002_lockdown_zerotouch_sessions_rls.sql
│   │   ├── 003_add_conversation_topics.sql
│   │   ├── 004_process2_topic_pipeline.sql
│   │   ├── 005_add_device_settings.sql
│   │   └── 006_live_topic_container_constraints.sql
│   ├── Dockerfile
│   ├── docker-compose.prod.yml
│   └── requirements.txt
└── docs/
    ├── ambient-agent-spec.md    # 企画書
    └── conversation-pipeline-processes.md # 現行の会話可視化パイプライン定義
```

## 今後の拡張（Phase 2+）

- ファクト抽出 → アノテーション → ナレッジ化パイプライン
- Lambda + SQS による自動パイプライン
- 現場固有の用語・ルール学習
- エージェント行動（予約登録、リマインド等）

## 参考

- 企画書: `docs/ambient-agent-spec.md`
- 会話パイプライン試行メモ: `docs/conversation-pipeline-processes.md`
- アンビエント録音デバッグ手順: `docs/ambient-recording-debug-checklist.md`
- モデルプロジェクト: `/Users/kaya.matsumoto/projects/watchme/business`
- WatchMe インフラ: `/Users/kaya.matsumoto/projects/watchme/server-configs`

## 最近の変更（運用メモ / 2026-03-25）

### UIリデザイン（全面刷新）

Material Design 3 × Notion ミニマリズムをベースに全コンポーネントを刷新。

#### 情報密度の改善
- Topic はデフォルト展開（全カードがすぐ見える）、手動で折りたたみも可能
- Topic 内の Card は可変高さ（内容に応じて 1〜3 行）
- カード間スペース縮小、余白も全体的に引き締め

#### 3段階カードアニメーション
- **Stage 1 (録音検出)**: 赤いパルスドット + "Listening..." — カード即時表示
- **Stage 2 (文字起こし中)**: オレンジ "Transcribing..." + アニメーションドット
- **Stage 3 (完了)**: テキストフェードイン
- カード出現時のバウンスアニメーション（scale + alpha）
- Topic 確定 cooling 中は "Analyzing conversation..." パルス表示

#### UI新機能（モック含む）
- ユーザーアバター（TopBar 左）+ Ambient dot オーバーレイ
- BottomNav バッジ: アクティブ Topic 数 / ブックマーク数
- フィルターチップ: All / Today / Live / Finalized
- 検索バーに FilterList アイコン追加
- 設定シートのセクションヘッダーにアイコン追加

#### テーマ
- `Color.kt` に Topic/CardRow/Badge/Avatar/FilterChip 用カラー追加
- `Type.kt` タイポグラフィ引き締め（フォントサイズ 1-2sp 縮小）

#### 以前の変更（2026-03-24）

- `SND` / `VOC` 2軸のリスニング可視化
- Silero VAD 実装（声判定メイン）、WebRTC VAD は未実装
- カード詳細 `Re-Transcribe EN` ボタン（API: `POST /api/transcribe/{id}?language=en`）
