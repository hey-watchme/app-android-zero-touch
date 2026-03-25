# ZeroTouch Handoff

最終更新: 2026-03-25

## 最初に読むもの

1. この `HANDOFF.md`
2. GitHub Issues: `hey-watchme/app-android-zero-touch`
3. [README.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/README.md)
4. [docs/conversation-pipeline-processes.md](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/conversation-pipeline-processes.md)

## このプロジェクトの現在地

ZeroTouch は WatchMe の既存インフラを使った派生サービスです。
Android タブレットを現場に置き、アンビエント録音から会話カードとトピックを生成するプロダクトです。

ただし現在は「Process 2 への移行途中」であり、さらに録音/VAD の基本動作が不安定です。
先に Process 2 を完成させるのではなく、まず ambient recording の正常化を優先してください。

## 重要な判断

- Process 2 の backend 中核はかなり実装済み
- しかし Android 側と topic フローには旧実装が混在している
- さらに ambient recording が不安定で、発話セッションが正しく閉じない
- そのため、最優先は録音/VAD の安定化
- Process 2 の実装再開は、その後

## このセッションでやったこと（2026-03-25）

- `#6` の計測ログを実装（録音開始/終了理由、VAD 遷移、read stall、watchdog 再起動理由の可視化）
  - `app/src/main/java/com/example/zero_touch/audio/ambient/AmbientRecorder.kt`
  - `app/src/main/java/com/example/zero_touch/audio/ambient/AmbientRecordingService.kt`
  - `app/src/main/java/com/example/zero_touch/audio/ambient/VadDetector.kt`
  - `app/src/main/java/com/example/zero_touch/audio/ambient/SileroVadDetector.kt`
  - `app/src/main/java/com/example/zero_touch/audio/ambient/WebRtcVadDetector.kt`
- デバッグチェックリストを追加: `docs/ambient-recording-debug-checklist.md`
- README にチェックリストへの参照を追加
- Java Runtime を `openjdk@21` で導入し、`./gradlew :app:compileDebugKotlin` が成功
- 無音環境ログ（エアコン停止）を採取し、誤起動が出ないことを確認
  - 2026-03-25 22:19:51〜22:20:41（JST）
  - `recording=false` のまま推移、`Recording started` なし
  - 暫定分類: `no_false_start`（無音では起動しない）
- VAD 開始/継続の確認条件を強化
  - 連続 `speech` フレーム + `confidence` しきい値を満たした場合のみ録音開始
  - 録音中も短いスパイクで silence がリセットされないように調整
- Watchdog 再起動のクールダウンを追加
  - 連続再起動ループを抑止（10秒クールダウン）
  - recorder が null の場合は状態だけリセットして再起動しない
- UI の音声検出表示を録音状態と分離
  - 録音中のみステータスを "Recording" に固定
  - 録音していない場合は "Listening..." を維持し、音声検出は補助ラベル表示
- リングバッファのプリロールをセッションごとにリセット
  - 録音終了時に `PcmRingBuffer` をクリアして前セッションの音が混ざらないようにする
- Android 側の topic フローを `evaluate-pending` に切替（backfill を runtime から除外）
  - `ZeroTouchViewModel.loadTopicCards` が `evaluate-pending` を呼ぶ
- アンビエント停止時に `evaluate-pending` を強制実行
  - `VoiceMemoScreen` のトグルOFFで `evaluate-pending` をトリガー

## テスト / 実行

- `./gradlew :app:compileDebugKotlin`（JDK 21 で成功）
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`


## GitHub Issues

このセッションで、次の実行順で Issue を作成済みです。
次セッションの担当者には「まず GitHub Issue を上から順に見てください」で通じます。

- `#5` P0 Umbrella: stabilize ambient recording before Process 2 rollout
- `#6` P0: add instrumentation for ambient recording and VAD session lifecycle
- `#7` P0: fix false-positive VAD causing endless ambient recording sessions
- `#8` P0: fix stale-heartbeat auto-restart loop in AmbientRecordingService
- `#9` P1: separate UI speech indication from actual recording state
- `#10` P1: prevent ring-buffer pre-roll leakage across ambient sessions
- `#11` P1: switch Android topic flow from backfillTopics to evaluate-pending
- `#12` P1: trigger Process 2 topic evaluation when ambient mode stops
- `#13` P2: remove legacy Process 1 topic flow from the runtime path
- `#14` P2: align Process 2 topic lifecycle and evaluation-run metadata with spec

推奨順:

`#6 -> #7 -> #8 -> #9 -> #10 -> #11 -> #12 -> #13 -> #14`

## 次セッションの開始メッセージ

次の担当者には、まずこれを伝えれば十分です。

> ZeroTouch は Process 2 移行途中ですが、今は機能追加より先に ambient recording の正常化が必要です。  
> GitHub の `hey-watchme/app-android-zero-touch` で `#5` を親として順に進めます。  
> `#6` の計測ログは実装済みなので、まず実機 or エミュレータでログを1本採取して受け入れ条件を満たしてください。  
> その後 `#7`（VAD false-positive 調整）に進みます。  
> README と conversation-pipeline-processes.md を再確認して、Issue の受け入れ条件に沿って進めてください。

## 現状の技術的な要点

録音/VAD の主な確認箇所:

- [AmbientRecorder.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/audio/ambient/AmbientRecorder.kt)
- [AmbientRecordingService.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/audio/ambient/AmbientRecordingService.kt)
- [VadDetector.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/audio/ambient/VadDetector.kt)
- [SileroVadDetector.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/audio/ambient/SileroVadDetector.kt)

Process 2 の主な確認箇所:

- [topic_manager_process2.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/services/topic_manager_process2.py)
- [background_tasks.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/services/background_tasks.py)
- [app.py](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/app.py)
- [004_process2_topic_pipeline.sql](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/migrations/004_process2_topic_pipeline.sql)
- [005_add_device_settings.sql](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/migrations/005_add_device_settings.sql)

Android 側で旧フローが残っている箇所:

- [ZeroTouchViewModel.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/ui/ZeroTouchViewModel.kt)
- [VoiceMemoScreen.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/ui/VoiceMemoScreen.kt)
- [ZeroTouchApi.kt](/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/example/zero_touch/api/ZeroTouchApi.kt)

## いま実装しないこと

- Process 2 の追加機能拡張
- topic/task extraction の高度化
- UI の大幅リデザイン

## 停止点

`#6` の実装とコンパイルまで完了しています。
次のセッションでは、デバイスまたはエミュレータでログ採取を行い、`#6` の受け入れ条件を満たす証跡を作ってから `#7` に進んでください。
