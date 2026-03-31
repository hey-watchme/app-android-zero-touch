# ZeroTouch Context Enrichment Project

## 目的

この文書は、ZeroTouch に事前コンテクストを追加するための
初期設計を定義する。

ここでいうコンテクストとは、

- このアカウントが何者か
- この workspace がどんな現場か
- この device がどこに置かれ何を収録しているか
- この分析で何を知りたいか

を、会話ログとは別に保持する情報である。

主な狙いは次の 3 つ。

- stateful / stateless どちらの分析でも「何について話しているのか」を読みやすくする
- task / decision / knowledge 抽出時の前提不足を減らす
- 将来の業種別運用で、時間帯や環境文脈を分析軸として使えるようにする

関連文書:

- [conversation-pipeline-processes.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/conversation-pipeline-processes.md)
- [amical-stateful-daily-design.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/amical-stateful-daily-design.md)

---

## 結論

初期方針は次の通り。

1. 所有モデルは既存の `account -> workspace -> device` を維持する
2. 分析用コンテクストは、まず `workspace` 単位で集約して管理する
3. `account / workspace / device / environment / analysis goal` を
   1 つの論理スキーマとして定義する
4. Android 側では、初回オンボーディングと後編集可能なマイページで入力する
5. 分析時は `context bundle` と prompt に常時注入する

つまり、最初にやるべきことは
**複雑な推論機構ではなく、前提情報の正本を定義して入力可能にすること**
である。

### 実装状況

この文書を土台に、まず次の範囲を実装済み。

- `zerotouch_context_profiles` のスキーマ拡張 migration
- Android の初回オンボーディング画面
- Android の Context Settings 編集シート
- API クライアント / ViewModel の context profile 読み書き

バックエンドの endpoint 自体は既に存在しているため、
今回の変更で中心になったのはストレージの拡張と Android 側の入力導線である。

---

## なぜ今必要か

現状の artifact は会話そのものから task / knowledge を再構成するが、
次の弱点がある。

- 対象者が何をしている人かが分からない
- その場が自宅執務室なのか、店舗なのか、ホテルなのかが分からない
- Android 端末が何のために置かれているかが分からない
- この分析で何を得たいのかが分からない

その結果、

- task の優先度
- knowledge のカテゴリ
- 会話の意味づけ
- 時間帯の重要性

が文脈不足のまま判断される。

ZeroTouch の次段では、
会話ログそのものの精度改善だけでなく、
**ログ外にある前提情報を先に入れる**
ことが必要である。

---

## 設計原則

- 最初は最小限で始める。入力必須項目は少なくする
- 論理スキーマは先に広めに定義し、実装は段階導入する
- 業種依存の項目は固定カラムではなく自由記述や JSON で受ける
- まずは `workspace` 単位の分析精度向上を優先する
- 時間帯や曜日のような環境文脈は、手入力と自動導出を分けて扱う

---

## 論理スキーマ v1

`context profile` は、分析に渡す論理文脈として次の 5 レイヤーを持つ。

### 1. Account Context

その人が何者か、何を目指しているか。

最小項目:

- `display_name`
- `identity_summary`
  例: `企業家でエンジニア。音声AIを使ったデバイスを開発している`
- `primary_roles`
  例: `["founder", "engineer"]`
- `product_summary`
  例: `現場会話を task / knowledge に変える ambient device を開発中`
- `personal_goals`
  例: `["開発速度を上げる", "会話から業務状態を把握する"]`

### 2. Workspace Context

その現場がどこで、何のために使われているか。

最小項目:

- `workspace_name`
- `workspace_type`
  例: `home_office`, `restaurant`, `hotel`, `store`, `lab`, `other`
- `workspace_summary`
  例: `自宅の執務室で、主に開発作業と設計会話が行われる`
- `people_involved`
  例: `["本人"]`, `["スタッフ", "顧客"]`
- `workspace_goals`
  例: `["開発会話の可視化", "未完了 task の再発見"]`
- `operating_rules`
  例: `["平日日中が主稼働", "私的会話は分析対象外にしたい"]`

### 3. Device Context

その device がどこに置かれ、どう使われるか。

最小項目:

- `device_id`
- `device_name`
- `device_kind`
- `placement`
  例: `デスク右上`, `レジ横`, `厨房入口`
- `capture_purpose`
  例: `作業中の独話・打ち合わせの収集`
- `capture_mode`
  例: `ambient_continuous`, `manual`, `virtual_import`
- `device_notes`

### 4. Environment Context

時間・曜日・季節・現場運用の軸。

最小項目:

- `timezone`
- `active_days`
  例: `["mon", "tue", "wed", "thu", "fri"]`
- `active_hours`
  例: `{"start":"09:00","end":"20:00"}`
- `important_time_axes`
  例: `["weekday_vs_weekend", "morning_vs_night"]`
- `environment_summary`
  例: `平日日中は開発、夜は個人作業が多い`

補足:

- `曜日`
- `時刻`
- `月`
- `祝日`

は会話 timestamp から自動導出できるため、
環境文脈では「どういう軸が意味を持つか」を主に持つ。

### 5. Analysis Context

このアプリで何を知りたいか。

最小項目:

- `analysis_objective`
  例: `開発会話から task / decision / knowledge を抽出したい`
- `success_criteria`
  例: `その日やることが一目で分かる`, `重要 decision が翌日に残る`
- `focus_topics`
  例: `["Android開発", "音声AI", "パイプライン設計"]`
- `ignore_topics`
  例: `["雑談", "私的メモ"]`
- `preferred_outputs`
  例: `["task timeline", "knowledge base", "daily wrap-up"]`

---

## 物理保存方針 v1

初期実装では、新しい正規化テーブルを増やしすぎない。

### 既存テーブルの役割

- `zerotouch_accounts`
  基本的なアカウント識別と表示情報
- `zerotouch_workspaces`
  workspace の基本情報
- `zerotouch_devices`
  物理 / 仮想 device の登録情報
- `zerotouch_context_profiles`
  分析用コンテクストの正本

### 推奨方針

`zerotouch_context_profiles` を
`workspace` 単位の分析コンテクスト文書として強化する。

初期段階では、
`accounts / workspaces / devices` にカラムを大量追加するより、
`context_profiles` に JSONB を足して保持する方が速い。

推奨イメージ:

```json
{
  "schema_version": 1,
  "account_context": {},
  "workspace_context": {},
  "device_contexts": [],
  "environment_context": {},
  "analysis_context": {}
}
```

既存の次のカラムは、そのまま使う。

- `profile_name`
- `owner_name`
- `role_title`
- `environment`
- `usage_scenario`
- `goal`
- `reference_materials`
- `glossary`
- `prompt_preamble`

新規 migration では、必要に応じて次を追加する。

- `schema_version`
- `account_context JSONB`
- `workspace_context JSONB`
- `device_contexts JSONB`
- `environment_context JSONB`
- `analysis_context JSONB`
- `onboarding_completed_at`

---

## 入力導線

### 1. 初回オンボーディング

アプリ初回起動時に、任意入力のオンボーディングを出す。

方針:

- スキップ可能
- 入力すると分析精度が上がることを明示
- 1 画面 1 テーマで短く入力させる

推奨フロー:

1. welcome
2. あなたについて
3. この workspace について
4. この device について
5. 何を知りたいか
6. review / skip / save

### 2. マイページ / Context Settings

オンボーディング後も、いつでも編集可能にする。

画面セクション:

- Account
- Workspace
- Device
- Environment
- Analysis Goal
- Reference Materials / Glossary

### 3. 空欄時の扱い

空欄は許可する。

ただし viewer や prompt では、
空欄を無理に補完せず、`not provided` として扱う。

---

## 分析への使い方

### 1. Prompt 入力

task / knowledge 抽出や stateful daily 生成時に、
`context profile` を明示的に渡す。

特に効く箇所:

- task の意味づけ
- knowledge のカテゴリ化
- decision の背景解釈
- viewer 上の「この会話は何のためのものか」の説明

### 2. Artifact への埋め込み

次の artifact に context summary を含める。

- `09_context_bundle.json`
- `10_stateful_daily_rollup.json`
- `12_active_state_snapshot.json`

初期は full context ではなく、
分析に必要な圧縮要約だけを入れる。

### 3. Viewer 表示

Web viewer では次を上段固定表示の候補とする。

- 対象者の役割
- workspace の説明
- device の説明
- analysis objective
- 重要な環境軸

---

## 初期実装スコープ

### Phase 1

この文書の確定。

やること:

- logical schema を固定
- Android の入力導線を決める
- どの artifact に何を入れるかを決める

### Phase 2

Android に最小入力 UI を追加。

やること:

- 初回オンボーディング画面
- マイページ / context settings 画面
- API 経由の保存 / 再取得

### Phase 3

Backend / artifact に context 注入。

やること:

- prompt 入力に context summary を追加
- `09/10/12` artifact に context summary を追加

### Phase 4

Web viewer で context を見せる。

やること:

- 固定コンテクスト枠
- 表示 / 非表示切り替え
- context 付き / なしの比較

---

## 初期入力項目の推奨最小セット

オンボーディング v1 では、まず次だけでよい。

- あなたは何をしている人か
- この workspace はどんな場所か
- この device はどこに置かれているか
- このアプリで何を把握したいか

具体例:

- `企業家でエンジニア。音声AIデバイスを開発している`
- `自宅の執務室`
- `Xiaomi の Android タブレットをデスクに置いて ambient 録音している`
- `仕事中の会話から task / decision / knowledge を把握したい`

これだけでも、現在の分析より大きく改善する可能性が高い。

---

## 未解決事項

- account 単位と workspace 単位のコンテクストの境界をどこまで分けるか
- 複数 device がある workspace で device_context をどう見せるか
- private / sensitive な前提情報をどこまで保存するか
- environment context のうち手入力と自動導出をどこで分けるか
- context を変更した時に、過去 artifact を再生成するかどうか

---

## 次にやること

1. Android のオンボーディング画面の情報設計を作る
2. `zerotouch_context_profiles` の拡張 migration を定義する
3. Android API client と backend の request / response schema を広げる
4. Web viewer で context summary の固定枠を追加する
