# ZeroTouch - ロードマップ

最終更新: 2026-03-23

---

## 現在の状態

アンビエント録音 MVP が実装済み。**LLM カード生成は停止中**（Speechmatics の文字起こしのみ）。

| コンポーネント | 状態 |
|---|---|
| バックエンド（FastAPI :8061） | EC2 稼働中、CI/CD 正常 |
| Nginx `/zerotouch/` → `:8061` | 設定済み |
| Android アプリ（1画面 + フッターナビ） | 実機動作確認済み（Xiaomi Redmi タブレット） |
| Supabase `zerotouch_sessions` | 作成済み |
| GitHub Actions → ECR → EC2 | 正常動作 |

### 現在のパイプライン（アンビエント自動）

```
Android: VAD 検出 → 自動録音 → Upload
  ↓
Backend: S3保存 → Speechmatics文字起こし
  ↓
Android: 5秒ポーリング → カード表示
```

**録音ルール（現在値）**
- 先頭 2 秒リングバッファを含めて保存
- 無音 5 秒でセッション終了
- 最小 3 秒未満は破棄

---

## ゴール：アンビエント録音アーキテクチャ

「置くだけで、会話がカードになる」を実現する。
ユーザー操作なし、コスト効率の高い常時録音パイプライン。

### 最終形：カスケードフィルタ方式

```
Tier 0 ─ VAD（常時稼働、無料）
  │  人の声を検出。沈黙・環境音を除外。
  ▼
Tier 1 ─ オンデバイス STT（無料）
  │  Android SpeechRecognizer で粗い文字起こし。
  │  短すぎる発話・無意味な音を除外。
  ▼
Tier 2 ─ セッション境界判定
  │  発話の塊を「1セッション」にまとめる。
  │  30秒沈黙 → セッション区切り。最大5分で強制分割。
  ▼
Tier 3 ─ クラウド処理（有料）
  │  Speechmatics 高精度文字起こし + GPT カード生成。
  ▼
結果 → Supabase → アプリ表示
```

コスト目標：8時間の営業でクラウド処理は1-2時間分のみ（月$30-45）。

---

## ロードマップ

### Step 1：VAD + 自動録音/停止（完了）

**目的**: 「ボタンを押さなくても会話がカードになる」体験の成立確認。

**変更範囲**: Android のみ（バックエンド変更なし）

**実装内容**:
- `AudioRecord` + 簡易 VAD（エネルギー + ZCR）を実装
- 音声検出 → 録音開始、無音 5 秒 → 録音停止
- 停止時に自動で既存の `/api/upload` → `/api/transcribe` を呼ぶ
- UI にアンビエントトグル / ステータス / カード一覧を追加
- Foreground Service で画面オフでも動作
 - 先頭 2 秒のリングバッファを保存

**検証項目**:
- 会話の開始/終了を正しく拾えるか
- 1日何セッション生成されるか
- 誤検出率（テレビ、BGM、関係ない独り言）
- バッテリー消費（充電中想定だが計測）

**セッション境界ルール（初期値）**:
```
沈黙 < 5秒    → 同一セッション（息継ぎ・間）
沈黙 >= 5秒   → 新セッション（現行）
連続発話 > 5分 → 強制分割
最小セッション長 < 3秒 → 破棄
```

**成功基準**: 手動操作なしで、意味のあるカードが1日5枚以上生成される。

---

### Step 2：オンデバイス STT フィルタ（コスト最適化）

**目的**: クラウド処理に送る前に、無意味なセッションをデバイス上で除外する。

**変更範囲**: Android のみ

**実装内容**:
- Android `SpeechRecognizer` API（オフライン対応）でリアルタイム粗文字起こし
- フィルタ条件:
  - 文字数 < 10文字 → 破棄（「はい」「うん」だけ等）
  - 業務キーワード辞書によるスコアリング（予約、確認、連絡、お願い 等）
  - スコアが閾値未満 → 破棄
- フィルタ通過率の計測ログ

**検証項目**:
- フィルタで何%のセッションが除外されるか（目標: 60-80%）
- 除外されたセッションに本来処理すべきものがなかったか（偽陰性率）
- オンデバイス STT の CPU/メモリ負荷

**成功基準**: クラウド処理量が Step 1 比で 50%以上削減、かつ偽陰性率 5%以下。

---

### Step 3：セッション品質の向上

**目的**: カード生成の精度と実用性を上げる。

**変更範囲**: Android + バックエンド

**実装内容**:
- セッション境界ルールのチューニング（Step 1-2 の実データに基づく）
- 話者分離情報の活用（Speechmatics の diarization 結果をカードに反映）
- セッションメタデータの充実:
  - オンデバイス STT のプレビューテキスト
  - 発話者数の推定
  - 環境ノイズレベル
- バックエンドのプロンプト改善（現場コンテキストを反映）

**検証項目**:
- カードの「使える率」（ユーザーが実際に参照するか）
- 1セッションあたりのカード数と種類分布

---

### Step 4：ナレッジ化パイプライン（Phase 2）

**目的**: 単発カードの蓄積から、現場固有のナレッジを構築する。

**変更範囲**: バックエンド + 新 UI

**実装内容**:
- カードの自動分類・統合（同じ顧客、同じトピック）
- ファクト抽出 → アノテーション（Business プロジェクトの手法を転用）
- 検索可能なナレッジベース
- 現場固有の用語辞書の自動構築

---

## 実装ファイルマップ

### Android
| ファイル | 役割 |
|---|---|
| `app/.../MainActivity.kt` | 1画面 + フッターナビ |
| `app/.../api/ZeroTouchApi.kt` | APIクライアント（OkHttp） |
| `app/.../api/DeviceIdProvider.kt` | デバイスID（SharedPreferences + UUID） |
| `app/.../ui/ZeroTouchViewModel.kt` | 状態管理、アップロード、ポーリング |
| `app/.../ui/VoiceMemoScreen.kt` | 状態表示 + カードリスト |
| `app/.../audio/ambient/AmbientRecordingService.kt` | Foreground Service |
| `app/.../audio/ambient/AmbientRecorder.kt` | VAD / 録音 / セッション分割 |
| `app/.../audio/ambient/VadDetector.kt` | 簡易 VAD（エネルギー + ZCR） |
| `app/.../audio/ambient/PcmRingBuffer.kt` | 先頭 2 秒バッファ |
| `app/.../audio/ambient/Mp4AudioWriter.kt` | AAC/MP4 エンコード |

### バックエンド
| ファイル | 役割 |
|---|---|
| `backend/app.py` | FastAPI メインアプリ（ポート 8061） |
| `backend/services/prompts.py` | カード生成プロンプト |
| `backend/services/background_tasks.py` | 非同期処理（文字起こし→カード生成チェーン） |
| `backend/services/llm_providers.py` | OpenAI/Gemini 抽象化 |
| `backend/services/llm_models.py` | モデルカタログ（デフォルト: gpt-5.4） |
| `backend/services/asr_providers/speechmatics_provider.py` | Speechmatics 話者分離 |
| `backend/docker-compose.prod.yml` | 本番コンテナ（ポート 8061） |

### インフラ
| リソース | 詳細 |
|---|---|
| EC2 | 3.24.16.82（既存 WatchMe サーバー） |
| ECR | `zerotouch-api` |
| S3 | `watchme-vault/zerotouch/{device_id}/{date}/{session_id}.m4a` |
| Supabase | プロジェクト `qvtlwotzuzbavrzqhyvt`、テーブル `zerotouch_sessions` |
| Nginx | `api.hey-watch.me/zerotouch/` → `localhost:8061` |
| CI/CD | `.github/workflows/deploy-to-ecr.yml` |

---

## 参考

- 企画書: `docs/ambient-agent-spec.md`
- モデルプロジェクト: `/Users/kaya.matsumoto/projects/watchme/business`
- WatchMe インフラ: `/Users/kaya.matsumoto/projects/watchme/server-configs`
- GitHub: `hey-watchme/app-android-zero-touch`
