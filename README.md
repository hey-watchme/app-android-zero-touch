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
- 閲覧専用の Web ダッシュボードは **別リポジトリ** です  
  リポジトリ: `/Users/kaya.matsumoto/projects/watchme/app/web-zero-touch`  
  公開URL: `https://app-web-zero-touch.vercel.app/`

必要に応じて、このリポジトリのGitHub Actionsは  
`.github/workflows/` 配下を確認してください。

## 現在の方針

**ソースオブトゥルース**: `docs/conversation-pipeline-processes.md`

ZeroTouch の会話可視化パイプラインは、現在以下のモデルへ整理し直しています。

- `Card` は発言の最小単位で、ASR 完了後に必ず生成される
- `Topic` は Card を受ける live な箱で、Card と同時に必ず存在する
- Topic の境界は `30秒無発言` または `Ambient Off` の機械ルールで決まる
- LLM は Topic を分割せず、Topic 確定時にタイトル、要約、説明文を整える

**目標パイプライン**: アンビエント録音 → S3アップロード → ASR文字起こし（Speechmatics / Deepgram / Azure Speech Service）→ Card生成 → active Topic へ即時紐付け → アプリ表示 → idle / ambient stop で Topic finalize → LLM で Topic 要約

※ **LLM カード生成は一旦停止中**（`/api/generate-cards` は未使用）  
※ コードベースには旧 `pending -> batch grouping` モデルの実装が一部残っており、現在はこの README と `docs/conversation-pipeline-processes.md` を基準に整理中です。

**ASRプロバイダー**: Speechmatics / Deepgram / Azure Speech Service（稼働中）  
**保留中**: Cohere（現在の ambient 録音形式 `m4a` をそのまま受け付けないため）

**Web 閲覧ダッシュボード**: `https://app-web-zero-touch.vercel.app/`  
Android 側の Topic / Card を Web で閲覧できる読み取り専用 MVP を公開中です。

> 重要: ZeroTouch は WatchMe のインフラ（同一 Supabase / S3 / EC2）を「間借り」していますが、  
> **DB は `zerotouch_sessions`（Card）と `zerotouch_conversation_topics`（Topic）を中心に使う POC** です。WatchMe 本家の既存テーブル/パイプラインには触れません。

## 所有モデル

ZeroTouch は今後 `account -> workspace -> device` の多層モデルで運用します。

- `account`: Google サインイン等で識別されるユーザー
- `workspace`: 家庭、店舗、検証環境などの論理単位
- `device`: Android 本体、または Amical などを投入する仮想デバイス
- `context profile`: その workspace の利用者、現場、目的、補足資料、語彙

既存パイプラインは引き続き `device_id` で動作しますが、`workspace_id` を併せて持つことで、
複数デバイスや仮想デバイスを 1 つのナレッジ単位に束ねられるようにします。

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
  ↓ ASR (Speechmatics / Deepgram / Azure Speech Service)
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
- **カード詳細**: タップで右側 drawer 展開（全文選択可、Copy / Save / Remove アクション）
- **ローディング**: シマーアニメーション付きスケルトンカード
- **BottomNav**: Material Icons 付き（Timeline / Saved）
- **設定**: 右側 drawer（感度、最小録音長、自動文字起こし等）
- **テーマ**: Notion風ウォームニュートラル + ブルーアクセント（ライト固定）

**録音ルール（現在値）**
- 先頭 2 秒のリングバッファを含めて保存
- 通常は無音 5 秒でセッション終了
- 連続録音が 2 分を超えたら、無音 2.5 秒でセッション終了
- 1 セッションの上限は 10 分
- 最小 3 秒未満は破棄

**現在の音声形式**
- Ambient 録音は `AAC + MPEG-4 container` の `.m4a` で保存する
- upload 時の `Content-Type` は `audio/mp4`
- Speechmatics / Deepgram はこのまま受け付ける
- Azure Speech Service は Batch Transcription で `contentUrls` を使って処理する
- Cohere は `m4a` 非対応で、`flac, mp3, mpeg, mpga, ogg, wav` のみ対応のため、現状は保留

**Topic finalize ルール（現在値）**
- 最後の Card から 30 秒間新しい発言がなければ Topic を finalize
- Ambient Off でも Topic を finalize
- これらの値は運用しながら継続的に調整する前提

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
| `/api/accounts` | GET / POST | アカウント一覧 / 作成 |
| `/api/workspaces` | GET / POST | ワークスペース一覧 / 作成 |
| `/api/devices` | GET / POST | デバイス一覧 / 登録 |
| `/api/context-profiles/{workspace_id}` | GET / POST | ワークスペースの文脈プロファイル取得 / 更新 |
| `/api/sessions/{id}` | GET | セッション詳細取得 |
| `/api/sessions` | GET | セッション一覧 |
| `/api/models` | GET | 利用可能なLLMモデル一覧 |

※ `/api/transcribe/{id}` は `provider` / `model` のクエリ指定に対応（例: `?provider=deepgram&model=nova-3`, `?provider=azure&model=ja-JP`）
※ `/api/transcribe/{id}` は `language` のクエリ指定に対応（例: `?language=en`）。`ja`, `en` をサポート。

### データベース

- テーブル:
  - `zerotouch_accounts`（ユーザーアカウント）
  - `zerotouch_workspaces`（家庭 / 店舗 / 検証環境）
  - `zerotouch_workspace_members`（workspace メンバー）
  - `zerotouch_devices`（物理デバイス / 仮想デバイス）
  - `zerotouch_context_profiles`（workspace の前提情報）
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
| STT | Speechmatics Batch API / Deepgram Nova / Azure Speech Service（稼働中） / Cohere Transcribe（保留） |
| LLM | **一旦停止中**（将来は GPT-5.4 を想定） |
| DB | Supabase (`zerotouch_` prefix) |
| Storage | S3 (`watchme-vault`) |
| Deploy | Docker / EC2 / GitHub Actions |

## ASRコストメモ

### Azure Speech Service 導入メモ（2026-03-28）

- ZeroTouch では WatchMe 側で既に使っている `AZURE_SPEECH_KEY` と `AZURE_SERVICE_REGION` をそのまま流用できる
- GitHub Secrets は org secret として設定済みで、この public repo から利用できる前提で運用する
- `provider=azure` は **Batch Transcription REST** を使う（`/speechtotext/transcriptions:submit?api-version=2025-10-15`）
- `language=ja|en` からデフォルト locale を `ja-JP` / `en-US` へ自動解決する
- Batch Transcription は `contentUrls` が必要なので、ZeroTouch では S3 presigned URL を使う
- `contentUrls` は外部からアクセス可能である必要があるため、失敗する場合は Azure Blob + SAS へ切替える
- diarization は `properties.diarization.enabled=true` で有効化（`maxSpeakers` は環境変数で調整）
- Azure Batch Transcription は **Standard (S0)** の Speech リソースが必要（Free/Trial だと `Only "Standard" subscriptions...` で失敗）
- 既定の REST API バージョンは `AZURE_SPEECH_API_VERSION=2025-10-15`（必要なら変更可能）
- diarization / polling / TTL / presigned URL 有効期限は `.env` で調整する
- GitHub Actions で EC2 に `.env` を作る際も `AZURE_SPEECH_KEY`, `AZURE_SERVICE_REGION` を注入する

### Cohere 導入メモ（2026-03-28）

- Cohere は一度 provider として接続確認したが、現行の ambient 録音形式 `m4a` と非互換のため、現時点では保留
- 公式 docs では `POST /v2/audio/transcriptions` で `model` / `language` / `file` を multipart 送信する
- 現行実装のデフォルト model は `cohere-transcribe-03-2026`
- 制約として、Cohere Transcribe は `timestamps / speaker diarization 非対応`
- さらに `m4a` 非対応で、`flac, mp3, mpeg, mpga, ogg, wav` のみ対応
- そのため ZeroTouch 上では `speaker_count=0`、utterance 単位の詳細も空で扱う
- 参照:
  `https://docs.cohere.com/reference/create-audio-transcription`
  `https://docs.cohere.com/docs/transcribe`

## 音声モデル追加時の注意点

- まず最初に、公式 docs で `対応ファイル拡張子 / コンテナ / codec / 最大サイズ` を確認する
- `m4a` を受け付けるかどうかは要確認。今回の ambient 録音は `.m4a` 固定で、ここが最大の相性ポイントだった
- `speaker diarization`, `timestamps`, `utterances`, `confidence` の有無を先に確認する
- 既存 UI / DB が期待しているメタデータとズレる場合は、先に欠損時の見せ方を決める
- 公式 SDK があるか、生 HTTP 実装が必要かを確認する。生 HTTP の場合は request 形式とエラー処理を厚めに書く
- GitHub Actions で API キーを注入する場合は、`repo secret / org secret / repo visibility / rerun の要否` を最初に確認する
- retry を入れる場合でも、最終的な inner exception が DB / UI に見えるようにしておく
- 新しい provider は、ambient 本番運用の前に `短い既知音声1本` で疎通確認してから切り替える

### Speechmatics 運用メモ（2026-03-28）

- Speechmatics で `status=failed` になった一因として、無料枠超過を確認した
- 無料枠を超えると batch transcription が失敗するため、`Speechmatics が落ちている` ように見えても、まず利用枠と課金設定を確認する
- 価格は変動しうるため、最新情報は公式 pricing を確認する  
  `https://www.speechmatics.com/pricing`

### Speechmatics 単価メモ

- Pro / Enhanced の単価メモ: `$0.24 / 時間`
- 参考: Speechmatics 公式 pricing ページでは `Free 480 minutes per month` の記載あり
- アカウントの実際の無料付与量や請求状態は、必ず Speechmatics ダッシュボード側で再確認する

### 8時間 / 日 運用時の試算メモ

- 前提:
  - 1日 `8時間`
  - 月 `30日`
  - 単価 `$0.24 / 時間`
- 1日のコスト:
  - `8時間 × $0.24 = $1.92`
- 1ヶ月の総コスト:
  - `$1.92 × 30 = $57.60`
- 無料枠 `480分 (= 8時間)` を差し引く試算:
  - `$57.60 - $1.92 = $55.68`

### Deepgram 運用メモ（2026-03-28）

- Deepgram pricing ページで `Nova-3 (Monolingual) $0.0077 / min` を確認
- 現在の ZeroTouch 実装では `diarize=True` を有効にしているため、`Speaker Diarization $0.0020 / min` の add-on もコストに乗る前提で見る
- 無料枠として `Free $200 Credit` の記載あり
- 価格は変動しうるため、最新情報は公式 pricing を確認する  
  `https://deepgram.com/pricing`

### Deepgram 単価メモ

- `Nova-3 (Monolingual)`: `$0.0077 / min`
- `Speaker Diarization` add-on: `$0.0020 / min`
- ZeroTouch の現行設定に近い実効単価メモ:
  - `0.0077 + 0.0020 = $0.0097 / min`

### Deepgram 8時間 / 日 運用時の試算メモ

- 前提:
  - 1日 `8時間 = 480分`
  - 月 `30日 = 14,400分`
- `Nova-3 (Monolingual)` 本体のみ:
  - `14,400分 × $0.0077 = $110.88 / 月`
- `Speaker Diarization` add-on を含む参考値:
  - `14,400分 × $0.0097 = $139.68 / 月`
- そのため、無料クレジット消化後は Deepgram も常時稼働用途では安くはない

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
# - Cohere: COHERE_API_KEY
# - Azure Speech Service: AZURE_SPEECH_KEY / AZURE_SERVICE_REGION
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
7. `backend/migrations/007_add_topic_scoring.sql`
8. `backend/migrations/008_create_zerotouch_facts.sql`
9. `backend/migrations/009_add_workspace_ownership_model.sql`

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
│   │       ├── speechmatics_provider.py
│   │       ├── deepgram_provider.py
│   │       └── cohere_provider.py
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
