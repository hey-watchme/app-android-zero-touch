# Amical Batch Reprocessing (Low-Cost LLM)

目的: Amical の大量ログを「イベント(=Topic) → タスク/ナレッジ注釈 → stateful artifacts」に変換し、
`web-zero-touch` の Timeline/Tasks/Knowledge で 8 時間以上の負荷を確認する。

前提:
- 入力は Amical のテキスト化済みログ
- 出力は `experiments/amical/artifacts/daily-rollups/<YYYY-MM-DD>/` 配下の JSON
- 既存の `experiments/amical` スクリプトを利用し、低コスト LLM で生成する

## 1) 必要な出力 (最低限)

必須:
- `08_daily_rollup.json`
- `10_stateful_daily_rollup.json`
- `11_state_delta.json`
- `12_active_state_snapshot.json`

あると便利:
- `09_context_bundle.json`

※ `web-zero-touch` の Timeline は `10_stateful_daily_rollup.json` の `source_spots` を使う。

## 2) 低コスト LLM の方針

狙い: 精度のピークより「安定出力」と「大量処理」を優先する。

推奨:
- 単発の高性能モデルは使わない
- 1 つの長文を投げず、短い chunk を繰り返し処理
- 出力は必ず JSON。型が壊れたら即リトライ

プロンプト共通ルール:
- 出力は JSON のみ
- 空/不明な場合は空配列・空文字を返す
- source id / time などの参照キーは必ず残す

## 3) 推奨処理フロー (既存スクリプト前提)

以下は `experiments/amical/README.md` の流れを、低コスト LLM 前提で再整理したもの。

1. 正規化
```bash
python3 experiments/amical/normalize_amical_log.py \
  --input /path/to/amical.log \
  --start 2026-03-29T09:00:00 \
  --minutes 480
```

2. Topic 化 (時間ギャップベース)
```bash
python3 experiments/amical/topic_raw_corpus.py \
  --dataset-dir experiments/amical/artifacts/<dataset>
```

3. 蒸留 (低コスト LLM)
```bash
python3 experiments/amical/extract_distilled_items.py \
  --dataset-dir experiments/amical/artifacts/<dataset> \
  --model <cheap-llm>
```

4. Canonical 化
```bash
python3 experiments/amical/canonicalize_distilled_items.py \
  --dataset-dir experiments/amical/artifacts/<dataset> \
  --model <cheap-llm>
```

5. Spot/Daily/Stateful 生成
```bash
python3 experiments/amical/generate_spot_wrapup.py \
  --dataset-dir experiments/amical/artifacts/<dataset> \
  --model <cheap-llm>

python3 experiments/amical/generate_daily_rollup.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts \
  --model <cheap-llm>

python3 experiments/amical/generate_active_state_snapshot.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts \
  --model <cheap-llm>

python3 experiments/amical/generate_context_bundle.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts \
  --model <cheap-llm>

python3 experiments/amical/generate_stateful_daily.py \
  --date 2026-03-29 \
  --artifacts-root experiments/amical/artifacts \
  --model <cheap-llm>
```

## 4) モデル選定の要件 (安価モデル向け)

最低限の条件:
- JSON 出力が安定
- 32k 以上のコンテキストがなくても良い (短文 chunk 前提)
- 失敗時にリトライできる

評価基準:
- task/knowledge の取り漏れを許容しつつ、誤検出を抑える
- source_refs が欠落しないこと
- 同日内での粒度がブレすぎないこと

## 5) データ量の目安と分割

実務ログは長いので、必ず分割して処理する。

分割指針:
- 1 時間 = 1 dataset (最初はこれで十分)
- 8 時間 = 8 dataset で生成し、`daily-rollups` に統合する
- 1 日分の `source_spots` が複数できることが Timeline 表示の前提

## 6) Timeline での確認ポイント

- 24 時間の中で event が散らばって見えるか
- event が密集する時間帯でも重ならずに見えるか
- event に task / knowledge が乗ったときの密度が過剰にならないか

## 7) 失敗時のよくある原因

- `source_spots` が 1 件しかない
  - 1 時間分しか処理していない可能性が高い
- JSON が壊れる
  - 低コストモデルの出力安定化が必要
- 同じ task が大量に重複する
  - canonical 化のプロンプトを強くする

## 8) 最低限の完了条件

- 8 時間分の `source_spots` が作られている
- `Timeline` に複数イベントが並ぶ
- `Tasks` と `Knowledge` が表示される (内容は粗くても良い)
