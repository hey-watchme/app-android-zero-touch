# Amical Experiments

このディレクトリは、`Amical` の文字起こし済みログを使って
ZeroTouch の知識化プロセスをオフライン検証するための作業場所。

## 目的

まずは Android アプリやリアルタイム処理から切り離し、
会話ログから `task` と `knowledge` を抽出・正規化・統合し、
さらに人間が読める `spot wrap-up` と `daily rollup` へ再構成できるかを検証する。

## 保存方針

コードと生成物は分ける。

- スクリプトやメモ: Git 管理する
- 実験生成物: `experiments/amical/artifacts/` に出力し、Git 管理しない

## 生成物の段階

### `00_source_window.json`

対象ログと時間窓のメタ情報。

- input path
- start / end
- total log events
- extracted candidate count

### `01_candidate_utterances.jsonl`

生ログから転写候補だけを抜き出した段階。

- 空文字以外の転写
- session_id や audio_file_path が拾えれば付与
- まだ重複や途中経過を含む

### `02_raw_corpus.jsonl`

知識化パイプラインの入力に使う正規化済み発話列。

- 時系列順
- text を正規化
- 明らかな重複を除外
- `raw_ref` で元ログに戻れる

### `02_cleaning_report.json`

どの候補がなぜ除外されたかの記録。

### `03_topics.jsonl`

`02_raw_corpus.jsonl` を時系列でまとめた最初の Topic 群。

- 初期実装では時間ギャップベース
- utterance ids を保持
- 後段の summary / distillation の入力

### `03_topic_report.json`

Topic 化の設定値と件数サマリ。

### `03_topics_preview.md`

人間が読みやすい Topic 一覧。

### `05_distilled_items.jsonl`

Topic ごとに抽出した `task` / `knowledge` 候補。

- item type
- summary
- rationale
- evidence utterance ids
- source topic id

### `05_distilled_report.json`

抽出件数、利用モデル、Topic ごとの件数サマリ。

### `05_distilled_preview.md`

人間が読みやすい task / knowledge の一覧。

### `06_canonical_items.jsonl`

同じ意味の distilled items を統合した current-window canonical 層。

- `task` は統合済みタスクリストとして保持
- `knowledge` はカテゴリ付きで保持
- source item / topic / utterance へ遡れる

### `06_canonical_report.json`

canonical 化の件数、利用モデル、カテゴリ一覧。

### `06_canonical_preview.md`

人間が読みやすい canonical 出力。

- Task List
- Knowledge by Category

### `07_spot_wrapup.json`

スポット窓を読み物として再構成した wrap-up 本体。

- headline
- abstract
- status summary
- narrative sections
- decisions
- open tasks
- knowledge refs
- annotations

### `07_spot_wrapup_report.json`

spot wrap-up 生成の件数サマリと利用モデル情報。

### `07_spot_wrapup_preview.md`

人間が読みやすい spot wrap-up プレビュー。

- 概要本文
- 主要トピック
- 決まったこと
- やること
- 参照知識
- annotation index

### `08_daily_rollup.json`

同日の複数 spot wrap-up を集約した日次ロールアップ本体。

- headline
- abstract
- status summary
- main threads
- decisions
- open tasks
- knowledge refs
- source spots

### `08_daily_rollup_report.json`

daily rollup 生成の件数サマリと利用モデル情報。

### `08_daily_rollup_preview.md`

人間が読みやすい daily rollup プレビュー。

### `stateful daily` の出力

状態をまたいで参照する実験では、次の artifact を使う。

- `09_context_bundle.json`
- `10_stateful_daily_rollup.json`
- `11_state_delta.json`
- `12_active_state_snapshot.json`

必要に応じて、それぞれの preview / report も同じ stem で出力する。

補足:

- `12_active_state_snapshot.json` と `09_context_bundle.json` は file-based reducer で生成する
- `10_stateful_daily_rollup.json` と `11_state_delta.json` は prior state を見た planner を通して生成する
- `generate_stateful_daily.py` は既定で LLM planner を使い、失敗時は deterministic fallback に落ちる
- LLM を使わずに確認したい場合は `--disable-llm` を付ける

### 以降の予定

今後は必要に応じて以下を追加する。

- `04_topic_summaries.jsonl`
- `09_retrieval_docs.jsonl`

## 実行例

```bash
python3 experiments/amical/normalize_amical_log.py \
  --input /Users/kaya.matsumoto/Desktop/amical-logs-2026-03-29.log \
  --start 2026-03-27T15:11:00 \
  --minutes 60

python3 experiments/amical/topic_raw_corpus.py \
  --dataset-dir experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000

python3 experiments/amical/extract_distilled_items.py \
  --dataset-dir experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000

python3 experiments/amical/canonicalize_distilled_items.py \
  --dataset-dir experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000

python3 experiments/amical/generate_spot_wrapup.py \
  --dataset-dir experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000

python3 experiments/amical/generate_daily_rollup.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_active_state_snapshot.py \
  --date 2026-03-28 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_context_bundle.py \
  --date 2026-03-28 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_active_state_snapshot.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_context_bundle.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_stateful_daily.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts

python3 experiments/amical/generate_stateful_daily.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts \
  --disable-llm
```
