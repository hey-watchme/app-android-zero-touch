# ZeroTouch Docs Index

このディレクトリの入口です。

ZeroTouch は、現場の会話を単に記録するアプリではなく、
会話を **業務システムが処理できる Action / Draft / Knowledge** に変換するための
Conversation Action Platform です。

---

## 🔴 現状ステータス（2026-05-02）

Home 画面では本来、次の **2 つのパスを並列で走らせる** 設計になっている。

| パス | 役割 | 現状 |
|------|------|------|
| **Live Support path** | リアルタイム文字起こし + 翻訳（presentation 用） | ✅ 動作中 |
| **Conversation path** | Card → Topic → Fact → Wiki（本体パイプライン） | 🔴 **動いていない / 再稼働が必要** |

### 直近の最優先タスク

**従来の Conversation パイプラインを再度動かすこと。**

Wiki / Action Candidate / Connector などの後段機能はすべて Conversation パイプラインに依存しているため、それが動くまでこれらの実装には進まない。

順番:

1. 🔴 Conversation パイプラインを再稼働させる ← **いまここ**
2. ⏸ Wiki Ingest / Query が会話起点で動くことを確認
3. ⏸ Action Candidate / Review / Connector の検証・拡張
4. ⏸ それ以降は `roadmap.md` を参照

---

## まず読むもの

1. **`live-support.md`**  
   現状動いている Live Support 機能の技術スタック・API・DB・実装場所。これは完成済み。

2. **`conversation-visualization-pipeline.md`**  
   Card → Topic → Fact → Wiki の本体パイプラインの正本。**再稼働させる対象の設計書**。

3. **`roadmap.md`**  
   将来の TODO（Action Candidate / Connector / Take-Home / 多言語化など）。Conversation パイプライン復活後に着手する。

4. **`conversation-action-platform.md`**  
   ZeroTouch の最上位方針。会話を SaaS / ERP / 業務システムの入力へ変換する設計。

5. **`knowledge-pipeline-v2.md`**  
   Wiki / 長期記憶レイヤーの設計。Conversation パイプラインの後段。

---

## 現在の設計正本

| ファイル | 内容 | 状態 |
|---------|------|------|
| `live-support.md` | Live Support 機能（リアルタイム文字起こし + 翻訳）の現状仕様 | ✅ 実装済み |
| `conversation-visualization-pipeline.md` | Capture / Card / Topic / Fact / Wiki の本体可視化 pipeline 正本 | 🔴 再稼働対象 |
| `roadmap.md` | 将来の TODO 集約 | 📋 構想 |
| `conversation-action-platform.md` | 会話を Action / Draft / SaaS 入力へ変換する最上位設計 | 📋 構想 |
| `poc-knowledge-worker-domain.md` | ナレッジワーカードメインの PoC 設計 | 📋 構想 |
| `knowledge-pipeline-v2.md` | Wiki / 長期記憶レイヤーの設計 | 📋 構想 |
| `wiki-project-category-page-design.md` | Wiki の project / category / page 分類設計 | 📋 構想 |
| `account-workspace-org-design.md` | Account / Workspace / Organization の所有モデル | ✅ 有効 |
| `context-enrichment-project.md` | Converter / Wiki の精度を上げる文脈入力 | 📋 構想 |
| `pipeline-cost-benchmarks.md` | 再処理コスト、トークン目安 | ✅ 有効 |
| `amical-longterm-memory-handoff.md` | 旧系引き継ぎメモ（歴史的経緯の参照のみ） | 🟡 参照用 |

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

旧 v1 / stateful daily / snapshot reducer 系の設計は、現在の正本ではない。

現在の判断:

- 破壊的な state snapshot 更新は採用しない
- Raw sources は残す
- Wiki は長期記憶レイヤーとして残す
- その上に Action Candidate / Review / Connector レイヤーを作る（ただし Conversation パイプライン復活後）
- 飲食店の例は最初の参照実装であり、ZeroTouch 自体は業界横断の基盤として設計する
