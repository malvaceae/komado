# 互換性

以下は対応候補であり、**対応範囲の確定に必要な検証は完了していません**。依存ライブラリとOS APIの要件を調査し、実際のビルドと動作検証を経て確定します。

| 対象 | 初期候補 | 状態 |
|---|---|---|
| macOS | macOS 13以降 | 最低OSを13としたビルド済み。macOS 13での実行は未検証 |
| Mac CPU / 配布 | Apple Silicon・Intel、Universal Binary | 両CPUを含む開発用アプリをビルド済み。Intel実機・配布は未検証 |
| Android | Android 8.0 / API 26以降 | API 26・36のarm64エミュレーターで自動テスト済み。実機は未検証 |
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
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | 製品の開発用アプリのUniversal Binary | `build-app.sh --universal`でビルドし、実行ファイルと同梱フレームワークのアーキテクチャ、最低OS、アドホック署名を確認 | 両方ともarm64とx86_64を含み、最低OSは13.0。Intel Mac・macOS 13での実行、配布用署名・公証は未検証 |
| 2026-11-27 | stasel/WebRTC 158.0.0 | macOS向けバイナリのCPU | 配布XCFrameworkのInfo.plist確認 | macOS用にarm64とx86_64を含む |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | macOS 13を最低対象にしたWebRTCライブラリの導入 | 初期化確認用プログラムのビルド・実行 | arm64でビルド・実行、RTCPeerConnectionFactoryの生成に成功。x86_64でもビルド成功。生成バイナリと両WebRTCスライスの最低macOSは13.0と確認。macOS 13・Intelでの実行やAndroidとの相互接続は未検証 |
| 2026-11-27 | io.getstream:stream-webrtc-android 1.3.10 | Android ABIと最低APIの宣言 | Maven CentralのAARの内容・マニフェスト確認 | arm64-v8a、armeabi-v7a、x86、x86_64のネイティブライブラリを含み、minSdkVersion 21を宣言。ビルド・実行・Android API 26での動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | ScreenCaptureKitからWebRTCへの映像フレーム | [CaptureProbe](../experiments/README.md#macosでの映像取得)でメインディスプレイを3秒間キャプチャし、映像トラックの受信数を確認 | 89フレームを受信。画面内容の保存や通信は行っていない。Intel実機・macOS 13・エンコード・転送は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | ウインドウ単位のキャプチャ・Accessibility操作 | 検証用の自プロセスのウインドウを取得し、AXで位置を変更・照合・復元後にキャプチャ | ウインドウを22フレーム取得。位置変更の照合と復元APIが成功。別アプリ、非アクティブ・最小化時、Intel実機およびmacOS 13は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | CGEventによるキー・Unicode入力 | 検証用ウインドウを持つ自プロセスにPID指定でイベントを送信 | キー押下と日本語・絵文字を含むテスト文字列を受信。Android IME、他アプリ、フォーカス制御、ショートカット、キー解放は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、macOS 26.7.3、Apple Silicon | Android KeystoreのP-256署名とmacOS側の検証 | エミュレーターの一時的なテストアプリで鍵を生成し、署名をCryptoKitで検証 | 正しい署名を受理し、変更したチャレンジを拒否。アプリ・鍵は削除済み。端末認証のプロトコル、実機およびハードウェア保護は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | ペアリング済み端末のKeychain保存 | 分離したテスト用項目で登録・取得・一覧・削除と不正な保存内容の拒否を確認 | Swiftの自動テストが成功。ペアリングやセッションとの連携、IntelおよびmacOS 13での動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、swift-certificates 1.21.2 | TLS用の鍵と自己署名証明書 | 分離したKeychain項目で生成・再取得と保存データや秘密鍵の欠損時の拒否を確認 | Swiftの自動テストが成功。TLS通信、Androidとの照合、IntelおよびmacOS 13での動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | Macのメニューバーアプリ初期版 | 開発用アプリのビルド、アドホック署名の検証、起動プロセスの確認 | ビルドと起動プロセスを確認。UI自動操作ツールがメニューバー常駐アプリへのアクセスでタイムアウトしたため、画面操作は未検証。IntelおよびmacOS 13での動作も未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター | TLS証明書フィンガープリント照合 | エミュレーター内のループバックTLS通信でテスト用鍵を生成し、正しいフィンガープリントと異なる値で接続 | 正しい値では通信成功、異なる値ではハンドシェイクを拒否。その時点ではMacとのTLS通信、ペアリング、Android実機およびAPI 26は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター、swift-certificates 1.21.2 | 両OS間のTLSと証明書照合 | Macで生成した証明書を使い、Macのループバック限定の一時的なTLS待ち受けにエミュレーターから接続 | 正しいフィンガープリントで通信し、異なる値での接続を拒否。テスト用の鍵・証明書・コードは削除。製品の接続、LAN・Tailscale経由、ペアリング、Android実機は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター | TLS上のペアリングと再認証 | Macのループバック限定の一時的な待ち受けにAndroid側の接続処理から接続し、誤った証明書、初回登録、再接続を確認 | 不一致の証明書を拒否し、登録と再認証に成功。テスト用の鍵・証明書・登録情報・待ち受けコードは削除。製品の待ち受け・画面、LAN・Tailscale経由、Android実機は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | インターフェースを指定したTLS待ち受け | Wi-Fiのローカルリンク限定待ち受けと、Tailscaleに割り当てられた`utun`の待ち受けを一時的に作り、同じMacからTLS接続を試行 | Wi-Fiのアドレスへの接続は成功。`utun`のアドレスへの同一Macからの接続はタイムアウトし、原因は未確定。別端末からのLAN・Tailscale接続や外部からの到達制限は未検証。テスト用の待ち受けコードと鍵は削除 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター | MacのTLS待ち受けとAndroidの初期認証 | Wi-Fiアドレスに固定したMac側待ち受けへ同じMac上のエミュレーターから接続し、不一致のフィンガープリント、初回登録、再認証を確認 | 不一致を拒否し、登録と再認証に成功。テスト用の鍵・登録情報と一時テストコードは削除。別端末のLAN・Tailscale接続、画面との統合、WebRTCは未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター | 手動ペアリング画面とMacの待ち受け管理 | Macアプリの待ち受け・コード発行処理をテスト用の保存領域で起動し、Androidアプリの画面から誤ったフィンガープリント、正しい情報での登録、接続確認を操作 | 誤った値を拒否し、ペアリングと再認証に成功。Mac画面は自動操作ツールがタイムアウトしたため未確認。別端末のLAN・Tailscale接続、IPv6とWebRTC、Android実機は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター | 認証済みTLSでのSDP送受信 | Mac側待ち受けとAndroid接続処理でペアリング後、テスト文字列のOfferとAnswerを送受信 | 送受信に成功。WebRTCが生成した実際のSDP、ICE・DTLS接続、別端末のLAN・Tailscale経由は未検証。テスト用の鍵と一時テストコードは削除 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター、stasel/WebRTC 158.0.0・io.getstream:stream-webrtc-android 1.3.10 | 製品アプリへのWebRTC組み込み | Macアプリのビルド・署名検証とWebRTCファクトリーの生成、Androidアプリのエミュレーターテスト | 両OSでファクトリーの生成に成功。Macアプリにフレームワーク、Android APKに4 ABIのネイティブライブラリを同梱。製品アプリ内でのメディア接続、他OS・実機での動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | 製品の画面取得処理 | テストランナーに許可された画面収録権限でメインディスプレイを取得し、WebRTC映像トラックへのフレーム到達と停止を確認 | フレーム到達と停止後の受信停止に成功。画面内容の保存・転送は行っていない。アプリ画面からの起動、ウインドウ単位の取得、他OS・Intel・Androidへの転送は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | 画面フレームのコンテンツ領域 | 取得したフレームの`contentRect`と`scaleFactor`を確認。通常の出力と正方形の出力を試した | 通常出力ではコンテンツが全体を占め、正方形出力では余白が生じた。実コンテンツ領域を使う座標変換と余白の拒否を単体テストした。別ディスプレイ・OS、回転、実際の入力座標の一致は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | クリックイベントの生成 | テスト用の送信先を使い、権限・セッション状態・連番を検査して左右・中ボタンの押下と解放を生成 | イベントの種類と座標を確認。実際のイベント投稿、Accessibility権限の付与、フォーカス、画面ロック時の動作は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | アイドルスリープの抑制 | 製品のアクティビティを作成し、`pmset -g assertions`で生成と明示的解除・解放時の解除を確認 | 抑制要求の作成と解除を確認。実際に時間経過でスリープしないこと、明示的スリープ、画面転送との連動、別OS・Intel・MacBookの蓋を閉じた状態は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2 | 画面共有中の表示 | メニューバー・ウインドウへの状態表示を追加してビルドし、待ち受け開始・停止の既存テストを実行 | ビルドとテストは成功。画面共有時の表示切り替え、メニューバーアイコンとウインドウの見た目は未検証 |
| 2026-11-27 | 同上 | 確定文字列のキーイベント生成 | テスト用の送信先を使い、日本語・絵文字と長い文字列をUnicodeキーイベントとして生成 | イベント内の文字列を読み戻して一致を確認。イベントは投稿しておらず、実際のアプリへの入力、文字変換、フォーカス、画面ロック時の動作は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、WebRTC | DTLSフィンガープリントの不一致 | エミュレーター内の2つのピアでOfferのフィンガープリントを改変して接続 | DataChannelは開かず接続が失敗し、Androidの製品セッションが失敗を通知した。Mac側の接続失敗通知は模擬テストのみ。Mac・Android間や実機・実ネットワークの改変試験は未実施 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、WebRTC | 映像接続の待機期限 | Answer生成後に相手側が接続を進めない状態を作る | Android側で20秒後に失敗を通知して終了した。実機や高遅延ネットワークでは未検証 |
| 2026-11-27 | Android 8.0 API 26 arm64エミュレーター、io.getstream:stream-webrtc-android 1.3.10 | 製品の認証・映像関連テスト | `:security:connectedDebugAndroidTest :app:connectedDebugAndroidTest`を実行 | 認証・プロトコル12件、アプリ13件が成功。エミュレーター内のWebRTC接続・生成映像の描画を含む。Macとの接続、実際のIME、実機、別ABIは未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Xcode 27.2、stasel/WebRTC 158.0.0 | 製品のWebRTCセッション | 映像トラックとDataChannelを持つOfferの生成、再生成・終了後のAnswerの拒否をテスト | Offerの生成と状態制御に成功。製品セッションとAndroid間のAnswer交換・接続・画面転送は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター | IMEの未確定文字列と確定文字列 | 一時的なテスト用IMEからInputConnectionへ変換途中の文字列と日本語・絵文字を含む確定文字列を送信 | 両方の変換途中イベントと確定文字列を受信し、エディターの結果も一致。元のIMEを復元。実際の日本語IME、Macへの転送および実機は未検証 |
| 2026-11-27 | Android 16 API 36 arm64エミュレーター、Androidアプリ初期版 | 接続先のローカル保存 | デバッグ版を起動し、接続先の追加、再起動後の表示と削除を操作 | 確認した範囲で成功。編集の保存、実機、API 26、ペアリングと接続は未検証 |
| 2026-11-27 | Android 16 API 36、arm64エミュレーター、io.getstream:stream-webrtc-android 1.3.10 | WebRTCとSurfaceViewRenderer | Android検証アプリの起動・ログ確認 | 初期化に成功。実機、API 26、映像の受信・描画、相互接続は未検証 |
| 2026-11-27 | Android 16 API 36、arm64エミュレーター、io.getstream:stream-webrtc-android 1.3.10 | 製品の映像描画処理 | 生成した映像フレームをWebRTC映像トラックからアプリのSurfaceViewRendererに渡し、描画イベントとリソース解放を確認 | 描画イベントを受信し、トラックの解除と描画リソースの解放に成功。Macからの受信、見た目、画面回転、Android実機では未検証 |
| 2026-11-27 | 同上 | 製品のAndroid WebRTCセッション | エミュレーター内の送信側とOffer・Answerを交換し、選択されたローカル候補の確認、生成映像の受信、不正なOfferの拒否をテスト | 指定アドレスの選択後に映像を受信し、不正なOfferを拒否。Macの製品セッション、認証済みシグナリング、実機、ネットワーク変更時の動作は未検証 |
| 2026-11-27 | Android 16 API 36、arm64エミュレーター | IMEの確定文字列処理 | InputConnectionに変換中・確定・変換終了・接続終了を模擬して渡す | 変換中は送らず、確定時に一度だけ通知し、送信失敗時と接続終了時の挙動を確認。実際のキーボードアプリ、画面、Macへの入力、Android実機は未検証 |
| 2026-11-27 | macOS 26.7.3、Apple Silicon、Android 16 API 36 arm64エミュレーター、stasel/WebRTC 158.0.0・io.getstream:stream-webrtc-android 1.3.10 | 異なるWebRTC配布物の接続 | [相互接続検証](../experiments/README.md#macosとandroidエミュレーターの相互接続) | 生成映像とメインディスプレイの実画面をそれぞれ10フレーム以上受信し、H.264とDataChannelの往復を確認。Tailscale経由、Android実機およびハードウェア処理は未検証 |
| 2026-11-27 | 同上 | ICE候補を絞った相互接続 | 一時的な検証でMacのOfferをWi-Fiアドレスの候補、AndroidのAnswerをMacへの経路で選択されたアドレスの候補に絞り、生成映像とDataChannelを確認 | H.264映像を10フレーム以上受信し、DataChannelの往復に成功。SDPの候補を除くだけではWebRTCが別のインターフェースを使わないことを保証できない。Tailscale、別端末、製品アプリでの経路制限は未検証 |
| 2026-11-27 | 同上 | ICE接続後の選択候補 | 一時的な検証でWebRTCの統計から選択された候補を確認。別途MacのWi-FiアドレスへのTCP接続で相手のアドレスを取得 | 候補を絞らない場合はMac側の候補がWi-Fiと異なり、絞った場合は一致した。Android側の候補はpeer-reflexiveで、どちらの場合もTCP接続元とは異なった。エミュレーター内の結果であり、他環境の経路固定や接続元一致を保証しない |
| 2026-11-27 | 同上 | 製品のSDP候補制限処理 | 検証用WebRTCが生成したOfferとAnswerを、両OSの実装でWi-Fi経路のアドレスに絞って交換 | 両実装の出力が一致し、生成映像の受信とDataChannelの往復に成功。製品のシグナリングとの接続、IPv6、Tailscale、別端末は未検証。候補制限はソケットの経路固定を保証しない |
| 2026-11-27 | 同上 | WebRTCの選択候補変更通知 | 候補を絞った検証用接続でmacOS側の選択候補変更コールバックを確認 | 通知されたローカル候補がWi-Fiアドレスと一致。変更時の遮断、Android側の通知、Tailscaleや実機では未検証 |
| 2026-11-27 | 同上 | 製品の両OS WebRTCセッション | 一時的なテストで両OSのセッション間のSDPをローカルファイルとadbで交換し、選択候補を照合して生成映像を送信 | Androidで10フレーム以上を受信。エミュレーターのNATによりAndroid側の選択候補はpeer-reflexiveとなり、候補の関連アドレスがシグナリング相当のローカルアドレスと一致した。製品シグナリング、実画面、別端末、Tailscaleは未検証。テストコードと一時ファイルは削除 |
| 2026-11-27 | 同上 | 認証から実画面の表示まで | 一時的なテストでMacの待ち受け管理とAndroidアプリを使い、Wi-FiアドレスへのTLS接続、ペアリング、再認証、画面転送の開始、描画イベント、画面を閉じた後の切断を確認 | メインディスプレイの描画イベントと切断を確認。Mac画面の目視、Androidの見た目、別端末、Tailscale、Android実機、操作・音声は未検証。テスト用の登録情報・鍵・一時テストコードを削除 |
| 2026-11-27 | 同上 | 認証後の画面表示とキャプチャ通知 | 両アプリの認証・WebRTCセッションを使い、Mac実画面の描画とDataChannelでのリビジョン通知、Android画面を閉じた後の切断を一時的なテストで確認 | 描画イベント、リビジョン`1`の受信、切断を確認。実際の操作、画面ロック・構成変更、別端末、Tailscale、Android実機は未検証。テスト用の登録情報・鍵・一時テストコードを削除 |
| 2026-11-27 | 同上 | DataChannelによるクリック情報の交換 | 一時的なテストで両OSのWebRTCセッションを接続し、キャプチャ通知後にクリック情報をAndroidからMacのテスト用受信処理へ送信 | クリック情報を受信し、期待値を確認。Macへの実入力は行っていない。テスト用シグナリングはローカルファイルとadbを使用し、別端末、Tailscale、Android実機は未検証。テストコードと一時ファイルは削除 |

実画面の相互接続検証では、1280×800の映像でVideoToolboxが`kVTParameterErr`（-12902）を返し、エンコードされなかった。1152×720に縮小するとエンコード・受信に成功した。失敗時のH.264設定は`profile-level-id=42e01f`（Level 3.1）で、1280×800は4,000マクロブロックと、[WebRTCのLevel 3.1の上限](https://webrtc.googlesource.com/src/+/fb1c41a93d92d4188f068f437d80ef2c4b50c3a9/api/video_codecs/h264_profile_level_id.cc)である3,600を超えるため、これが一因の可能性がある。エラーの原因は確定しておらず、製品の解像度上限としては扱わない。

stasel/WebRTC 158.0.0のmacOS向け配布物では、`RTCPeerConnectionFactory.h`に`RTCAudioDevice`の前方宣言はあるが、プロトコル定義のヘッダーは含まれていない。`RTCAudioSource`の公開ヘッダーにもPCMを供給するメソッドは見当たらない。システム音声をWebRTCに取り込む方法は未確定であり、利用可能なAPIや代替ライブラリの調査が必要。

最低対応環境と最新環境、Apple Silicon / Intel、ARM64 / ARM32、画面・ネットワーク・OS状態の組み合わせは[テスト計画](testing.md)に従って検証します。対応範囲を変更する場合は具体的な技術的理由を記録します。
