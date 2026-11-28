# 開発ガイド

komadoはmacOSホストとAndroidクライアントのモノレポです。要件と開発順序は[開発計画書](development-plan.md)、実装の構成は[アーキテクチャ](architecture.md)、検証状況と未検証事項は[互換性](compatibility.md)を参照してください。

## 環境

Xcode、JDK、Android SDK Platform 37、Androidエミュレーターとadbを用意します。Xcodeの選択先は`xcode-select -p`で確認できます。Command Line Toolsが選択されている場合は、以下の例のように`DEVELOPER_DIR`でXcodeを指定できます。

## ビルドとテスト

macOSのテストと開発用アプリのビルド：

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path macos
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./macos/build-app.sh
open macos/.build/komado.app
```

`build-app.sh --universal`はApple SiliconとIntel向け、`--release`は最適化したローカル確認用アプリを生成します。いずれもアドホック署名で、配布用の署名・公証は行いません。`swift package clean`は`.build/`内のアプリも削除するため、必要な場合は先に退避してください。

Androidのビルド、JVMテスト、lint：

```sh
./android/gradlew -p android :core:test :security:lintDebug :app:lintDebug :app:assembleDebug
```

Androidのエミュレーターまたは実機を起動し、テスト用APKをビルドして既存のアプリを保持したままインストールします。以下のシリアルは例です。

```sh
./android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest :security:assembleDebugAndroidTest
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 install -r android/security/build/outputs/apk/androidTest/debug/security-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w me.mallows.komado.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w me.mallows.komado.security.test/androidx.test.runner.AndroidJUnitRunner
```

Gradleの`connectedDebugAndroidTest`は実行後にアプリをアンインストールすることがあるため、既存データを残す場合は上記の手順を使います。Android 17以降でネットワークを使うテストにはローカルネットワーク権限が必要です。実行前の設定を確認し、必要な場合は`adb -s emulator-5554 shell pm grant me.mallows.komado android.permission.ACCESS_LOCAL_NETWORK`で付与します。

未署名リリースAPKは`./android/gradlew -p android :app:assembleRelease`で生成します。配布には署名が必要です。GitHub ActionsのCIは現在設定していません。

## 接続と操作

1. Macで画面収録を許可し、待ち受けを開始します。Androidから操作する場合は、Accessibility権限と「Androidからの操作を許可」も必要です。
2. Macに表示された接続先を選び、QRコードをAndroidで読み取るか、アドレス・ポート、フィンガープリントとペアリングコードを手入力します。
3. Androidで登録済みMacの画面またはウインドウを選びます。画面表示中にAndroidがバックグラウンドへ移ると接続を終了するため、復帰後は接続し直します。

「アプリを表示」または「アプリを操作」では、アイコン付きのアプリ一覧から選べます。選択したアプリのウインドウがある画面を表示し、他の画面にあるウインドウは含まれません。

表示中に通信が切れると再接続を試みます。操作を再開するにはMacで再び許可してください。Mac側で共有を停止した場合は再開しません。MacのIPアドレスを登録していて変更された場合はAndroidの登録済み接続先を更新してください。名前解決が使える環境では、接続先にホスト名も指定できます。

映像表示中の「表示対象を変更」から、同じ接続で画面やウインドウを選び直せます。「全画面表示」では操作欄を隠し、「全画面を終了」またはAndroidの戻る操作で通常表示に戻せます。

ウインドウの操作時は「ウインドウサイズを自動調整」を有効にすると、表示領域の縦横比に合わせてMac側のウインドウを調整します。解除・切断時は元のサイズと位置への復元を試みます。

Mac側で操作中のウインドウを手動リサイズした場合、次のポインター・キー入力時に接続を終了し、Androidが再接続を試みます。操作の再開にはMacで再び許可が必要です。

各アドレスは同じポートを使います。IPv6リンクローカルアドレスにはAndroid側のインターフェース名をゾーンとして指定する必要がある場合があります。Android 17以降は接続時にローカルネットワーク権限を要求し、拒否した場合もVPN経由の接続を試せます。

クリップボード共有はMac側で別途許可が必要です。自動同期は両端で有効にした場合のみ動作します。キー入力やウインドウ操作の制約は[通信プロトコル](protocol.md)と[セキュリティ](security.md)を参照してください。

Macのログイン時起動を有効にしても、通常は待ち受けを手動で開始します。待ち受け中にアプリが異常終了した場合は、次の起動時に待ち受けを再開します。Androidの「最終認証」と「接続を確認」は記録された時点の結果であり、現在のオンライン状態を保証しません。接続情報の往復時間も操作の画面反映までの遅延とは異なります。

開発時は通信仕様と双方の実装・共通テストデータを合わせて更新し、ビルド、エミュレーター、実機の検証結果を区別して記録します。署名鍵や認証情報はリポジトリに含めません。
