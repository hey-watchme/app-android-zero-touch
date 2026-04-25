# ZeroTouch Android

`ZeroTouch` は、Android タブレットを現場に置くだけで、会話を
**業務システムが処理できる Action / Draft / Knowledge** に変換するための
Conversation Action Platform です。

価値は録音や文字起こしそのものではなく、現場の非構造な会話を
SaaS / ERP / 業務システムに渡せる入力、下書き、タスク、SOP に変換することにあります。

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

**現在の入口**: `docs/README.md`

最上位のプロダクト方針は、次の文書を正本にする。

- **会話 → 業務アクション変換の設計正本**: `docs/conversation-action-platform.md`
- **現在の作業引き継ぎ**: `docs/amical-longterm-memory-handoff.md`
- **Wiki / 長期記憶レイヤーの設計**: `docs/knowledge-pipeline-v2.md`

ZeroTouch の全体パイプラインは、今後以下の形を目標にする。

```text
現場の会話
  → ASR / Card / Topic
  → Fact / Wiki / Context
  → ZeroTouch Converter
  → Action Candidate / Draft
  → Human Review
  → SaaS / ERP / 業務システム
```

まずは飲食店を参照ケースとして、予約台帳、発注、在庫、顧客メモ、SOP への
下書き生成から始める。
ただし ZeroTouch は飲食店専用ではなく、建設、医療、福祉、教育など、
口頭判断や暗黙知が業務入力から漏れやすい現場へ広げる前提で設計する。

業界・個社によって出力先の SaaS / ERP は異なるため、外部連携は connector として増え続ける。
コアは `Action Candidate` という中間表現を持ち、各 connector がそれを個別システムの
下書きや API payload に変換する。

## 現在できていること

ZeroTouch の会話可視化パイプラインは、現在以下のモデルへ整理し直しています。

- `Card` は発言の最小単位で、ASR 完了後に必ず生成される
- `Topic` は Card を受ける live な箱で、Card と同時に必ず存在する
- Topic の境界は `30秒無発言` または `Ambient Off` の機械ルールで決まる
- LLM は Topic を分割せず、Topic 確定時にタイトル、要約、説明文を整える

**現行の実装済みパイプライン**: アンビエント録音 → S3アップロード → ASR文字起こし（Speechmatics / Deepgram / Azure Speech Service）→ Card生成 → active Topic へ即時紐付け → アプリ表示 → idle / ambient stop で Topic finalize → Topic / Fact / Wiki / Query

※ **LLM カード生成は一旦停止中**（`/api/generate-cards` は未使用）  
※ コードベースには旧 `pending -> batch grouping` モデルや stateful 実験の痕跡が一部残っていますが、現行判断は `docs/conversation-action-platform.md` を最上位にし、Wiki / 長期記憶については `docs/knowledge-pipeline-v2.md` を基準に進めます。

**ASRプロバイダー**: Speechmatics / Deepgram / Azure Speech Service（稼働中）  
**保留中**: Cohere（現在の ambient 録音形式 `m4a` をそのまま受け付けないため）

**Web 閲覧ダッシュボード**: `https://app-web-zero-touch.vercel.app/`  
Android 側の Topic / Card を Web で閲覧できる読み取り専用 MVP を公開中です。

**Wiki Query 稼働状況（2026-04-20）**
- Android の Query タブ（WebView）は `https://app-web-zero-touch.vercel.app/query` を利用
- Query 実処理 API は `https://api.hey-watch.me/zerotouch` 配下の
  `POST /api/query-wiki` と `GET /api/wiki-log`
- 現在は本番で利用可能（404 は解消済み）
- Android ビルドなしでも API 疎通は確認可能:
  - `curl -X POST https://api.hey-watch.me/zerotouch/api/query-wiki -H "Content-Type: application/json" -d '{"device_id":"amical-db-test","question":"テスト質問","provider":"openai","model":"gpt-4.1-mini","max_pages":3}'`
  - `curl "https://api.hey-watch.me/zerotouch/api/wiki-log?device_id=amical-db-test&operation=query&limit=10"`

> 重要: ZeroTouch は WatchMe のインフラ（同一 Supabase / S3 / EC2）を「間借り」していますが、  
> **DB は `zerotouch_sessions`（Card）と `zerotouch_conversation_topics`（Topic）を中心に使う POC** です。WatchMe 本家の既存テーブル/パイプラインには触れません。

## Action / Connector レイヤー（次の実装対象）

次に作る中核は、会話から業務イベントを抽出し、外部システムに渡せる下書きへ変換する層です。

想定する最小構成:

- `domain schema`: 飲食店、教育、建設、医療、福祉などの intent / field 定義
- `action candidate`: 会話から抽出された業務アクション候補
- `review queue`: 人間が確認、編集、承認、却下する画面
- `connector draft`: SaaS / ERP ごとの下書き payload
- `connector run`: 外部システムへ送信した履歴

最初の参照実装は飲食店にする。

例:

| 会話 | Intent | 出力下書き |
|------|--------|------------|
| `4番テーブル、1名追加で` | `change_guest_count` | 予約台帳の人数変更下書き |
| `冷蔵庫のレモン切れてる` | `stock_alert` | 発注システムへの補充メモ |
| `3番、味付けが濃いって言われた` | `quality_issue` | 顧客メモ、再発防止タスク |
| `金曜のコース、20時から2名追加で予約入れて` | `booking_update` | 予約システム更新下書き |

初期段階では外部システムを直接更新せず、必ず `Human Review` を挟む。
信頼度、業界リスク、出力先の重要度に応じて、自動化レベルを段階的に上げる。

## 所有モデル

ZeroTouch は `account -> workspace -> device` の多層モデルで運用します。

- `account`: Google サインイン等で識別されるユーザー
- `workspace`: 家庭、店舗、検証環境などの論理単位
- `device`: 録音主体である物理端末。Android では端末内で生成された `device_id` が正本
- `context profile`: その workspace の利用者、現場、目的、補足資料、語彙

Android アプリでは、ユーザーが device を選択しません。録音、Home、Timeline、Wiki の
対象は常にその Android 端末の `device_id` です。`zerotouch_devices.display_name` は
端末を見分けやすくするためのニックネームであり、ID の代替ではありません。

`workspace_id` は、複数の物理端末を 1 つの現場ナレッジへ束ねるために使います。
Amical import などの検証データは、Android で選択する device ではなく、検証用の外部データソースとして扱います。

## Context Enrichment

会話だけでは「何について話しているか」が読みづらいため、
workspace 単位で事前コンテクストを持たせる取り組みを進めています。

- 設計文書: [docs/context-enrichment-project.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/context-enrichment-project.md)
- DB 拡張: `zerotouch_context_profiles` に `account / workspace / device / environment / analysis` の JSONB カラムを追加
- Android: 初回オンボーディング画面と、あとから編集できるマイページを実装済み
- API: `/api/context-profiles/{workspace_id}` の GET / POST を使って保存する

このコンテクストは、Wiki 生成だけでなく、Action Candidate 生成にも使う。
業界、店舗、教室、施設、現場ごとの前提を先に持つことで、
同じ発話でも「何の業務イベントか」「どのシステムに下書きを出すべきか」を判定しやすくする。

## 認証

Android は Supabase Auth を使い、**Google ログイン** と **メールアドレス + パスワード** に対応しています。

### 最小限の設定

- Supabase の Auth で Google と Email を有効化
- Android の `local.properties` に下記を設定
- `local.properties` は Android Studio が作る `sdk.dir=...` と同じファイルに追記する
- 接続情報は `gradle.properties` ではなく `local.properties` か環境変数で渡す
- 雛形は [local.properties.example](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/local.properties.example)

```
SUPABASE_URL=https://qvtlwotzuzbavrzqhyvt.supabase.co
SUPABASE_ANON_KEY=（anon key）
GOOGLE_WEB_CLIENT_ID=（web client id）
```

`SUPABASE_ANON_KEY` は **Android クライアント用** です（Service Role Key は置かない）。
`local.properties` は Git 管理しないため、実値をコミットしない運用になります。

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
  ↓
Fact / Wiki / Context Profile
  ↓
ZeroTouch Converter
  ↓
Action Candidate / Draft / Review
  ↓
SaaS / ERP / 業務システム Connector
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
| `/api/ingest-wiki` | POST | Wiki Ingest 実行（Fact → zerotouch_wiki_pages 更新）|
| `/api/query-wiki` | POST | Wiki に自然言語で質問 → 回答 + filing-back |
| `/api/wiki-log` | GET | Wiki 操作ログ取得（`?device_id=&operation=&limit=`）|

今後追加する API:

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/api/action-candidates` | GET / POST | 会話から生成された業務アクション候補の一覧 / 作成 |
| `/api/action-candidates/{id}` | GET | アクション候補の詳細、根拠会話、payload |
| `/api/action-candidates/{id}/review` | POST | 承認 / 編集 / 却下 |
| `/api/connectors` | GET | 利用可能な connector 一覧 |
| `/api/connector-drafts` | GET / POST | 外部システム向け下書き payload |
| `/api/connector-runs` | GET | connector 実行履歴 |

※ `/api/transcribe/{id}` は `provider` / `model` のクエリ指定に対応（例: `?provider=deepgram&model=nova-3`, `?provider=azure&model=ja-JP`）
※ `/api/transcribe/{id}` は `language` のクエリ指定に対応（例: `?language=en`）。`ja`, `en` をサポート。

### データベース

- テーブル:
  - `zerotouch_accounts`（ユーザーアカウント）
  - `zerotouch_workspaces`（家庭 / 店舗 / 検証環境）
  - `zerotouch_workspace_members`（workspace メンバー）
  - `zerotouch_devices`（物理端末IDとニックネーム。Android では物理端末IDが正本）
  - `zerotouch_context_profiles`（workspace の前提情報）
  - `zerotouch_sessions`（Card / 発言単位）
  - `zerotouch_conversation_topics`（Topic / Card を束ねる会話区間）
  - `zerotouch_topic_evaluation_runs`（旧 Process 2 の評価バッチ管理。整理対象）
  - `zerotouch_device_settings`（デバイスごとの LLM 設定）
  - `zerotouch_wiki_pages`（Wiki / 長期記憶）
  - `zerotouch_wiki_index`（Query 入口）
  - `zerotouch_wiki_log`（ingest / query / lint ログ）
  - `zerotouch_action_candidates`（今後追加: 業務アクション候補）
  - `zerotouch_action_candidate_sources`（今後追加: 根拠会話 / Topic / Fact）
  - `zerotouch_action_reviews`（今後追加: 承認 / 編集 / 却下）
  - `zerotouch_connector_drafts`（今後追加: 外部システム向け下書き）
  - `zerotouch_connector_runs`（今後追加: connector 実行履歴）
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
| Deploy | Docker / Amazon ECR / EC2 / GitHub Actions |

**運用注意（EC2）**
- 本番 API は EC2 上のコンテナで稼働しており、現状はリソース逼迫しやすい前提で運用する
- 高負荷時は Query 応答遅延や失敗率上昇が起こり得るため、再処理バッチと同時実行数を調整する

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
10. `backend/migrations/010_expand_context_profiles.sql`
11. `backend/migrations/011_create_zerotouch_wiki_pages.sql`（`zerotouch_wiki_pages` テーブル）
12. `backend/migrations/012_create_workspace_projects_and_extend_wiki_pages.sql`（project/category 分類軸）
13. `backend/migrations/013_create_wiki_index_and_log.sql`（`zerotouch_wiki_index` / `zerotouch_wiki_log` テーブル）

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
│   │   ├── topic_scorer.py     # Topic scoring
│   │   ├── topic_annotator.py  # Fact annotation
│   │   ├── wiki_ingestor.py    # Wiki ingest
│   │   ├── wiki_querier.py     # Wiki query / filing-back
│   │   ├── wiki_linter.py      # Wiki lint
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
│   │   ├── 006_live_topic_container_constraints.sql
│   │   └── ...
│   ├── Dockerfile
│   ├── docker-compose.prod.yml
│   └── requirements.txt
└── docs/
    ├── README.md                # ドキュメント入口
    ├── conversation-action-platform.md # 会話 → Action / Draft 変換の設計正本
    ├── knowledge-pipeline-v2.md # Wiki / 長期記憶レイヤーの設計
    └── amical-longterm-memory-handoff.md # 現在の作業引き継ぎ
```

## 今後の拡張（Phase 2+）

- 飲食店 domain schema と Action Candidate 生成
- Review Queue（承認 / 編集 / 却下）
- Connector Draft（予約台帳、発注、在庫、顧客メモ、SOP）
- Google Sheets / Notion / webhook などの初期 connector
- 建設、医療、福祉、教育向け domain schema
- 現場固有の用語、ルール、SOP 学習
- Lambda + SQS による自動パイプライン

## 参考

- 企画書: `docs/ambient-agent-spec.md`
- ドキュメント入口: `docs/README.md`
- 会話 → 業務アクション変換設計: `docs/conversation-action-platform.md`
- Wiki / 長期記憶レイヤー設計: `docs/knowledge-pipeline-v2.md`
- 現在の作業引き継ぎ: `docs/amical-longterm-memory-handoff.md`
- アンビエント録音デバッグ手順: `docs/ambient-recording-debug-checklist.md`
- モデルプロジェクト: `/Users/kaya.matsumoto/projects/watchme/business`
- WatchMe インフラ: `/Users/kaya.matsumoto/projects/watchme/server-configs`

## 最近の変更（運用メモ / 2026-03-25）

### Home UI redesign toward Conversation Action Platform（2026-04-25）

ホーム画面を、録音ログの一覧ではなく「現場会話が処理されて知識・アクションへ流れる」ことを
一目で見るための performance surface として再設計中。

- 画面上部に Ambient recording strip を固定表示
- メインを 3 レーン構成に変更
  - 左: `Cards / Topics`
  - 中央: `Intake / Processing`
  - 右: `Wiki / Knowledge`
- 左レーンは録音中 / upload中 / ASR中の pending topic も表示
- 中央レーンは `Cards -> Intake -> Wiki` の flow history を表示
- flow row に animated connector を入れ、情報が左から右へ流れる見せ方を追加
- 右レーンは選択Topicの Fact に近い Wiki page を上に寄せる

次の焦点:

- intake / action 処理履歴の永続化
- Action Candidate / Review Queue / Connector Draft の追加
- 左Topic、中央Intake、右Wikiをまたぐ接続線の可視化
- Timeline タブを過去情報アクセス用に再設計

## 過去の変更

### UIリデザイン（全面刷新 / 2026-03-25）

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
