# ZeroTouch Conversation Pipeline

## この文書の目的

この文書は、ZeroTouch における会話可視化パイプラインの現行方針を定義するためのものです。

ZeroTouch の最重要 UX は、アンビエント録音中の会話が細かい単位で次々と可視化されることです。
そのため、ここではまず `Card` を即時に生成し、その Card を受ける箱として `Topic` を扱います。

この段階の Topic は、意味理解の結果ではありません。
Topic は、連続した会話区間を受けるための機械的なコンテナです。

LLM は Topic を分割するためには使わず、Topic を確定するときにタイトル、要約、説明文を整えるために使います。

## コアモデル

### Card

Card は発言の最小単位です。

- 実体は `zerotouch_sessions` の 1 レコード
- 1 回の録音チャンク + ASR 結果が 1 Card
- `えーっと` のような短い発言でも Card
- Card は独立したオブジェクト
- Card は後から別 Topic に移動できる

### Topic

Topic は Card を受ける箱です。

- 実体は `zerotouch_conversation_topics` の 1 レコード
- ある連続した会話区間を表す
- Card が 1 件でも生成されたら、その Card が属する Topic も必ず存在する
- Topic 自体は先に意味を持たなくてよい
- 生成直後の Topic は暫定タイトル、暫定説明を持っていてよい
- Topic は device ごとに同時に 1 件だけ `active` なものを持つ

### LLM の役割

この段階の LLM は grouping には使いません。

- Topic の切れ目は機械ルールで決まる
- LLM は Topic 確定時に `title` / `summary` / `description` を整える
- LLM は後段の fact extraction のための素材を整形する

### 後段への受け渡し

このパイプラインで得られるものは、後段の素材です。

- Card 群
- Topic
- Topic のタイトル
- Topic の要約
- Topic の説明文

これらを次のプロセスで使い、タスク化、ナレッジ化、ファクト抽出へ進めます。

## 基本原則

- Card は常に即時に生成し、即時に表示する
- Topic も Card と同時に必ず存在する
- Topic の切り替えは LLM ではなく機械ルールで決める
- LLM は Topic 確定時の整形に限定する
- UI では Card を主役にし、Topic はその Card 群の枠として扱う
- Topic は後から Card を付け替えられる前提で設計する

## パイプライン全体像

1. アンビエント録音が開始される
2. 通常は無音 5 秒で 1 発言ぶんの録音チャンクが閉じる
3. 音声ファイルを S3 にアップロードする
4. ASR が文字起こしを行う
5. `zerotouch_sessions` に Card を生成する
6. その Card を受ける `active Topic` が存在しなければ、新しい Topic を生成する
7. Card を current Topic に紐付ける
8. UI に Card を即時表示する
9. 以降の Card も、Topic を切り替える条件が来るまでは同じ Topic に入る
10. `30秒無発言` または `Ambient Off` で Topic を閉じる
11. Topic を閉じるタイミングで LLM が走り、Topic のタイトル、要約、説明文を整える
12. Topic を `finalized` にする
13. 次の Card が来たら、新しい Topic を生成して次の会話区間を始める

## Topic の切り替えルール

Topic の境界は完全に機械的に決めます。

### 主ルール

- 最後の Card から `30秒間` 新しい発言がなければ Topic を閉じる
- アンビエントモードを `Off` にしたら、その時点で Topic を閉じる

### 補足

- `30秒無音` ではなく `30秒無発言`
- Topic は会話の意味ではなく、連続区間で切る
- Topic の切り替え判断に LLM を使わない

### 録音チャンクの現行ルール

- 通常は `5秒無音` でセッションを閉じる
- 連続録音が `2分` を超えたら `2.5秒無音` で閉じる
- `10分` に達したセッションは強制的に閉じて次のセッションへ移る
- この値は運用しながら継続的に調整する

## Topic のライフサイクル

### 1. Topic 生成

Card が生成された時点で、その Card が属する Topic が必ず生成されます。

- device に `active` Topic がなければ新規作成
- `active` Topic があれば、その Topic に Card を入れる

この時点では Topic は暫定状態です。

- タイトルは仮でよい
- 要約や説明文も仮でよい
- ただし Topic レコード自体は本物として作る

### 2. Topic 進行中

Topic が `active` の間は、同じ Topic に Card が追加され続けます。

- 新しい Card が来るたびに `last_card_at` を更新する
- `card_count` を増やす
- UI には新しい Card が次々見える
- Topic は「今の会話の箱」として存在し続ける

### 3. Topic 確定

境界条件に達したら、その Topic を閉じます。

- status を `finalizing` にしてもよい
- LLM を呼び、Topic 全体のタイトル、要約、説明文を整える
- 最終値を保存して `finalized` にする

## UI 要件

### Card 表示

- Home では新しい Card が上から順に追加される
- Card は Topic の有無に関係なく常に表示される
- Topic 未確定でも Card は表示される
- 再文字起こしや削除などの操作対象は Card

### Topic 表示

- Topic は Card 群をまとめる見出しや枠として見せる
- `active` Topic は live 状態で見えていてよい
- `finalized` Topic はタイトル、要約、説明文つきで見える
- Topic の存在を理由に Card を隠さない

### 編集可能性

将来的には、Card を別 Topic へ移動できるようにする前提で設計します。

- Topic は分類先の箱
- Card は移動可能なオブジェクト
- この時点では UI 実装がなくても、データモデルはそれを妨げないようにする

## データモデル方針

### `zerotouch_sessions`

Card テーブルとして扱います。

最低限必要になる考え方:

- 1 session = 1 Card
- ASR 完了後に `topic_id` が必ず入る
- `topic_id` は後から付け替え可能
- Topic 表示や後段処理のため、`recorded_at` と `created_at` は保持する

### `zerotouch_conversation_topics`

Topic テーブルとして扱います。

最低限必要になる考え方:

- `device_id`
- `status` (`active`, `finalized`)
- `started_at`
- `last_card_at`
- `finalized_at`
- `card_count`
- 暫定タイトル
- 暫定説明
- 最終タイトル
- 最終要約
- 最終説明文
- 境界理由 (`idle_timeout`, `ambient_stopped`)

現行コードの物理カラム名との対応:

- `status` -> `topic_status`
- `started_at` -> `start_at`
- `last_card_at` -> `last_utterance_at`
- `card_count` -> `utterance_count`
- 暫定タイトル -> `live_title`
- 暫定説明 -> `live_description`（未移行環境では `live_summary` を暫定利用）
- 最終タイトル -> `final_title`
- 最終要約 -> `final_summary`
- 最終説明文 -> `final_description`
- 境界理由 -> `boundary_reason`

### device ごとの active Topic

1 device につき同時に 1 Topic だけ `active` とします。

- 新しい Card は current active Topic に入る
- active Topic がない場合だけ新規作成する

## LLM の入るタイミング

LLM は Topic 確定時だけ使います。

### 入力

- その Topic に属する Card 群
- Card の時系列
- 必要なら recorded_at などのメタデータ

### 出力

- Topic の最終タイトル
- Topic の最終要約
- Topic の説明文

### 非目標

この段階では以下をやりません。

- Card 群を複数 Topic に再分割する
- 既存 Topic へ join するか new にするかを LLM で判定する
- Topic の merge / reopen を賢くやる

## 期待する UX

1. 録音中に Card が細かい粒度でどんどん増える
2. Topic は同時に生成されており、その Card 群の箱として存在する
3. しばらく発言が止まると、その Topic が確定する
4. Topic 確定後に、タイトル、要約、説明文が自然な表現に更新される
5. 確定した Topic と Card 群が次プロセスの素材になる

## 例

### 例 1: 単発発言

- Card 1: 「疲れた」
- Card 1 と同時に Topic A を作成
- 30 秒間次の発言なし
- Topic A を finalize
- LLM がタイトルと要約を整える

### 例 2: 連続会話

- Card 1: 予約の相談
- Topic A を作成
- Card 2: 日程の確認
- Card 3: 金額の確認
- 3 枚とも Topic A に入る
- 30 秒間次の発言なし
- Topic A を finalize

### 例 3: アンビエント停止

- Card 1, Card 2 が Topic A に入っている
- ユーザーが Ambient Off
- その時点で Topic A を finalize
- 次に Ambient を再開して Card 3 が来たら Topic B を新規作成

## 現在の中断ポイント

現在のコードベースには、途中まで別モデルの Process 2 実装が入っています。

そのモデルは以下です。

- Card を一旦 `pending` に置く
- 30秒無発言の後に未評価 Card 群をまとめて Topic 化する
- LLM が grouping に関わる

これは本ドキュメントのモデルとは異なります。

今後は以下へ作り替えます。

- Card 生成時に Topic も必ず存在する
- Topic は live container として先に作る
- LLM は finalize 時の整形だけを担当する

## 実装の大きな方向性

1. `active Topic` を中心にバックエンドを組み替える
2. Card は ASR 完了時に即 Topic へ紐付ける
3. Topic finalize の責務を明確にする
4. UI を card-first に戻し、Topic をその箱として見せる
5. 旧 pending-grouping モデルを段階的に撤去する

## この段階での完成条件

- 新しい Card が必ず即時に見える
- Card には必ず Topic がある
- Topic の境界は `30秒無発言` または `Ambient Off` で決まる
- Topic 確定時に LLM がタイトル、要約、説明文を整える
- Topic と Card 群が後段の fact extraction の素材として保存される
