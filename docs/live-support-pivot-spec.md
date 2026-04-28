---
作成日: 2026-04-28
更新: 2026-04-28（v3: Home=MeMo / Dashboard=ZeroTouch の情報設計を反映）
ステータス: ドラフト（要レビュー）
位置づけ: プロダクトピボット仕様書。既存の `conversation-action-platform.md` を補完し、当面のフォーカスを定義する
---

# Live Support ピボット仕様書

## 0. このドキュメントの目的

ZeroTouch のプロダクトコンセプトを **「会話の事後変換」から「会話中のライブサポート」に当面のフォーカスを移す** ためのピボット仕様。

最終的なあるべき姿（会話 → 業務アクション / Draft / 長期記憶）は変えない。ただし、ユーザー価値を最も鋭く感じてもらえる初期ウェッジを「会話の最中に役立つ」体験へ寄せる。

既存のホーム画面・パイプラインは **`ZeroTouch` モード** として残し、新たに **`Live` モード** のメインページを追加する。両者は同じバックエンド・DB・録音インフラを共有する。

---

## 1. コンセプト

### 1.1 ビジョン（最終形態）

会話という一見不安定ですぐ消えてしまうものを、

- その場に置いてあるデバイスが拾い
- リアルタイムに論点・補足・翻訳・ラップアップを生成し
- タブレット画面に表示される
- 気になる人は **スマホで撮影する** か **QR コードで該当ページにアクセスする** だけで持ち帰れる

という体験を、薬局・クリニック・不動産・教育・福祉・カフェ・居酒屋など、難しい / 価値ある会話が起きるすべての場所に提供する。

ログインもアカウントも要らない。物理的にその場にいた人だけが恩恵を受け、要らなければ放置すれば消えていく。

### 1.2 プロダクト名・モード名

- 既存（事後変換 / Action Platform）: **ZeroTouch モード**（社内向け / バックオフィス / 永続的な業務統合）
- 新規（ライブサポート）: **MeMo Live モード**（現場面 / その場サポート / 軽量シェア）

リポジトリ名やドメインは現行のまま（`android-zero-touch`, `app-web-zero-touch.vercel.app`）。UIラベルでモードを使い分ける。

### 1.3 情報設計（IA）: Home と Dashboard

ユーザー向けの画面名称は次で固定する。

- **Home**: `MeMo Live`（アンビエントメモ / Live Support のメイン体験）
- **Dashboard**: 既存 ZeroTouch（Topic / Fact / Wiki / Query / Action Candidate の後段活用）

補足:
- 既存の「O / Q ホーム相当」の画面は削除しない。役割を **Dashboard** に寄せる
- Live で取得したデータは、後段で Dashboard 側に流して活用する

### 1.4 戦略上の優先順位

1. **Live モードが最初**。これが「使われる体験」を作る入口。
2. ZeroTouch モードはそのまま残し、Live で蓄積された会話資産が後段で Action Candidate に流れる構造を保つ。
3. Live で価値を感じたユーザーが、徐々に ZeroTouch モード（業務統合）にも興味を持つ動線にする。

---

## 2. 利用シーン

### 2.1 シーン A: 薬剤師の服薬説明（B2B 初期ウェッジ）

| 役者 | 行動 |
|------|------|
| 薬剤師 | カウンターに置かれたタブレットの前で薬の説明をする |
| 患者 | 内容が複雑であまり理解できない。本当は持ち帰って読み返したい |
| タブレット | 会話を拾い、要点・薬剤名・注意事項・QA をリアルタイムに大きく表示 |
| 患者の選択肢 | (a) 画面を **スマホで撮影** する、(b) 端の **QR コード** をスキャンしてページを開く |
| 患者のスマホ | QR からアクセスしたページに、会話のサマリ・服薬要点・タイムスタンプが残る |

価値: 「書いてあるからいいか」で済まされていた重要情報が、患者の手元に確実に渡る。

### 2.2 シーン B: 不動産・契約説明 / 学校面談 / 行政窓口（B2B 同型展開）

同じ構造。「説明者 + 受け手」型の会話。説明者は普段通り喋り、受け手はその場で聞きながらタブレットを横目で見て、最後に撮影 or QR で持ち帰る。

### 2.3 シーン C: カフェ・居酒屋・リビング（B2C 普及形態）

| 役者 | 行動 |
|------|------|
| 友人グループ | テーブル上のデバイスをオンにして雑談 |
| デバイス | 会話を拾い、論点補完・翻訳・要約をライブ表示 |
| 参加者 | 気になる話題があれば撮影 or QR で持ち帰る。要らなければ無視 |

価値: 「あれ何だっけ」「あの店どこだっけ」「結局何決めたっけ」が後から消えない。

### 2.4 すべてのシーンに共通する設計原則

- **ログイン不要**。その場にいた人 = 受益者
- **能動的アクセスのみ**。撮影 / QR スキャンを「自分でやる」ことで取得（プライバシーとコントロールが両立）
- **削除権限は気軽**。誰でも消せる、あるいは元から保存されない設定も選べる
- **デバイス側に履歴は溜まる**が、見える先・拡散先はその場の人間だけ

---

## 3. UX 要件

### 3.1 新メイン画面: `Live Display`（タブレット側）

タブレットを **据え置きで横向き表示** することを前提にした、「離れた席からも読める」レイアウト。

> 2026-04-28: ユーザーから UI 参照画像が共有された（`/Users/kaya.matsumoto/Desktop/ChatGPT Image 2026年4月28日 14_44_09.png`）。以下は **画像から読み取れる構成**。画像はあくまで参考で、必ずしも忠実な再現を求められているわけではない。

#### 3.1.1 レイアウト構成（画像ベース）

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [📝 MeMo Live ● Live]   [EN][日本語✓][Español]    [Share][Add Note][×]    │ ← TopBar
├──────────────────────────────────────────────┬─────────────────────────────┤
│                                              │ Conversation                │
│  ┃ 患者さんは昨日から                        │ ┌─────────────────────────┐ │
│  ┃ ★微熱と咳が続いていて、★                  │ │ 4 min ago               │ │
│  ┃ 胸部の圧痛があります。                    │ │ （過去の発話/要約）      │ │
│                                              │ └─────────────────────────┘ │
│  The patient has had a slight fever and      │ ┌─────────────────────────┐ │
│  cough since yesterday, and has              │ │ 3 min ago               │ │
│  tenderness in the chest.                    │ │ ...                     │ │
│                                              │ └─────────────────────────┘ │
│  ┌─────── 用語注釈カード（浮きカード）────┐  │                             │
│  │ Live grade fever                        │  │ ┌─────────────────────────┐ │
│  │ Slightly elevated body temperature      │  │ │ 2 min ago               │ │
│  │ (37.0–37.5℃) but...                     │  │ │ ...                     │ │
│  └─────────────────────────────────────────┘  │ └─────────────────────────┘ │
│                                              │                             │
├──────────────────────────────────────────────┤ ┌─────────────────────────┐ │
│ [● Listening...] 00:24  ▮▮▮▯▯  [Wrap Up & Save] │ │ 1 min ago               │ │
└──────────────────────────────────────────────┴─────────────────────────────┘
```

#### 3.1.2 主要コンポーネント

| 領域 | 内容 | 状態 |
|------|------|------|
| TopBar 左 | プロダクト名 + Live インジケータ（赤ドット） | 録音中は常時パルス |
| TopBar 中央 | 言語トグル `EN / 日本語 / Español`（Phase 1 は日本語のみアクティブ、要決定） | §11.2 オープンクエスチョン |
| TopBar 右 | `Share` / `Add Note` / 閉じる（×） | Share = QR モーダル想定（要決定） |
| メイン左 | 大きな現在発言（原文 + 翻訳）、キーワードハイライト | 画像では「微熱と咳」をハイライト |
| 用語注釈カード | LLM が専門用語に補足を出す浮きカード | Phase 1 採用可否は要決定 |
| メイン右（Conversation） | 過去発話の時系列ログ、`N min ago` タイムスタンプ付きカード | 表示粒度（生発話 / Card / 要約）は要決定 |
| フッター左 | `● Listening... 00:24` + 音声レベルバー | プライバシーシグナルとして必須。常時録音なのでタイマーは「現在セッション開始からの経過」 |
| フッター右 | （`Wrap Up & Save` は廃止） | 常時録音前提のため明示的な終了アクションは不要。共有は右上の Share ボタンから |

#### 3.1.3 既存仕様から維持する要素

- **撮影前提デザイン**: 文字は遠目から読めるサイズ（最低 24px、見出しは 48〜72px）
- **Listening インジケータ**: 録音中であることを明示（画像でも左下にあり）
- **QR コード**: 画像では明示的に出ていない。「Share」ボタン経由のモーダル表示が有力（§11.2 オープンクエスチョン）

#### 3.1.4 既存仕様から落とす / 変更する要素

- 前回案では QR を画面常時固定としたが、画像では「Share」ボタン起動の想定。トレードオフ:
  - 常時表示: 受け手が能動的に気づきやすい
  - Share ボタン: 画面がスッキリし、本来の説明体験を阻害しない
  - **推奨**: 小さなQR を画面端に常時 + 「Share」ボタンで拡大モーダル のハイブリッド
- 前回案の「キーポイント箇条書きパネル」は画像にはない。代わりに **メイン領域に大きく現在発言を出す** + **注釈カード** という構成。Keypoint パネルは右の Conversation 列に統合するか別途検討

### 3.2 公開シェアページ: `Take-Home Page`

QR コードのリンク先。**完全に認証なしで誰でも見られる**。

#### 3.2.1 URL 設計

- `https://app-web-zero-touch.vercel.app/s/{share_token}`
- `share_token` は推測不可な短いランダム文字列（例: 8-12文字 base62）
- セッション単位で発行（= Topic 単位 or Live セッション単位）

#### 3.2.2 ページ内容

- セッションのタイトル・日時
- 確定したキーポイント・要約（finalize 後の `final_summary` ベース）
- 全文転写（折りたたみ）
- 関連ドキュメント / SOP リンク（あれば）
- 「このページを削除する」ボタン（その場にいた誰でも押せる、ただし誤操作対策で確認モーダル）
- セッションの保存期限（例: 30日 or 7日。設定ベース）

#### 3.2.3 設計原則

- **読み取り専用 / 編集不可**
- **モバイルファースト**（スマホで開かれる前提）
- **PDF / 画像エクスポートできる**（後段の業務統合のため）

### 3.3 既存 `ZeroTouch` モードのページ

そのまま残す。ナビゲーションでモード切替できるようにする：

- Android: BottomNav の第一導線を `Home` / `Dashboard` にする
  - `Home` = `MeMo Live`
  - `Dashboard` = 既存 ZeroTouch コンソール
- Web: ヘッダーに `Home (Live)` / `Dashboard (ZeroTouch)` のリンク

---

## 4. 既存インフラ資産の棚卸し

調査済み（2026-04-28、Explore subagent x 2 による）。

### 4.1 そのまま使える ✅

| 資産 | 場所 | 利用方針 |
|------|------|---------|
| QR コード生成API | `api/qr-code-generator` (`https://api.hey-watch.me/qrcode/`) | Live セッション開始時に share_token を渡してQR生成 |
| 公開S3バケット | `watchme-qrcodes` (Block Public Access 無効) | QR画像配信に使う |
| Supabase RLS public-read | `zerotouch_wiki_pages` / `zerotouch_facts` | Take-Home ページから直接 read できる |
| Topic / Fact 抽出パイプライン | `backend/services/topic_*` `wiki_ingestor` | Live でも同じ抽出ロジックを再利用、ただし呼び出し頻度を変える |
| LLM プロバイダー抽象化 | `backend/services/llm_providers.py` | 翻訳タスクなど追加用途に流用 |
| アンビエント録音 / VAD | `AmbientRecordingService.kt` | そのまま流用、ただしセッション分割ルールは Live 用に再調整（§6 参照） |
| Web (Next.js 16) 動的ルート | `app/web-zero-touch` | `/s/[token]` ルート追加で Take-Home ページ実装可 |

### 4.2 拡張すれば使える 🟡

| 資産 | 現状 | 必要な拡張 |
|------|------|-----------|
| 分析用 ASR | Speechmatics / Deepgram / Azure すべて batch 呼び出し | **そのまま継続**（Live モードの裏側で従来通り走らせ、Fact / Wiki / Action Candidate の精度を維持） |
| Topic finalize | 30秒無発言 → finalize | live_summary を 5〜15秒間隔で更新、finalize は別軸で |
| `live_title` / `live_summary` カラム | 既に存在するが「Topic finalize 直前まで空」 | **生成頻度を上げる**（5秒 〜 15秒間隔） |
| Web ダッシュボード polling | SWR refreshInterval=2500ms | Phase 1 はそのまま polling、Phase 2 で Supabase Realtime に切替 |

### 4.3 ゼロから作る ❌（ただし business プロジェクトから流用できるものが多い）

| 必要なもの | 概要 | business 流用 |
|-----------|------|--------------|
| プレゼン用リアルタイム ASR | 2.5秒チャンクで OpenAI `gpt-4o-mini-transcribe` を呼び、即座に表示 | ✅ `business/backend/app.py:407-484` の `/api/transcribe/realtime` をほぼコピーで使える |
| Live セッションテーブル | `zerotouch_live_sessions`（device, start_at, end_at, share_token, expires_at, deleted_at） | — |
| Take-Home ページ | `/s/[token]` の公開ページ（read-only） | — |
| 共有トークン発行 / 失効 | API + Supabase での管理 | — |
| Live キーポイント生成器 | 5〜15秒ごとに最近の発言から要点を抽出する LLM 呼び出し | 🟡 既存 `topic_annotator.py` を縮小版で流用 |
| 翻訳ストリーム（Phase 3） | live_summary の翻訳を別 LLM で生成 | — |

### 4.4 business プロジェクトとの ASR 共有方針

`/Users/kaya.matsumoto/projects/watchme/business` で運用中の **保護者ヒアリング画面** と同じリアルタイム ASR を流用する。これは経営判断であり、技術的整理は次のとおり：

- business ではすでに `POST /api/transcribe/realtime` が **本番運用されており**、OpenAI `gpt-4o-mini-transcribe` を **2.5秒チャンク** で呼ぶ HTTP ベースの実装（WebSocket 不要）
- 体感遅延 1〜3秒で、UI 表示用には十分実用的
- 実装ファイル: `business/backend/app.py:407-484`（API）、`business/frontend/src/components/RecordingSession.tsx`（クライアント側のチャンク化ロジック）
- 共通環境変数で運用可能: `OPENAI_API_KEY`, `REALTIME_TRANSCRIBE_MODEL`（default `gpt-4o-mini-transcribe`）

**共有形態**: シンボリックリンクや monorepo パッケージ化は今フェーズでは見送る（過剰な抽象化）。**android-zero-touch backend に business のコードを参照しつつコピーで持ち込む**。同じ環境変数を共有 GitHub Secrets で管理し、機能不一致が出てきた段階でリファクタを検討する。

---

## 5. データモデル拡張

### 5.1 新規テーブル

```sql
-- Live セッション。1回の「タブレットを置いて会話した時間」に相当
-- セッション境界は **時間ベース**（無発言の長さ・話者変化で区切る）。常時録音前提のためユーザー操作の Live ON/OFF はない
CREATE TABLE zerotouch_live_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id UUID NOT NULL,
  workspace_id UUID,
  conversation_topic_id UUID REFERENCES zerotouch_conversation_topics(id),  -- 分析用パイプラインの Topic と紐付く（事後）
  share_token TEXT NOT NULL UNIQUE,             -- 公開ページ用、推測不可
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ended_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,                       -- NULL = 期限なし、Phase 1 はデフォルト 30日（後述）
  qr_code_url TEXT,                             -- QR画像のS3 URL
  language_primary TEXT DEFAULT 'ja',
  visibility TEXT NOT NULL DEFAULT 'public',    -- 'public' | 'private' | 'deleted'
  deleted_at TIMESTAMPTZ,
  deleted_reason TEXT,                          -- 'manual' | 'expired'
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_zerotouch_live_sessions_share_token ON zerotouch_live_sessions(share_token);
CREATE INDEX idx_zerotouch_live_sessions_device ON zerotouch_live_sessions(device_id, started_at DESC);

-- プレゼン用ASRの転写ストリーム（流れる発話単位）
-- 既存の zerotouch_sessions（分析用 Card）とは独立した、Live 表示専用テーブル
CREATE TABLE zerotouch_live_transcripts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_session_id UUID NOT NULL REFERENCES zerotouch_live_sessions(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL,                 -- 何番目のチャンクか
  text TEXT NOT NULL,
  speaker_label TEXT,                           -- Phase 3 で diarization
  generated_at TIMESTAMPTZ DEFAULT NOW(),
  asr_provider TEXT DEFAULT 'openai_realtime'   -- 監査用
);

CREATE INDEX idx_live_transcripts_session ON zerotouch_live_transcripts(live_session_id, chunk_index);

-- ライブキーポイント（LLM が抽出する「今出ている要点」）
CREATE TABLE zerotouch_live_keypoints (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_session_id UUID NOT NULL REFERENCES zerotouch_live_sessions(id) ON DELETE CASCADE,
  body TEXT NOT NULL,
  category TEXT,                                -- 'instruction' | 'warning' | 'qa' | 'todo' 等
  importance SMALLINT DEFAULT 1,                -- 1=normal, 2=important, 3=critical
  source_transcript_ids UUID[],                 -- 由来となった zerotouch_live_transcripts.id
  generated_at TIMESTAMPTZ DEFAULT NOW(),
  superseded_by UUID REFERENCES zerotouch_live_keypoints(id)
);

CREATE INDEX idx_keypoints_live_session ON zerotouch_live_keypoints(live_session_id, generated_at DESC);
```

設計意図:
- `zerotouch_live_transcripts` は **プレゼン用ASRの結果のみ** を保持する。これにより、既存の分析用パイプライン（`zerotouch_sessions` / `zerotouch_conversation_topics`）に一切手を入れずに済む
- セッション終了後、分析用 ASR の結果が同じ Live セッションに後追いで紐づく（`conversation_topic_id`）。Take-Home ページは「初期表示=ライブ転写」「数分後=分析用の精度高めサマリ」へとアップグレードされる

### 5.2 既存テーブルへの非破壊的追加

```sql
-- 既存 conversation_topics に live mode 用のフラグ
ALTER TABLE zerotouch_conversation_topics
  ADD COLUMN IF NOT EXISTS live_session_id UUID REFERENCES zerotouch_live_sessions(id),
  ADD COLUMN IF NOT EXISTS live_keypoints_updated_at TIMESTAMPTZ;
```

### 5.3 RLS ポリシー方針

- `zerotouch_live_sessions`: `share_token` をクエリ条件に含む場合のみ public read 可（または Service Role経由でのみ取得）
- `zerotouch_live_keypoints`: 親 live_session が public なら read 可

実装の都合上、Take-Home ページは Service Role（サーバーサイド）で fetch する現行方針（`app/web-zero-touch/src/lib/supabase-server.ts`）を踏襲する。

---

## 6. パイプライン再設計

### 6.1 旧パイプライン（ZeroTouch モード、現状）

```
録音セッション終了
  → S3 アップロード
  → ASR (batch)                  [3〜10秒遅延]
  → Card 生成
  → Topic active → cooling → finalized  [30秒アイドル]
  → Fact 抽出
  → Wiki ingest
  → Action Candidate 生成 (手動)
```

### 6.2 新パイプライン（Live モード / デュアル ASR 構成）

🔴 **重要な設計判断**: ASR を **2系統並走** させる。

- **プレゼン用 ASR**（速度重視 / 精度は犠牲にしてOK）: タブレット表示と Take-Home ページの「リアルタイム転写」専用
- **分析用 ASR**（精度重視 / 遅延OK）: 既存パイプライン（Speechmatics / Deepgram / Azure）。Topic / Fact / Wiki / Action Candidate の精度を維持

これは「リアルタイム ASR は所詮プレゼン用で、業務分析にはそこまで実用的ではない」という前提に基づく。同じ音声を二度処理することにはなるが、それぞれ目的が違う。

```
タブレット「Listening」開始
  ↓
Android が live_session を作成 + share_token + QR 取得
  ↓
オンデバイス VAD で発話開始を検知
  ↓
┌──────────────────────────────────────┬─────────────────────────────────────┐
│ プレゼン用 ASR ストリーム            │ 分析用 ASR ストリーム               │
│ （リアルタイム / OpenAI gpt-4o-mini）│ （既存パイプライン / バッチ）       │
├──────────────────────────────────────┼─────────────────────────────────────┤
│ 2.5秒チャンクで HTTP POST            │ 通常のセッション境界で S3 アップ    │
│ → /api/transcribe/realtime           │ → /api/transcribe/{id} (既存)       │
│ → 1〜3秒で転写返却                   │ → Speechmatics/Deepgram/Azure       │
│ → live_keypoints テーブルに書き込み  │ → zerotouch_sessions に書き込み     │
│ → タブレット & Take-Home に即時反映  │ → Topic / Fact / Wiki に流れる      │
│                                      │ → Action Candidate 生成へ           │
└──────────────────────────────────────┴─────────────────────────────────────┘
                       ↓ どちらも同じ live_session_id に紐付く

[タブレット OFF] または [長時間アイドル] で Live セッション終了
  ↓
分析用パイプライン完了後、Take-Home ページの「精度高めの最終サマリ」が裏で更新される
（リアルタイム表示は粗くてもOK、永続版は精度が上がる）
```

### 6.3 ASR の選定（決定済み）

| 用途 | プロバイダー | 採用理由 |
|------|------------|---------|
| **プレゼン用（リアルタイム）** | OpenAI `gpt-4o-mini-transcribe` | business プロジェクトで稼働実績あり、HTTP チャンクで 1〜3 秒遅延、WebSocket 不要 |
| **分析用（既存）** | Speechmatics / Deepgram / Azure | 既存運用継続。Topic/Fact 抽出の精度を維持 |

**業務上の利点**:

- WebSocket / SSE インフラを新規導入しなくて済む（HTTP POST チャンクで足りる）
- business プロジェクトと OpenAI API キー・モデル設定を共有でき、運用が一本化できる
- 分析用パイプラインは一切触らないので、既存 ZeroTouch モードへの副作用ゼロ

### 6.4 遅延予算

| 段階 | 目標遅延 | 根拠 |
|------|---------|------|
| 発話 → タブレット転写表示 | 1〜3 秒 | business 既存実装の実績値（2.5秒チャンク + OpenAI 応答） |
| 発話 → Take-Home ページ反映 | 1〜5 秒 | Phase 1 は polling、Phase 2 で Realtime に短縮 |
| 発話 → Keypoint 更新 | 5〜15 秒（許容） | LLM の最低限の文脈量を確保するため |
| Live セッション → Take-Home ページ生成 | 即時 | QR は録音開始時に発行 |
| 発話 → 分析用 Topic / Fact 反映 | 数十秒〜数分 | 既存パイプラインそのまま、Live UX には影響しない |

### 6.5 Keypoint Extractor のプロンプト方針

5〜15秒ごとに、直近の発話バッファ（例: 過去30秒）+ Workspace の Context Profile を渡し：

- 重要な事実・指示・警告・QA を箇条書きで抽出
- 既存 keypoint と重複しないように supersede 判定
- カテゴリ・重要度を付ける

実装は `backend/services/topic_annotator.py` を参考にしつつ、軽量な専用 prompt を `backend/services/live_keypoint_extractor.py`（新規）として書く。

---

## 7. API 設計（追加分）

### 7.1 Live セッション

| メソッド | エンドポイント | 説明 |
|---------|--------------|------|
| POST | `/api/live-sessions` | Live セッション開始。device_id を渡し、share_token + QR URL を返す |
| GET | `/api/live-sessions/{id}` | セッション情報取得 |
| POST | `/api/live-sessions/{id}/end` | セッション終了 |
| POST | `/api/live-sessions/{id}/delete` | 公開を取り消す（visibility=deleted） |
| GET | `/api/live-sessions/by-token/{share_token}` | 公開トークンからセッション取得（Take-Home ページ用） |

### 7.2 リアルタイム転写（プレゼン用 ASR）

| メソッド | エンドポイント | 説明 |
|---------|--------------|------|
| POST | `/api/transcribe/realtime` | 2.5秒チャンクの音声を受け、即座に転写テキストを返す。business プロジェクトの同名エンドポイントを **ほぼコピー** で持ち込む |

**実装の出処**:
- API: `business/backend/app.py:407-484`
- クライアント側のチャンク化ロジック: `business/frontend/src/components/RecordingSession.tsx:31-68, 333-364`
- ただし重複排除（LCS 比較）の複雑なロジックは Phase 1 では持ち込まず、シンプルに「届いた順に表示」で良い

**書き込み先**: 戻り値を `zerotouch_live_keypoints` ではなく、まず Topic 配下の発話ストリームとして `zerotouch_sessions`（既存テーブル）に append するか、別の Live 専用テーブルを切るかは Phase 1 で決定する。一旦は Live 専用の軽量テーブル（次節）を用意する方針。

### 7.3 Keypoint

| メソッド | エンドポイント | 説明 |
|---------|--------------|------|
| GET | `/api/live-sessions/{id}/keypoints` | キーポイント一覧（Take-Home でも使用） |

### 7.4 既存 QR API の使い方

`POST https://api.hey-watch.me/qrcode/v1/devices/{device_id}/qrcode` は device 単位でQRを発行する設計。

Live モードでは **session 単位の URL** をエンコードしたいため、既存APIを拡張するか、別エンドポイント（`/qrcode/v1/sessions/{share_token}`）を追加する。

> 決定事項: **新エンドポイント追加** が低リスク。既存 device QR API は触らない。

---

## 8. フェーズ分割（マイルストーン）

### Phase 0: 仕様確定（このドキュメントのレビュー）

- 本仕様書をレビュー、決定事項を確定
- 既存 ZeroTouch モードとの共存方針を最終化
- ASR プロバイダー選定（Deepgram Live で確定するか、Azure 検証するか）

### Phase 0.5: 開発前準備（着手前チェック）

- [ ] Android IA ワイヤー確定（`Home=MeMo Live / Dashboard=ZeroTouch` の画面遷移図）
- [ ] 参照画像との差分方針を確定（`Add Note` を Phase 1 で出さない方針を維持するか）
- [ ] migration 番号と既存SQLとの衝突確認（`backend/migrations` の次番号を確定）
- [ ] backend `.env` 項目確認（`OPENAI_API_KEY`, `REALTIME_TRANSCRIBE_MODEL` など不足キーの洗い出し）
- [ ] API 命名を確定（`/api/live-sessions*`, `/api/transcribe/realtime`, QR session endpoint）
- [ ] Web 側 `/s/[share_token]` のデータ取得責務を確定（Service Role 経由 fetch）
- [ ] 受け入れテスト観点を確定（遅延1〜3秒、Share導線、Dashboard連携）

### Phase 1: Minimum Live Display（4〜7日想定）

ゴール: 「タブレットに会話がリアルタイムで流れて、撮影 / QR で持ち帰れる」が動く。

- [ ] migration 017: `zerotouch_live_sessions` + `zerotouch_live_transcripts` + `zerotouch_live_keypoints`
- [ ] Backend: `POST /api/live-sessions`（share_token 発行 + QR画像URL返却）
- [ ] Backend: `POST /api/transcribe/realtime` を business からコピーして導入
  - `business/backend/app.py:407-484` を参考に、書き込み先を `zerotouch_live_transcripts` に変更
  - `OPENAI_API_KEY`, `REALTIME_TRANSCRIBE_MODEL` を GitHub Secrets で共有
- [ ] Backend: 既存 QR API に session 用エンドポイント追加（`/qrcode/v1/sessions/{share_token}`）
- [ ] Android: 新タブ `Live` 追加、`LiveDisplayScreen` 実装
- [ ] Android: IA 反映（第一導線を `Home` / `Dashboard` に変更。`Home` を MeMo Live の既定表示にする）
- [ ] Android: 2.5秒チャンク化したWebM/Opus音声を `/api/transcribe/realtime` に POST する送信ロジック
  - `business/frontend/src/components/RecordingSession.tsx:31-68, 333-364` の Kotlin 移植
  - 重複排除の複雑なロジックは Phase 1 では持ち込まず、シンプルに「届いた順に表示」
- [ ] タブレットの大型UI（横向き、撮影前提レイアウト、最低 24px / 見出し 48px）
- [ ] QR コードを画面に常時表示
- [ ] Web: `/s/[share_token]` Take-Home ページ実装（read-only / Service Role 経由 fetch）
- [ ] Live セッション終了 → 既存 ZeroTouch パイプライン（バッチ ASR 経由の Topic / Fact）と紐付け

### Phase 2: Keypoint + Take-Home 充実（5〜10日想定）

- [ ] Live Keypoint Extractor（5〜15秒間隔、`backend/services/live_keypoint_extractor.py`）
- [ ] Supabase Realtime 導入（タブレット & Take-Home ページの polling 削減）
- [ ] Take-Home ページに削除ボタン、PDF / 画像エクスポート
- [ ] Live セッション終了後の Take-Home ページ「精度アップグレード」（バッチ ASR 結果が出揃ったら自動置換）

### Phase 3: 翻訳 / Diarization / 補足生成（7〜14日想定）

- [ ] 翻訳ストリーム（live_keypoints を別 LLM で日→英翻訳して並列表示）
- [ ] 「補足して」ボタン（説明者が押すとFAQ / 追加情報を生成）
- [ ] Diarization で「薬剤師」「患者」ラベル
- [ ] Workspace Context Profile を Live にも反映（薬局 / カフェなど業界別の抽出指示）
- [ ] **セッション境界のチューニング**（「その場の会話だけを Take-Home に含める」課題への対処。Phase 1 では Live ON〜OFF の単純境界）

### Phase 4: 業務統合への接続

- [ ] Live セッション → Action Candidate 自動生成
- [ ] 薬局向け SOP 連携（処方薬DB引き当てなど）
- [ ] 不動産・教育・行政の domain schema 追加

---

## 9. 既存「ZeroTouch」モードとの共存

### 9.1 維持するもの

- `HomeDashboardScreen.kt` 〜 既存 3 レーンUI、Wiki / Query タブ、Action Candidate 関連UI
- Action Candidate API、Wiki API、Topic Annotator、すべてそのまま
- 既存の左レーン更新バグ（amical-longterm-memory-handoff.md 参照）は **Live モードと別軸の課題** として残す

### 9.2 役割分担

| モード | 主な使い手 | 焦点 | 永続化先 |
|-------|-----------|------|---------|
| Live | その場にいる全員（説明者 + 受け手） | 会話中の理解・持ち帰り | Live Session + Take-Home Page |
| ZeroTouch | デバイスのオーナー / 業務管理者 | 業務アクション・長期記憶 | Topic / Fact / Wiki / Action Candidate |

### 9.3 データの流れ方

Live セッションが終了すると、その `conversation_topic_id` は既存 ZeroTouch パイプラインの入力にもなる。すなわち：

- Live で消費されたあと、ZeroTouch モードでは「過去の Live セッションを Topic として遡れる」状態になる
- 業務統合（Action Candidate など）は ZeroTouch モード側からアクセス

---

## 10. プライバシー / セキュリティ要件

- **Listening 中であることが必ず明示される**（タブレット画面の常時インジケータ）
- **share_token は推測不可な乱数**（最低 8文字 base62、衝突検査必須）
- **保存期間の方針**:
  - Phase 1 のデフォルトは **30日**（`expires_at = started_at + 30 days`）
  - ただしプライバシー視点では「もっと短くてもよい」というスタンス。Phase 2 で **Workspace ごとに保存期間を設定可能** にする（24時間 / 7日 / 30日 / 期限なし）
  - 業種ごとの推奨デフォルトを Workspace の Context Profile から選べるようにする（医療系=短め、社内会議=長め、など）
- **「このセッションを削除」ボタンは認証なしで誰でも押せる**（その場の全員が消す権利を持つ）。ただし誤操作対策の確認モーダル必須
- **PII の扱い**: 患者名・電話番号などが転写に乗ってしまった場合の手動マスク機能は Phase 3 で検討
- **Workspace のオーナーは履歴閲覧可能**（ZeroTouch モード経由）。ここはオーナー権限を持つ人だけがアクセスできる

---

## 11. 決定事項と残存オープンクエスチョン

### 11.1 確定済み（2026-04-28 更新）

| 項目 | 決定内容 |
|------|---------|
| プロダクト名 | `MeMo`（フォルダ名 `android-zero-touch` / `web-zero-touch` は混乱回避のためそのまま。正式プロダクト名としてのみ MeMo を使う） |
| 画面名称（IA） | 第一導線は `Home`（MeMo Live）/ `Dashboard`（ZeroTouch）。既存画面は削除せず Dashboard 側へ再配置 |
| プラットフォーム | **Android Native (Compose)**。`android-zero-touch` リポジトリに新画面として追加 |
| Phase 1 言語 | 日本語のみ。**翻訳機能は Phase 1 では実装しない**。言語トグルUIは表示するが日本語のみアクティブ（C1） |
| 言語トグル UI | 表示はするがグレーアウト（タップしても切替なし、または「Phase 2 対応予定」表示） |
| 用語注釈カード | Phase 1 は **サンプル/モックデータで見た目だけ** 実装（D2）。LLM 連携は Phase 2 |
| Conversation ログ（右パネル）の粒度 | **Card 相当**。プレゼン用ASRの生発話ではなく、**分析用ASRから生成された Card** を表示（E2） |
| Add Note 機能 | **Phase 1 では実装しない**（F）。ボタン自体も出さない |
| 共有 UI | 右上の `Share` ボタン → モーダルで QR + 短縮 URL 表示（H-2）。「現在のセッション」を共有 |
| **セッション境界** | **時間ベース**。デバイスは常時録音前提で「Live ON/OFF」概念を持たない。1分無発言 / 話者変化 などで別セッション扱い（具体的しきい値は後でチューニング） |
| `Wrap Up & Save` ボタン | **不要**（常時録音前提のため）。代わりに右上 Share でいつでも現在セッションを取り出せる |
| デフォルト保存期間 | Phase 1 は 30日。Phase 2 で Workspace ごと可変に |
| プレゼン用 ASR | OpenAI `gpt-4o-mini-transcribe`（business から流用） |
| 分析用 ASR | 既存パイプライン（Speechmatics / Deepgram / Azure）をそのまま継続 |
| Take-Home ページ編集機能 | 不要（read-only） |
| 既存「左レーン更新バグ」 | Live モードと別軸で対処 |
| WebSocket / SSE 採用 | しない（HTTP POST チャンクで十分） |

### 11.2 残存オープンクエスチョン（後でチューニング / 詳細設計で判断）

- **セッション境界の具体ルール**: 無発言1分? 30秒? 話者変化での区切り条件? 連続最大時間? 後段でチューニング項目として運用しながら決める
- 「録音している」明示の強さ（医療現場では強めが望ましい）
- 共有トークンの URL 形式（`/s/{token}` で良いか）
- Live セッション → 分析用パイプラインへの音声受け渡しタイミング（常時録音なので「セッション区切りごとにバックエンドへ」が自然）

---

## 12. このドキュメントの位置づけ

- 上位設計の正本: `docs/conversation-action-platform.md`
- ライブモードの設計正本: 本ドキュメント `docs/live-support-pivot-spec.md`
- Wiki / 長期記憶: `docs/knowledge-pipeline-v2.md`
- 直近の作業引き継ぎ: `docs/amical-longterm-memory-handoff.md`

ピボット決定後は、`amical-longterm-memory-handoff.md` の最優先項目を本仕様書の Phase 1 に差し替える。

---

## 付録 A: 用語

- **Live セッション**: タブレットを「Listening」状態にしてから止めるまでの一連の会話単位。1〜N 個の Topic を含む
- **share_token**: Take-Home ページの公開キー。推測不可
- **Take-Home ページ**: QR / URL でアクセスできる公開シェアページ
- **Keypoint**: ライブ抽出された短い要点。Topic 全体のサマリより細粒度
- **ZeroTouch モード**: 既存の事後変換 + 業務統合機能群
- **Live モード**: 本仕様書で定義する新メイン体験

## 付録 B: 既存資産チェックリスト

- [x] QR コード生成API（本番稼働中）: `api/qr-code-generator/README.md`
- [x] Supabase RLS public read: `migrations/011_create_zerotouch_wiki_pages.sql:35-41`
- [x] アンビエント録音 + VAD: `app/src/main/java/.../audio/ambient/AmbientRecordingService.kt`
- [x] Topic / Fact 抽出パイプライン: `backend/services/topic_annotator.py`, `wiki_ingestor.py`
- [x] Web 動的ルート可能（Next.js 16 App Router）: `app/web-zero-touch/src/app/`
- [x] **business プロジェクトのリアルタイム ASR（流用元）**: `business/backend/app.py:407-484`
- [ ] プレゼン用リアルタイム ASR（android-zero-touch 側に business から導入）: 未実装（Phase 1）
- [ ] WebSocket: **採用しない**（HTTP POST チャンクで十分）
- [ ] Supabase Realtime: 未使用（Phase 2 で導入）
- [ ] 翻訳: 未実装（Phase 3、別 LLM）

## 付録 C: business プロジェクトとの共通環境変数

| 環境変数 | 用途 | 現在の設定先 |
|---------|------|-------------|
| `OPENAI_API_KEY` | プレゼン用 ASR + LLM 全般 | business と共通の GitHub Secrets |
| `REALTIME_TRANSCRIBE_MODEL` | デフォルト `gpt-4o-mini-transcribe` | business と共通 |
| `SPEECHMATICS_API_KEY` | 分析用 ASR（既存） | ZeroTouch / business 双方 |
| `DEEPGRAM_API_KEY` | 分析用 ASR（既存） | ZeroTouch / business 双方 |

GitHub Secrets で同じ値を共有することにし、CI/CD ワークフローでは双方のリポジトリの `.github/workflows/deploy-to-ecr.yml` に同名で注入する。
