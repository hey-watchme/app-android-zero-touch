# Conversation Action Platform

更新日: `2026-04-25`

この文書は、ZeroTouch の現在のプロダクト方向を定義する設計正本です。

ZeroTouch の価値は、現場の会話を録音・文字起こしして保存することではなく、
会話を **業務システムが処理できる入力、下書き、タスク、ナレッジ** に変換することにある。

```text
現場の会話
  -> ASR / Card / Topic
  -> ZeroTouch Converter
  -> Action Candidate / Draft
  -> Human Review
  -> SaaS / ERP / 業務システム
  -> Wiki / SOP / 長期記憶
```

## 方針

ZeroTouch は業界特化アプリではなく、現場会話をデジタル業務へ接続する変換レイヤーである。

対象になりうる現場:

- 飲食店
- 建設現場
- 医療、看護、福祉
- 教育、授業、学習支援
- 小売、倉庫、製造
- その他、口頭判断や暗黙知が業務入力から漏れやすい現場

仕組みは共通だが、出力先とスキーマは業界・企業ごとに変わる。
そのため、コアは汎用化し、外部システム連携は adapter / connector として増やしていく。

## 最初の参照ケース

最初は飲食店の事例で作る。

理由:

- 会話から業務イベントを読み取りやすい
- 予約、人数変更、在庫、品質問題、SOP など出力先が明確
- SaaS / ERP への「下書き」を見せやすい
- 価値が「記録」ではなく「変換」にあることを説明しやすい

例:

| 会話 | Intent | Action Draft |
|------|--------|--------------|
| `4番テーブル、1名追加で` | `change_guest_count` | 予約台帳の人数変更下書き |
| `冷蔵庫のレモン切れてる` | `stock_alert` | 発注システムへの補充メモ |
| `3番、味付けが濃いって言われた` | `quality_issue` | 顧客メモ、再発防止タスク |
| `金曜のコース、20時から2名追加で予約入れて` | `booking_update` | 予約システム更新下書き |

## 業界展開の考え方

同じ Converter を使い、domain schema と connector を差し替える。

### 教育、授業

| Intent | 出力例 |
|--------|--------|
| `attendance_note` | 出欠、遅刻、早退メモ |
| `assignment_missing` | 未提出課題の確認タスク |
| `student_follow_up` | 個別フォロー候補 |
| `parent_contact_draft` | 保護者連絡の下書き |
| `lesson_improvement` | 授業改善メモ、教材改善タスク |
| `lms_update_draft` | LMS / Google Classroom への更新下書き |

### 建設現場

| Intent | 出力例 |
|--------|--------|
| `safety_issue` | 安全指摘、KY 記録 |
| `material_shortage` | 資材不足、発注依頼 |
| `work_progress_update` | 進捗報告、日報下書き |
| `inspection_note` | 是正事項、写真台帳への追記 |
| `schedule_risk` | 工程遅延リスク、調整タスク |

### 医療、福祉

| Intent | 出力例 |
|--------|--------|
| `care_note` | ケア記録の下書き |
| `incident_risk` | ヒヤリハット候補 |
| `medication_follow_up` | 服薬確認、申し送り |
| `family_contact_draft` | 家族連絡の下書き |
| `handover_note` | 申し送りメモ |

医療・福祉では誤記録のリスクが高いため、初期段階では必ず human review を挟む。

## レイヤー構造

### 1. Raw Capture

現場音声を取得し、発話単位に分解する。

既存実装:

- Android Ambient Recording
- ASR provider: Speechmatics / Deepgram / Azure Speech Service
- `zerotouch_sessions` as Card
- `zerotouch_conversation_topics` as Topic

### 2. Knowledge Layer

会話を Fact / Wiki / Query に変換し、長期的な現場知識として蓄積する。

既存実装:

- scoring
- annotation
- wiki ingest
- wiki query
- filing-back

このレイヤーは今後も重要だが、最終ゴールではない。
Action Candidate を作るための根拠、補助記憶、SOP、文脈として使う。

### 3. Converter Layer

発話、Topic、Fact、Context Profile、Wiki を入力にして、業務イベントを抽出する。

出力するもの:

- intent type
- extracted fields
- confidence
- source session / topic / fact
- destination candidates
- review requirements

例:

```json
{
  "intent_type": "change_guest_count",
  "title": "4番テーブルの人数変更",
  "payload": {
    "table": "4",
    "delta": 1,
    "unit": "person"
  },
  "confidence": 0.96,
  "requires_review": true,
  "sources": {
    "session_ids": ["..."],
    "topic_id": "..."
  }
}
```

### 4. Action Candidate / Draft

外部システムに送れる形の下書きを作る。

ここではまだ外部システムを確定更新しない。
まず human review に出す。

想定テーブル:

- `zerotouch_action_candidates`
- `zerotouch_action_candidate_sources`
- `zerotouch_action_reviews`
- `zerotouch_connector_drafts`
- `zerotouch_connector_runs`

`zerotouch_action_candidates` の最小フィールド:

| field | meaning |
|-------|---------|
| `id` | action candidate id |
| `workspace_id` | 対象 workspace |
| `device_id` | 入力元 device |
| `domain` | `restaurant`, `education`, `construction`, `healthcare` など |
| `intent_type` | `change_guest_count`, `stock_alert` など |
| `title` | 人間向けタイトル |
| `summary` | 下書きの要約 |
| `payload` | JSONB。業務イベントの構造化データ |
| `destination` | 出力先候補。例: `toreta`, `askul`, `notion` |
| `status` | `pending`, `approved`, `rejected`, `exported`, `failed` |
| `confidence` | 0.0 - 1.0 |
| `requires_review` | 人間承認が必要か |
| `created_at` | 作成時刻 |

### 5. Human Review

ZeroTouch は最初から完全自動更新を目指さない。

初期 UX:

- 下書きを見る
- 根拠になった会話を見る
- 編集する
- 承認して反映する
- 却下する

信頼度、業界リスク、出力先の重要度に応じて、自動化レベルを上げる。

### 6. Connector Layer

外部システムへ下書きまたは確定データを送る。

最初は実 API 連携にこだわらず、以下から始める。

- CSV export
- webhook
- Google Sheets
- Notion database
- Slack notification
- 手動確認リンク

その後、業界・個社ごとの connector を追加する。

飲食店の例:

- 予約台帳
- POS
- 発注システム
- 在庫管理
- 勤怠、シフト
- 顧客メモ、SOP

教育の例:

- LMS
- Google Classroom
- 校務支援
- 出欠管理
- 保護者連絡
- 課題管理

## 現在の実装とのギャップ

実装済み:

- 音声取得
- ASR
- Card / Topic
- Fact / Wiki
- Query / filing-back
- Android / Web viewer

未実装:

- domain schema registry
- intent extraction for action
- action candidate table
- review queue
- connector draft
- connector run history
- industry-specific adapters

## 次に作るもの

1. 飲食店 domain schema を定義する
2. `zerotouch_action_candidates` の migration を追加する
3. Topic / Fact から action candidate を生成する backend service を作る
4. Android または WebViewer に Review Queue を作る
5. 最初の connector は実 API ではなく、Notion / Google Sheets / JSON export から始める
6. その後、予約台帳や発注システムへの連携を検証する

## 既存ドキュメントの位置づけ

- `knowledge-pipeline-v2.md`
  - Wiki / 長期記憶レイヤーの設計として残す
  - ただし、プロダクト全体の最上位正本ではない
- `context-enrichment-project.md`
  - Converter の精度を上げるための文脈入力として継続利用する
- `wiki-project-category-page-design.md`
  - Wiki を業務知識、SOP、Entity として整理するために使う
- `account-workspace-org-design.md`
  - 業界・企業・現場単位の所有モデルとして継続利用する
