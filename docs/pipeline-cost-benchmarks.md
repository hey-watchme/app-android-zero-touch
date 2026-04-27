# Pipeline Cost Benchmarks

この文書は、パイプライン再処理・検証実験のコスト・トークン目安を記録する。
都度比較検証のために更新していく。

最終更新: `2026-04-11`

---

## データ規模（2026-04-11 時点）

`device_id = virtual-amical-2026-03-28-import`（28日 curated 3 spots）

| 項目 | 件数 | 備考 |
|-----|------|------|
| sessions（Card）| 109 | 平均 54 chars/件、合計 5,862 chars |
| topics | 40 | 平均 113 chars/件（summary）|
| high_value_topics（importance >= 3）| 37 | Annotation 対象 |
| facts | 65 | 平均 49 chars/件 |

---

## 系統別トークン試算

### 系統1: DB backend 再処理（Phase 1 + Phase 2 のみ）

DBに入っている topic/fact を再スコアリング・再アノテーションする。
コンテキスト注入の効果を最小工数で確認するための最初のステップ。

| 処理 | 件数 | 1件あたり（tokens）| 合計（tokens）|
|-----|------|-----------------|-------------|
| Phase 1 Scoring | 40 topics | ~500 | ~20,000 |
| Phase 2 Annotation | 37 topics | ~1,000 | ~37,000 |
| **合計** | | | **~57,000** |

コスト目安（gpt-4.1-mini）: **$0.03〜0.05**

### 系統2: ファイルベース全パイプライン再実行

28日（フルデイ: 241発話・113 topic）＋ 29日（3 spots）を全ステップ再処理。
コンテキスト注入の効果をエンドツーエンドで確認するステップ。

| ステップ | スクリプト | 処理 | tokens目安 |
|---------|----------|------|----------|
| distilled_items抽出 | `extract_distilled_items.py` | 113 topics × LLM 1回 | ~62,000 |
| canonical化 | `canonicalize_distilled_items.py` | 169 items → まとめ1回 | ~10,000 |
| spot wrapup | `generate_spot_wrapup.py` | 3〜6 spots × LLM 1回 | ~15,000 |
| daily rollup | `generate_daily_rollup.py` | 2日分 × 1回 | ~10,000 |
| action candidate 抽出 | 今後実装 | 2日分 × 1回 | 未計測 |
| **合計** | | | **~97,000 + action candidate 分** |

コスト目安（gpt-4.1-mini）: **$0.10〜0.15**

---

## コンテキスト注入による追加トークン

各処理にコンテキストを注入する場合、1呼び出しあたり以下が追加される。

| コンテキスト内容 | 追加 tokens 目安 |
|---------------|----------------|
| prompt_preamble（約 300 chars）| +75 tokens |
| account_context + analysis_context（圧縮版）| +150 tokens |
| 合計追加 | **+200〜250 tokens/call** |

系統1（77 呼び出し）での追加コスト: **+15,000〜20,000 tokens**（誤差範囲内）

---

## 実績記録

| 日付 | 処理 | モデル | 実トークン | 実コスト | 備考 |
|-----|------|--------|---------|--------|------|
| 2026-04-11 | 系統1 context注入再処理（予定）| gpt-4.1-mini | TBD | TBD | コンテキスト初回注入 |

実行後に実績値を記入する。

---

## 参照

- パイプライン設計の正本: [knowledge-pipeline-v2.md](./knowledge-pipeline-v2.md)
- Amical 実験データ詳細: [amical-longterm-memory-handoff.md](./amical-longterm-memory-handoff.md)
