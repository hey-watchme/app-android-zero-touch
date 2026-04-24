# Wiki Project / Category / Page Design

更新日: `2026-04-19`

## 目的

ZeroTouch Wiki の分類軸を、現状の `theme × kind` から

```text
project > category > page
             ×
            kind
```

へ再設計する。

主な狙いは次の 3 つ。

1. `theme` の粒度が細かすぎて、上位分類として機能していない問題を解消する
2. `ZeroTouch / WealthPark / WKFL` などの **人間が意図して管理したいまとまり** を first-class にする
3. WebViewer / Query / Lint で「どの project の、どの category の、どの page か」を明確に扱えるようにする

---

## 用語の決定

今後の用語は次で統一する。

- `project`
  - 例: `ZeroTouch`, `WealthPark`, `WKFL`, `起業準備`, `学習サポート`
  - 人間が意図して管理する上位単位
- `category`
  - 例: `README運用`, `音声AI`, `iOS配布`, `人事支援`
  - project の中にある中分類
- `page`
  - 個別の wiki page
- `kind`
  - `decision | rule | insight | procedure | task`
  - page の性質を示す軸

### 命名上の判断

- これまでの `theme` は、概念的には **`category` に改名** する
- 新しく `project` をその上位に追加する
- `kind` はそのまま残す

---

## なぜ `theme` では足りないか

現状の `theme` は、

- 上位分類として使うには細かすぎる
- page title に近い粒度のラベルが生成されやすい
- `ReadMe` と `ReadMe運用` のような近接ラベルが分裂しやすい
- ユーザーが「まず project 単位で見たい」という読み方に合わない

その結果、UI 上ではフォルダのように見えていても、
実際には「project の中の category」ではなく、
「細かく分裂した topic 群」の集合になってしまう。

---

## 目標情報構造

### UI の見え方

```text
Project
  Category
    Page
```

例:

```text
ZeroTouch
  README運用
    ZeroTouch 音声分析アプリのREADME追記項目
    開発再開時の参照ドキュメント確認
  音声AI
    ZeroTouch 音声感情分析・SER調査
    ZeroTouch AST現行評価とモデル比較の進め方

WealthPark
  人事支援
    WealthPark 人事条件とパッケージ・転職戦略
    WealthPark 対象社員の転職支援実務メモ
```

### 軸の役割

- `project`
  - まず何の仕事・活動に属するかを決める
- `category`
  - その project の中のまとまりを表す
- `page`
  - 実際の知識単位
- `kind`
  - その page が決定・手順・知見・タスクのどれかを示す

---

## 設計原則

### 1. project は human-managed を基本にする

project は ZeroTouch 側で勝手に増殖させない。

- canonical な project 一覧は **workspace ごとに人間が管理する**
- 初期状態では `context_profile.workspace_context.key_projects` から seed してよい
- LLM は **既存 project への分類** は行ってよい
- ただし LLM が新 project を勝手に canonical registry に追加しない

### 2. category は project 内の中分類として LLM 生成を許可する

category は project より柔軟でよい。

- project ごとに category は複数持てる
- 最初は free text でよい
- ただし prompt で粒度制約を強くかける
- 後で必要なら category registry を追加する

### 3. page は今の wiki page をそのまま使う

- page title
- page body
- source_fact_ids
- version
- kind

は現行設計を維持する。

---

## Project の管理モデル

### 推奨: 専用 registry テーブルを持つ

新規テーブル案:

`zerotouch_workspace_projects`

カラム案:

- `id`
- `workspace_id`
- `project_key`
  - 安定キー。例: `zerotouch`, `wealthpark`, `wkfl`
- `display_name`
  - 例: `ZeroTouch`
- `description`
- `aliases`
  - 例: `["ZT", "audio app", "zerotouch app"]`
- `status`
  - `active | archived`
- `source`
  - `manual | seeded_from_context | imported`
- `created_at`
- `updated_at`

### 初期 seed

初回は `context_profile.workspace_context.key_projects` から seed する。

ただし seed 後は registry 側を正本として扱う。

### LLM との関係

- prompt には `active project list` を渡す
- LLM は出力時に `project_key` を返す
- 不確実なら `project_key = "unassigned"` に寄せる
- optional で `project_suggestion` を返してもよいが、registry への自動追加はしない

---

## Page データモデル

### 推奨変更

既存 `zerotouch_wiki_pages` に次を追加する。

- `project_id UUID NULL`
  - `zerotouch_workspace_projects.id` を参照
- `category TEXT NULL`
- `page_key TEXT NULL`
  - page の stable key
  - title 変更や project/category 再編の影響を受けない識別子
- `title TEXT`
  - 表示名として維持する

既存の `theme` は移行期間だけ残し、最終的には廃止候補とする。

### 移行期の扱い

- `theme` は legacy field として残す
- 画面/API では新規に `project` と `category` を優先表示する
- 旧データは backfill 時に
  - `theme -> category`
  - `project` は registry と prompt から補完
  - `page_key` は backfill 時に新規採番する

### `page_key` を持つ理由

現状は ingest が page を lowercased `title` だけで同一視している。
これだと次の問題が起きる。

- title 修正で別 page 扱いになる
- 同名 page が別 project にあると衝突する
- `[[WikiLink]]` が title だけでは曖昧になる

したがって、表示上の title とは別に stable な `page_key` を導入する。

---

## Ingest 出力スキーマ

現状:

```json
{
  "title": "string",
  "body": "string",
  "theme": "string",
  "kind": "decision | rule | insight | procedure | task",
  "source_fact_ids": ["uuid"]
}
```

変更後:

```json
{
  "page_key": "string",
  "title": "string",
  "body": "string",
  "project_key": "zerotouch | wealthpark | wkfl | unassigned",
  "category": "string",
  "kind": "decision | rule | insight | procedure | task",
  "source_fact_ids": ["uuid"]
}
```

### prompt 制約

- `project_key` は与えられた project list から選ばせる
- `category` は project 内の中分類として 1 つ返す
- `page_key` は既存 page を更新するときに優先して維持する
- page title に近すぎる category を避ける
- `README`, `README運用`, `README更新` のような近接ラベル乱立を避ける
- category は短く、統合方向に寄せる

---

## Annotation への影響

Phase 2 Annotation は直接 wiki page を作らないため、
主変更は不要。

ただし将来的には facts に対して次を持たせる余地がある。

- `project_candidates`
- `category_hints`

これは必須ではない。
第一段階では project/category は ingest 時に決めればよい。

---

## API / UI 変更方針

### API

`/api/wiki` と pipeline board の wiki payload に次を追加する。

- `project_id`
- `project_key`
- `project_name`
- `category`
- `page_key`

移行期間中は `theme` も返す。

### WebViewer

現在:

- 左カラム: `theme` ごとにグルーピング
- 詳細上部: `kind` と `theme` のピル表示

変更後:

- 左カラム: `project > category > page`
- 詳細上部: `project`, `category`, `kind` のピル表示
- 検索対象にも `project_name` と `category` を含める
- `related pages` は同一 category だけでなく **同一 project 内** を前提に再定義する
- `[[WikiLink]]` は title だけではなく `page_key` ベース解決に切り替える

---

## Query / Lint への影響

### Query

将来の Query 実装では、
`project` を first-class filter として使えるようになる。

例:

- ZeroTouch の音声AI関連だけ質問
- WealthPark の人事支援 category だけ対象

### Lint

Lint では次がやりやすくなる。

- page が project 未割当のまま残っていないか
- 同一 project 内で category が分裂していないか
- 近接 category の統合候補検出
- project registry にない label が混入していないか

---

## 段階的移行プラン

### Phase A: 用語と設計の固定

- docs を `project > category > page (+ kind)` 前提に更新
- `theme` は legacy 名称と明記

### Phase B: Project registry 導入

- `zerotouch_workspace_projects` migration 追加
- `context_profile.workspace_context.key_projects` から seed する同期処理を追加
- `project_key = unassigned` をシステム予約値として定義

### Phase C: Wiki page schema 拡張

- `zerotouch_wiki_pages.project_id`
- `zerotouch_wiki_pages.category`
- `zerotouch_wiki_pages.page_key`
- 必要なら `project_key` denormalized cache も追加

### Phase D: Ingest prompt / service 更新

- prompt に active project list を渡す
- LLM 出力を `project_key + category + page_key + kind` に変更
- server 側で `project_key -> project_id` 解決
- upsert は `page_key` 優先、legacy title fallback を補助として使う

### Phase E: Backfill

- 既存 page を再 ingest して `project/category` を付与
- `theme` は `category` として暫定転写
- 必要なら project 未割当 page を `unassigned` に集約
- title だけで一致していた page は `page_key` を新規付与して再同定する

### Phase F: UI 切り替え

- WebViewer を `project > category > page` 表示へ変更
- 画面上の `theme` 表記を `category` に変更
- `theme` 1段前提の sidebar を 2段階 navigator に置き換える

### Phase G: Cleanup

- `theme` を read-only legacy field 化
- Query / Lint も新軸に切り替える
- 十分安定したら `theme` 廃止を検討

---

## 非目標

今回の設計では、まだ次はやらない。

- category registry の厳格運用
- graph view の大改修
- entity page の完全導入
- Android Wiki UI 実装

---

## この設計でまず実装すべき最小セット

最初の実装スコープは次で十分。

1. `workspace_projects` テーブル追加
2. `wiki_pages` に `project_id`, `category` を追加
3. ingest prompt を `project_key + category + kind` に変更
4. WebViewer を `project > category > page` に変更
5. 既存 page を再 ingest して backfill

これで分類の散在問題と UI の読みにくさは大きく改善できる。

---

## 結論

今後の ZeroTouch Wiki は、

- `project` を人間が意図して管理する上位単位
- `category` を project 内の中分類
- `page` を個別知識単位
- `kind` を page の性質

として扱う。

`theme` は用語として曖昧であり、
今後は設計上 `category` に置き換えていく。
