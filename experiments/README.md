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
```

`emulator-5554`は対象エミュレーターのシリアルに置き換えます。このスクリプトは実機を拒否し、検証用アプリをインストールして起動します。macOSとエミュレーターでのみ生成映像と固定メッセージをやり取りし、Android側で10フレーム以上の受信とDataChannelによる応答を確認します。受信コーデックも表示します。

シグナリング情報はMacの一時ディレクトリとAndroidアプリのプライベート領域の間でadbを使って受け渡し、終了時に削除します。待ち受け用のシグナリングサーバーは起動しません。映像通信はエミュレーターのネットワークを使用します。この検証は認証・Tailscale経路制限の実装や検証ではなく、実際の画面や入力イベントを送信するものではありません。

## macOSのICE候補の確認

Tailscale接続中のMacで、上記の`ExchangeProbe`をビルドした後に実行します。

```sh
python3 experiments/check-macos-ice.py
```

通常設定、WebRTCのオプションでWi-Fi・Ethernet・携帯・ループバックを除外した場合、VPNを除外した場合について、ICE候補のインターフェースを比較します。IPアドレスやSDPは表示せず、一時ファイルは終了時に削除します。インターフェース名はローカルの情報とTailscaleのアドレスから判定し、WebRTCが報告するネットワーク種別も表示します。候補がTailscaleのアドレスに一致することだけでは、実際の経路や接続先の正当性を保証できません。
