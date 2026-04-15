# Amical Long-Term Memory Handoff

## 目的

ZeroTouch における長期記憶・知識蓄積パイプラインの引き継ぎメモ。
次のセッションでここから再開する。

更新日: `2026-04-16`

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

### 設計書に残っている操作（未実装・後回し）

| 操作 | 内容 |
|------|------|
| Query | Wiki に自然言語で質問 → 回答を Wiki に書き戻す |
| Lint | Wiki 全体の健全性チェック（矛盾・陳腐化・孤立ページ検出）|

---

## データの現状

### amical-db-test（蓄積テスト用）

| 日付 | 状態 |
|------|------|
| 2026-03-02 | ✅ 投入・Ingest済み |
| 2026-03-03 | ✅ 投入・Ingest済み |
| 2026-03-04 | ✅ 投入・Ingest済み |
| 2026-03-05 | ✅ 投入・Ingest済み |
| 2026-03-06 以降 | 未投入 |

- Fact 総数: 145件（Lv.3以上）
- Wiki ページ数: 11ページ（初回 Ingest 結果）

### Ingest コマンド（次回データ追加後に実行）

```bash
# データ投入（backend/ から実行）
python3 ../experiments/amical/import_amical_db.py \
  --date 2026-03-06 \
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
| Wiki | ✅ 動作中（`zerotouch_wiki_pages` から表示）|
| ~~Ingest~~ | 削除済み（処理であってデータ種別ではないため）|

---

## 次にやること

### 優先: Wiki UI の改善

現在の Wiki タブはカードの羅列のみ。以下を目指す：

- テーマ（theme）軸でのナビゲーション
- ページ詳細表示（本文全文・根拠 Fact へのリンク）
- kind（decision / rule / insight 等）でのフィルタ
- 検索

### その後

1. データ追加（03-06 以降を 1 日ずつ投入 → Ingest 再実行）してWikiが育つか確認
2. Query 実装（Wiki への自然言語 Q&A）
3. Lint 実装（週次ヘルスチェック）

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
