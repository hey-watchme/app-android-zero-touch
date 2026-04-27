# Ambient Pipeline Refactor Handoff

更新日: `2026-04-27`

この文書は、Ambient 録音から upload / ASR / Topic finalize までのリファクタリング再開メモです。
利用者互換は考慮しない。プロダクトとしてあるべき責務分離と運用耐性を優先する。

## 今回入れた変更

### Android

- `ZeroTouchApi`
  - `ZeroTouchApiException` を追加
  - HTTP / network failure を retryable / non-retryable に分類
  - `transcribe` trigger は `IOException`, `408`, `429`, `5xx` で retry
  - `upload` は `local_recording_id` がある場合のみ retry 可能
- `AmbientRecordingService`
  - 録音ごとに `localRecordingId` を生成
  - `/api/upload` に `local_recording_id` を送信
  - `webrtc` VAD 設定は未実装なので `silero` に fallback
- `AmbientStatus`
  - `AmbientRecordingEntry.localRecordingId` を追加
- `Mp4AudioWriter`
  - `stop()` の codec / muxer release を `finally` で保証
- `ZeroTouchViewModel`
  - `loadSessions` / `refreshSessions` から `evaluate-pending` 呼び出しを削除
  - 画面読み込みが backend mutation を起こさない構造へ変更
  - Ambient Off の明示 finalize だけ残す

### Backend

- `/api/upload`
  - `local_recording_id` を受け取る
  - 既存 `(device_id, local_recording_id)` があれば既存 session を返す
  - insert race 時も既存 session を返す fallback を追加
- migration
  - `015_add_session_upload_idempotency.sql`
  - `zerotouch_sessions.local_recording_id`
  - unique index `(device_id, local_recording_id)` where not null
- Topic finalize
  - FastAPI process 内 scheduler を追加
  - active topic を定期確認し、30 秒 idle で `finalize_active_topic_for_device` を呼ぶ
  - Android の read path から finalize mutation を外した

## 検証済み

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
python3 -m py_compile backend/app.py backend/services/topic_manager_process2.py backend/services/background_tasks.py
git diff --check
```

## 適用時の注意

DB migration を先に適用する。

```sql
backend/migrations/015_add_session_upload_idempotency.sql
```

この migration なしでも backend は missing column fallback で upload 自体は動くが、
`local_recording_id` による idempotency は成立しない。

## 既知のリスク

### 1. Scheduler concurrency

現在の scheduler は FastAPI process 内 thread。

`uvicorn -w 4` や gunicorn 複数 worker で起動すると、worker 数だけ scheduler thread が立つ。
同じ device / topic に対して複数 finalize が同時実行されると、LLM 課金と DB 整合性の両方でリスクがある。

暫定運用:

- prod で in-process scheduler を使う場合、`TOPIC_FINALIZE_SCHEDULER_ENABLED=true` は 1 process のみ
- 複数 worker では原則 `TOPIC_FINALIZE_SCHEDULER_ENABLED=false`
- durable scheduler に移すまでは、運用で単一起動を守る

次の安全策:

- `pg_try_advisory_lock(hashtext('topic_finalize:' || device_id))` 相当を入れる
- または scheduler を Lambda / EventBridge へ移して in-process scheduler を削除する

### 2. Failed session の再送 UI が未実装

API 層 retry は入ったが、最終的に `failed` になった session を UI から再送する導線はまだ無い。

必要な導線:

- failed card / detail sheet に retry action を出す
- upload 前失敗: 同じ local file が残っている場合のみ retry upload
- upload 後失敗: 既存 session id に対して retry transcribe

現時点では、upload 済み session の retry transcribe は ViewModel に既存関数がある。
upload 自体の retry UI は、ローカルファイル保持・削除方針を決めてから実装する。

### 3. Ambient state の整理は未着手

`AmbientStatus` と ViewModel 派生 state の二重化は残っている。
今回、read path mutation を削ったことで緊急性は下がったが、いずれ single source of truth を整理する。

## 次にやること

### A. Scheduler 安全弁

小さく先にやる。

1. README / deploy docs に `TOPIC_FINALIZE_SCHEDULER_ENABLED` の prod 運用ルールを明記
2. backend の finalize 実行前に advisory lock を入れる
3. lock が取れない場合は skip してログだけ出す

### B. Durable scheduler へ移行

本命。

1. `zerotouch-topic-finalize-trigger` Lambda を作る
2. EventBridge `rate(1 minute)` で起動
3. active topic を列挙して `finalize_active_topic_for_device` を呼ぶ
4. Supabase / LLM / env / secrets は既存 WatchMe Lambda のパターンに合わせる
5. 移行後、FastAPI in-process scheduler を削除

### C. Failed session retry UI

Android 側。

1. failed card の表示に retry affordance を追加
2. upload 済みなら `retryTranscribeSession`
3. upload 前なら local file が残っている場合だけ `uploadAudio(local_recording_id=既存値)` で再送
4. ローカル録音ファイルの retention / cleanup 方針を決める

### D. 後回し

- Silero confidence を実モデル score として扱う
- watchdog threshold の再設計
- VAD debug stats の build guard
- 録音コアの unit / integration test 拡充

## 再開時の確認コマンド

```bash
cd /Users/kaya.matsumoto/projects/watchme/app/android-zero-touch
git status --short
./gradlew :app:compileDebugKotlin
python3 -m py_compile backend/app.py
```

backend deploy 前には migration 適用状況と scheduler 起動数を必ず確認する。
