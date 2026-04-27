# Current Handoff

更新日: `2026-04-27`（Slice 1 デプロイ完了）

この文書は、次のセッションで ZeroTouch 開発を再開するための最新メモです。

最上位の設計正本は [`conversation-action-platform.md`](./conversation-action-platform.md)。
Ambient pipeline の直近リファクタリング詳細は [`ambient-pipeline-refactor-handoff.md`](./ambient-pipeline-refactor-handoff.md)。
Knowledge Worker PoC 設計は [`poc-knowledge-worker-domain.md`](./poc-knowledge-worker-domain.md)。
ZeroTouch は、現場会話を録音・保存するアプリではなく、
会話を **業務アクション候補、外部システム用の下書き、長期記憶** に変換する基盤として進める。

---

## 現在のゴール

```text
現場会話
  -> ASR / Card / Topic
  -> Fact / Wiki / Context
  -> ZeroTouch Converter  ← Slice 1 完成・デプロイ済み
  -> Action Candidate / Draft
  -> Human Review
  -> SaaS / ERP / 業務システム
```

最初のドメインは `knowledge_worker`（デザイン / PM / ベンダー調整）。
自分の業務会話で PoC を進め、構造的完成形を確認してから他ドメインへ横展開する。
（飲食店 domain schema は後続フェーズ）

---

## 実装済み

### Home UI / Conversation Action Surface

2026-04-25 に、ホーム画面を Conversation Action Platform の方向へ寄せた。

実装:

- `HomeDashboardScreen.kt` を追加
- ホームタブだけ従来の `WorkspaceHeader` を外し、画面最上部に固定 Ambient recording strip を配置
- メイン領域を 3 レーンに変更
  - 左: `Cards / Topics`
  - 中央: `Intake / Processing`
  - 右: `Wiki / Knowledge`
- 左レーンは確定済み `topicCards` に加えて、録音中 / upload中 / ASR中の pending topic も表示する
- 中央レーンは最近の Topic を `Cards -> Intake -> Wiki` の flow history として表示する
- flow row には animated connector を入れ、ノード間を情報が流れているように見せる
- 行をタップすると、その Topic の processing steps と extracted facts が下に表示される
- 右レーンは `wiki-pages` API から Wiki を取得し、選択Topicの fact categories / intents / entities に近いページを上に寄せる

現在の制約:

- 録音中の live transcript streaming は未実装。ASR は録音セッション終了後に反映される
- 中央レーンの履歴は永続テーブルではなく、現在読み込んでいる Topic / Fact から再構成している
- `Action Candidate` と connector draft はまだ未実装で、中央レーンでは `未実装` として表示している
- Mockup のように左レーンから中央、中央から右レーンへまたがる曲線接続はまだ未実装

次にやるなら:

1. `zerotouch_intake_events` または `zerotouch_action_candidates` を追加し、処理履歴を永続化する
2. 中央レーンの `Cards -> Intake -> Wiki` を `Cards -> Intent -> Action Draft -> Wiki/Connector` へ拡張する
3. Canvas overlay で、左 Topic card から中央 flow node、中央 flow node から右 Wiki row へ接続線を引く
4. Review Queue を追加し、Action Candidate を承認 / 編集 / 却下できるようにする
5. Timeline タブは後続で、過去情報にすぐアクセスするための画面として再設計する

### Capture / ASR / Card / Topic

| 項目 | 状態 |
|------|------|
| Android Ambient Recording | 実装済み |
| S3 upload | 実装済み。`local_recording_id` による idempotency を追加 |
| ASR | Speechmatics / Deepgram / Azure Speech Service 稼働 |
| `zerotouch_sessions` | Card / 発話単位として利用 |
| `zerotouch_conversation_topics` | Topic / 会話区間として利用 |
| live topic model | Card と同時に active topic へ紐付ける方針 |
| Topic finalize | idle finalize は backend scheduler、Ambient Off は Android の明示 trigger |

2026-04-27 checkpoint:

- Android の `loadSessions` / `refreshSessions` から `evaluate-pending` mutation を削除
- `transcribe` trigger retry と API 例外分類を追加
- WebRTC VAD 設定は未実装なので Silero に fallback
- `Mp4AudioWriter` の release を `finally` で保証
- `backend/migrations/015_add_session_upload_idempotency.sql` を追加
- in-process scheduler は複数 worker 起動で重複実行リスクあり。次は advisory lock または Lambda / EventBridge 化

### Knowledge Layer

| 項目 | 状態 |
|------|------|
| Topic scoring | 実装済み |
| Topic annotation / Fact extraction | 実装済み |
| Wiki ingest | 実装済み |
| Wiki query | 実装済み |
| filing-back | 実装済み |
| Wiki index / log | 実装済み |
| Android Wiki / Query | WebView ハイブリッドで実装済み |

このレイヤーは今後も使う。
ただし、プロダクトの最終ゴールは Wiki そのものではなく、
Action Candidate を作るための根拠、文脈、SOP、長期記憶として位置づける。

---

## Slice 1 完了（2026-04-27 デプロイ済み）

### 実装内容

| ファイル | 内容 |
|---------|------|
| `backend/migrations/016_create_action_candidates.sql` | `zerotouch_action_candidates` テーブル（JSONB payload/sources/review_state）|
| `backend/services/domain_schemas/__init__.py` | domain registry |
| `backend/services/domain_schemas/knowledge_worker.py` | `email_draft` intent 定義 + LLM プロンプトビルダー |
| `backend/services/action_converter.py` | Topic+Facts → Action Candidates 変換、idempotency/supersede/review ロジック |
| `backend/app.py` | 4 エンドポイント追加 |
| `api/ZeroTouchApi.kt` | ActionCandidate data class + 3 API メソッド |
| `ui/HomeDashboardScreen.kt` | 中央レーンのフッターを「Action 候補を生成」ボタンに差し替え。右レーンの SAAS_SLOTS ダミーを EmailDraftCard に差し替え |

### API エンドポイント（本番稼働中）

```
POST /api/action-candidates/from-topic/{topic_id}  → Action Candidate 生成
GET  /api/action-candidates                         → 一覧（device_id/topic_id/status/intent_type/limit）
GET  /api/action-candidates/{candidate_id}          → 詳細
POST /api/action-candidates/{candidate_id}/review   → approve / reject / edit
```

### Slice 1 の制約（意図的な未実装）

- intent は `email_draft` のみ。他は Slice 2 以降
- 自動トリガーなし（Topic finalize 後、手動でボタンをタップ）
- Gmail 実 API 連携なし。Android の Copy ボタンでクリップボード経由
- payload の直接編集なし（approve / reject のステータス更新のみ）

---

## 次に作るもの（Slice 2 候補）

### 1. Android ビルドして E2E テスト

1. Android Studio で Run → redmi-001 にインストール
2. 業務会話で「〇〇さんからメール来たから返信しなきゃ」とつぶやく
3. Topic finalize 後、「Action 候補を生成」をタップ
4. 右レーンに Email Draft Card が出現するか確認
5. Copy → Gmail に貼り付けて検証

### 2. slack_message_draft intent の追加（Slice 2）

`knowledge_worker.py` に intent を 1 件追加するだけ。
converter prompt と normalize_payload を拡張。

### 3. Gmail draft API 連携（Slice 2）

Copy ボタンの代わりに「Gmail に下書き作成」ボタン。
Google Sign-In + Gmail API (`POST /gmail/v1/users/me/drafts`)。
送信はしない。下書き作成のみ。

### 4. 自動トリガー（Slice 3）

Topic finalize webhook → `POST /api/action-candidates/from-topic/{topic_id}` を自動呼び出し。
confidence しきい値（≥0.8）で自動承認、それ以下は pending に残す。

---

## 現在のデータメモ

### amical-db-test

- Amical 由来の検証データ
- 2026-03-02 から 2026-03-06 まで投入済み
- Wiki / Query の検証に使っていたデータセット

### redmi-001

- 実機 Android データ
- context profile 紐付け済み
- 実機データに対する Action Candidate 生成は未実施

---

## ローカル起動

```bash
cd /Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend
set -a && source .env && set +a && python3 app.py
# -> http://localhost:8061
```

```bash
cd /Users/kaya.matsumoto/projects/watchme/app/web-zero-touch
npm run dev
# -> http://localhost:3000
```

WebViewer をローカル backend に向ける場合は、
`web-zero-touch/.env.local` の `ZEROTOUCH_API_BASE_URL` を `http://localhost:8061` にする。

---

## 参照

- 最上位設計: [`conversation-action-platform.md`](./conversation-action-platform.md)
- Wiki / 長期記憶: [`knowledge-pipeline-v2.md`](./knowledge-pipeline-v2.md)
- Context Profile: [`context-enrichment-project.md`](./context-enrichment-project.md)
- Wiki分類設計: [`wiki-project-category-page-design.md`](./wiki-project-category-page-design.md)
