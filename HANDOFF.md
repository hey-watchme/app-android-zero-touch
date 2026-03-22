# Android ZeroTouch - 引き継ぎメモ

このディレクトリは「録音→停止（保存）→再生→停止」だけができる最小のボイスメモ実装を含みます。
端末（Xiaomi タブレット）疎通確認と、今後の Mock / インフラ接続の土台として残しています。

## いま入っている機能（最小）

- ボイスメモ録音（AAC / MP4 コンテナ）: アプリ内ストレージに `voice_memo.m4a` として保存
- ボイスメモ再生（保存済みファイルを再生）
- マイク権限（`RECORD_AUDIO`）のリクエスト UI
- 画面は 1 枚のみ（`MainActivity` が直接表示）

主な実装ファイル:

- `app/src/main/java/com/example/zero_touch/ui/VoiceMemoScreen.kt`
- `app/src/main/java/com/example/zero_touch/audio/VoiceMemoEngine.kt`
- `app/src/main/AndroidManifest.xml`
- UI の存在確認だけの instrumented test:
  - `app/src/androidTest/java/com/example/zero_touch/VoiceMemoScreenTest.kt`

## 実機での動作確認

1. アプリ起動 → 「マイク権限を許可」
2. 「録音」→「停止」
3. 「再生」→「停止」

## 開発環境メモ

- Gradle/ビルドには JDK が必要です。Android Studio の `Gradle JDK` を設定してください。

## 次にやること（watchme のインフラ活用の足場）

- Supabase:
  - Auth / Storage を使うなら「録音ファイルのアップロード」までをまず実装
  - 端末側は「ローカル保存 → アップロード → URL で再生」へ拡張しやすい構成
- AWS:
  - S3 を使う場合も同様に「ローカル保存 → S3 PUT → 署名 URL/公開 URL で再生」に拡張
- Mock:
  - まずは「録音メタデータ（作成日時、長さ、アップロード状態）」をローカルで持つ（Room or in-memory）→ 後で Supabase/Backend に差し替え

