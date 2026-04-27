# PoC: Knowledge Worker Domain

更新日: `2026-04-27`

この文書は、ZeroTouch の **最初の PoC 実装** を「ナレッジワーカー（オフィスワーカー）」ドメインで作るための設計メモです。

最上位の設計正本は [`conversation-action-platform.md`](./conversation-action-platform.md)。
本文書はその下位仕様として、最初の PoC を「飲食店」ではなく「自分の業務（デザイン / プロジェクト推進 / 発注 / 会議設定 / 社内オペレーション）」で作る判断と、その必要データ・出力カタログを定義する。

---

## なぜ Knowledge Worker ドメインから始めるか

最初の PoC として、当初は飲食店を想定していた。
ただし以下の理由で、まず **自分の業務（ナレッジワーカー）** で完成させる。

- 飲食店だと正確な現場データを継続的に取れない
- 自分の業務であれば、毎日 ambient 録音を回せる
- 出力先 SaaS（Google Workspace / Jira / Slack / Email / freee / SmartHR）が明確で評価しやすい
- 「会話 → デジタル成果物」の変換ループを自分で評価できる
- プレゼン用に、現場 → 成果物の流れを通して見せられる

この PoC が完成すれば、ZeroTouch の **構造的完成形**（Conversation Action Platform）が一度通る。
その後、飲食店・建設・医療・教育などの domain schema と connector を差し替えて横展開する。

---

## 対象ドメイン

`domain = knowledge_worker`

想定される役割:

- デザイナー / クリエイティブディレクター
- プロジェクトマネージャー / プロデューサー
- 社内オペレーション / バックオフィス担当
- ベンダー対応 / 発注担当

業務の特徴:

- 会話の多くが「次の成果物」のための入力になる
- その成果物は SaaS / ERP / メール / Slack / Doc に流れる
- 単発タスクだけでなく、判断理由・暗黙知が残らないと回らない
- 出力先は固定的（Google Workspace, Jira, Slack, Email, freee, SmartHR など）

---

## 出力カタログ（6 種類）

ZeroTouch がこのドメインで生成すべき成果物は以下の 6 種類。

### 1. Task

最も基本。会話から最も自然に生まれる成果物。

- Jira issue draft
- Jira subtask draft
- 個人 ToDo
- 期限つきフォローアップ
- 誰待ちかの依存関係メモ
- ブロッカー一覧

### 2. Communication 下書き

会話のあとに Slack / メールで書く文の下書き。

- Slack 返信案 / 共有文案
- 社内確認メッセージ
- ベンダー向け依頼メール
- お礼 / 催促 / 確認メール
- MTG 後の要点共有文

### 3. Schedule 下書き

会話から発生する予定や調整。

- Google Calendar イベント案
- 日程調整候補
- 会議アジェンダ案
- リマインダー
- 定例化候補
- フォローアップ日時の提案

### 4. 発注 / 申請成果物

業務オペレーションの中核。

- ノベルティ / 印刷物の発注依頼下書き
- 見積依頼文
- freee 申請の下書き項目
- 稟議の要点整理
- 発注先比較メモ

### 5. ナレッジ

単発タスクではなく、後で効く蓄積。

- 意思決定ログ
- プロジェクト変更履歴
- ベンダーやり取り要約
- デザイン判断の理由
- 過去事例の再利用メモ
- 暗黙知のメモ

### 6. ステータス更新

地味だが重要。会話 → プロジェクト状態の更新。

- 進行状況の更新文
- 今日進んだこと / 止まっていること
- 誰にボールがあるか
- 次のマイルストーン
- レビュー / 発注待ち一覧

---

## PoC の最小構成（最初に作るもの）

6 種類すべてを最初から作らない。
**4 種類** に絞り、ここで「会話 → 成果物」の変換ループが回ることを示す。

| Output | 出力先 | 最小フィールド |
|--------|-------|----------------|
| Task | Jira draft / 個人 ToDo | title, what, who, due |
| Communication 下書き | Slack / Email | channel or recipient, subject, body |
| Schedule 下書き | Google Calendar | title, attendees, candidate datetimes, agenda |
| 意思決定ログ | Wiki / Doc | decision, options, reason, owner, date |

PoC 成功の定義:

> 1 日の ambient 録音から、Jira 1 件 / Slack 1 本 / Calendar 1 件 / 決定ログ 1 件が自然に生成される

---

## ユースケース（参照シナリオ）

実装と評価で使う 5 つの代表シナリオ。

### A. デザインレビュー会話

**入力例**

> 「このバナー、スマホだと文字が詰まって見える」
> 「見出しだけもう少し強くしたい」
> 「これは来週水曜までに必要」
> 「英語版は後でいい」

**Intent 候補**: `design_change_request`, `priority_update`, `deadline_confirmed`, `scope_split`

**出力例**:

- Jira チケット更新案（修正内容、期限、スコープ分離）
- デザイナー向け Slack 要約
- 「英語版は後続対応」スコープメモ
- 決定事項ログ

### B. 発注関連の会話

**入力例**

> 「ノベルティ、500 個で見積取ろう」
> 「予算は 30 万以内」
> 「箱は高そうだから簡易包装で」
> 「来月のイベント前には欲しい」

**Intent 候補**: `procurement_request`, `budget_constraint`, `spec_change`, `deadline_constraint`

**出力例**:

- ベンダー向け見積依頼メール下書き
- 発注仕様メモ（数量 / 予算 / 仕様 / 納期）
- freee 申請下書き
- 比較表作成タスク
- 承認依頼メモ

### C. 会議設定の会話

**入力例**

> 「来週 Jose さんとも話したい」
> 「30 分で大丈夫」
> 「この件だけ議題にしたい」
> 「横江さんも入れた方がいい」

**Intent 候補**: `meeting_request`, `participant_add`, `agenda_seed`, `duration_defined`

**出力例**:

- Google Calendar イベント案
- 参加者候補
- アジェンダ下書き
- 日程確認 Slack / メール文案

### D. プロジェクト推進会話

**入力例**

> 「これは Pressance 側の確認待ちですね」
> 「トップの導線がまだ未完成」
> 「公開は確認不要で進めていい」
> 「サムネイルだけ Jose さんにお願いしたい」

**Intent 候補**: `dependency_identified`, `status_update`, `approval_granted`, `task_assignment`

**出力例**:

- プロジェクトステータス更新文
- Jira 担当振り分け
- Slack 依頼文
- 公開条件メモ
- 待ち項目一覧

### E. 社内オペレーション会話

**入力例**

> 「T シャツの発注、サイズ確認必要」
> 「横江さんに在庫を聞きましょう」
> 「月曜に棚卸したい」
> 「チャンネル作って管理したい」

**Intent 候補**: `inventory_check_request`, `followup_needed`, `meeting_candidate`, `workflow_change_idea`

**出力例**:

- Slack 文案
- 在庫確認タスク
- Calendar 仮予定
- 新規チャンネル説明文案
- オペ改善メモ

---

## Knowledge Worker domain schema

PoC で扱う Intent 型と必須フィールドを最初に固定する。
スキーマは `zerotouch_action_candidates.payload` (JSONB) に格納する。

### Intent カタログ（PoC 初期）

| intent_type | output category | 必須フィールド | destination 候補 |
|-------------|-----------------|----------------|------------------|
| `task_create` | Task | title, owner, due, source_quote | Jira / Notion / Doc |
| `followup_create` | Task | who_waits_for, what, by_when | Personal ToDo |
| `slack_message_draft` | Communication | recipient_or_channel, intent (確認/共有/依頼/催促), body | Slack |
| `email_draft` | Communication | recipient, subject, body, tone | Gmail draft |
| `meeting_request` | Schedule | title, attendees, duration, agenda, candidate_dates | Google Calendar |
| `agenda_draft` | Schedule | meeting_title, points, decisions_needed | Google Doc |
| `procurement_request` | 発注 | item, quantity, budget, deadline, vendor_candidates, spec | freee / メール |
| `expense_application_draft` | 発注 | purpose, amount, category, approver | freee |
| `decision_log` | Knowledge | decision, options, reason, owner, date, source_quote | Wiki / Notion |
| `vendor_note` | Knowledge | vendor, topic, summary, follow_up | Wiki / Notion |
| `status_update` | Status | project, progress, blockers, next_step, ball_owner | Slack / Doc |

それぞれの intent は最初は **下書き** であり、Human Review を経て初めて connector へ流れる。

### Knowledge Worker 固有の context profile

`zerotouch_context_profiles` を以下の知識で拡張・利用する（既存 JSONB カラムを使う）。

- 自分の役割と所属
- 進行中プロジェクト一覧（プロジェクト名 / Jira key / Slack channel / 関係者）
- よく出るベンダー名 / 取引先 / 金額レンジ
- 主要関係者（フルネーム / 呼び方の揺れ / 役割 / 所属）
- 出力先のデフォルト
  - Jira: project key, default assignee
  - Slack: default workspace, よく投げるチャンネル
  - Google Workspace: domain, calendar id
  - Email: 送信元 / 署名

このコンテクストは Converter の精度に直結する。
PoC 開始時に「自分用 context profile」を 1 件しっかり書くことが必須前提。

---

## 必要データ整理

### 入力データ（既存パイプラインで取得済み）

- ambient 録音 (`zerotouch_sessions` の Card)
- Topic 区切り (`zerotouch_conversation_topics`)
- Fact 抽出 (`zerotouch_facts`)
- Wiki / 長期記憶 (`zerotouch_wiki_pages` / `zerotouch_wiki_index`)

### 入力データ（PoC のために整備が必要）

- Knowledge Worker 用 context profile（自分の役割 / プロジェクト一覧 / 関係者 / 出力先デフォルト）
- 主要関係者の表記揺れ辞書（例: 「Jose さん」「ホセさん」 → 同一人物）
- プロジェクト辞書（プロジェクト名 → Jira key, Slack channel, 関係者）
- ベンダー辞書（ベンダー名 → 連絡先 / 過去発注履歴 / 平均納期）

→ これは context profile の `analysis` JSONB に格納する。

### 中間データ（Converter が出力）

- Intent 型
- 抽出フィールド（payload）
- confidence
- 根拠会話（session_ids / topic_id / quote）
- destination 候補
- requires_review

### 出力データ（Action Candidate / Connector Draft）

- `zerotouch_action_candidates`（候補本体）
- `zerotouch_action_candidate_sources`（根拠 Card / Topic / Fact / Quote）
- `zerotouch_action_reviews`（承認 / 編集 / 却下履歴）
- `zerotouch_connector_drafts`（出力先別 payload）
- `zerotouch_connector_runs`（実行履歴 / 連携結果）

スキーマは [`conversation-action-platform.md`](./conversation-action-platform.md) の最小定義に従い、`domain = knowledge_worker` で運用する。

---

## Connector レイヤー（PoC 段階）

実 API 連携は **後回し**。最初は人間が 1 クリックでコピペできる「下書き表示」で十分。

| 段階 | connector | 内容 |
|------|-----------|------|
| Phase 1 | `inline_draft` | Review Queue 上で下書きを表示するだけ |
| Phase 1 | `json_export` | JSON ダウンロード |
| Phase 2 | `slack_draft` | Slack に下書きとして投稿（自分宛 DM） |
| Phase 2 | `gmail_draft` | Gmail の draft 作成（送信はしない） |
| Phase 2 | `google_calendar_draft` | Calendar に「仮」イベント作成 |
| Phase 3 | `notion_database` | Notion DB に行追加 |
| Phase 3 | `jira_issue_draft` | Jira draft 作成（Status: Draft） |
| Phase 4 | `freee_form_prefill` | freee 申請の URL prefill |

ポイント:

- どの connector も **送信 / 確定はしない**。下書きを置くだけ
- 承認は Review Queue 上で人間が押す
- connector が増えても、Action Candidate スキーマは変わらない

---

## 実装段階

### Phase 1: Manual Converter + Inline Review

1. `zerotouch_action_candidates` 系テーブルの migration を追加
2. domain schema registry を `backend/services/domain_schemas/knowledge_worker.py` に作る
3. `wiki_ingestor` と同じ流儀で `action_converter` service を作る
   - 入力: 1 つの Topic + その Fact + context profile
   - 出力: 0..N の action candidate
4. 手動トリガー API: `POST /api/action-candidates/from-topic/{topic_id}`
5. WebViewer に Review Queue タブを追加（一覧 / 詳細 / 根拠会話 / 承認 / 却下 / 編集）
6. Inline draft 表示のみ（外部送信はしない）

→ ここまでで PoC の最小ループが回る。
→ 自分の業務会話で 1 週間運用し、出力品質を評価する。

### Phase 2: First Real Connector

1. Slack draft（自分宛 DM）
2. Gmail draft 作成
3. Google Calendar 仮イベント
4. Review Queue 上で「下書き作成」ボタン
5. `zerotouch_connector_drafts` / `zerotouch_connector_runs` を埋め始める

### Phase 3: Auto-trigger + Notion / Jira

1. Topic finalize → 自動で converter 起動
2. Notion DB / Jira draft connector 追加
3. confidence しきい値で自動下書き作成 vs 手動承認の切り替え

### Phase 4: 横展開

- 飲食店 domain schema
- 教育 domain schema
- 業界 connector（Toreta / askul など）

---

## PoC の評価指標

数値で見るのは以下。

- **被覆率**: 1 日の Topic 数のうち、Action Candidate が 1 件以上生成された割合
- **精度（人間判定）**: 承認率 / 編集率 / 却下率
- **時間短縮**: 同じ成果物を手動で書いた時間との比較（自己計測）
- **抜け漏れ防止**: 録音由来でキャッチできた依頼・約束の数（主観評価）
- **出力先カバレッジ**: 6 出力カテゴリのうち何種類を実運用に使えたか

PoC 成功条件:

> 1 週間連続して、毎日少なくとも 1 つの実用的な成果物（Jira / Slack / Calendar / 決定ログ）が ambient 録音から自動下書きとして生成され、人間がそれを承認・編集して使った

---

## 既存実装との接続

| 既存 | PoC で使う形 |
|------|-------------|
| ambient 録音 / ASR / Card / Topic | そのまま入力。変更不要 |
| Topic scoring / annotation / Fact | Converter の入力として使う |
| Wiki / 長期記憶 | Converter の補助文脈として参照（過去の決定 / ベンダー履歴 / プロジェクト state） |
| Wiki Query | Review 時の根拠検索に使う |
| context profile | Knowledge Worker 用にスキーマ拡張 |
| Home Dashboard 3 レーン | 中央レーンに `Action Candidate` を流す |

ホーム画面 3 レーンの中央 `Intake / Processing` レーンが、そのまま Action Candidate のリアルタイム表示先になる。
左 `Cards / Topics` から右 `Wiki / Knowledge` への流れに、中央で `Action Candidate / Draft / Review` が挟まる構造。

---

## 次の作業

1. 本ドキュメントをレビュー、確定
2. Knowledge Worker 用 context profile を 1 件書き起こす（自分の役割 / プロジェクト / 関係者 / 出力先）
3. `zerotouch_action_candidates` 系 migration（014）を書く
4. `backend/services/domain_schemas/knowledge_worker.py` で intent カタログを実装
5. `backend/services/action_converter.py` を作成（Topic + Fact + context → action candidates）
6. 手動トリガー API を追加: `POST /api/action-candidates/from-topic/{topic_id}`
7. WebViewer に Review Queue タブを追加
8. 1 週間の自己 PoC 運用 → 評価 → 改善

---

## 参照

- 最上位設計: [`conversation-action-platform.md`](./conversation-action-platform.md)
- Wiki / 長期記憶: [`knowledge-pipeline-v2.md`](./knowledge-pipeline-v2.md)
- Context Profile 拡張: [`context-enrichment-project.md`](./context-enrichment-project.md)
- Wiki 分類: [`wiki-project-category-page-design.md`](./wiki-project-category-page-design.md)
- 現在の作業引き継ぎ: [`amical-longterm-memory-handoff.md`](./amical-longterm-memory-handoff.md)
