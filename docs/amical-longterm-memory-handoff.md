# Current Handoff

更新日: `2026-04-25`

この文書は、次のセッションで ZeroTouch 開発を再開するための最新メモです。

最上位の設計正本は [`conversation-action-platform.md`](./conversation-action-platform.md)。
ZeroTouch は、現場会話を録音・保存するアプリではなく、
会話を **業務アクション候補、外部システム用の下書き、長期記憶** に変換する基盤として進める。

---

## 現在のゴール

```text
現場会話
  -> ASR / Card / Topic
  -> Fact / Wiki / Context
  -> ZeroTouch Converter
  -> Action Candidate / Draft
  -> Human Review
  -> SaaS / ERP / 業務システム
```

最初は飲食店の例で作る。

ただし、ZeroTouch は飲食店専用ではない。
同じ仕組みを、建設、医療、福祉、教育などにも展開する前提で設計する。
業界・個社ごとに出力先 SaaS / ERP は変わるため、connector は今後増え続ける。

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
| S3 upload | 実装済み |
| ASR | Speechmatics / Deepgram / Azure Speech Service 稼働 |
| `zerotouch_sessions` | Card / 発話単位として利用 |
| `zerotouch_conversation_topics` | Topic / 会話区間として利用 |
| live topic model | Card と同時に active topic へ紐付ける方針 |

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

## 未実装

Action / Connector レイヤーはまだ未実装。

必要なもの:

- domain schema registry
- restaurant domain schema
- intent extraction for action
- `zerotouch_action_candidates`
- `zerotouch_action_candidate_sources`
- `zerotouch_action_reviews`
- `zerotouch_connector_drafts`
- `zerotouch_connector_runs`
- Review Queue UI
- 初期 connector

---

## 次に作るもの

### 1. 飲食店 domain schema

最初の参照実装として、飲食店の intent と required fields を定義する。

候補:

| intent | 説明 | 出力例 |
|--------|------|--------|
| `change_guest_count` | テーブル人数変更 | 予約台帳の人数変更下書き |
| `booking_update` | 予約の新規 / 変更 | 予約システム更新下書き |
| `stock_alert` | 在庫不足 / 補充 | 発注システムへの補充メモ |
| `quality_issue` | 顧客フィードバック / 品質問題 | 顧客メモ、再発防止タスク |
| `equipment_request` | 備品や設備の依頼 | 店舗運用タスク |
| `shift_note` | 人員、シフト、休憩の調整 | シフト管理メモ |

### 2. Action Candidate schema

最小テーブルを追加する。

```text
zerotouch_action_candidates
zerotouch_action_candidate_sources
zerotouch_action_reviews
zerotouch_connector_drafts
zerotouch_connector_runs
```

最初は外部 SaaS を直接更新しない。
`status=pending` の下書きを作り、人間が確認してから反映する。

### 3. Converter service

Topic / Fact / Context Profile / Wiki を入力にして、Action Candidate を生成する。

最初は batch / manual trigger でよい。
リアルタイム化は後で行う。

### 4. Review Queue

Android か WebViewer に、承認待ちの下書きを表示する。

必要な操作:

- 根拠会話を見る
- payload を確認する
- 編集する
- 承認する
- 却下する

### 5. 初期 connector

最初から特定SaaSの本番 API 更新に寄せない。

候補:

- JSON export
- Google Sheets
- Notion database
- Slack notification
- webhook

その後、予約台帳、発注、在庫、POS などへ広げる。

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
