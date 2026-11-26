# 互換性

以下は対応候補であり、**対応範囲の確定に必要な検証は完了していません**。依存ライブラリとOS APIの要件を調査し、実際のビルドと動作検証を経て確定します。

| 対象 | 初期候補 | 状態 |
|---|---|---|
| macOS | macOS 13以降 | 未検証 |
| Mac CPU / 配布 | Apple Silicon・Intel、Universal Binary | 未検証 |
| Android | Android 8.0 / API 26以降 | 未検証 |
| Android ABI | arm64-v8a・armeabi-v7a | 未検証 |
| Android ABI | x86・x86_64：依存が対応し追加実装が不要なら対応 | 未検証 |
| 端末 | Androidスマートフォン・タブレット | 未検証 |
| 接続 | Tailscale、1ホストに1クライアント | 未検証 |

最新のSDKでビルドすることと、最低対応OSは別に扱います。共通実装で対応可能な環境を意図的に除外せず、古い環境のためだけの複雑な代替実装や旧依存への固定は原則避けます。機能単位の制限で基本機能を維持できる場合はその方法を検討します。

## 検証結果の記録

結果を追加する際は、日付、アプリ・依存バージョン、OS、CPU / ABI、端末、ネットワーク、対象機能、ビルド・エミュレーター・実機の別、結果、制約、再現手順または証跡を記載します。実機がない場合はビルドや依存バイナリの確認結果と実機動作を分けて記録します。

| 日付 | 環境・バージョン | 対象 | 検証方法 | 結果・制約・証跡 |
|---|---|---|---|---|
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Command Line ToolsのmacOS 27.0 SDK | macOS 13をターゲットとするScreenCaptureKitの基本API | Swiftの型検査（arm64 / x86_64） | 両ターゲットで成功。アプリのビルド・実行やmacOS 13実機の検証ではない |
| 2026-11-27 | stasel/WebRTC 158.0.0 | macOS向けバイナリのCPU | 配布XCFrameworkのInfo.plist確認 | macOS用にarm64とx86_64を含む |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | macOS 13を最低対象にしたWebRTCライブラリの導入 | [検証用プログラム](../experiments/macos-webrtc/README.md)のビルド・実行 | arm64でビルド・実行、RTCPeerConnectionFactoryの生成に成功。x86_64でもビルド成功。生成バイナリと両WebRTCスライスの最低macOSは13.0と確認。macOS 13・Intelでの実行やAndroidとの相互接続は未検証 |
| 2026-11-27 | io.getstream:stream-webrtc-android 1.3.10 | Android ABIと最低APIの宣言 | Maven CentralのAARの内容・マニフェスト確認 | arm64-v8a、armeabi-v7a、x86、x86_64のネイティブライブラリを含み、minSdkVersion 21を宣言。ビルド・実行・Android API 26での動作は未検証 |

| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | ScreenCaptureKitからWebRTCへの映像フレーム | [CaptureProbe](../experiments/macos-webrtc/README.md#映像フレームの受け渡し)でメインディスプレイを3秒間キャプチャし、映像トラックの受信数を確認 | 89フレームを受信。画面内容の保存や通信は行っていない。Intel実機・macOS 13・エンコード・転送は未検証 |
| 2026-11-27 | Android 16 API 36、arm64エミュレーター、io.getstream:stream-webrtc-android 1.3.10 | WebRTCとSurfaceViewRenderer | [Android検証アプリ](../experiments/android-webrtc/README.md)の起動・ログ確認 | 初期化に成功。実機、API 26、映像の受信・描画、相互接続は未検証 |

最低対応環境と最新環境、Apple Silicon / Intel、ARM64 / ARM32、画面・ネットワーク・OS状態の組み合わせは[テスト計画](testing.md)に従って検証します。対応範囲を変更する場合は具体的な技術的理由を記録します。
