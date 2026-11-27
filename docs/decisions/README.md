# 技術選定の記録（ADR）

主要な技術選定や変更について、検証結果と判断理由をここに記録します。[ADR 0001: 同一LANとTailscaleからの接続](0001-network-access.md)、[ADR 0004: QRペアリング](0004-qr-pairing.md)、[ADR 0005: ディスプレイの選択](0005-display-selection.md)、[ADR 0006: ウインドウの選択](0006-window-selection.md)、[ADR 0007: メイン画面の操作許可](0007-main-display-control.md)、[ADR 0008: 操作機能の確認](0008-feature-negotiation.md)、[ADR 0009: ウインドウ単位の操作](0009-window-control.md)、[ADR 0010: 選択したディスプレイの操作](0010-display-control.md)、[ADR 0011: 手動クリップボード共有](0011-manual-clipboard.md)、[ADR 0012: クリップボード自動同期](0012-automatic-clipboard.md)を採用し、[ADR 0002: 端末署名のプリミティブ](0002-device-signature.md)と[ADR 0003: シグナリングのTLSとホスト確認](0003-signaling-tls.md)を提案中です。開発計画書の技術スタックや最低対応環境は検証前の候補です。

`NNNN-短い題名.md`を作成し、少なくとも以下を記載します。

```markdown
# ADR NNNN: 題名

- 状態: 提案中 / 採用 / 却下 / 廃止 / 置き換え
- 日付: YYYY-MM-DD

## 背景

## 選択肢と検証結果

## 決定と理由

## 影響・制約・未解決事項

## 関連資料
```

採用後に判断を変える場合は、元の記録を残し、後継ADRとの関係を明示します。
