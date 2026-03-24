# ZeroTouch Conversation Pipeline Processes

## この文書の目的

この文書は、ZeroTouch の会話処理パイプラインを試行錯誤するための設計メモです。

- `Process 1`: 現在の実装方式
- `Process 2`: 次に試す方式

ここでは「どのタイミングで何を画面に出すか」「どの条件で LLM を走らせるか」を明確にし、実装変更時の判断軸を残します。

## Process 1

### 位置づけ

現在の実装方式です。発言単位のカード化と、並行して行うトピック紐付けを試した最初のプロセスとして固定します。

### 流れ

1. アンビエント録音が開始される
2. 無音 5 秒で 1 発言ぶんの録音が終了する
3. 録音ファイルをアップロードする
4. ASR が文字起こしを行う
5. `zerotouch_sessions` に 1 発言ぶんのカードとして表示する
6. 文字起こし完了直後に、その発言を既存トピックへ join するか、新規 topic にするかを評価する
7. 必要に応じて `conversation_topics` を更新する

### 特徴

- 発言ごとの反応が速い
- UI 上はカードが先に見える
- トピック評価が発言のたびに走る
- トピックの join / new 判定に LLM を使う
- 発言が流れている最中でもトピックが更新される

### 良い点

- 「話したらすぐ出る」という感覚が強い
- POC として見栄えが良い
- 1 発言ごとの処理単位が明快

### 問題意識

- 発言の途中段階でトピック判断が走るため、文脈が固まる前に評価してしまう
- 会話全体が終わる前にトピックが細かく分裂しやすい
- 途中 join 判定に失敗すると、トピック側の体験が不安定になりやすい

## Process 2

### 位置づけ

次に試す新しい方式です。カードの即時性は維持しつつ、トピック化は会話がいったん止まった後にまとめて行います。

### 採用方針

Process 2 は、以下の前提で進めます。

- トリガーは `60秒無発言`（**無音ではなく無発言**）
- 対象範囲は `device_id` ごと
- 評価対象は `最後の確定 topic 以降にある未評価カードを全部`
- topic は毎回新規作成し、既存 topic への merge / reopen はやらない
- 評価失敗時は、その時点で切り出したまとまりを保ったまま再試行できるようにする

### 命名規約

WatchMe 共通 DB を使うため、ZeroTouch 専用テーブルはすべて `zerotouch_` 接頭辞を付けます。

- 既存: `zerotouch_sessions`
- 現行 topic テーブル: `conversation_topics`
- Process 2 で揃えるべき topic テーブル名: `zerotouch_conversation_topics`
- Process 2 で追加する評価 run テーブル名: `zerotouch_topic_evaluation_runs`

つまり Process 2 に入る前に、`conversation_topics` は命名規約上 `zerotouch_conversation_topics` へ寄せる前提で考えます。

### 基本方針

- 発言はこれまで通り即座にカード化する
- **カードは常に表示する**（トピック表示モードでもカードは隠さない）
- **カードとトピックは独立した別の箱**として扱う
- **仮トピック（プレースホルダ）は使わない**
- トピック化はカード生成と切り離す
- 最後の発言から 1 分間、新しい発言が来なかったら LLM 評価を走らせる
- その 1 分ぶんまでに溜まった未評価カードを、1 つ以上の topic にまとめる
- どんな短い発言でも、最終的には必ず何らかの topic に属させる
- 作成された topic は、後段のナレッジ化・タスク化の素材として使う

**理由（UX）**

- まずカードが即時に見えることが最重要。フィードバックが遅いと体験が崩れる。
- トピックは後から意味付けするための整理レイヤー。カードの即時性を犠牲にしない。
- カードは最小単位の発言オブジェクトであり、トピックとは独立して存在できる。

### 想定 UX

1. 会話中:
   カードが 5 秒, 10 秒単位でどんどん増える（トピック表示モードでも見える）
2. 会話終了直後:
   しばらくはカードだけが積まれる
3. 最後の発言から 1 分経過:
   LLM が直近カード群を読み、topic を生成する
4. トピック確定後:
   トピックにタイトル、要約、カード群が付与される
   カードは引き続き見える（即時フィードバックのため）

### トリガー

- 主トリガー:
  最後の発言から 60 秒間、新しい発言がない
- 補助トリガー:
  明示的な再評価 API、または定期バッチ
- 追加トリガー:
  **アンビエントモードを解除したタイミング**で、未評価カードがあれば即時評価

### Process 2 の処理順

1. 録音終了
2. ASR 完了
3. `zerotouch_sessions` にカード追加
4. そのカードを `topic_pending` のような未評価状態に置く
5. 直近 60 秒以内に新しい発言があれば、topic 評価を延期する
6. 60 秒経っても新規発言がなければ、未評価カード群をまとめて LLM に渡す
7. **アンビエントモード解除時**にも、未評価カード群をまとめて LLM に渡す
8. LLM が topic 分割数、タイトル、要約、必要なら種別を返す
9. `zerotouch_conversation_topics` を生成し、対象カードを必ずどこかの topic に紐付ける

### 期待する挙動

- 「疲れた」だけの単発発言でも、1 分後に単独 topic を 1 件作る
- 数件のカードが同じ話題なら、1 topic にまとまる
- 会話の意味が分かれていれば、2 つ以上の topic に分割してよい
- 判断が難しければ、保守的に新規 topic を作ってよい
- トピック表示モードでもカードは表示される（即時フィードバック優先）

### Merge の考え方

Process 2 では、まず topic の乱立を許容します。

- 初期方針:
  1 分区切りで作った topic をそのまま確定してよい
- 将来拡張:
  新しい topic が直前 topic と同じ意味だと判断できる場合のみ merge を検討する

この merge は別フェーズに切り出すのが安全です。最初から join / reopen を賢くやろうとすると、パイプライン全体が不安定になります。

### Process 2 で必要になる状態

#### Session 側

- `ungrouped`
- `pending`
- `processing`
- `grouped`
- `needs_retry`

#### Topic 側

- `generated`
- `finalized`
- 将来的に `merged`

#### Evaluation Run 側

- `processing`
- `completed`
- `needs_retry`

### Process 2 で追加するテーブル

#### `zerotouch_topic_evaluation_runs`

このテーブルは、「どの未評価カード群を、どの 1 回の評価バッチとして扱ったか」を保存するためのものです。

想定カラム:

- `id`
- `device_id`
- `status`
- `triggered_at`
- `locked_at`
- `completed_at`
- `retry_count`
- `session_count`
- `first_session_id`
- `last_session_id`
- `error_code`
- `error_message`
- `llm_provider`
- `llm_model`
- `llm_response_json`

#### `zerotouch_sessions` に追加する想定カラム

- `topic_eval_status`
- `topic_eval_run_id`
- `topic_eval_marked_at`

#### `zerotouch_conversation_topics`

Process 2 では、topic はこのテーブルに新規作成のみ行います。直前 topic との結合は初回実装では扱いません。

### LLM に期待する役割

- 直近カード群を意味単位でまとめる
- 各 topic のタイトルをつける
- 各 topic の短い要約をつける
- 必要なら `topic_type` を返す

この段階では、タスク抽出は必須ではありません。まずは会話を topic として自然に見せることを優先します。

## Process 1 と Process 2 の違い

| 項目 | Process 1 | Process 2 |
|---|---|---|
| カード表示 | 即時 | 即時（トピック表示モードでも維持） |
| トピック評価タイミング | 発言ごと | 最後の発言から 1 分後 |
| join/new 判定 | 発言単位 | カード群単位 |
| UX の主役 | カードとトピックが並行成長 | まずカード、その後トピック |
| 分裂リスク | 高め | 下げやすい |
| 実装難易度 | 中 | 中だが整理しやすい |

## Process 2 の設計原則

- 即時性はカードで担保する
- 意味解釈は会話停止後にまとめて行う
- どのカードも最終的に必ず topic に所属させる
- merge は最初から賢くやらず、後段の改善テーマにする
- トピック表示モードでもカードの即時性は崩さない

## 次に詰めるべき項目

1. `conversation_topics` から `zerotouch_conversation_topics` への移行方針
2. `zerotouch_topic_evaluation_runs` の DDL
3. 60 秒無発言を検知する Background Job のロジック
4. LLM への入力件数上限と chunking 方針
5. topic 生成レスポンスの JSON schema
