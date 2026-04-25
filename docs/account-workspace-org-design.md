# Account / Workspace / Organization / Device 設計

作成日: 2026-04-24  
対象: ZeroTouch バックエンド（`backend/app.py` + migration 009, 014）

---

## データ構造（4層）

```
Organization（組織）
  ├─ organization_members（account ↔ org の紐付け、role: org_admin / org_member）
  └─ Workspace（1店舗 / 1現場 など）
      ├─ workspace_members（account ↔ workspace の紐付け、role: admin / editor / viewer）
      ├─ workspace_invitations（招待トークン）
      └─ Device（録音主体の物理端末）
            ├─ Sessions（カード）
            ├─ Topics（トピック）
            ├─ Facts（事実）
            └─ WikiPages（ナレッジ）
```

- **データの所有者は物理端末IDとしてのデバイス**。カード・トピック・Fact はすべて `device_id` に紐づく
- **WikiPage はワークスペース単位で集約**。配下の全デバイスのトピックから生成される
- **アカウントはデバイスを直接持たない**。Org → Workspace → Device の経路でアクセスする

## Device 方針

Android アプリにおける `device` は、ユーザーが選ぶデータソースではない。
その Android 端末が保持する `DeviceIdProvider.getDeviceId()` の値が正本であり、
録音、Home、Timeline、Wiki は常にこの物理端末IDを対象にする。

- `device_id`: 物理端末ID。録音データの主キー的な識別子で、表示名では置き換えない
- `display_name`: 人間が見分けるためのニックネーム。例: `redmi-001`
- Android UI: device 一覧選択はしない。現在端末のIDを表示し、必要ならニックネームだけ編集する
- `workspace_id`: 物理端末をどの現場・検証環境に属させるかを表す

Amical import などの検証データは、Android 録音端末として選ばせない。
必要な場合は検証用データソースとして別途扱い、Android の通常録音導線とは混ぜない。

---

## テーブル一覧

| テーブル | 用途 | migration |
|---------|------|-----------|
| `zerotouch_accounts` | ユーザーアカウント | 009 |
| `zerotouch_organizations` | 組織（上位テナント） | 014 |
| `zerotouch_organization_members` | Org ↔ Account の紐付け | 014 |
| `zerotouch_workspaces` | ワークスペース | 009 |
| `zerotouch_workspace_members` | WS ↔ Account の紐付け | 009, 014 |
| `zerotouch_workspace_invitations` | 招待トークン | 014 |
| `zerotouch_devices` | デバイス | 009 |
| `zerotouch_context_profiles` | WS ごとのコンテキスト設定 | 009, 010 |

---

## 権限モデル（現状）

### Organization レベル

| role | 意味 |
|------|------|
| `org_admin` | 配下の全 Workspace に暗黙 admin アクセス。Org 設定変更・メンバー管理可 |
| `org_member` | Org への所属のみ。Workspace へのアクセスは workspace_members で別途管理 |

> **注**: 現状、org_admin は `_workspace_ids_for_organizations()` 経由で配下 Workspace すべてへの読み書きアクセスを得る。  
> 権限チェックヘルパー（`_check_workspace_permission`）は未実装のため、app.py 側での厳格な施行は今後の課題。

### Workspace レベル

| role | 意味 |
|------|------|
| `admin` | WS 設定変更、メンバー管理、デバイス追加 |
| `editor` | データ閲覧・更新（デフォルト） |
| `viewer` | 閲覧のみ |

招待時のデフォルト role は `editor`。

---

## アクセス解決ロジック（`_workspace_ids_for_account`）

```
account_id が渡されたとき:
  1. zerotouch_organization_members から organization_id を取得
  2. その organization_id に紐づく全 workspace_id を取得（Org 経由）
  3. zerotouch_workspace_members から直接紐づく workspace_id も取得（後方互換）
  4. 1 ∪ 2 の和集合を返す
```

つまり、org_admin は手動で workspace_members に登録しなくても全 WS にアクセスできる。

---

## API エンドポイント一覧

### Organizations

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/api/organizations` | 一覧（`?account_id=` でフィルタ可） |
| POST | `/api/organizations` | 新規作成（作成者が自動で org_admin に） |
| PATCH | `/api/organizations/{id}` | 名前・slug 更新 |
| GET | `/api/organizations/{id}/members` | メンバー一覧 |
| POST | `/api/organizations/{id}/members` | 既存 Account を Org に追加 |
| DELETE | `/api/organizations/{id}/members/{account_id}` | Org から除名 |

### Workspaces

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/api/workspaces` | 一覧（`?account_id=` で Org 経由解決） |
| POST | `/api/workspaces` | 新規作成（`organization_id` 必須） |
| PATCH | `/api/workspaces/{id}` | 名前・説明更新 |
| GET | `/api/workspaces/{id}/members` | メンバー一覧 |
| POST | `/api/workspaces/{id}/members` | メンバー追加・ロール変更 |
| DELETE | `/api/workspaces/{id}/members/{account_id}` | メンバー除名 |

### 招待（テーブルのみ実装、API は未実装）

`zerotouch_workspace_invitations` テーブルは migration 014 で作成済み。  
以下の API は未実装（Phase 2a-② として残作業）:

- `POST /api/workspaces/{id}/invitations` — トークン発行
- `GET /api/invitations/{token}` — 招待詳細取得
- `POST /api/invitations/{token}/accept` — 受諾（Account 自動作成）

---

## 既存データ（Key IDs）

| 項目 | 値 |
|------|-----|
| Supabase project ID | `qvtlwotzuzbavrzqhyvt` |
| Amical Lab（Organization） | `ef098fa1-2036-4724-8614-0988c8ebb3c6` |
| Amical Lab（Workspace） | `6cbaeb05-9de6-4127-8b0a-dc0e46ac4046` |
| Amical Kaya（account） | `69437974-999a-4154-bdab-af0b052c012d` |
| 松本夏弥（account） | `0229f025-c74d-4ec6-8517-f8a8e7b8f056` |

---

## 残作業

| 優先度 | 内容 |
|--------|------|
| ✅ | Android: device 選択を廃止し、物理端末ID固定 + ニックネーム編集へ整理 |
| 🟡 | Phase 2a-②: 招待 API 実装（token 発行・受諾フロー） |
| 🟡 | Phase 2a-③: `_check_workspace_permission()` 権限チェックヘルパー追加 |
| 🟢 | Phase 4: Android 招待受諾画面・WS メンバー管理 UI |
