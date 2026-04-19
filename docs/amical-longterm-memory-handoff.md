# Amical Long-Term Memory Handoff

## 目的

ZeroTouch における長期記憶・知識蓄積パイプラインの引き継ぎメモ。
次のセッションでここから再開する。

本パイプラインは Andrej Karpathy の「LLM Wiki」概念をベースにした長期記憶の仕組み。
Raw sources（Card / Topic / Fact）→ Ingest → Wiki という構造ですでにコア実装済み。

- Karpathy gist: <https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f>
- 設計正本: [`knowledge-pipeline-v2.md`](./knowledge-pipeline-v2.md)

更新日: `2026-04-19`（Android WebView 移植・Vercel デプロイ・デバイス登録・マイページ修正を反映）

---

## マイルストーン

**ゴール**: 下記 M1 + M2 を完了させれば、Android 移植前の前段として「実用的な長期記憶パイプライン」が揃う。Android への Wiki UI 移植はその後。

### M1: コンテキスト入力（最優先）

`context_profile` の実値（account / workspace / device / environment / analysis_goal）を入力する。
`knowledge-pipeline-v2.md` では「精度改善に最も効く最優先項目」と位置づけられているが、現状は**未入力のまま**。
これを入れて Ingest を再実行することで、Wiki ページの分類（特に `theme` 命中率）と抽出精度が大幅向上する前提。

### M2: Karpathy 設計との乖離の補完

現行実装は Karpathy の「LLM Wiki」中核部分はカバーしているが、`index.md` / `log.md` / Entity 軸 / Query + filing back / Lint などが未実装。
これらを足して初めて「使えば使うほど複利で蓄積される Wiki」になる。詳細は後述の M2 タスクリスト参照。

### 現在の設計着手: 分類軸の再設計

WebViewer 上で `theme` が上位分類として細かすぎる問題が確認されたため、
分類軸を `theme × kind` から

```text
project > category > page
             ×
            kind
```

へ再設計する方針に切り替えた。

- project は workspace ごとに人間が意図して管理する上位単位
- category は project 内の中分類
- page は個別の wiki page
- kind は page の性質

詳細設計: [`wiki-project-category-page-design.md`](./wiki-project-category-page-design.md)

### ゴールの先: Android 移植 ✅ 完了（2026-04-19）

Android アプリへの Wiki/Query UI 実装フェーズは完了。

---

## パイプラインの現状

### 実装済み（コア完成）

```
Card → Topic → Scoring → Annotation → Fact → Ingest → Wiki
  ✅       ✅        ✅           ✅         ✅       ✅      ✅
```

| Phase | 実装 | 場所 |
|-------|------|------|
| Phase 1: Scoring | ✅ | `services/topic_scorer.py` |
| Phase 2: Annotation | ✅ | `services/topic_annotator.py` |
| Phase 3: Ingest | ✅ | `services/wiki_ingestor.py` |
| Ingest API | ✅ | `POST /api/ingest-wiki` |
| DB テーブル | ✅ | `zerotouch_wiki_pages`（migration 011）|

### Query + filing-back（実装完了 2026-04-19）

| Phase | 実装 | 場所 |
|-------|------|------|
| Query パイプライン | ✅ | `services/wiki_querier.py` |
| Index テーブル | ✅ | `zerotouch_wiki_index`（migration 013）|
| Log テーブル | ✅ | `zerotouch_wiki_log`（migration 013）|
| Query API | ✅ | `POST /api/query-wiki` |
| Wiki ログ API | ✅ | `GET /api/wiki-log` |
| WebViewer Query UI | ✅ | `web-zero-touch/src/app/query/page.tsx` |
| Backfill スクリプト | ✅ | `backend/scripts/backfill_wiki_index.py` |

#### filing-back 3分岐ロジック

| outcome | 処理 |
|---------|------|
| `derivable` | log のみ記録（canonical ページは書き換えない）|
| `synthesis` | `kind=query_answer` の新規ページ作成 + index 同期 |
| `gap_or_conflict` | log に `target_page_id` 付きで記録 |

### 設計書に残っている操作（未実装・M2 後半で扱う）

| 操作 | 内容 |
|------|------|
| Lint | Wiki 全体の健全性チェック（矛盾・陳腐化・孤立ページ検出）|

---

## データの現状

### デバイス別トピック数（2026-04-19時点）

| device_id | 表示名 | トピック数 | workspace紐付け | 備考 |
|-----------|--------|-----------|----------------|------|
| `amical-db-test` | Amical Kaya (test) | 90件 | ✅ | テストデータ（3/2〜3/6） |
| `24246c8e-...` | redmi-001 | 88件 | ✅（本日修正） | 実機Android・本番データ |
| `d896b8bd-...` | （未登録） | 97件 | ❌ | 旧Android。未登録のまま |

### amical-db-test（蓄積テスト用）

| 日付 | 状態 |
|------|------|
| 2026-03-02 | ✅ 投入・Ingest済み |
| 2026-03-03 | ✅ 投入・Ingest済み |
| 2026-03-04 | ✅ 投入・Ingest済み |
| 2026-03-05 | ✅ 投入・Ingest済み |
| 2026-03-06 | ✅ 投入・Ingest済み |
| 2026-03-07 以降 | 未投入 |

- Fact 総数: 278件（Lv.3以上、2026-04-16時点）
- Wiki ページ数: 142件（`zerotouch_wiki_pages`、`kind=query_answer` 1件含む）
- Index 件数: 142件（`zerotouch_wiki_index`、backfill 済み・完全同期）
- Wiki ログ: 2件（`zerotouch_wiki_log`、`operation=query`）

### redmi-001（実機Android・本番データ）

- トピック数: 88件（`workspace_id` 補完済み）
- Wiki Ingest: **未実行**（コンテキスト紐付け後の初回 Ingest が必要）

### Ingest コマンド（次回データ追加後に実行）

```bash
# データ投入（backend/ から実行）
python3 ../experiments/amical/import_amical_db.py \
  --date 2026-03-07 \
  --provider openai \
  --model gpt-4.1-mini

# Wiki 再 Ingest（バックエンド起動済みの状態で）
curl -X POST http://localhost:8060/api/ingest-wiki \
  -H "Content-Type: application/json" \
  -d '{"device_id": "amical-db-test", "provider": "openai", "model": "gpt-4.1-mini"}'
```

---

## WebViewer の現状

URL: `http://localhost:3000`

| タブ | 状態 |
|------|------|
| Raw Sources | ✅ 動作中（Topics / Cards 表示）|
| Facts | ✅ 動作中（Annotation 済み Fact 一覧）|
| Wiki | ✅ 動作中（`zerotouch_wiki_pages` から表示。`[[WikiLink]]` 相互リンク解決済み）|
| Query | ✅ 動作中（`http://localhost:3000/query`。チャット UI・履歴・provider 選択・filing-back 表示）|
| ~~Ingest~~ | 削除済み（処理であってデータ種別ではないため）|

> **ローカルテスト時の注意**: `web-zero-touch/.env.local` の `ZEROTOUCH_API_BASE_URL` を `http://localhost:8060` に変更してから `npm run dev` を起動すること。

---

## Karpathy 設計との対応表

Karpathy gist の設計（<https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f>）と ZeroTouch 現行実装の照合結果。

### 対応済み（コア部分）

| Karpathy 概念 | ZeroTouch 実装 |
|--------------|--------------|
| 3層アーキテクチャ（Raw sources / Wiki / Schema） | `zerotouch_sessions` / `topics` / `facts` + `zerotouch_wiki_pages` + `knowledge-pipeline-v2.md` |
| Raw sources 不変 | ✅ 削除・上書きしない実装 |
| Wiki は LLM が全面管理 | ✅ `services/wiki_ingestor.py` で LLM が構築 |
| `[[WikiLink]]` による相互リンク | ✅ WebViewer 側で解決実装済み |
| ページ version / 出典管理 | ✅ `version` / `source_fact_ids` カラム |

### 対応済み（Query + filing-back フェーズで追加）

| Karpathy 概念 | ZeroTouch 実装 |
|--------------|--------------|
| `index.md` 相当 | ✅ `zerotouch_wiki_index` テーブル（migration 013）。Query パイプラインが最初に参照する入口 |
| `log.md` 相当 | ✅ `zerotouch_wiki_log` テーブル（migration 013）。`ingest` / `query` / `lint` 操作を時系列で追記 |
| Query + filing back | ✅ `POST /api/query-wiki`。回答を `kind=query_answer` ページとして書き戻す |

### 未対応または弱い領域（残課題）

| 領域 | 現状 | Karpathy 設計での位置づけ |
|------|------|---------------------|
| **1ソース → 複数ページへの広がりが弱い** | Fact 278件 → Wiki 142ページ（改善中） | entity / concept / summary / cross-ref の多面更新で「1ソース = 10〜15 ページ触れる」想定 |
| **Entity 軸がない** | `theme × kind` のみ。「Deepgram」「Speechmatics」のようなエンティティ専用ページが自動生成されない | entity pages / concept pages / summary pages を明確に区別 |
| **page_key 重複** | `zerotouch-ios-testflight-distribution` が 2 ページ存在 | 正規化・重複統合が必要 |
| **Lint（設計済み・未実装）** | 未実装（5種類に分類・優先度設計済み。詳細は M2 タスクリスト参照） | 矛盾・陳腐化・孤立ページ・**データギャップ**（頻出 entity なのにページなし等）の検出 |
| **Graph 可視化なし** | WebViewer にグラフビューがない | Obsidian graph view 相当 |

---

## M1 タスクリスト

### ✅ 完了（2026-04-19）

- **`context_profile` の実値入力**
  - `workspace_id = 6cbaeb05-9de6-4127-8b0a-dc0e46ac4046`（Amical Lab）に入力済み
  - `account_context`, `workspace_context`, `analysis_context` すべて詳細入力済み
  - `onboarding_completed_at: 2026-04-11`

- **実機 Android デバイスの登録とコンテキスト紐付け**
  - `zerotouch_devices` に `redmi-001`（`device_id: 24246c8e-9da0-4b9f-a104-cabff203785f`）を登録
  - 既存トピック88件に `workspace_id` を補完済み
  - これ以降の録音処理はコンテキスト付きで動く

- **Android マイページのコンテキスト表示を実データに刷新**
  - `ZeroTouchApi.kt` のデータクラスに実フィールドを追加（`primary_roles`, `workspace_summary`, `key_projects`, `analysis_objective`, `focus_topics` など）
  - マイページ表示をモックフィールドから実 DB フィールドに変更

### 🔴 残作業

- **Ingest 再実行（コンテキスト付き）**
  - `amical-db-test` に対して `POST /api/ingest-wiki` を再実行
  - 実機 Android（`redmi-001`）の蓄積データに対しても実行
  - コマンド:
    ```bash
    curl -X POST http://localhost:8060/api/ingest-wiki \
      -H "Content-Type: application/json" \
      -d '{"device_id": "amical-db-test", "provider": "openai", "model": "gpt-4.1-mini"}'
    ```
- **完了判定**
  - 生成された Wiki ページの分類（project / category）が focus_topics と整合しているか確認

---

## M2 タスクリスト

Karpathy 設計との乖離を埋め、Wiki が複利で育つ土台を作る。

### 🔴 高優先（M2 コア）

- **Wiki 分類軸を `project > category > page` に切り替える**
  - 設計: [`wiki-project-category-page-design.md`](./wiki-project-category-page-design.md)
  - project registry 導入
  - `zerotouch_wiki_pages` に `project_id`, `category`, `page_key` を追加
  - ingest prompt / WebViewer / API の切り替え
  - existing wiki pages の backfill 再 ingest
  - 備考: 既存 `theme` は移行期間だけ legacy field として維持

- **Entity 軸の追加**
  - **Phase 2 (Annotation) プロンプト** を改修し、Fact から entity（人・製品・場所・概念）を抽出する
    - 対象: `services/topic_annotator.py`
  - **Phase 3 (Ingest) プロンプト** を改修し、`kind: entity` ページを生成できるようにする
    - 対象: `services/wiki_ingestor.py`
    - DB: `zerotouch_wiki_pages.kind` enum に `entity` を追加（マイグレーション追加）
  - 例: 「Deepgram」「Speechmatics」ページに関連 Fact が自動で紐付く状態を作る
  - 効果: 「1 ファクト → 複数ページ更新」のスケール感が実現できる（0.04 → 目標 0.5+ pages/fact）

- **~~index テーブルの実装~~** ✅ **完了（2026-04-19）**
  - `zerotouch_wiki_index` テーブルを migration 013 で実装（選択肢 A を採用）
  - カラム: `page_id`, `title`, `kind`, `summary_one_liner`, `source_count`, `updated_at` 他
  - Query パイプラインが最初に参照する入口として機能

- **~~log テーブルの実装~~** ✅ **完了（2026-04-19）**
  - `zerotouch_wiki_log` テーブルを migration 013 で実装
  - `operation`（`ingest` / `query` / `lint`）/ `target_page_ids` / `summary` カラムで追記管理
  - Android での「Wiki 成長タイムライン」表示にも活用可能

### 🟡 中優先（M2 仕上げ）

- **~~Query + filing back 実装~~** ✅ **完了（2026-04-19）**
  - `POST /api/query-wiki`・`GET /api/wiki-log` 実装済み
  - `zerotouch_wiki_index` / `zerotouch_wiki_log` テーブル追加（migration 013）
  - filing-back 3分岐（derivable / synthesis / gap_or_conflict）実装済み
  - WebViewer Query UI（`http://localhost:3000/query`）実装済み

#### 次のフェーズ候補（Query 完了後）

| 優先度 | タスク | 概要 |
|--------|--------|------|
| (a) | ローカル UI テスト | `web-zero-touch/.env.local` を `localhost:8060` に向けて Query UI を手動検証 |
| (b) | Android 移植 | Query UI を Android Kotlin/Compose に移植。`POST /api/query-wiki` 連携 |
| (c) | Lint フェーズ実装 | 矛盾・陳腐化・孤立ページ・データギャップの検出バッチ |
| (d) | page_key 重複修正 | `zerotouch-ios-testflight-distribution` が 2 ページ存在する。重複を検出・統合する |

- **Lint 実装**（2026-04-19 設計確定）
  - Lint はページを書き換えない。結果は `zerotouch_wiki_log` に `operation='lint'` で記録するのみ
  - 矛盾・陳腐化は「全ページ対比較」ではなく index ベースの2段階絞り込みでスケール対応
  - 構造 Lint は index だけで判断できるため、陳腐化・矛盾より先に実装可能
  - 実行形態: 週次バッチ または オンデマンド API

  | 優先度 | 種類 | 方法 | 状態 |
  |--------|------|------|------|
  | MVP | 孤立ページ検出 | SQL で `[[page_key]]` 参照カウント | 未実装 |
  | MVP | データギャップ検出 | `gap_or_conflict` ログ集計 + LLM | 未実装 |
  | 次回 | 構造 Lint（分類誤り検出） | index 全行（title / summary / project / category）→ LLM 1回で分類適切性を判断。推奨移動先を `wiki_log` に記録 | 未実装 |
  | 後日 | 矛盾検出 | index で候補ペアを絞り込み → full body 比較（差分ベース） | 未実装 |
  | 後日 | 陳腐化検出 | 前回 Lint 以降に更新されたページ + 新 Fact との差分 → LLM | 未実装 |

### ⬜ 低優先（M2 後、余裕があれば）

- Graph 可視化（WebViewer にページ間リンクのグラフビューを追加）
- 出力フォーマット多様化（Marp / chart 等）
- Schema の co-evolution サポート（Wiki が育ったら schema 自体も LLM が更新）

---

## Android 移植の位置づけ

**M1 + M2 完了後** に Android への Wiki UI 実装フェーズへ移る。

- 現状 Android アプリには Wiki 表示 UI は**一切ない**ため、ゼロから作る
- Android 側で必要になるもの:
  - Wiki ページリスト画面（theme / kind で絞り込み）
  - ページ詳細ビュー（Markdown レンダリング、`[[WikiLink]]` タップで遷移）
  - Query UI（自然言語入力 → 回答 → filing back）
  - バックエンド `/api/wiki` および（M2 で追加される）`/api/query-wiki` との連携
- M2 で `zerotouch_wiki_log` ができていれば、Android 側で「Wiki の成長タイムライン」も表示可能

### Android Wiki/Query 移植（2026-04-19 完了）

**方式: WebView ハイブリッド**

| 実装内容 | 状態 |
|---------|------|
| `WikiWebViewScreen.kt` — `/wiki` を WebView 表示 | ✅ |
| `QueryWebViewScreen.kt` — `/query` を WebView 表示 | ✅ |
| MainActivity に Wiki/Query タブ追加（tab 4/5） | ✅ |
| Vercel デプロイ（main ブランチにマージ） | ✅ |
| Vercel 環境変数設定（SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, ZEROTOUCH_API_BASE_URL） | ✅ |

**URL**: `https://app-web-zero-touch.vercel.app/wiki` / `/query`

**注意**: web-zero-touch の開発ブランチは `codex/web-stateful-viewer`。Vercel は `main` を監視しているため、変更後は `main` へマージが必要。

---

## Query API の使い方

### Query 実行

```bash
curl -X POST http://localhost:8060/api/query-wiki \
  -H "Content-Type: application/json" \
  -d '{"device_id":"amical-db-test","question":"<質問>","provider":"openai","model":"gpt-4.1-mini","max_pages":5}'
```

レスポンス例:

```json
{
  "answer": "...",
  "outcome": "derivable",
  "referenced_pages": ["page-key-1", "page-key-2"],
  "new_page_id": null
}
```

`outcome` は `derivable` / `synthesis` / `gap_or_conflict` の3値。

### Query ログ確認

```bash
curl "http://localhost:8060/api/wiki-log?device_id=amical-db-test&operation=query&limit=10"
```

### Index backfill（既存ページを index に一括登録）

新規環境構築時や migration 013 適用直後に実行する。

```bash
cd backend && set -a && source .env && set +a
python3 scripts/backfill_wiki_index.py --device-id amical-db-test
```

### WebViewer でのテスト

1. `web-zero-touch/.env.local` の `ZEROTOUCH_API_BASE_URL` を `http://localhost:8060` に変更
2. `npm run dev` でローカル起動
3. `http://localhost:3000/query` を開く

---

## ローカル環境の起動

```bash
# WebViewer
cd /Users/kaya.matsumoto/projects/watchme/app/web-zero-touch
npm run dev
# → http://localhost:3000

# バックエンド
cd /Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend
set -a && source .env && set +a && python3 app.py
# → http://localhost:8060
```

---

## 設計の正本

長期記憶パイプラインの設計判断は [`knowledge-pipeline-v2.md`](./knowledge-pipeline-v2.md) を参照。

Karpathy の「LLM Wiki」設計原本: <https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f>
（主要概念: `index.md` / `log.md` / Ingest / Query / Lint / entity pages / filing back）
