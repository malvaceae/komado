# Android WebRTC導入検証

Phase 0の技術検証用です。WebRTCライブラリと描画用の`SurfaceViewRenderer`を初期化します。ネットワーク接続や遠隔操作は行いません。製品版のAndroidアプリではありません。

必要な環境はJDK、Android SDK Platform 37です。Gradle WrapperとAndroid Gradle Pluginのバージョンはプロジェクト内で固定しています。

```sh
cd experiments/android-webrtc
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n me.mallows.komado.probe/.MainActivity
adb logcat -d -s KomadoProbe:I '*:S'
```

実機とエミュレーターが複数接続されている場合は、各`adb`コマンドに対象を指定してください。`WebRTC and renderer initialized`が表示されたら初期化成功です。互換性や相互接続の検証結果は[互換性](../../docs/compatibility.md)を参照してください。
