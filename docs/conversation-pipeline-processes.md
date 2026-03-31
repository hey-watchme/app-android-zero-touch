# ZeroTouch Conversation & Knowledge Pipeline

## この文書の目的

ZeroTouch における **会話可視化** と **知識蒸留** の全パイプラインを定義します。

`Amical` の文字起こし済みログを使ったオフライン検証方針は、
`docs/amical-validation-wrapup.md` を参照してください。
この文書は最終的な到達アーキテクチャを定義するものであり、
Amical を使った価値実証の実験計画とは役割を分けます。

パイプラインは大きく2層に分かれます。

1. **会話可視化層**（既存・稼働中）: 録音 → Card → Topic → finalize
2. **知識蒸留層**（新規）: Topic → Scoring → Annotation → Consolidation → Interface

会話可視化層が素材を作り、知識蒸留層がその素材を「現場のナレッジ」へ変換します。

---

## Part 0: Ownership & Context Layer

知識蒸留の精度を上げるため、ZeroTouch は `account -> workspace -> device` の所有モデルを持ちます。

- `account`: サインイン主体。1ユーザーが複数 workspace を扱える
- `workspace`: 家庭、店舗、検証環境など、知識を分ける論理単位
- `device`: Android 本体、または Amical などを投入する仮想デバイス
- `context profile`: 利用者、役割、現場、目的、補足資料、語彙、運用ルール

### この層の目的

- 複数デバイスのデータを 1 つの現場ナレッジとして束ねる
- `Amical` のような外部ログを仮想デバイスとして同じパイプラインに流し込む
- LLM に対して workspace 固有の前提情報を渡し、抽出精度を上げる

### 基本ルール

- `device_id` は引き続き物理/仮想デバイス識別子として残す
- `workspace_id` を `sessions / topics / facts / device_settings` に持たせる
- 1 account は複数 device を持てる
- 1 workspace は複数 device を持てる
- `Amical` は `source_type=amical_transcriptions` の仮想デバイスとして扱う

---

## Part 1: 会話可視化層（既存パイプライン）

### コアモデル

#### Card

Card は発言の最小単位です。

- 実体は `zerotouch_sessions` の 1 レコード
- 1 回の録音チャンク + ASR 結果が 1 Card
- Card は独立したオブジェクトで、後から別 Topic に移動できる

#### Topic

Topic は Card を受ける箱です。

- 実体は `zerotouch_conversation_topics` の 1 レコード
- ある連続した会話区間を表す
- Card が 1 件でも生成されたら、その Card が属する Topic も必ず存在する
- device ごとに同時に 1 件だけ `active` な Topic を持つ

#### LLM の役割（この層）

- Topic の切れ目は機械ルールで決まる（LLM は使わない）
- LLM は Topic 確定時に `title` / `summary` / `description` を整える

### パイプライン

1. アンビエント録音開始
2. 無音 5 秒で録音チャンクを閉じる
3. S3 にアップロード
4. ASR が文字起こし（Speechmatics / Deepgram）
5. `zerotouch_sessions` に Card を生成
6. active Topic がなければ新規作成、あれば既存に紐付け
7. UI に Card を即時表示
8. `30秒無発言` または `Ambient Off` で Topic を閉じる
9. LLM が Topic の title / summary / description を整える
10. Topic を `finalized` にする

### Topic 切り替えルール

- 最後の Card から `30秒間` 新しい発言がなければ Topic を閉じる（`idle_timeout`）
- アンビエントモードを `Off` にしたら Topic を閉じる（`ambient_stopped`）
- `30秒無音` ではなく `30秒無発言`（Card が来ない = 無発言）

### 録音チャンクのルール

- 通常は `5秒無音` でセッションを閉じる
- 連続録音が `2分` を超えたら `2.5秒無音` で閉じる
- `10分` で強制的に閉じる
- `3秒` 未満は破棄

### この層の完成条件

- 新しい Card が必ず即時に見える
- Card には必ず Topic がある
- Topic の境界は `30秒無発言` または `Ambient Off` で決まる
- Topic 確定時に LLM がタイトル、要約、説明文を整える
- Topic と Card 群が知識蒸留層の入力になる

---

## Part 2: 知識蒸留層（Knowledge Distillation Pipeline）

### 概要

Topic が finalize された時点で、知識蒸留パイプラインが起動します。
目的は、会話の断片を **検索可能で、自動更新されるナレッジベース** へ変換すること。

パイプラインは 4 フェーズで構成されます。

```
Topic (finalized)
  │
  ▼
Phase 1: Scoring（重要度判定）
  │  → Lv.0〜5 の格付け
  │  → Lv.2 以下は蒸留対象外
  ▼
Phase 2: Annotation（属性付与）
  │  → エンティティ、カテゴリ、インテント、TTL
  ▼
Phase 3: Consolidation（統合・結晶化）
  │  → 重複統合、知識更新、ベクトルインデックス作成
  │  → Fact Store + Master Knowledge 生成
  ▼
Phase 4: Interface（知識の引き出し）
     → RAG 検索、自動レポート、傾向分析
```

### 実行タイミング

| フェーズ | トリガー | 実行タイミング |
|---------|---------|-------------|
| Phase 1 | Topic finalize 直後 | 即時（finalize の延長） |
| Phase 2 | Phase 1 完了 & Lv.3 以上 | 即時（Phase 1 の連鎖） |
| Phase 3 | Phase 2 完了、または定期バッチ | 非同期バッチ（5分〜1時間間隔） |
| Phase 4 | ユーザーのクエリ、または定期生成 | オンデマンド + 定期 |

---

### Phase 1: Scoring（重要度判定）

Topic が finalize された直後に、LLM がその Topic の「情報の密度」を判定します。

#### 重要度レベル

| Level | 名称 | 例 | 処理 |
|-------|------|---|------|
| Lv.0 | ノイズ | 誤検知、聞き取り不能な断片 | 非表示（DB保存、検索対象外） |
| Lv.1 | 日常・情緒 | 「おはよう」「疲れたね」「暑い」 | 集計データとして利用（活気指標） |
| Lv.2 | 定型業務 | 「レジ締め完了」「在庫確認しました」 | ログとして記録 |
| Lv.3 | 共有事実 | 「XX様が来週来る」「水道が漏れてるかも」 | **ナレッジ候補** → Phase 2 へ |
| Lv.4 | 意思決定 | 「このメニューは売り切れにする」「クレーム対応方針」 | **マニュアル候補** → Phase 2 へ（即時整理） |
| Lv.5 | 資産・マスター | 「新メニューの作り方」「VIP顧客の好み」「緊急連絡先」 | **黄金の知識** → Phase 2 へ（最優先で結晶化） |

#### 入力

- Topic の final_title, final_summary, final_description
- Topic に属する全 Card のテキスト（時系列順）

#### 出力

- `importance_level`: 0〜5 の整数
- `importance_reason`: 判定理由（1文）

#### LLM 選択

- 軽量モデルを使用（gpt-4.1-nano / gemini-2.5-flash）
- コスト最適化: 全 Topic に対して実行するため、高速・低コストが必須

#### 実装方針

- Topic finalize の直後に同期的に実行（finalize 処理の延長）
- `zerotouch_conversation_topics` に `importance_level`, `importance_reason` カラムを追加
- Lv.2 以下はここでパイプライン終了（Phase 2 に進まない）

---

### Phase 2: Annotation（属性付与）

Lv.3 以上の Topic に対し、検索・整理のための構造化メタデータを付与します。

#### 付与する属性

##### 1. エンティティ抽出

固有名詞と参照情報を抽出します。

- 人名（顧客名、スタッフ名）
- 商品名・メニュー名
- 場所（テーブル番号、倉庫、店舗名）
- 日時（絶対日時に正規化: 「明日」→ `2026-03-29`）
- 金額

##### 2. カテゴリ分類

会話の業務領域を分類します。
カテゴリは運用に合わせて拡張可能。

初期カテゴリ:

```
接客 | 調理 | 清掃 | 事務 | トラブル | アイデア | 人事 | 在庫 | 設備
```

##### 3. インテント（意図）判定

発話の意図を判定します。

```
報告 | 相談 | 指示 | 不満 | 提案 | 質問 | 確認 | 共有
```

##### 4. TTL（生存期間）設定

知識の有効期限を設定します。

| TTL タイプ | 例 | 期間 |
|-----------|---|------|
| ephemeral | 「明日の予約」 | 1〜7 日 |
| seasonal | 「夏メニューの仕込み方」 | 1〜6 ヶ月 |
| permanent | 「ビールの注ぎ方」「緊急連絡先」 | 無期限 |

#### 入力

- Topic の全情報（Phase 1 の出力含む）
- Topic に属する全 Card のテキスト + メタデータ

#### 出力

```json
{
  "entities": [
    {"type": "person", "value": "田中様", "role": "customer"},
    {"type": "datetime", "value": "2026-03-29T12:00:00+09:00", "raw": "明日の昼"}
  ],
  "categories": ["接客", "予約"],
  "intents": ["報告", "確認"],
  "ttl": {
    "type": "ephemeral",
    "expires_at": "2026-04-05T23:59:59+09:00"
  }
}
```

#### LLM 選択

- 中量モデル（gpt-4.1-mini / gemini-2.5-flash）
- エンティティ抽出の精度が重要なため、nano よりは上位を使う
- Lv.5 の場合は gpt-5.4 / gemini-3.1-pro も検討

#### 実装方針

- Phase 1 完了後に非同期で実行
- 新テーブル `zerotouch_facts` に保存（後述）
- Lv.4〜5 は優先キューで先に処理

---

### Phase 3: Consolidation（統合・結晶化）

**最も重要なフェーズ。** 散在する会話の断片を、1つの整理された知識に統合します。

#### 3つの処理

##### 3a. 重複の統合（Deduplication & Merge）

同じ話題について繰り返し語られた内容を統合します。

- 例: 「レジ締めのコツ」が 10 個の Topic に分散 → 1 つの「レジ締めマニュアル」に集約
- ベクトル類似度検索で候補を抽出し、LLM が統合判定

##### 3b. 知識の更新（Knowledge Update）

既存のナレッジと矛盾する新情報を検知し、更新します。

- 例: 「手順 A → 手順 B に変更」を検知 → 既存ナレッジを B に書き換え
- 旧バージョンはバージョン履歴として保持

##### 3c. インデックス作成（Indexing）

Fact（フロー情報: いつ、誰が言ったか）を Master Knowledge（ストック情報: この店ではこうする）に変換し、検索可能な形に格納します。

- ベクトル DB にエンベディングを格納（RAG 用）
- リレーショナル DB に構造化データを格納（フィルタ用）

#### ベクトル検索

**Google Vertex AI Vector Search** を使用します。

- Embedding 生成: `text-embedding-005`（Google）または `text-embedding-3-small`（OpenAI）
- インデックス: Vertex AI Vector Search（マネージドサービス）
- フォールバック: Supabase pgvector（コスト最適化フェーズで検討）

#### LLM 選択

- 統合判定: 高精度モデル（gpt-5.4 / gemini-3.1-pro）
- 知識文書の生成: 高精度モデル（gpt-5.4 / gemini-3.1-pro）
- 類似度判定のみ: ベクトル検索（LLM 不要）

#### 実行モード

- **リアルタイム**: Lv.5 の新規 Fact は即時に Consolidation を試行
- **バッチ**: 定期バッチ（初期は 1 時間間隔）で未統合の Fact を処理
- **手動**: 管理者が統合をトリガー

#### 実装方針

- 新テーブル `zerotouch_knowledge` に Master Knowledge を保存（後述）
- Vertex AI Vector Search にエンベディングを格納
- Fact → Knowledge の紐付けは `zerotouch_fact_knowledge_links` で管理
- バージョン管理は `zerotouch_knowledge` の `version` + `superseded_by` で実現

---

### Phase 4: Interface（知識の引き出し）

整理されたナレッジに自然言語でアクセスする層です。

#### 4a. 質問応答モード（RAG）

ユーザーの質問に対し、ナレッジベースから根拠を引いて回答します。

```
新人: 「XX さんの好みの席ってどこ？」
AI:   「過去3回の来店ログと店長の指示から、窓際の4番テーブルを好まれます」
      [出典: Fact #123 (2026-03-15), Knowledge #45 "常連客の席の好み"]
```

- ベクトル検索で関連 Fact / Knowledge を取得
- LLM がコンテキストに基づいて回答を生成
- 出典（どの Topic / Fact から来たか）を必ず提示

#### 4b. 自動レポートモード

定期的にナレッジベースを分析し、レポートを生成します。

- **日報**: 今日あった出来事のサマリー（Lv.2 以上を集計）
- **週報**: トラブルと解決策の一覧、新しく追加されたナレッジ
- **傾向レポート**: 特定カテゴリの増減を検知

```
「今週、在庫切れに関する会話が先週比 3 倍に増えています。
 発注タイミングを見直しますか？」
```

#### 4c. 現場の健康診断モード

ナレッジベースの状態から、現場の問題を早期検知します。

- カテゴリ別の会話頻度の変化
- 未解決トラブルの検出
- 知識の陳腐化（TTL 超過）アラート

#### LLM 選択

- RAG 回答生成: gpt-5.4 / gemini-3.1-pro（正確性が最重要）
- レポート生成: gpt-4.1-mini（コスト効率）
- 傾向分析: ベクトル検索 + 集計（LLM は最終サマリーのみ）

---

## データモデル

### 既存テーブル（変更あり）

#### `zerotouch_conversation_topics`（追加カラム）

| カラム | 型 | 説明 |
|--------|---|------|
| importance_level | INTEGER | Phase 1 の重要度（0〜5） |
| importance_reason | TEXT | 判定理由 |
| distillation_status | TEXT | `pending` / `scored` / `annotated` / `consolidated` / `skipped` |
| scored_at | TIMESTAMPTZ | Phase 1 完了日時 |

#### `zerotouch_sessions`（変更なし）

既存のまま。Card としての役割は変わらない。

### 新規テーブル

#### `zerotouch_facts`

Fact Store。アノテーション済みの事実データ。Topic から抽出された個別の知識単位。

| カラム | 型 | 説明 |
|--------|---|------|
| id | UUID | PK |
| topic_id | UUID | FK → zerotouch_conversation_topics |
| device_id | TEXT | デバイス ID |
| fact_text | TEXT | 事実の本文（LLM が整形した 1 文〜数文） |
| importance_level | INTEGER | 元 Topic の重要度を継承 |
| entities | JSONB | 抽出されたエンティティ |
| categories | TEXT[] | カテゴリ配列 |
| intents | TEXT[] | インテント配列 |
| ttl_type | TEXT | `ephemeral` / `seasonal` / `permanent` |
| expires_at | TIMESTAMPTZ | TTL に基づく有効期限（NULL = 無期限） |
| embedding_id | TEXT | Vertex AI Vector Search のベクトル ID |
| consolidation_status | TEXT | `pending` / `merged` / `standalone` |
| knowledge_id | UUID | FK → zerotouch_knowledge（統合先） |
| source_cards | UUID[] | 元になった Card（session）の ID 群 |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

#### `zerotouch_knowledge`

Master Knowledge。Fact を統合・結晶化した「整理された文書」。

| カラム | 型 | 説明 |
|--------|---|------|
| id | UUID | PK |
| device_id | TEXT | デバイス ID（将来は組織単位に拡張） |
| title | TEXT | ナレッジのタイトル |
| content | TEXT | 整理された本文（マニュアル形式） |
| content_format | TEXT | `markdown` / `structured_json` |
| categories | TEXT[] | カテゴリ配列 |
| importance_level | INTEGER | 最大の Fact レベルを継承 |
| fact_count | INTEGER | 統合された Fact の数 |
| version | INTEGER | バージョン番号 |
| superseded_by | UUID | 新バージョンの knowledge ID |
| embedding_id | TEXT | Vertex AI Vector Search のベクトル ID |
| ttl_type | TEXT | `ephemeral` / `seasonal` / `permanent` |
| expires_at | TIMESTAMPTZ | |
| last_consolidated_at | TIMESTAMPTZ | 最終統合日時 |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

#### `zerotouch_fact_knowledge_links`

Fact と Knowledge の多対多リンク。

| カラム | 型 | 説明 |
|--------|---|------|
| id | UUID | PK |
| fact_id | UUID | FK → zerotouch_facts |
| knowledge_id | UUID | FK → zerotouch_knowledge |
| link_type | TEXT | `source` / `update` / `contradiction` |
| created_at | TIMESTAMPTZ | |

#### `zerotouch_distillation_runs`

蒸留バッチの実行ログ。

| カラム | 型 | 説明 |
|--------|---|------|
| id | UUID | PK |
| device_id | TEXT | |
| phase | TEXT | `scoring` / `annotation` / `consolidation` |
| run_status | TEXT | `processing` / `completed` / `failed` |
| topic_count | INTEGER | 処理した Topic 数 |
| fact_count | INTEGER | 生成した Fact 数 |
| knowledge_count | INTEGER | 更新した Knowledge 数 |
| llm_provider | TEXT | |
| llm_model | TEXT | |
| started_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | |
| error_message | TEXT | |
| created_at | TIMESTAMPTZ | |

---

## API エンドポイント（新規）

### 蒸留パイプライン

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/api/distill/score/{topic_id}` | POST | Phase 1: 手動スコアリング |
| `/api/distill/annotate/{topic_id}` | POST | Phase 2: 手動アノテーション |
| `/api/distill/consolidate` | POST | Phase 3: 統合バッチ実行 |
| `/api/distill/status` | GET | パイプライン実行状況 |

### ナレッジベース

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/api/knowledge` | GET | Knowledge 一覧（フィルタ: カテゴリ、重要度） |
| `/api/knowledge/{id}` | GET | Knowledge 詳細（リンクされた Fact 含む） |
| `/api/knowledge/{id}/history` | GET | バージョン履歴 |
| `/api/facts` | GET | Fact 一覧 |
| `/api/facts/{id}` | GET | Fact 詳細 |

### RAG / Interface

| エンドポイント | メソッド | 説明 |
|-------------|---------|------|
| `/api/ask` | POST | 質問応答（RAG） |
| `/api/reports/daily` | GET | 日報生成 |
| `/api/reports/weekly` | GET | 週報生成 |
| `/api/reports/trends` | GET | 傾向分析 |

---

## LLM 使用マップ

| フェーズ | 処理 | 推奨モデル | 理由 |
|---------|------|-----------|------|
| Topic finalize | title / summary / description | gpt-4.1-nano | 高頻度、低コスト |
| Phase 1 Scoring | 重要度判定 | gpt-4.1-nano | 全 Topic に実行、高速 |
| Phase 2 Annotation | エンティティ・カテゴリ抽出 | gpt-4.1-mini | 精度が必要 |
| Phase 3 Consolidation | 重複判定・統合文書生成 | gpt-5.4 | 最重要、高精度 |
| Phase 4 RAG | 質問応答 | gpt-5.4 | ユーザー向け、正確性最重要 |
| Phase 4 Report | レポート生成 | gpt-4.1-mini | 定型、コスト効率 |

---

## インフラ構成

```
                          ┌─────────────────────────┐
                          │    Android App           │
                          │  (Ambient Recording)     │
                          └──────────┬──────────────┘
                                     │ upload
                                     ▼
┌──────────┐    ASR    ┌─────────────────────────────┐
│   S3     │◄─────────►│   ZeroTouch Backend          │
│  (audio) │           │   FastAPI :8061              │
└──────────┘           │                              │
                       │  ┌─ Card/Topic Pipeline ──┐  │
                       │  │  ASR → Card → Topic    │  │
                       │  │  → finalize            │  │
                       │  └────────┬───────────────┘  │
                       │           │                   │
                       │  ┌─ Distillation Pipeline ─┐  │
                       │  │  Scoring → Annotation   │  │
                       │  │  → Consolidation        │  │
                       │  └────────┬───────────────┘  │
                       │           │                   │
                       └───────────┼───────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
                    ▼              ▼              ▼
             ┌───────────┐ ┌───────────┐ ┌──────────────┐
             │ Supabase  │ │ Vertex AI │ │  LLM APIs    │
             │ (Postgres)│ │ Vector    │ │  OpenAI      │
             │           │ │ Search    │ │  Gemini      │
             │ facts     │ │           │ │              │
             │ knowledge │ │ embeddings│ │              │
             │ topics    │ │           │ │              │
             └───────────┘ └───────────┘ └──────────────┘
                    │              │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │  RAG Layer   │
                    │  (Phase 4)   │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ 質問応答  │ │ 自動レポ │ │ 傾向分析  │
        │ (ask)    │ │ ート     │ │ (trends) │
        └──────────┘ └──────────┘ └──────────┘
```

---

## 実装ロードマップ

### Step 1: Phase 1 Scoring（最短ルート）

**目標**: finalize 時に重要度が自動で付く状態を作る

- `zerotouch_conversation_topics` に `importance_level`, `importance_reason`, `distillation_status` カラム追加
- `topic_manager_process2.py` の finalize 処理に Scoring ロジックを追加
- Scoring 用プロンプトを `prompts.py` に追加
- 軽量 LLM（gpt-4.1-nano）で全 Topic をスコアリング

### Step 2: Phase 2 Annotation

**目標**: Lv.3 以上の Topic から構造化された Fact を抽出する

- `zerotouch_facts` テーブル作成
- Annotation 用プロンプトを追加
- Phase 1 → Phase 2 の連鎖実行を実装
- エンティティ抽出・カテゴリ分類のプロンプトチューニング

### Step 3: Phase 3 Consolidation（コア）

**目標**: 散在する Fact を 1 つの Knowledge に統合する

- `zerotouch_knowledge`, `zerotouch_fact_knowledge_links` テーブル作成
- Vertex AI Vector Search のセットアップ
- Embedding 生成パイプライン（Fact / Knowledge のベクトル化）
- 類似 Fact 検索 → 統合判定 → Knowledge 文書生成
- バージョン管理（更新・矛盾検知）
- 定期バッチの実装

### Step 4: Phase 4 Interface

**目標**: ナレッジベースに自然言語でアクセスできる

- `/api/ask` エンドポイント（RAG パイプライン）
- レポート生成エンドポイント
- Web ダッシュボード連携（web-zero-touch 側）
- Android アプリからの質問 UI

---

## 設計原則

1. **Card は常に即時表示** — 知識蒸留は Card/Topic 表示を遅延させない
2. **全データは保持** — Lv.0 のノイズも DB に残す（非表示にするだけ）
3. **出典の追跡可能性** — Knowledge から元の Topic / Card まで辿れること
4. **段階的な劣化** — LLM が落ちても Card/Topic は表示される、Scoring が落ちても Topic は finalize される
5. **コスト意識** — 全 Topic に高精度 LLM を使わない。Phase ごとにモデルを使い分ける
6. **TTL による鮮度管理** — 期限切れの知識は検索優先度を下げる（削除はしない）
