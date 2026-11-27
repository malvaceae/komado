# ADR 0014: ログイン時の起動

- 状態: 採用
- 日付: 2026-11-27

## 決定

macOS 13以降の公開API `SMAppService.mainApp` を使い、ユーザーが選んだ場合にアプリ本体をログイン項目に登録する。登録の有無とシステム設定での許可待ちはOSの状態を参照し、アプリが前面に戻った際に再確認する。登録に失敗した場合やアプリを特定できない場合は画面に表示する。

自動起動はアプリ本体に限る。待ち受けと操作の許可は自動的には開始しない。登録や解除はユーザーがアプリ画面で明示的に操作したときだけ行う。

## 検証と制約

状態の切り替えと失敗時の挙動を、OSの登録処理を差し替えた自動テストで確認する。実際のログイン項目登録、システム設定の操作、ログアウト後の起動、署名済み配布物は未検証である。

## 参考

- [Apple: SMAppService](https://developer.apple.com/documentation/servicemanagement/smappservice)
- [Apple: 許可待ち状態](https://developer.apple.com/documentation/servicemanagement/smappservice/status-swift.enum/requiresapproval)
