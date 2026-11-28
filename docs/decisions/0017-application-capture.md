# ADR 0017: アプリ単位の表示と操作

- 状態: 採用
- 日付: 2026-11-28

## 背景

一つのアプリの複数ウインドウを表示し、対象を切り替えずに操作する。別アプリへの誤入力は避ける。

## 選択肢と検証結果

ScreenCaptureKitの[アプリ指定フィルター](https://developer.apple.com/documentation/screencapturekit/sccontentfilter/init%28display%3Aincluding%3Aexceptingwindows%3A%29)は、指定アプリのウインドウを一つのディスプレイ上で取得する。別プロセスのテストアプリの2ウインドウがAndroidに表示され、アプリへの入力が届くことと、手前の別アプリへの入力が拒否されることを確認した。

## 決定と理由

認証後にScreenCaptureKitのアプリ一覧をページ単位で送り、アイコンはAndroidが表示する候補から必要に応じて取得する。選択画面から一覧を手動で再取得できる。開始時は送信済みのプロセスIDとバンドル識別子を再照合する。アプリの画面上のウインドウを含むディスプレイを選び、なければメイン画面を使う。ポインター入力時はAccessibilityで最前面の要素のプロセスIDを取得し、対象アプリと照合する。メニュー項目はウインドウ情報を持たない場合がある。キーボード入力時はフォーカスしたウインドウを照合する。

未起動アプリは[NSMetadataQuery](https://developer.apple.com/documentation/foundation/nsmetadataquery)でSpotlightの索引から選び、Mac側の操作許可を確認して[NSWorkspace](https://developer.apple.com/documentation/appkit/nsworkspace)で起動する。候補のIDは接続中の一覧に限り有効で、Androidからファイルパスは指定できない。名前が重複する候補には場所を表示する。起動後は起動中の一覧を再取得する。

## 影響・制約・未解決事項

取得対象は一つのディスプレイで、別の画面にあるウインドウは表示されない。アプリ終了やプロセスIDの再利用などの競合を完全には防げない。アイコンが取得できないアプリは標準の代替アイコンを表示する。テストアプリのコンテキストメニューとモーダルダイアログがMacの映像に含まれ、Androidエミュレーターから操作できることを確認した。メニューバーはアプリ指定フィルターの標準設定では映らない。macOS 14.2以降の[設定](https://developer.apple.com/documentation/screencapturekit/sccontentfilter/includemenubar)で有効にすると別アプリが前面のときもそのメニューバーが映るため、表示・操作には画面全体への切り替えを使う。Androidエミュレーターから画面全体に切り替え、テストアプリのメニューバーの項目を選択できることを確認した。アプリ外に生成されるメニュー・ダイアログ、一般アプリ、実機、複数ディスプレイは未検証である。

起動候補はSpotlightの索引に依存するため、索引にないアプリは表示されない。起動の受理後にアプリのウインドウが現れるまで時間がかかる場合は、起動中の一覧を更新する。API 37で一時的なアプリについて、操作許可なしの拒否と許可後の起動・表示を確認した。一般アプリ、索引が無効な環境とAndroid実機は未検証である。

## 関連資料

[ウインドウの選択](0006-window-selection.md)、[ウインドウ単位の操作](0009-window-control.md)、[通信プロトコル](../protocol.md)、[互換性](../compatibility.md)
