# Android ZeroTouch - 引き継ぎメモ

最終更新: 2026-03-22

## 現在の状態

MVP 実装済み。録音→S3アップロード→Speechmatics文字起こし→GPT-5.4カード生成 のパイプラインが完成。
**バックエンドはまだ EC2 にデプロイしていない。**

---

## 実装済み機能

### Android アプリ（3タブ構成）
- **Record タブ**: 録音 → 停止 → 「Upload & Analyze」ボタンでAPIへ送信
- **Sessions タブ**: セッション一覧（ステータス表示、タップで詳細へ）
- **Detail タブ**: 文字起こしプレビュー + 生成カード（task/memo/schedule 等）表示、5秒ポーリング

### バックエンド（FastAPI）
- `POST /api/upload` — 音声を S3（watchme-vault/zerotouch/...）に保存、DBセッション作成
- `POST /api/transcribe/{id}` — Speechmatics で文字起こし（auto_chain=true で自動的にカード生成まで走る）
- `POST /api/generate-cards/{id}` — GPT-5.4 でカード生成（スポット実行）
- `GET /api/sessions` / `GET /api/sessions/{id}` — セッション取得

### データベース（Supabase）
- `zerotouch_sessions` テーブル作成済み（migration 実行済み）
- プロジェクト ID: `qvtlwotzuzbavrzqhyvt`（Business と同一プロジェクト）

---

## 主な実装ファイル

### Android
| ファイル | 役割 |
|---------|------|
| `app/.../MainActivity.kt` | 3タブナビゲーション、SnackbarエラーUI |
| `app/.../api/ZeroTouchApi.kt` | バックエンドAPIクライアント（OkHttp） |
| `app/.../api/DeviceIdProvider.kt` | デバイスID管理（SharedPreferences + UUID） |
| `app/.../ui/ZeroTouchViewModel.kt` | 状態管理、アップロードフロー、ポーリング |
| `app/.../ui/VoiceMemoScreen.kt` | 録音UI + アップロードボタン |
| `app/.../ui/SessionListScreen.kt` | セッション一覧UI |
| `app/.../ui/CardDetailScreen.kt` | カード表示UI |
| `app/.../audio/VoiceMemoEngine.kt` | 録音/再生エンジン（変更なし） |

### バックエンド
| ファイル | 役割 |
|---------|------|
| `backend/app.py` | FastAPI メインアプリ（ポート 8060） |
| `backend/services/prompts.py` | カード生成プロンプト（英語、JSON出力） |
| `backend/services/background_tasks.py` | 非同期処理（文字起こし→カード生成チェーン） |
| `backend/services/llm_providers.py` | OpenAI/Gemini 抽象化 |
| `backend/services/llm_models.py` | モデルカタログ（デフォルト: gpt-5.4-2026-03-05） |
| `backend/services/asr_providers/speechmatics_provider.py` | Speechmatics 話者分離 |
| `backend/migrations/001_create_zerotouch_sessions.sql` | DBテーブル定義 |
| `backend/docker-compose.prod.yml` | 本番コンテナ設定 |

---

## 次にやること（デプロイ）

### 1. ECR リポジトリ作成
```bash
aws ecr create-repository --repository-name zerotouch-api --region ap-southeast-2
```

### 2. GitHub Actions CI/CD 追加
`business` の `.github/workflows/deploy-to-ecr.yml` を参考に、`zerotouch-api` 用を作成。
主な変更点:
- ECR リポジトリ名: `zerotouch-api`
- コンテナ名: `zerotouch-api`
- ポート: `8060`
- ディレクトリ: `backend/`

### 3. GitHub Secrets に追加
```
SUPABASE_URL, SUPABASE_KEY, SUPABASE_SERVICE_ROLE_KEY
AWS_REGION, S3_BUCKET
SPEECHMATICS_API_KEY
OPENAI_API_KEY, GEMINI_API_KEY
LLM_DEFAULT_PROVIDER, LLM_DEFAULT_MODEL
```

### 4. Nginx 設定追加（server-configs リポジトリ）
`/zerotouch/` パスを `:8060` にリバースプロキシする設定を追加。

### 5. Android アプリの API URL 確認
`ZeroTouchApi.kt` の `baseUrl` が `https://api.hey-watch.me` になっている（Nginx 設定と合わせること）。

---

## 今後の拡張（Phase 2+）

- Lambda + SQS による S3イベント駆動の自動パイプライン（現在は手動トリガー）
- ファクト抽出 → アノテーション → ナレッジ化（Business の Phase 1-3 に相当）
- 現場固有の用語・ルール学習
- エージェント行動（予約登録、リマインド等）

---

## 参考

- 企画書: `docs/ambient-agent-spec.md`
- モデルプロジェクト（パイプライン参考）: `/Users/kaya.matsumoto/projects/watchme/business`
- WatchMe インフラ: `/Users/kaya.matsumoto/projects/watchme/server-configs`
- GitHub Issue #1: https://github.com/hey-watchme/app-android-zero-touch/issues/1
