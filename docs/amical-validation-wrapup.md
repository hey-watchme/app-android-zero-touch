# Amical Validation Wrap-Up

> この文書は初期検証の履歴です。
> 現在の作業再開は [`amical-longterm-memory-handoff.md`](./amical-longterm-memory-handoff.md)、
> 設計判断は [`knowledge-pipeline-v2.md`](./knowledge-pipeline-v2.md) を参照してください。

## 目的

この文書は、`Amical` の文字起こし済みログを使った
ZeroTouch のオフライン価値検証について、
次のセッションからゼロベースで再開できるように
現時点の結果をまとめた引き継ぎメモである。

ここでの主題は Android アプリ実装そのものではなく、
**会話ログから task / knowledge を抽出し、整理し、再利用できる形にできるか**
という知識化プロセスの検証である。

関連文書:

- [amical-longterm-memory-handoff.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/amical-longterm-memory-handoff.md)
- [knowledge-pipeline-v2.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/knowledge-pipeline-v2.md)

---

## 出発点

確認済みの前提:

- `Amical` は音声入力アプリであり、文字起こし済みログを持つ
- 実ログファイルは `/Users/kaya.matsumoto/Desktop/amical-logs-2026-03-29.log`
- ログには内部イベントも含まれるが、`Cloud transcription successful` から発話候補を抽出できる
- `Amical` は ZeroTouch 上では `source_type=amical_transcriptions` の仮想デバイスとして扱う想定

このログは `2026-03-27 15:11:43` から `2026-03-29 11:08:37` までを含む。
完全な 1 日として使えるのは `2026-03-28`。

---

## 価値検証の方針（短縮版）

- まずは `Amical` の文字起こし済みログで `task / knowledge` を再構築できるかを確認する
- リアルタイム処理や本番 DB 連携は後回しにし、再実行可能なバッチで検証する
- 最初は `1時間 → 半日 → 1日` の順に広げ、品質と密度を評価する
- 最終的には「構造化された知識」と「探索的検索」を両立させる

---

## 今回作った実験パイプライン

保存方針:

- スクリプトと設計メモは Git 管理する
- 実験生成物は `experiments/amical/artifacts/` に置き、Git 管理しない

作成した実験スクリプト:

- [normalize_amical_log.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/normalize_amical_log.py)
- [topic_raw_corpus.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/topic_raw_corpus.py)
- [extract_distilled_items.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/extract_distilled_items.py)
- [canonicalize_distilled_items.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/canonicalize_distilled_items.py)
- [generate_spot_wrapup.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/generate_spot_wrapup.py)
- [generate_daily_rollup.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/generate_daily_rollup.py)
- [generate_active_state_snapshot.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/generate_active_state_snapshot.py)
- [generate_context_bundle.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/generate_context_bundle.py)
- [generate_stateful_daily.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/generate_stateful_daily.py)

生成物の段階:

1. `00_source_window.json`
入力ログと対象時間窓のメタ情報

2. `01_candidate_utterances.jsonl`
`Cloud transcription successful` から抽出した転写候補

3. `02_raw_corpus.jsonl`
空転写、明らかな重複、途中経過を落とした正規化済み発話列

4. `03_topics.jsonl`
`30秒以上の無発話ギャップ` で分割した first-pass topic

5. `05_distilled_items.jsonl`
topic ごとに抽出した `task` / `knowledge`

6. `06_canonical_items.jsonl`
同一 window 内で重複統合した canonical task / canonical knowledge

7. `07_spot_wrapup.json`
スポット窓を読み物として再構成した wrap-up 本体

8. `08_daily_rollup.json`
同日の複数 spot wrap-up を集約した日次ロールアップ本体

9. `09_context_bundle.json`
翌日に渡す圧縮文脈

10. `10_stateful_daily_rollup.json`
前日までの state を参照した日次読み物

11. `11_state_delta.json`
当日の state 変化ログ

12. `12_active_state_snapshot.json`
その日終了時点の current state

補助生成物:

- `02_cleaning_report.json`
- `03_topic_report.json`
- `03_topics_preview.md`
- `05_distilled_report.json`
- `05_distilled_preview.md`
- `06_canonical_report.json`
- `06_canonical_preview.md`
- `07_spot_wrapup_report.json`
- `07_spot_wrapup_preview.md`
- `08_daily_rollup_report.json`
- `08_daily_rollup_preview.md`
- `10_stateful_daily_rollup_preview.md`

---

## 使ったプロンプト

### 1. Distilled Items 抽出

実装箇所:

- [extract_distilled_items.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/extract_distilled_items.py)

意図:

- topic を入力にする
- 出力は `task` と `knowledge` の 2 種のみ
- filler や弱い断片は落とす
- `evidence_utterance_ids` を必須にして、元発話へ戻れるようにする

要点:

- `task` は「誰かがやるべき具体的行動」
- `knowledge` は「再利用可能な insight / rule / principle」
- topic の utterance id を証拠として保持
- 日本語で出力
- JSON 固定出力

出力 schema:

```json
{
  "items": [
    {
      "item_type": "task|knowledge",
      "summary": "short Japanese summary",
      "rationale": "why this item matters",
      "confidence": "high|medium|low",
      "evidence_utterance_ids": ["utt-000001"]
    }
  ]
}
```

### 2. Canonical 化

実装箇所:

- [canonicalize_distilled_items.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/canonicalize_distilled_items.py)

意図:

- distilled items を current-window 単位で統合する
- `task` は to-do list に近い形で整理する
- `knowledge` はカテゴリ付きで整理する
- source item / topic / utterance へ遡れるようにする

要点:

- duplicate / near-duplicate を統合
- task は `implementation|documentation|research|decision|other`
- knowledge は短い日本語カテゴリを付与
- categories 例: `UI`, `設計`, `パイプライン`, `運用`, `コスト`
- 日本語で出力
- JSON 固定出力

出力 schema:

```json
{
  "canonical_tasks": [
    {
      "title": "string",
      "summary": "string",
      "task_kind": "implementation|documentation|research|decision|other",
      "priority": "high|medium|low",
      "source_item_ids": ["topic-001__item_001"]
    }
  ],
  "canonical_knowledge": [
    {
      "category": "string",
      "title": "string",
      "summary": "string",
      "source_item_ids": ["topic-001__item_005"]
    }
  ]
}
```

### 3. Topic 化

実装箇所:

- [topic_raw_corpus.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/topic_raw_corpus.py)

現状は LLM を使っていない。

ルール:

- `02_raw_corpus.jsonl` を時系列で読む
- 前発話から `30秒以上` 空いたら新 topic
- これは first-pass であり、意味ベースの結合は未実装

---

## 実施した window と結果

### 1. 最初の薄い 1 時間窓

対象:

- `2026-03-27 15:11:00` から `60分`

結果:

- `candidate 19`
- `raw corpus 11`

評価:

- 正規化の確認としては有効
- 価値検証用としては薄い

参照:

- [00_source_window.json](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260327T151100__20260327T161100/00_source_window.json)

### 2. 推奨 1 時間窓

対象:

- `2026-03-29 01:00:00` から `60分`

理由:

- 設計議論、UI、パイプライン、コスト、実行タイミングの話が集中している
- `task` と `knowledge` の両方が出やすい

結果:

- `candidate 59`
- `raw corpus 29`
- `topics 14`
- `distilled items 31`
- `canonical items 28`
  - `task 15`
  - `knowledge 13`

knowledge categories:

- `UI`
- `コスト`
- `パイプライン`
- `モデル`
- `設計`
- `運用`

評価:

- 小さなサンプルとしてはかなり良い
- `task` と `knowledge` の切り分けは成立
- ただし `30秒` だけで切ると、同じテーマの topic が分かれる箇所がある

主な参照:

- [03_topics_preview.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000/03_topics_preview.md)
- [05_distilled_preview.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000/05_distilled_preview.md)
- [06_canonical_preview.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260329T010000__20260329T020000/06_canonical_preview.md)

### 3. 完全な 1 日窓

対象:

- `2026-03-28 00:00:00` から `2026-03-29 00:00:00`

結果:

- `candidate 309`
- `raw corpus 241`
- `topics 113`
- `distilled items 169`
  - `task 105`
  - `knowledge 64`
- `canonical items 122`
  - `task 61`
  - `knowledge 61`

knowledge categories:

- `UI`
- `コスト`
- `ストレージ`
- `パイプライン`
- `実装`
- `強み`
- `環境`
- `端末`
- `精度`
- `表現`
- `設計`
- `調査`
- `運用`
- `音声認識`

評価:

- `1日をまとめて見ると重複がかなり増える`
- `0 item` の topic も相当数あり、ノイズ topic を自然に捨てられている
- ただし日次で直接 canonical 化するとまだ多すぎる
- `spot topic -> hourly rollup -> daily rollup` の多段集約の必要性が見えた

主な参照:

- [05_distilled_report.json](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260328T000000__20260329T000000/05_distilled_report.json)
- [06_canonical_report.json](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260328T000000__20260329T000000/06_canonical_report.json)
- [06_canonical_preview.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/experiments/amical/artifacts/amical-logs-2026-03-29__20260328T000000__20260329T000000/06_canonical_preview.md)

---

## ここまでで分かったこと

### うまくいっている点

- `Amical` ログから再利用可能な `task` / `knowledge` は抽出できる
- evidence 付きで保存できるため、元発話に戻れる
- 1 時間窓では `task list` と `category -> knowledge` の形がすでに見える
- 日次でも topic の一部は自然に `0 item` になり、ノイズが落ちる

### 足りていない点

- topic 分割がまだ時間ギャップ依存で、意味ベースの補正がない
- distilled items は過剰抽出気味の箇所がある
- canonical は current-window 単位では機能するが、1 日ではまだ粗い
- knowledge はまだ断片であり、「マニュアル」や「まとまった知識文書」にはなっていない

### 重要な設計示唆

一気に `1日 -> final canonical` へ行くより、
次のような上流構造が有望。

1. `topic spot analysis`
2. `hourly rollup`
3. `daily rollup`

現時点では、この理想形の最初の段として
`canonical` の次に **spot wrap-up** を置き、
その上で複数の spot をまとめる **daily rollup** を追加した。
これは `spot -> daily` という2段で読み物を蒸留する最初の形であり、
より細かい多段集約は後で精度を高めるために検討する。

この形なら、

- 1 topic の意味抽出
- 近接した topic の統合
- 日次単位での最終整理

を段階的に行える。

---

## 現在のアウトプットイメージ

### task 側

最終的に見せたいのは `Task List` に近い形。

現時点でも preview では以下に近い形で見えている。

- title
- summary
- priority
- task_kind
- source_item_ids

### knowledge 側

現時点では、まだ断片知識の束。
それでも `category -> knowledge items` の形で見えるようになっている。

ただし、ここから先は

- 断片の統合
- 冗長表現の圧縮
- より説明的な文章化
- バージョン更新

が必要。

つまり今の `canonical knowledge` は
**マニュアルの手前の中間層**
として扱うのが適切。

---

## 次にやるべきこと

優先順位順:

1. `07_spot_wrapup` の読み味を評価する
2. `08_daily_rollup` で日次粒度の圧縮が十分か確認する
3. `30秒ギャップ` に意味ベース補正を入れる
4. より細かい多段集約が必要なら後から追加する
5. canonical knowledge から `knowledge doc` を作る
6. その後に UI へどう見せるかを再検討する

今の判断としては、
**価値実証に最も近い次の一手は、spot と daily の2粒度で読み物を磨くこと。**

---

## 実験の読み方

人間がざっと見る時は次を見る。

1. `03_topics_preview.md`
topic の切れ方を見る

2. `05_distilled_preview.md`
task / knowledge がそれっぽく取れているか見る

3. `06_canonical_preview.md`
統合後にどう見えるかを見る

4. `07_spot_wrapup_preview.md`
そのスポット窓で何が起きたかを読み物として素早く把握できるかを見る

5. `08_daily_rollup_preview.md`
複数の spot を束ねたときに、その日を日次粒度で把握できるかを見る

機械処理や後続実験の入力に使う時は次を見る。

- `02_raw_corpus.jsonl`
- `03_topics.jsonl`
- `05_distilled_items.jsonl`
- `06_canonical_items.jsonl`
- `07_spot_wrapup.json`
- `08_daily_rollup.json`

---

## 現在の到達点と次の一手

到達点:

- `spot -> daily` の蒸留は実データで成立
- `stateful daily` の artifact (`09/10/11/12`) まで生成できる
- Web 側で `/stateful` viewer を作成し、人間が読み味を検証できる状態

次の一手:

- `11_state_delta.json` の `close / supersede` 判定を強化し、snapshot の肥大化を抑える
- `task / knowledge` の統合ルールを明確化して state を安定化する
- 数日〜1ヶ月の連続処理で読み味を評価する

---

## 補足

今回の検証は、コスト最適化を主目的にしていない。
`gpt-5.4-2026-03-05` を使い、
まずは **価値が本当に出るか** を優先している。

つまり、ここで作っているのは最終の省コスト設計ではなく、
**精度優先の理想パイプラインの最初のプロトタイプ**
である。
