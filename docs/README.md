# ZeroTouch Docs Index

このディレクトリの入口です。

ZeroTouch は、現場の会話を単に記録するアプリではなく、
会話を **業務システムが処理できる Action / Draft / Knowledge** に変換するための
Conversation Action Platform です。

---

## まず読むもの

1. **`conversation-action-platform.md`**
   - ZeroTouch の現在の最上位方針
   - 会話を SaaS / ERP / 業務システムの入力へ変換する設計
   - 飲食店を最初の参照ケースにしつつ、建設、医療、福祉、教育へ広げる考え方

2. **`amical-longterm-memory-handoff.md`**
   - 現在の実装状態と次にやること
   - 既存の Card / Topic / Wiki / Query を、Action 変換基盤の下位レイヤーとしてどう扱うか

3. **`knowledge-pipeline-v2.md`**
   - Wiki / 長期記憶レイヤーの設計
   - Raw sources を残し、Fact / Wiki / Query として蓄積する仕組み
   - Action Candidate の根拠、SOP、文脈補助として使う

---

## 現在の設計正本

| ファイル | 内容 | 状態 |
|---------|------|------|
| `conversation-action-platform.md` | 会話を Action / Draft / SaaS 入力へ変換する最上位設計 | ✅ 最新 |
| `amical-longterm-memory-handoff.md` | 現在の実装状態と再開ポイント | ✅ 更新対象 |
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

1. 飲食店 domain schema
2. `zerotouch_action_candidates` 系テーブル
3. Topic / Fact から Action Candidate を生成する backend service
4. Action Review Queue
5. 初期 connector: JSON export / Google Sheets / Notion など
6. 業界・個社ごとの connector 追加
