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
| 接続 | 同一LAN / Tailscale、1ホストに1クライアント | 未検証 |

最新のSDKでビルドすることと、最低対応OSは別に扱います。共通実装で対応可能な環境を意図的に除外せず、古い環境のためだけの複雑な代替実装や旧依存への固定は原則避けます。機能単位の制限で基本機能を維持できる場合はその方法を検討します。

## 検証結果の記録

結果を追加する際は、日付、アプリ・依存バージョン、OS、CPU / ABI、端末、ネットワーク、対象機能、ビルド・エミュレーター・実機の別、結果、制約、再現手順または証跡を記載します。実機がない場合はビルドや依存バイナリの確認結果と実機動作を分けて記録します。

| 日付 | 環境・バージョン | 対象 | 検証方法 | 結果・制約・証跡 |
|---|---|---|---|---|
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Command Line ToolsのmacOS 27.0 SDK | macOS 13をターゲットとするScreenCaptureKitの基本API | Swiftの型検査（arm64 / x86_64） | 両ターゲットで成功。アプリのビルド・実行やmacOS 13実機の検証ではない |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | macOS 13を最低対象としたUniversal Binary | CaptureProbeをarm64 / x86_64向けに同時ビルドし、lipoとMach-Oヘッダーを確認 | 2アーキテクチャを含み、両方の最低macOSは13.0。Intel MacおよびmacOS 13での実行は未検証 |
| 2026-11-27 | stasel/WebRTC 158.0.0 | macOS向けバイナリのCPU | 配布XCFrameworkのInfo.plist確認 | macOS用にarm64とx86_64を含む |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | macOS 13を最低対象にしたWebRTCライブラリの導入 | 初期化確認用プログラムのビルド・実行 | arm64でビルド・実行、RTCPeerConnectionFactoryの生成に成功。x86_64でもビルド成功。生成バイナリと両WebRTCスライスの最低macOSは13.0と確認。macOS 13・Intelでの実行やAndroidとの相互接続は未検証 |
| 2026-11-27 | io.getstream:stream-webrtc-android 1.3.10 | Android ABIと最低APIの宣言 | Maven CentralのAARの内容・マニフェスト確認 | arm64-v8a、armeabi-v7a、x86、x86_64のネイティブライブラリを含み、minSdkVersion 21を宣言。ビルド・実行・Android API 26での動作は未検証 |

| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | ScreenCaptureKitからWebRTCへの映像フレーム | [CaptureProbe](../experiments/README.md#macosでの映像取得)でメインディスプレイを3秒間キャプチャし、映像トラックの受信数を確認 | 89フレームを受信。画面内容の保存や通信は行っていない。Intel実機・macOS 13・エンコード・転送は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | ウインドウ単位のキャプチャ・Accessibility操作 | 検証用の自プロセスのウインドウを取得し、AXで位置を変更・照合・復元後にキャプチャ | ウインドウを22フレーム取得。位置変更の照合と復元APIが成功。別アプリ、非アクティブ・最小化時、Intel実機およびmacOS 13は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | CGEventによるキー・Unicode入力 | 検証用ウインドウを持つ自プロセスにPID指定でイベントを送信 | キー押下と日本語・絵文字を含むテスト文字列を受信。Android IME、他アプリ、フォーカス制御、ショートカット、キー解放は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、macOS 26.7.3、Apple Silicon | Android KeystoreのP-256署名とmacOS側の検証 | エミュレーターの一時的なテストアプリで鍵を生成し、署名をCryptoKitで検証 | 正しい署名を受理し、変更したチャレンジを拒否。アプリ・鍵は削除済み。端末認証のプロトコル、実機およびハードウェア保護は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | ペアリング済み端末のKeychain保存 | 分離したテスト用項目で登録・取得・一覧・削除と不正な保存内容の拒否を確認 | Swiftの自動テストが成功。ペアリングやセッションとの連携、IntelおよびmacOS 13での動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、swift-certificates 1.21.2 | TLS用の鍵と自己署名証明書 | 分離したKeychain項目で生成・再取得と保存データや秘密鍵の欠損時の拒否を確認 | Swiftの自動テストが成功。TLS通信、Androidとの照合、IntelおよびmacOS 13での動作は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター | TLS証明書フィンガープリント照合 | エミュレーター内のループバックTLS通信でテスト用鍵を生成し、正しいフィンガープリントと異なる値で接続 | 正しい値では通信成功、異なる値ではハンドシェイクを拒否。MacとのTLS通信、ペアリング、Android実機およびAPI 26は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター | IMEの未確定文字列と確定文字列 | 一時的なテスト用IMEからInputConnectionへ変換途中の文字列と日本語・絵文字を含む確定文字列を送信 | 両方の変換途中イベントと確定文字列を受信し、エディターの結果も一致。元のIMEを復元。実際の日本語IME、Macへの転送および実機は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、Androidアプリ初期版 | 接続先のローカル保存 | デバッグ版を起動し、接続先の追加、再起動後の表示と削除を操作 | 確認した範囲で成功。編集の保存、実機、API 26、ペアリングと接続は未検証 |
| 2026-11-27 | Android 16 API 36、arm64エミュレーター、io.getstream:stream-webrtc-android 1.3.10 | WebRTCとSurfaceViewRenderer | Android検証アプリの起動・ログ確認 | 初期化に成功。実機、API 26、映像の受信・描画、相互接続は未検証 |

| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター、stasel/WebRTC 158.0.0・io.getstream:stream-webrtc-android 1.3.10 | 異なるWebRTC配布物の接続 | [相互接続検証](../experiments/README.md#macosとandroidエミュレーターの相互接続) | 生成映像とメインディスプレイの実画面をそれぞれ10フレーム以上受信し、H.264とDataChannelの往復を確認。Tailscale経由、Android実機およびハードウェア処理は未検証 |

実画面の相互接続検証では、1280×800の映像でVideoToolboxが`kVTParameterErr`（-12902）を返し、エンコードされなかった。1152×720に縮小するとエンコード・受信に成功した。失敗時のH.264設定は`profile-level-id=42e01f`（Level 3.1）で、1280×800は4,000マクロブロックと、[WebRTCのLevel 3.1の上限](https://webrtc.googlesource.com/src/+/fb1c41a93d92d4188f068f437d80ef2c4b50c3a9/api/video_codecs/h264_profile_level_id.cc)である3,600を超えるため、これが一因の可能性がある。エラーの原因は確定しておらず、製品の解像度上限としては扱わない。

stasel/WebRTC 158.0.0のmacOS向け配布物では、`RTCPeerConnectionFactory.h`に`RTCAudioDevice`の前方宣言はあるが、プロトコル定義のヘッダーは含まれていない。`RTCAudioSource`の公開ヘッダーにもPCMを供給するメソッドは見当たらない。システム音声をWebRTCに取り込む方法は未確定であり、利用可能なAPIや代替ライブラリの調査が必要。

最低対応環境と最新環境、Apple Silicon / Intel、ARM64 / ARM32、画面・ネットワーク・OS状態の組み合わせは[テスト計画](testing.md)に従って検証します。対応範囲を変更する場合は具体的な技術的理由を記録します。
