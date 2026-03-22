# Android Ambient Agent

Android タブレットを専用機として使い、現場の音声や操作入力から事実を拾い上げ、
タスクカードやイベントカードとして構造化・蓄積するための実験アプリです。

現在の実装は「録音 → 停止 → 再生」の最小ボイスメモ版です。
今後は `STT -> ファクト抽出 -> アノテーション -> マッピング/整形 -> カード表示` の
パイプラインへ拡張します。

企画と仕様の基準は [docs/ambient-agent-spec.md](docs/ambient-agent-spec.md) を参照してください。

既存の実装メモは [HANDOFF.md](HANDOFF.md) にあります。
