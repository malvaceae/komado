# macOS WebRTC導入検証

Phase 0の技術検証用です。候補のstasel/WebRTCをSwift Package Managerで取得し、macOS 13を最低対象とする実行ファイルにリンクして、`RTCPeerConnectionFactory`の生成を確認します。製品版のホスト、映像転送、セキュリティ機能ではありません。

```sh
cd experiments/macos-webrtc
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run WebRTCProbe
```

配布物の取得にはネットワーク接続が必要です。`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build --arch x86_64`でIntel向けビルドも確認できます。Xcode 27.2ではx86_64の非推奨警告が出ます。

この確認だけではIntel実機、macOS 13、Androidとの接続、映像・音声やハードウェア処理の動作は確認できません。検証結果は[互換性](../../docs/compatibility.md)に記録します。

## 映像フレームの受け渡し

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run CaptureProbe
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run CaptureProbe --screen
```

最初のコマンドは生成したフレームを使います。`--screen`は画面収録権限がある場合にメインディスプレイを3秒間キャプチャします。どちらもWebRTCの映像トラックで受け取ったフレーム数のみを表示し、映像を保存・表示・送信しません。権限がない場合は要求せず終了します。
