# ZeroTouch Docs Index

このディレクトリの入口です。

ZeroTouch は、現場の会話を単に記録するアプリではなく、
会話を **業務システムが処理できる Action / Draft / Knowledge** に変換するための
Conversation Action Platform です。

2026-04-29 時点で、当面の実装優先は **Live Support ピボット（Live モード）** です。  
まず `live-support-next-session-handoff.md` で最新の運用状態を確認し、その後 `live-support-pivot-spec.md` を読む。

画面情報設計は `Home`（MeMo Live）/ `Dashboard`（ZeroTouch）を基準にします。

---

## まず読むもの

1. **`live-support-next-session-handoff.md`**
   - 次セッション再開用の最新版ステータス
   - デプロイ要件（Android / backend / Supabase）と確認コマンド
   - Live Transcript フォーカスでの次アクション

2. **`live-support-pivot-spec.md`**
   - 当面の最優先仕様（Live モードへのピボット）
   - 「会話中のライブサポート」を先に実装するための要件・Phase 設計
   - ZeroTouch モードとの共存方針、デュアル ASR 方針、Take-Home ページ方針

3. **`conversation-action-platform.md`**
   - ZeroTouch の現在の最上位方針
   - 会話を SaaS / ERP / 業務システムの入力へ変換する設計
   - 飲食店を最初の参照ケースにしつつ、建設、医療、福祉、教育へ広げる考え方

4. **`poc-knowledge-worker-domain.md`**
   - 最初の PoC を「自分の業務（ナレッジワーカー）」で実装するための設計
   - 6 つの出力カテゴリ / 4 つの最小構成 / 必要データ / 実装段階

5. **`amical-longterm-memory-handoff.md`**
   - 現在の実装状態と次にやること
   - 既存の Card / Topic / Wiki / Query を、Action 変換基盤の下位レイヤーとしてどう扱うか

6. **`knowledge-pipeline-v2.md`**
   - Wiki / 長期記憶レイヤーの設計
   - Raw sources を残し、Fact / Wiki / Query として蓄積する仕組み
   - Action Candidate の根拠、SOP、文脈補助として使う

7. **`ambient-pipeline-refactor-handoff.md`**
   - Ambient 録音 / upload idempotency / ASR retry / Topic finalize scheduler の再開メモ
   - 今回の変更、検証済みコマンド、残タスク、scheduler 並行性リスク

---

## 現在の設計正本

| ファイル | 内容 | 状態 |
|---------|------|------|
| `live-support-next-session-handoff.md` | Live 実装の最新状態、依存デプロイ条件、次セッション開始手順 | ✅ 最新 |
| `live-support-pivot-spec.md` | Live モードへのピボット仕様（当面の実装優先） | ✅ 最新 |
| `conversation-action-platform.md` | 会話を Action / Draft / SaaS 入力へ変換する最上位設計 | ✅ 最新 |
| `poc-knowledge-worker-domain.md` | 最初の PoC をナレッジワーカードメインで作る設計 | ✅ 最新 |
| `amical-longterm-memory-handoff.md` | 旧系引き継ぎメモ（歴史的経緯の参照） | 🟡 参照用 |
| `ambient-pipeline-refactor-handoff.md` | Ambient pipeline リファクタリングの再開ポイント | ✅ 最新 |
| `knowledge-pipeline-v2.md` | Wiki / 長期記憶レイヤーの設計 | ✅ 有効 |
| `wiki-project-category-page-design.md` | Wiki の project / category / page 分類設計 | ✅ 有効 |
| `account-workspace-org-design.md` | Account / Workspace / Organization の所有モデル | ✅ 有効 |
| `context-enrichment-project.md` | Converter / Wiki の精度を上げる文脈入力 | ✅ 有効 |
| `pipeline-cost-benchmarks.md` | 再処理コスト、トークン目安 | ✅ 有効 |

---

## 実装・運用メモ

| ファイル | 内容 |
|---------|------|
| `ambient-recording-debug-checklist.md` | Android Ambient 録音のデバッグ手順 |
| `ambient-agent-spec.md` | 初期の Ambient Agent 企画メモ |
| `vad-improvement-plan.md` | VAD 改善メモ |
| `Ambient_Context_Monopoly.pdf` | 関連調査資料 |

---

## 古い前提の扱い

旧 v1 / stateful daily / snapshot reducer 系の設計は、現在の正本ではありません。

現在の判断:

- 破壊的な state snapshot 更新は採用しない
- Raw sources は残す
- Wiki は長期記憶レイヤーとして残す
- その上に Action Candidate / Review / Connector レイヤーを作る
- 飲食店の例は最初の参照実装であり、ZeroTouch 自体は業界横断の基盤として設計する

---

## 次に作るもの

1. `Live` モード Phase 1（`live-support-pivot-spec.md`）
2. Live セッション / リアルタイム転写 / Take-Home ページの実装
3. Live と既存 Topic / Fact / Wiki の接続
4. その後に ZeroTouch モード側の Action Candidate / Review / Connector を拡張
