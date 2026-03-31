# Amical Stateful Daily Design

## 目的

この文書は、`stateful daily` を実装するための具体設計を定義する。
ここでいう `stateful daily` とは、

- その日の複数 `spot wrap-up` をまとめる
- 直近までの継続状態を参照する
- その日の `daily` 本文を作る
- 同時に durable state を更新する

という 1 回の処理である。

この文書はゼロベースで読めるように、

- 何を解決したいのか
- 何を入力にするのか
- どんな state を持つのか
- どう更新するのか
- どこまでを初期実装に含めるのか

を固定する。

関連文書:

- [amical-validation-wrapup.md](./amical-validation-wrapup.md)
- [conversation-pipeline-processes.md](./conversation-pipeline-processes.md)

---

## 結論

`stateful daily` は、単なる `daily rollup` ではない。

必要なのは次の 4 出力である。

1. `10_stateful_daily_rollup.json`
人間が読む日次サマリー

2. `11_state_delta.json`
その日を処理した結果として durable state に加える差分

3. `09_context_bundle.json`
翌日の `spot` / `daily` 生成時に参照する圧縮文脈

4. `12_active_state_snapshot.json`
その日終了時点の current state の正本

つまり `stateful daily` は、
**要約器ではなく「日次で状態を進める reducer」**
として扱うべきである。

---

## 何を解決するのか

現在の `spot -> daily` では、各日が独立している。

その結果、次ができない。

- 昨日から続く task の追跡
- 以前の decision が今も有効かの判断
- durable knowledge の更新と陳腐化管理
- 過去経緯を踏まえた daily 生成

`stateful daily` の役割は、この切断を埋めることである。

---

## スコープ

### 初期実装に含めるもの

- `spot wrap-up` 群を入力にした daily 生成
- task / decision / durable knowledge の最小 state 管理
- state 更新差分の出力
- 翌日用の context bundle 生成

### 初期実装に含めないもの

- weekly / monthly rollup
- 本番 DB 反映
- Vertex / embedding / vector retrieval の本実装
- Android / Web UI への統合

初期段階では、まず file-based artifacts として成立させる。

---

## 前提

### すでに存在するもの

- `spot wrap-up`
- `daily rollup`
- topic / distilled / canonical artifacts
- 重要度・知識化の長期方針

### 初期入力の単位

1 日に対して、
複数の `07_spot_wrapup.json` を入力として受け取る。

必要に応じて以下も参照する。

- 前日までの `daily` 本文
- active task 一覧
- current decision 一覧
- current durable knowledge 一覧

---

## Stateful Daily の入出力

## 入力

### 1. Current Spots

当日分の `spot wrap-up` 群。

最低限必要な内容:

- headline
- abstract
- narrative sections
- decisions
- open tasks
- knowledge refs
- annotations

### 2. Prior State Snapshot

前日終了時点の current state。

最低限必要な内容:

- active tasks
- active decisions
- current durable knowledge

### 3. Recent Rollup Context

直近数日の読み物的文脈。

最低限必要な内容:

- 直近 3〜7 日の daily headline / abstract
- unresolved / still-relevant items

### 4. Optional Retrieval Context

将来追加。

用途:

- 類似テーマの過去議論を引く
- durable knowledge の補強

初期実装ではなくてよい。

## 出力

### 1. `10_stateful_daily_rollup.json`

stateful daily 本体。

中身:

- headline
- abstract
- status summary
- main threads
- decisions today
- carried-over tasks
- newly opened tasks
- updated knowledge
- source spots

### 2. `11_state_delta.json`

durable state への差分。

中身:

- task mutations
- decision mutations
- durable knowledge mutations

### 3. `09_context_bundle.json`

翌日の `spot` / `daily` 生成に渡す圧縮文脈。

中身:

- current priorities
- unresolved tasks
- active decisions
- key durable knowledge summaries
- recent chronology summary

### 4. `10_stateful_daily_rollup_preview.md`

人間が読むための preview。

---

## Durable State の最小スキーマ

### A. Task State

役割:

- 継続中の task を日をまたいで追跡する
- 新規 / 継続 / 完了 / 無効化を管理する

最小フィールド:

```json
{
  "task_id": "uuid-or-stable-id",
  "task_key": "semantic stable key",
  "title": "string",
  "summary": "string",
  "status": "open|in_progress|done|superseded|dropped",
  "priority": "high|medium|low",
  "task_kind": "implementation|documentation|research|decision|other",
  "first_seen_at": "datetime",
  "last_seen_at": "datetime",
  "closed_at": "datetime|null",
  "superseded_by": "task_id|null",
  "source_refs": ["daily-2026-03-28:SPOT-001:T003"],
  "evidence_refs": ["topic-001", "utt-000123"],
  "notes": "optional"
}
```

更新ルール:

- 同じ意味の task が再登場したら `last_seen_at` を更新
- 完了が明示されたら `status=done`
- 別 task に吸収されたら `status=superseded`
- 長期間再登場せず relevance が低いものは `dropped` 候補

### B. Decision Log

役割:

- その時点で有効な運用方針や明示的決定を管理する

最小フィールド:

```json
{
  "decision_id": "uuid-or-stable-id",
  "decision_key": "semantic stable key",
  "statement": "string",
  "status": "active|superseded|revoked",
  "decided_at": "datetime",
  "last_confirmed_at": "datetime",
  "superseded_by": "decision_id|null",
  "source_refs": ["daily-2026-03-28:SPOT-001:K001"],
  "confidence": "high|medium|low"
}
```

更新ルール:

- 同一決定の再確認は `last_confirmed_at` を更新
- 反対の決定が出たら旧 decision を `superseded` または `revoked`
- 曖昧な提案は decision として昇格しない

### C. Durable Knowledge

役割:

- 日をまたいで参照すべき知識を current state として保持する

最小フィールド:

```json
{
  "knowledge_id": "uuid-or-stable-id",
  "knowledge_key": "semantic stable key",
  "title": "string",
  "summary": "string",
  "category": "string",
  "status": "current|outdated|superseded|merged",
  "valid_from": "datetime",
  "valid_to": "datetime|null",
  "last_confirmed_at": "datetime",
  "superseded_by": "knowledge_id|null",
  "source_refs": ["daily-2026-03-28:SPOT-003:K010"],
  "related_task_ids": ["task_id"],
  "notes": "optional"
}
```

更新ルール:

- 同じ知識の再確認は `last_confirmed_at` を更新
- より新しい方針や仕様が出たら旧 knowledge を `superseded`
- 季節性や期限付き情報は `valid_to` を持つ

### D. Context Bundle

役割:

- 翌日の生成時に毎回全部の履歴を渡さず、必要な state を圧縮して渡す

最小フィールド:

```json
{
  "bundle_date": "2026-03-28",
  "recent_chronology_summary": "string",
  "active_task_refs": ["task_id"],
  "active_decision_refs": ["decision_id"],
  "active_knowledge_refs": ["knowledge_id"],
  "priority_items": [
    "high-priority unresolved tasks",
    "critical active decisions",
    "knowledge likely to affect next-day interpretation"
  ]
}
```

更新ルール:

- その日の state を反映したあとに再生成する
- 全量 dump ではなく、次日の解釈に効く項目だけ残す

---

## Stateful Daily の処理フロー

```text
Current day spot wrap-ups
  + prior active state snapshot
  + recent daily context
      -> stateful daily planner
          -> daily readable output
          -> state delta
      -> state delta validator
      -> apply patch to current state
      -> generate next context bundle
```

### Step 1: Gather Inputs

集めるもの:

- 当日の spot wrap-up 一覧
- 前日終了時点の current state
- 直近数日の daily summary

### Step 2: Build Planning Prompt

LLM に渡すもの:

- 当日の spot
- active tasks
- active decisions
- active durable knowledge
- recent chronology summary

### Step 3: Produce Two Things at Once

LLM には同時に 2 種類を出させる。

1. `readable daily`
2. `state delta`

これを分ける理由:

- 読み物と正本を分離したい
- 後で UI と DB に別々に使える

### Step 4: Validate Patch

LLM 出力をそのまま apply しない。

最低限の検証:

- source ref が存在するか
- superseded_by が自己参照していないか
- closed task が open task と矛盾していないか
- decision / knowledge の status が正当か

### Step 5: Apply Patch

apply 後に current state を更新する。

### Step 6: Emit Next Context Bundle

更新済み state から、
翌日用の context bundle を作る。

---

## Stateful Daily の生成ルール

### daily readable output のルール

- その日新しく起きたことだけでなく、継続中の文脈も含める
- ただし全文の履歴説明にはしない
- `today's changes` と `continuing state` を分ける

### state delta のルール

- 新規 task は `create`
- 継続 task は `touch` / `update`
- 完了 task は `close`
- 知識の置換は `supersede`
- decision の更新は `confirm` / `replace`

初期実装では mutation type を明示してよい。

例:

```json
{
  "task_mutations": [
    {
      "mutation": "create",
      "task_key": "topic-filter-ui-fix",
      "title": "トピック一覧のレベルフィルター不具合を修正する"
    },
    {
      "mutation": "close",
      "task_id": "task-123",
      "reason": "completed"
    }
  ]
}
```

---

## 競合と陳腐化の扱い

### Task

問題:

- 同じ task が違う文で何度も出る
- 完了したのに再登場する

方針:

- semantic `task_key` を持つ
- close 後に再登場したら reopen ではなく new issue か再確認する
- 長期間観測されない task は stale 候補として別扱い

### Decision

問題:

- 古い方針と新しい方針が衝突する

方針:

- decision は active を 1 つに寄せる
- 新しい方針が明示されたら旧 decision を superseded
- 提案と決定は分ける

### Durable Knowledge

問題:

- 以前は正しかったが今は古い
- 似ている知識が増殖する

方針:

- `current / outdated / superseded / merged` を持つ
- 有効期限や last_confirmed を持つ
- 同義知識は merged

---

## 初期 artifact 設計

初期の file-based artifact は次で十分。

### `10_stateful_daily_rollup.json`

人間が読む本文 + machine-readable state refs を持つ。

### `11_state_delta.json`

durable state への差分。

### `12_active_state_snapshot.json`

その日終了時点の current state。

### `09_context_bundle.json`

翌日生成用の圧縮文脈。

### `10_stateful_daily_rollup_preview.md`

人間向け preview。

---

## DB 実装に落とすときの対応先

将来的には file artifact ではなく DB に乗せる。

対応先のイメージ:

- task state: 新規 `zerotouch_task_state`
- decision log: 新規 `zerotouch_decisions`
- durable knowledge: 既存の `zerotouch_knowledge` を活用
- context bundle: 生成 artifact または `zerotouch_context_bundles`

ここでは schema を固定しすぎず、
まず artifact で実験してから DB 化する。

---

## 実装順

### Step 1

`stateful daily` 専用 design doc を固定する

### Step 2

`11_state_delta.json` の schema を実装する

### Step 3

current state を file-based で持つ reducer を作る

### Step 4

`daily` 生成時に `prior state + context bundle` を渡す

### Step 5

その後に product 側で reader UI を作る

### Step 6

最後に retrieval / Vertex を加える

---

## 次の作業でやるべきこと

優先順位順:

1. `11_state_delta.json` の具体 schema を固定する
2. file-based `active state snapshot` を更新する reducer script を作る
3. `generate_stateful_daily.py` を追加する
4. その後に output を見ながら task 圧縮と mutation 品質を調整する

今の判断としては、
**次の実装対象は weekly / monthly ではなく、state delta を吐ける daily 生成器**
である。
