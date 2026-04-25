# ZeroTouch Knowledge Pipeline v2

**この文書は Wiki / 長期記憶レイヤーの設計正本です。**

更新日: `2026-04-25`

ZeroTouch 全体の最上位設計は
[`conversation-action-platform.md`](./conversation-action-platform.md) を参照してください。

このレイヤーは、プロダクトの最終ゴールではなく、Action Candidate / Draft を生成するための
根拠、文脈、SOP、長期記憶として使います。

現在の作業再開や運用上の最新状態は
[`amical-longterm-memory-handoff.md`](./amical-longterm-memory-handoff.md) を参照してください。

---

## v1 からの方向転換

### v1 の問題点

v1 の Phase 3 (Consolidation) は `state_delta` / `supersede` モデルで設計されていた。

- 毎日 state_delta を apply して snapshot を更新する
- `supersede` / `close` で古い知識が消える
- **破壊的更新** → 長期運用で何が蓄積されているか不明確になる
- LLM の判断で情報が消えるため、決定論的でない

### v2 の方針

Andrej Karpathy の「LLM Knowledge Base」方式に切り替える。

> 生のドキュメントを LLM に渡して、構造化された Markdown の wiki をコンパイルしてもらう。
> wiki は使うたびに育っていくので、知識が複利的に蓄積される。

核心：

- **Raw sources は不変**（削除・上書きしない）
- **Wiki は統合・累積**（破壊しない。矛盾は解消するが原文は残す）
- **使えば使うほど wiki が育つ**

---

## アーキテクチャ概要

```
Raw sources（発話ログ、不変）
    │
    ▼  Ingest
Wiki（LLM がコンパイルした Markdown / 構造化データ）
    │
    ├── Lint（健全性チェック）矛盾・陳腐化・孤立ページを検出
    │
    ▼  Query
回答 → wiki に filing back → 蓄積
```

Action 変換との関係:

```text
Raw sources / Topic / Fact
    │
    ├── Wiki / SOP / Context memory
    │
    ▼
ZeroTouch Converter
    │
    ▼
Action Candidate / Connector Draft
```

Wiki は、Action Candidate の作成時に以下のために参照する。

- 現場固有のルール、SOP、例外処理
- 過去の判断や顧客メモ
- 業界・workspace 固有の語彙
- 同じ種類の会話から過去に作った draft

### Raw sources

ZeroTouch の発話ログがそのまま Raw sources に相当する。

- `zerotouch_sessions`（Card / 発話単位）
- `zerotouch_conversation_topics`（Topic / 会話区間）
- `zerotouch_facts`（Fact / annotation 済み事実）

これらは**変更しない**。証拠として保持し続ける。

---

## Schema（wiki の設計図）

Wiki の構造と規約を定義する設定文書。**3軸で整理する。**

### 軸1: テーマ軸

会話の内容がどの業務領域に属するか。

workspace のタイプによって変わる。`context_profile` の `focus_topics` で定義する。

**開発環境の例（現在のユーザー）:**
```
パイプライン / UI / ASR / コスト / 設計 / 運用 / 実装 / 調査
```

**飲食店の例:**
```
接客 / 調理 / 在庫 / 設備 / 人事 / トラブル / アイデア / 予約
```

**学校の例:**
```
授業 / 生徒対応 / 保護者対応 / 行事 / 事務 / 施設
```

### 軸2: 種別軸

知識がどんな性質のものか。

| 種別 | 説明 | 例 |
|-----|------|---|
| `decision` | 技術的・運用上の決定 | 「ASR は Deepgram に統一する」 |
| `rule` | 作業ルール・運用方針 | 「Topic は30秒無発話で切る」 |
| `insight` | 知見・原則・教訓 | 「日次で直接 canonical 化するとまだ粗い」 |
| `procedure` | 手順・マニュアル | 「レジ締めの手順」「ビールの注ぎ方」 |
| `task` | 継続中のタスク | 「スコアリング後の意味分類設計」 |

### 軸3: コンテキスト軸

**誰が・どこで・何のために使っているか。** この軸が分析精度を決める。

5レイヤーで構成される（詳細は `context-enrichment-project.md` 参照）。

| レイヤー | 内容 | 例 |
|---------|------|---|
| Account | その人が何者か | ファウンダー・エンジニア |
| Workspace | どんな現場か | 自宅執務室・飲食店厨房 |
| Device | どこに置かれているか | デスク上・レジ横 |
| Environment | 時間帯・曜日の軸 | 平日日中が主稼働 |
| Analysis Goal | 何を知りたいか | task 抽出・マニュアル生成 |

> **現在この軸が未入力のため、抽出精度が低い状態。最優先で入力が必要。**

---

## Wiki の構造

LLM がコンパイルした Markdown ファイル群。

```
wiki/
├── index.md                       # 全体インデックス・ナビ
├── decisions/                     # 技術的決定
│   ├── asr-provider.md
│   └── topic-finalization-rules.md
├── pipeline/                      # パイプライン知識
│   ├── card-topic-model.md
│   └── knowledge-distillation.md
├── ui/                            # UI 知識・設計方針
│   └── design-principles.md
├── tasks/                         # 継続タスク
│   └── active-tasks.md
└── [workspace-specific]/          # 業種・現場固有のページ
```

各ページは以下を持つ。

- タイトル・カテゴリ
- 本文（LLM が整形した内容）
- 根拠へのリンク（どの Fact / Topic から来たか）
- 最終更新日・バージョン

---

## 3つの操作

### Ingest（取り込み）

新しい Topic / Fact を処理して wiki に統合する。

```
Fact (Lv.3 以上、annotation 済み)
    ↓
既存 wiki を検索
    ↓
既存ページを更新 or 新ページを作成
    ↓
矛盾があれば解消（旧情報も保持）
    ↓
index.md を更新
```

Ingest のトリガー:
- Phase 2 Annotation 完了後（バッチ or リアルタイム）
- Amical など外部ログの手動 import 後

### Query（質問）

wiki に対して自然言語で質問を投げ、回答を得る。

- 回答を新しいページとして wiki に filing back する
- 使えば使うほど wiki が充実する

### Lint（健全性チェック）

wiki 全体に対するヘルスチェック。週次推奨。

- 矛盾するデータの検出
- TTL 超過した古い情報の検出
- 孤立ページの検出
- 欠落リンクの修正提案

---

## 既存実装との対応

### そのまま使うもの

| 既存 | v2 での位置づけ |
|-----|---------------|
| `zerotouch_sessions` | Raw sources（Card）|
| `zerotouch_conversation_topics` | Raw sources（Topic）|
| `zerotouch_facts` | Raw sources（Annotation 済み Fact）|
| Phase 1: Scoring | そのまま使用 |
| Phase 2: Annotation | そのまま使用 |
| `zerotouch_context_profiles` | コンテキスト軸の正本 |

### 変更・廃止するもの

| 既存 | v2 での変更 |
|-----|-----------|
| Phase 3: Consolidation（state_delta / supersede）| **Ingest / Lint に置き換え** |
| `active_state_snapshot`（破壊的 reducer）| wiki に統合・廃止方向 |
| `state_delta` | Ingest の差分記録に置き換え |

### 実験用として残すもの

Amical 実験の file-based artifacts（`experiments/amical/artifacts/daily-rollups/`）は
価値検証用として当面残す。本番パイプラインとは別系統として扱う。

---

## コンテキスト注入方針

分析の各ポイントで `context_profile` を渡す。

| タイミング | 渡す内容 |
|-----------|---------|
| Scoring (Phase 1) | `workspace_summary`, `analysis_objective` |
| Annotation (Phase 2) | 全コンテキスト（エンティティ補助・カテゴリ補助）|
| Ingest（wiki 生成時）| 全コンテキスト + wiki schema |
| Query（質問応答時）| 全コンテキスト + 関連 wiki ページ |

---

## まず今やるべきこと

### 最優先: コンテキストを入力する

精度改善に最も効くのは、コンテキスト軸を入力することです。
Android のマイページまたは API 経由で入力してください。

**現在のユーザーの最小入力セット（例）:**

```
account_context:
  identity_summary: "Androidタブレットを使った音声認識DXツールを開発しているファウンダー"
  primary_roles: ["founder", "engineer"]
  product_summary: "現場会話を task/decision/knowledge に変える ambient device を開発中"

workspace_context:
  workspace_type: "home_office"
  workspace_summary: "自宅の執務室。主に開発作業と設計会話が行われる"
  workspace_goals: ["開発会話の可視化", "未完了 task の再発見", "意思決定の記録"]

analysis_context:
  analysis_objective: "仕事中の会話から task/decision/knowledge を抽出したい"
  focus_topics: ["パイプライン設計", "Android開発", "音声AI", "UI設計"]
  ignore_topics: ["雑談", "私的メモ"]
```

### アクション優先順位

| 優先 | アクション |
|-----|----------|
| 1 | Wiki / Context Profile を Action Candidate 生成の根拠として使えるようにする |
| 2 | domain schema と Wiki schema の対応を整理する |
| 3 | 飲食店の参照ケースで、Action Candidate 生成時に関連 Wiki を注入する |
| 4 | Review Queue で根拠 Wiki / 根拠会話を確認できるようにする |
| 5 | Query / filing-back で得た知識を次回の Action 生成に反映する |

---

## 参照ドキュメント

| ドキュメント | 役割 | 状態 |
|-----------|------|------|
| `conversation-action-platform.md` | ZeroTouch 全体の最上位設計 | ✅ 最新 |
| このドキュメント | Wiki / 長期記憶レイヤーの正本 | ✅ 最新 |
| `context-enrichment-project.md` | コンテキスト軸の詳細設計 | ✅ 現在も有効 |
| `amical-longterm-memory-handoff.md` | 現在の作業引き継ぎ | ✅ 随時更新 |
