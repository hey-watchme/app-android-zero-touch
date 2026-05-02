---
作成日: 2026-05-02
ステータス: 構想（未実装）
位置づけ: 将来やりたいことを集約。優先順位は現状ステータス（`docs/README.md`）に従う
---

# ZeroTouch Roadmap

このドキュメントは **将来の TODO** を集約する場所。  
ここに書かれているものは「やりたいこと」「やる予定があるもの」であり、**実装済みではない**。

実装済み機能・現状ステータスは `docs/README.md` と各種設計書を参照。

---

## 0. 直近の最優先タスク

詳細は `docs/README.md` の現状ステータスを参照。

1. **従来の Conversation パイプラインを再度動かす**  
   Card → Topic → Fact → Wiki の本体パイプラインが現在止まっている。Live Support と並列で走らせる元の設計に戻す。
2. Conversation パイプラインが安定稼働してから、以下のロードマップに着手する。

---

## 1. Live Support の拡張案

現状の Live Support は「リアルタイム文字起こし + 翻訳」だけ。次の拡張が候補。

### 1.1 Take-Home / 持ち帰り体験
- タブレット画面の QR コードをスキャンして、会話のサマリ・要点・タイムスタンプを Web で持ち帰れるページ
- ログイン不要 / 物理的にその場にいた人だけが恩恵を受ける
- 想定ユースケース: 薬剤師の服薬説明、不動産契約説明、学校面談、行政窓口

### 1.2 リアルタイム要点抽出
- 会話中に「論点」「補足」「決定事項」「QA」をリアルタイム生成して画面表示
- `zerotouch_live_keypoints` テーブルは予約済み（現在未使用）

### 1.3 翻訳の文脈強化
- 前後の句を翻訳リクエストに追加して文脈を保つ
- 用語集（glossary）を翻訳プロンプトに注入

### 1.4 多言語化
- 現在は日 → 英のみ。中・韓・ベトナム語などの追加を検討

---

## 2. ZeroTouch 本体パイプラインの拡張

Conversation パイプラインが復活してから着手するもの。

### 2.1 Action Candidate / Connector レイヤー
会話から業務イベントを抽出し、外部システムに渡せる下書きへ変換する層。

最小構成:
- `domain schema`: 飲食店、教育、建設、医療、福祉などの intent / field 定義
- `action candidate`: 会話から抽出された業務アクション候補
- `review queue`: 人間が確認、編集、承認、却下する画面
- `connector draft`: SaaS / ERP ごとの下書き payload
- `connector run`: 外部システムへ送信した履歴

最初の参照実装は **飲食店** とナレッジワーカードメイン（`docs/poc-knowledge-worker-domain.md`）。

#### 想定する追加 API

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/api/action-candidates` | GET / POST | 業務アクション候補の一覧 / 作成 |
| `/api/action-candidates/{id}` | GET | アクション候補の詳細、根拠会話、payload |
| `/api/action-candidates/{id}/review` | POST | 承認 / 編集 / 却下 |
| `/api/connectors` | GET | 利用可能な connector 一覧 |
| `/api/connector-drafts` | GET / POST | 外部システム向け下書き payload |
| `/api/connector-runs` | GET | connector 実行履歴 |

#### 想定する追加テーブル

- `zerotouch_action_candidates`
- `zerotouch_action_candidate_sources`（根拠会話 / Topic / Fact）
- `zerotouch_action_reviews`（承認 / 編集 / 却下）
- `zerotouch_connector_drafts`
- `zerotouch_connector_runs`

#### 飲食店ドメインの例

| 会話 | Intent | 出力下書き |
|------|--------|------------|
| `4番テーブル、1名追加で` | `change_guest_count` | 予約台帳の人数変更下書き |
| `冷蔵庫のレモン切れてる` | `stock_alert` | 発注システムへの補充メモ |
| `3番、味付けが濃いって言われた` | `quality_issue` | 顧客メモ、再発防止タスク |
| `金曜のコース、20時から2名追加で予約入れて` | `booking_update` | 予約システム更新下書き |

### 2.2 Connector 実装
- Google Sheets / Notion / Webhook などの初期 connector
- 業界別ドメインスキーマ（建設、医療、福祉、教育）

### 2.3 自動化レベルの段階的引き上げ
- 信頼度、業界リスク、出力先の重要度に応じて Human Review の自動化度合いを調整
- 初期段階では必ず Human Review を挟む

---

## 3. インフラ / 運用の改善

### 3.1 Topic finalize scheduler の外部化
- 現状: FastAPI process 内 thread。複数 worker 起動で重複実行されるため `TOPIC_FINALIZE_SCHEDULER_ENABLED` を 1 process だけ true にしている
- 次: Lambda / EventBridge など外部 worker 化

### 3.2 ローカル Docker 環境の復旧
- 現状: ローカル Docker は動作不可
- 動作確認は本番環境のみ
- ステージング環境の構築も合わせて検討

### 3.3 EC2 リソース逼迫対策
- Query 応答遅延・失敗率上昇の対策
- 再処理バッチと同時実行数の調整

---

## 4. ASR プロバイダー周辺

### 4.1 Cohere 対応
- 現在は ambient 録音形式 `m4a` 非対応で保留中
- 録音側を `wav / mp3 / ogg` のいずれかに切り替えれば利用可能

### 4.2 文字起こし精度・遅延の継続観測
- `gpt-4o-transcribe` を含む各 provider の品質比較
- Live 用と Conversation 用でプロバイダーを使い分ける構成（デュアル ASR）の検証

---

## 5. UI / UX

### 5.1 Timeline タブの再設計
- 過去情報アクセス用に再設計

### 5.2 Home の 3 レーン可視化（設計途中）
- 左: Cards / Topics
- 中央: Intake / Processing
- 右: Wiki / Knowledge
- レーン間の animated connector で「情報が左から右へ流れる」可視化

### 5.3 Action Candidate / Review Queue / Connector Draft の UI
- ZeroTouch 本体パイプライン復活後

---

## 6. 参照ドキュメント（構想ベース）

- `conversation-action-platform.md` — 会話 → 業務アクション変換の最上位設計
- `knowledge-pipeline-v2.md` — Wiki / 長期記憶レイヤーの設計
- `poc-knowledge-worker-domain.md` — ナレッジワーカードメインの PoC 設計
- `context-enrichment-project.md` — Workspace 単位の文脈入力で Wiki / Action 精度を上げる設計
- `wiki-project-category-page-design.md` — Wiki の project / category / page 分類設計
