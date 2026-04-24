# ZeroTouch Docs Index

このディレクトリの入口です。次のセッションでゼロベースから理解するための最短経路を示します。

---

## ⚠️ まず最初にこれを読む

長期記憶パイプラインの理解は、次の 2 本を基準にします。

- **`amical-longterm-memory-handoff.md`**
  現在の作業引き継ぎ。次に何をやるかを再開するための最新メモ。
- **`knowledge-pipeline-v2.md`**
  長期記憶パイプラインの設計正本。Karpathy wiki 方式の整理と設計判断。

旧 v1 / stateful daily 系の設計文書は削除済みです。現在の判断は上の 2 本に寄せます。

---

## 最短で読む順番

1. **`amical-longterm-memory-handoff.md`**
   - 長期記憶 / 知識構築 / Amical → ZeroTouch import の最新引き継ぎ
   - 次に再開する手順

2. **`knowledge-pipeline-v2.md`**
   - パイプラインの設計正本
   - wiki スキーマ 3 軸の定義
   - 既存実装との対応

3. `wiki-project-category-page-design.md`
   - `theme` を `project > category > page` に切り替える設計
   - project を人間管理しつつ、category/page を整理する方針

4. `context-enrichment-project.md`
   - account / workspace / device / environment を分析前提に入れる設計（現在も有効）

5. `pipeline-cost-benchmarks.md`
   - 再処理コストの目安

6. `amical-validation-wrapup.md`
   - 初期検証の結果まとめ。履歴参照用

---

## ドキュメント一覧

### 現在有効

| ファイル | 内容 | 状態 |
|---------|------|------|
| `amical-longterm-memory-handoff.md` | 現在の作業引き継ぎ | ✅ 最新 |
| `knowledge-pipeline-v2.md` | 長期記憶パイプラインの設計正本 | ✅ 最新 |
| `wiki-project-category-page-design.md` | `project > category > page` 再設計 | ✅ 最新 |
| `account-workspace-org-design.md` | Account / Workspace / Organization 4層構造と権限モデル | ✅ 最新 |
| `context-enrichment-project.md` | コンテキスト軸の詳細設計 | ✅ 有効 |
| `pipeline-cost-benchmarks.md` | 再処理コスト・トークン目安 | ✅ 有効 |

### 実験・参照用

| ファイル | 内容 | 状態 |
|---------|------|------|
| `amical-validation-wrapup.md` | 実験パイプライン・生成物・結果のまとめ | 📦 参照用 |
| `amical-batch-reprocessing.md` | 安い LLM で大量ログを再処理する運用メモ | 📦 参照用 |

---

## Amical 検証

- `amical-longterm-memory-handoff.md` — 長期記憶・知識構築・DB import の最新状態
- `amical-validation-wrapup.md` — 実験パイプライン・生成物・実データの結果・次の一手

## Android / Ambient 運用

- `ambient-recording-debug-checklist.md`
- `ambient-agent-spec.md`
- `vad-improvement-plan.md`

## Context Enrichment

- `context-enrichment-project.md`
  - コンテクスト追加のスキーマ、入力導線、artifact 注入、段階導入計画
- `wiki-project-category-page-design.md`
  - project / category / page の分類再設計

## その他

- `Ambient_Context_Monopoly.pdf` — 関連調査資料
