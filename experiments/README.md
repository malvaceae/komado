# Phase 0の検証

製品版の機能とは独立した検証コードです。ユーザーの画面や入力を通信する前に、映像取得と両WebRTCライブラリの相互接続を確認します。検証結果は[互換性](../docs/compatibility.md)に記録します。

## macOSでの映像取得

```sh
cd experiments/macos-webrtc
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run CaptureProbe
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run CaptureProbe --screen
```

最初のコマンドは生成したフレームを使います。`--screen`は画面収録権限がある場合にメインディスプレイを3秒間キャプチャします。どちらもWebRTCの映像トラックで受け取ったフレーム数のみを表示し、映像を保存・表示・送信しません。権限がない場合は要求せず終了します。

## macOSとAndroidエミュレーターの相互接続

Xcode、JDK、Android SDK Platform 37、Androidエミュレーター、adbが必要です。エミュレーターを起動したうえで、リポジトリのルートから実行します。

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build --package-path experiments/macos-webrtc --product ExchangeProbe
./experiments/android-webrtc/gradlew -p experiments/android-webrtc :app:assembleDebug :app:lintDebug
python3 experiments/run-exchange-probe.py emulator-5554
python3 experiments/run-exchange-probe.py emulator-5554 --screen
```

`emulator-5554`はローカルの対象エミュレーターのシリアルに置き換えます。このスクリプトは実機を拒否し、検証用アプリをインストールして起動します。最初は生成映像を使い、`--screen`では画面収録権限がある場合にメインディスプレイを5秒間キャプチャしてエミュレーターへ送信します。いずれもAndroid側で10フレーム以上の受信と固定メッセージのDataChannelによる応答を確認し、受信コーデックを表示します。実画面の検証は1280×720の範囲に収まるよう縦横比を維持して縮小します。これは検証用の設定であり、製品の画質上限ではありません。

シグナリング情報はMacの一時ディレクトリとAndroidアプリのプライベート領域の間でadbを使って受け渡し、終了時に削除します。待ち受け用のシグナリングサーバーは起動しません。映像通信はエミュレーターのネットワークを使用します。この検証は製品用の認証・暗号化済みシグナリングの実装や検証ではなく、入力イベントは送信しません。`--screen`の映像は検証用エミュレーターに表示し、ファイルには保存しません。
