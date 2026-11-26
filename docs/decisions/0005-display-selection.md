# ADR 0005: ディスプレイの選択

- 状態: 採用
- 日付: 2026-11-27

## 背景

認証済みAndroidから表示するMacの画面を選ぶ。従来のメイン画面を表示する操作は維持し、選択画面が変更・消失した場合に別の画面を意図せず共有しないようにする。

## 選択肢と検証結果

macOS標準のCore Graphicsは描画可能なディスプレイのIDとピクセルサイズを列挙できる。ScreenCaptureKitはキャプチャ対象として実際に利用可能なディスプレイを取得する。前者で認証後の選択肢を返し、後者で映像開始時に対象の存在を確認する初期実装を、画面1台のMacとAndroidエミュレーターで検証した。

両一覧は同一とは限らないため、選択肢に載っていてもキャプチャ開始に失敗する場合がある。複数画面、ミラーリング、表示構成の変更時は実機で未検証。

## 決定と理由

新規依存を加えず、Core Graphicsの一覧からIDとサイズを取得し、既存の認証済みTLS上でAndroidに渡す。Androidは同一接続で1画面を選択し、Macは一覧を再取得して同一であることとIDの存在を確認してから開始する。従来のメイン画面開始メッセージも残す。

## 影響・制約・未解決事項

- 一覧は1接続につき1回、最大64画面とし、上限超過時は一部を省略せず失敗する。
- Androidには番号とピクセルサイズを表示する。同サイズの画面を見分けにくいため、名前の取得と表示は今後の課題とする。
- 開始要求後の構成変更は既存の画面環境通知で接続を終了するが、画面変更時の全ての競合を検出できるとは保証していない。
- 旧Macアプリには一覧取得のメッセージがないため、新Androidからの画面選択は失敗する。従来のメイン画面表示を使用できる。

## 関連資料

- [通信プロトコル](../protocol.md)
- [互換性](../compatibility.md)
- [Apple: CGGetActiveDisplayList](https://developer.apple.com/documentation/coregraphics/cggetactivedisplaylist(_:_:_:))
- [Apple: SCDisplay](https://developer.apple.com/documentation/screencapturekit/scdisplay)
