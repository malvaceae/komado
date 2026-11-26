# komado 開発計画書

## 1. 概要

komadoは、AndroidスマートフォンからMacを遠隔操作するためのネイティブリモートデスクトップアプリケーションである。

一般的なリモートデスクトップアプリのようにデスクトップ全体を操作するだけでなく、特定のアプリケーションやウインドウを個別に操作できる機能を提供する。

スマートフォンの画面サイズや縦横比に適した操作環境を提供し、外出先からのプログラミング、AIコーディング、ターミナル操作、その他のMacアプリケーションの利用を快適に行えることを目指す。

ネットワーク接続にはTailscaleを利用し、komado独自のクラウドインフラを必要としない構成とする。

## 2. 基本方針

- リポジトリ名は`komado`とする。
- macOSとAndroidに特化する。
- 両プラットフォームともネイティブアプリとして開発する。
- macOS側をホスト、Android側をクライアントとする。
- デスクトップ全体およびウインドウ単位のリモート操作に対応する。
- スマートフォンに適したUI・UXを重視する。
- 映像の低遅延、高画質、省電力性を重視する。
- ネットワーク接続には既存のTailscale環境を利用する。
- リアルタイム通信にはWebRTCを利用する。
- OS標準APIおよび既存ライブラリを積極的に活用する。
- 独自の映像コーデックや通信基盤は開発しない。
- 外部の有料APIやkomado専用のクラウドサービスに依存しない。
- 共通実装で対応可能なOSバージョンおよびCPUアーキテクチャを幅広くサポートする。
- 互換性維持のみを目的とした複雑な実装は避ける。
- macOSとAndroidのソースコードはモノレポで管理する。
- AIコーディングエージェントによる継続的な開発を前提とする。

## 3. 対応環境

### 3.1. 対応候補

| 項目 | 対応範囲 |
|---|---|
| ホストOS | macOS 13（Ventura）以降 |
| ホストCPU | Apple Silicon / Intel |
| macOSバイナリ | Universal Binary（arm64 / x86_64） |
| クライアントOS | Android 8.0（API 26）以降 |
| Android ABI | arm64-v8a / armeabi-v7a |
| Android端末 | スマートフォン・タブレット |
| ネットワーク | Tailscale |
| 同時接続 | 1ホストにつき1クライアント |

Androidのx86およびx86_64についても、依存ライブラリが対応しており追加実装が不要であれば対応する。開発時のエミュレーター利用も考慮する。

最低対応OSは、Tailscale、OS標準API、依存ライブラリの要件を踏まえた候補であり、実際のビルドおよび動作検証を経て確定する。

### 3.2. 互換性方針

特定のOSバージョンやCPUアーキテクチャに限定する合理的な技術的理由がない限り、対応環境を意図的に制限しない。

以下の方針に従う。

- OSバージョンやCPUアーキテクチャに依存しない共通実装を優先する。
- 最新の安定版SDK、コンパイラ、開発ツール、依存ライブラリを利用する。
- ビルドに使用するSDKのバージョンと最低対応OSのバージョンを区別する。
- 古いOSに対応するためだけに依存ライブラリを旧バージョンへ固定しない。
- 古いOSやCPUに特化した複雑な条件分岐、代替実装、独自実装は原則として行わない。
- 軽微なAPIの利用可否判定や機能の有効・無効の切り替えで対応できる場合は、積極的に対応する。
- 一部の追加機能が利用できないことだけを理由に、基本機能が動作する環境を除外しない。
- ハードウェアの性能差については、可能な限り標準APIやWebRTCの機能検出、画質調整によって吸収する。
- 対応環境を制限する必要が生じた場合は、具体的な技術的理由を記録する。
- 最低対応環境と最新環境の双方で動作検証を行う。
- 未検証の環境を動作保証済みとして扱わない。

### 3.3. macOSの互換性

macOS版はApple SiliconおよびIntel Mac向けのUniversal Binaryとしてビルドする。

Swift、SwiftUI、AppKit、ScreenCaptureKit、VideoToolbox、Accessibility API、Core Graphicsなど、両CPUアーキテクチャで利用可能な標準APIを中心に実装する。

CPUアーキテクチャごとの独自実装は原則として行わない。

以下については実機で確認する。

- ScreenCaptureKitによる画面キャプチャ
- WebRTCライブラリの動作
- H.264ハードウェアエンコード
- CPU・GPU使用率
- 長時間稼働時の安定性

macOS 13で利用できないAPIを採用する場合は、その必要性と互換性への影響を検討する。

### 3.4. Androidの互換性

Android版は`minSdk = 26`を初期候補とする。

`compileSdk`および`targetSdk`は開発時点の最新安定版SDKや配布要件を踏まえて設定する。

ARM64およびARM32に対応するため、WebRTCライブラリに含まれるネイティブバイナリのABIを確認する。

端末ごとの映像デコーダーの違いについては、可能な限りWebRTCおよびMediaCodecの標準的な機能検出によって対応する。

依存ライブラリの最低対応APIレベルが26を超える場合や、重大な互換性上の問題がある場合は、最低対応バージョンを再検討する。

## 4. システム構成

```text
Android Client
    │
    ├─ Jetpack Compose
    ├─ libwebrtc
    ├─ MediaCodec
    └─ Touch / Keyboard / IME
    │
    │ Tailscale
    │
    ├─ WebRTC Video
    ├─ WebRTC Audio
    ├─ WebRTC DataChannel
    └─ WebSocket Signaling
    │
macOS Host
    │
    ├─ SwiftUI / AppKit
    ├─ libwebrtc
    ├─ ScreenCaptureKit
    ├─ VideoToolbox
    ├─ Accessibility API
    └─ Core Graphics
```

macOS側でシグナリングサーバーを起動し、Android側から接続する。

映像、音声、操作イベントの通信はWebRTCで行う。

Tailscaleが端末間のネットワーク接続を提供するため、komado独自の認証サーバー、VPNサーバー、STUNサーバー、TURNサーバーは原則として設置しない。

ただし、Tailscale自体の接続状況によってはDERPなどの中継経路を利用する。

TailscaleのVPN機能をkomadoに組み込むことはせず、各端末にインストールされたTailscaleクライアントを利用する。

## 5. 技術スタック

### 5.1. macOS

| 項目 | 採用技術 |
|---|---|
| 開発言語 | Swift 6 |
| UI | SwiftUI / AppKit |
| IDE | Xcode |
| パッケージ管理 | Swift Package Manager |
| 非同期処理 | Swift Concurrency |
| リアルタイム通信 | libwebrtc |
| 画面・ウインドウ取得 | ScreenCaptureKit |
| 映像エンコード | VideoToolbox（libwebrtc経由） |
| 音声取得 | ScreenCaptureKit / Core Audio |
| 入力イベント生成 | Core Graphics（CGEvent） |
| ウインドウ制御 | Accessibility API（AXUIElement） |
| アプリケーション管理 | NSWorkspace |
| ネットワーク通信 | Network.framework |
| クリップボード | NSPasteboard |
| スリープ抑制 | IOKit |
| ログイン時の自動起動 | SMAppService |
| 設定保存 | UserDefaults |
| 認証情報保存 | Keychain |
| ログ | OSLog |
| テスト | Swift Testing / XCTest |
| フォーマッター | swift-format |

#### WebRTCライブラリ

第一候補としてstasel/WebRTCを採用する。

https://github.com/stasel/WebRTC

GoogleのWebRTCソースコードからビルドされたXCFrameworkを利用する。

macOS向けのarm64およびx86_64バイナリが提供されている。

以下を検証したうえで正式採用する。

- macOS 13以降との互換性
- Apple SiliconおよびIntel Macでの動作
- H.264ハードウェアエンコード
- 外部映像ソースの取り込み
- 外部音声ソースの取り込み
- ICEの制御可否
- Android側ライブラリとの相互接続性

### 5.2. Android

| 項目 | 採用技術 |
|---|---|
| 開発言語 | Kotlin |
| UI | Jetpack Compose |
| デザインシステム | Material 3 |
| IDE | Android Studio |
| ビルド | Gradle Kotlin DSL |
| アーキテクチャ | MVVM / UDF |
| 状態管理 | ViewModel / StateFlow |
| 非同期処理 | Kotlin Coroutines |
| リアルタイム通信 | libwebrtc |
| 映像デコード | MediaCodec（libwebrtc経由） |
| 映像描画 | SurfaceViewRenderer |
| タッチ操作 | MotionEvent / Compose Gesture API |
| キーボード入力 | InputConnection / Android IME |
| シグナリング通信 | OkHttp |
| JSON処理 | kotlinx.serialization |
| クリップボード | ClipboardManager |
| 設定保存 | Jetpack DataStore |
| 認証情報保護 | Android Keystore |
| テスト | JUnit / Compose UI Testing |
| フォーマッター | ktlint |

#### WebRTCライブラリ

第一候補としてGetStream/webrtc-androidを採用する。

https://github.com/GetStream/webrtc-android

依存パッケージ：

```kotlin
implementation("io.getstream:stream-webrtc-android:<version>")
```

Streamのクラウドサービスは利用せず、ビルド済みWebRTCライブラリのみを利用する。

以下を検証したうえで正式採用する。

- Android 8（API 26）以降との互換性
- arm64-v8aおよびarmeabi-v7aへの対応
- x86およびx86_64への対応
- H.264ハードウェアデコード
- Jetpack Composeとの統合
- macOS側ライブラリとの相互接続性

両プラットフォームの依存バージョンは、互換性を確認したうえで固定する。

### 5.3. 通信

| 項目 | 採用技術 |
|---|---|
| ネットワーク | Tailscale / WireGuard |
| リアルタイム通信 | WebRTC |
| 映像転送 | SRTP |
| 映像コーデック | H.264 |
| 音声コーデック | Opus |
| 操作イベント | WebRTC DataChannel |
| シグナリング | WebSocket over TLS |
| メッセージ形式 | JSON |
| 接続先指定 | Tailscale IP / MagicDNS |
| 端末認証 | ペアリング方式 |

WebRTCが提供する輻輳制御、パケットロス対策、ビットレート制御、暗号化などを利用し、独自実装は最小限にする。

## 6. macOSホスト機能

### 6.1. 常駐アプリケーション

メニューバー常駐型のアプリケーションとして実装する。

メニューバーから以下の機能を利用できるようにする。

- ホスト機能の開始・停止
- 接続待機状態の確認
- 接続中の端末の確認
- 接続の強制切断
- ペアリング情報の表示
- 登録済み端末の管理・削除
- 設定画面の表示
- アプリケーションの終了

macOSへのログイン時に自動起動できるようにする。

### 6.2. 権限管理

以下の権限の取得状態を管理する。

- 画面収録
- システムオーディオ収録（音声転送時）
- アクセシビリティ
- その他、OSバージョンや実装方式に応じて必要となる権限

権限が不足している場合は、必要な設定画面への誘導を行う。

権限が付与されていない状態で、該当する操作を実行しない。

### 6.3. 画面キャプチャ

ScreenCaptureKitを利用して画面をキャプチャする。

以下のキャプチャ方式に対応する。

- ディスプレイ全体
- 特定のアプリケーション
- 特定のウインドウ

取得した映像フレームをWebRTCの映像ソースに渡し、H.264でエンコードしてAndroid側へ転送する。

可能な限りCVPixelBufferを活用し、不要なメモリコピーやCPUによる画像処理を避ける。

ハードウェアエンコードを優先するが、端末の対応状況に応じてWebRTCが提供するフォールバックを利用する。

HDR転送は初期対応範囲に含めず、SDRを基本とする。

### 6.4. ディスプレイ・ウインドウ管理

Android側から以下の情報を取得できるようにする。

- ディスプレイ一覧
- ディスプレイ名
- ディスプレイ解像度
- 実行中のアプリケーション一覧
- アプリケーション名
- アプリケーションアイコン
- ウインドウ一覧
- ウインドウタイトル
- ウインドウID
- ウインドウサイズ
- ウインドウ位置
- ウインドウの状態

ウインドウの生成、終了、タイトル変更、サイズ変更などを検知し、Android側に通知する。

### 6.5. ウインドウ制御

Accessibility APIおよびNSWorkspaceを利用して以下の操作を実装する。

- アプリケーションの起動
- アプリケーションのアクティブ化
- ウインドウのアクティブ化
- ウインドウの移動
- ウインドウのリサイズ
- ウインドウの最小化・復元
- ウインドウの終了

Android端末の画面サイズや縦横比に合わせて、Mac側のウインドウサイズを自動調整できるようにする。

ただし、ウインドウの最小サイズやリサイズ可否など、対象アプリケーション固有の制約を尊重する。

自動リサイズを解除した場合や接続を終了した場合は、可能な限り元のサイズ・位置に復元する。

### 6.6. 入力イベント

Android側から受信した操作イベントを、CGEventなどを利用してmacOSへ反映する。

対応する操作：

- マウスカーソル移動
- 左クリック
- 右クリック
- ダブルクリック
- ドラッグアンドドロップ
- 縦・横スクロール
- キーボード入力
- 修飾キー入力
- キーボードショートカット

ウインドウ単位で操作する場合は、キャプチャ対象と実際のウインドウ位置を対応付け、正しい座標へ入力イベントを送信する。

入力対象ウインドウのフォーカスを確認し、別のアプリケーションへ意図せず入力されることを防ぐ。

対象ウインドウが他のウインドウに隠れている場合は、必要に応じて前面へ移動する。

バックグラウンドのウインドウを、Mac本体の操作に影響を与えず独立して操作できることは初期要件としない。

### 6.7. 音声転送

ScreenCaptureKitなどを利用してMacのシステム音声を取得する。

取得した音声をWebRTCの音声ソースへ供給し、OpusでエンコードしてAndroid側へ転送する。

以下の機能を提供する。

- システム音声の転送
- 対応可能な範囲でのアプリケーション単位の音声取得
- 音声転送の有効化・無効化

ScreenCaptureKitで取得したPCM音声をWebRTCへ取り込む方法については、カスタムAudio Device Moduleなどの利用可否を検証する。

Macのマイク音声は初期設定では転送しない。

音声取得に関するAPIの対応状況にOSバージョン差がある場合は、可能な限り共通実装を利用し、必要に応じて該当機能のみ利用不可とする。

### 6.8. スリープ制御

リモート接続中は、必要に応じてMacのアイドルスリープを抑制する。

接続終了後はスリープ抑制を解除する。

接続待機中もスリープを抑制する設定を別途提供する。

MacBookの蓋を閉じた状態での動作は、macOSの電源管理やクラムシェルモードの条件に依存する。アイドルスリープの抑制だけで蓋を閉じた状態での動作を保証するものではない。

## 7. Androidクライアント機能

### 7.1. ホーム画面

登録済みのMacを一覧表示する。

表示項目：

- ホスト名
- 接続先アドレス
- 接続可否
- 最終接続日時

複数のMacを登録できるようにする。

### 7.2. ホスト登録

以下の方法でMacを登録できるようにする。

- QRコードによるペアリング
- Tailscale IPアドレスの手動入力
- MagicDNSホスト名の手動入力

ホストへの到達性を確認し、接続できない場合は原因に応じた案内を表示する。

Tailscaleの接続状態を取得できるかについては、Androidの公開APIおよびTailscaleクライアントの仕様を確認する。

Tailscale固有の接続状態を取得できない場合は、ホストへの到達性確認とユーザーへの案内によって対応する。

### 7.3. リモート操作対象の選択

接続後、以下の操作対象を選択できるようにする。

- デスクトップ
- ディスプレイ
- アプリケーション
- ウインドウ

アプリケーション一覧にはアイコンを表示する。

複数のウインドウを持つアプリケーションについては、対象ウインドウを個別に選択できるようにする。

### 7.4. デスクトップモード

Macのディスプレイ全体を表示・操作するモード。

以下の機能に対応する。

- 全画面表示
- ピンチイン・ピンチアウト
- 表示位置の移動
- ディスプレイの切り替え
- 縦画面・横画面の切り替え
- アスペクト比を維持した表示
- 表示倍率のリセット

### 7.5. ウインドウモード

特定のMacアプリケーションのウインドウだけを表示・操作するモード。

komadoの主要機能として位置付ける。

- ウインドウ単位の映像表示
- Androidの画面サイズに合わせた表示
- Mac側ウインドウの自動リサイズ
- 縦画面・横画面への追従
- アプリケーションの切り替え
- ウインドウの切り替え
- デスクトップモードへの切り替え

メニューバー、コンテキストメニュー、モーダルダイアログ、ポップアップなど、単一ウインドウキャプチャでは表示できない可能性がある要素についても検証する。

必要に応じてアプリケーション単位またはデスクトップ全体のキャプチャへ切り替えられるようにする。

### 7.6. タッチ操作

直接タッチモードとトラックパッドモードを提供する。

#### 直接タッチモード

タッチした位置をMac側のマウス座標に変換する。

| 操作 | 動作 |
|---|---|
| タップ | 左クリック |
| ダブルタップ | ダブルクリック |
| 長押し | 右クリック |
| 長押し後の移動 | ドラッグ |
| 2本指スワイプ | スクロール |
| ピンチ | 映像の拡大・縮小 |

長押しによる右クリックとドラッグの判定が競合しないように設計する。

具体的なジェスチャーの割り当ては実機での操作性を踏まえて調整する。

#### トラックパッドモード

スマートフォンの画面をトラックパッドとして利用する。

指の移動量に応じてMac側のマウスカーソルを移動する。

カーソルの移動速度やスクロール感度を調整できるようにする。

### 7.7. キーボード入力

Androidのソフトウェアキーボードを利用できるようにする。

以下の入力に対応する。

- 英数字
- 日本語
- Unicode文字
- 改行
- Backspace
- Delete
- 矢印キー
- Tab
- Escape
- ファンクションキー
- キーボードショートカット

Android側のIMEで確定された文字列をMac側へ送信する方式と、物理キーイベントを送信する方式を分離する。

通常の文字列入力と、ターミナル操作やショートカットなどのキー操作を区別して処理する。

日本語の未確定文字列は原則としてAndroid側で管理し、確定時にMac側へ送信する。

長文、絵文字、サロゲートペアなどを含むUnicode入力についても検証する。

### 7.8. 特殊キーツールバー

ソフトウェアキーボードだけでは入力しにくいMacの特殊キーをツールバーとして提供する。

- Command
- Option
- Control
- Shift
- Escape
- Tab
- 矢印キー
- F1〜F12

修飾キーの一時的な固定にも対応する。

接続切断や画面遷移時には、押下状態のまま残った修飾キーを解除する。

### 7.9. 外部入力機器

Android端末に接続した以下の機器からの操作に対応する。

- Bluetoothキーボード
- Bluetoothマウス
- USBキーボード
- USBマウス
- トラックパッド

キー配列の違いや、Android側で処理されるシステムショートカットについては個別に検証する。

OSバージョンによって外部入力機器に関するAPIの利用可否が異なる場合は、共通実装を優先し、必要に応じて一部機能のみ制限する。

### 7.10. クリップボード共有

MacとAndroid間でテキストのクリップボード共有を行う。

- MacからAndroidへのコピー
- AndroidからMacへのコピー
- 自動同期の有効化・無効化
- 手動同期

Androidのバックグラウンドクリップボードアクセス制限を尊重し、アプリ利用中の同期を基本とする。

同期ループや不要な機密情報の転送を防止する。

画像・ファイルのクリップボード共有は将来的な拡張機能とする。

## 8. 通信設計

### 8.1. 接続フロー

1. Android側で登録済みホストを選択する。
2. Tailscale経由でmacOS側のシグナリングサーバーに接続する。
3. ペアリング済み端末であることを認証する。
4. プロトコルバージョンと対応機能を交換する。
5. WebRTCのSDP Offer / Answerを交換する。
6. ICE Candidateを交換する。
7. WebRTC接続を確立する。
8. DataChannelを確立する。
9. Android側からキャプチャ対象を選択する。
10. macOS側でキャプチャを開始する。
11. 映像・音声・操作イベントの通信を開始する。

### 8.2. シグナリング

macOS側でWebSocketサーバーを起動する。

以下の情報を交換する。

- プロトコルバージョン
- 端末認証情報
- セッション情報
- SDP Offer
- SDP Answer
- ICE Candidate
- 接続状態
- 対応機能
- エラー情報

シグナリング通信にはTLSを利用する。

TLS証明書の信頼確立はペアリング処理と統合する。

WebRTCのメディア通信についてもTailscale経由に限定できるよう、ICE Candidateの選択、VPNインターフェースの認識、ソケットの経路制御を検証する。

### 8.3. 機能ネゴシエーション

ホストとクライアントの接続時に、双方が利用可能な機能を交換する。

主な項目：

- 対応プロトコルバージョン
- 対応映像コーデック
- 対応音声コーデック
- 音声転送の可否
- ウインドウ制御の可否
- 利用可能なキャプチャ方式
- 最大対応解像度
- 利用可能な追加機能

OSバージョンやCPUアーキテクチャを直接判定して処理を分岐するのではなく、可能な限り機能の利用可否に基づいて制御する。

対応していない機能はUI上で無効化するか、非表示とする。

### 8.4. DataChannel

操作イベントの性質に応じてDataChannelを分離する。

| チャネル | 通信方式 | 用途 |
|---|---|---|
| control | Reliable / Ordered | クリック、キー入力、ウインドウ操作 |
| pointer | Unreliable / Unordered | カーソル位置などの高頻度イベント |
| state | Reliable / Ordered | 状態通知、ウインドウ一覧 |
| clipboard | Reliable / Ordered | クリップボード同期 |

クリックやキー入力など、欠落すると操作状態が不整合になるイベントは信頼性のあるチャネルを使用する。

マウス移動などは古いイベントを再送する必要がないため、低遅延を優先する。

トラックパッド操作などの相対移動については、パケットロスを考慮した累積位置やシーケンス番号による補正を検討する。

異なるDataChannel間のメッセージ到着順序は保証されないため、クリックイベントには必要に応じて座標情報を含める。

### 8.5. メッセージ仕様

メッセージ形式はJSONとする。

共通フィールド：

- `type`
- `requestId`
- `sequence`
- `payload`

メッセージ例：

```json
{
  "type": "pointer.move",
  "sequence": 123,
  "payload": {
    "captureRevision": 4,
    "x": 0.5,
    "y": 0.25
  }
}
```

主なメッセージ種別：

```text
session.info
session.capabilities
session.disconnect

display.list
window.list
window.updated
application.list
application.launch

capture.start
capture.stop
capture.switch
capture.updated

window.focus
window.resize
window.move
window.minimize
window.close

pointer.move
pointer.button
pointer.scroll

keyboard.keyDown
keyboard.keyUp
keyboard.text

clipboard.read
clipboard.write
clipboard.updated

stream.configure
stream.stats

error
```

要求に対して応答が必要なメッセージは`requestId`で対応付ける。

メッセージ定義はJSON Schemaで管理し、macOSとAndroid間の互換性を維持する。

### 8.6. 座標変換

Androidから送信する絶対座標は、キャプチャ対象の映像領域を基準として0〜1に正規化する。

macOS側では以下の情報を考慮して実際の座標に変換する。

- キャプチャ対象の位置
- キャプチャ対象のサイズ
- ディスプレイの座標系
- Retinaディスプレイのスケール
- キャプチャ時のコンテンツ領域
- ウインドウの移動・リサイズ

Android側では以下の情報を考慮する。

- 表示倍率
- 表示位置
- アスペクト比
- レターボックス領域
- 端末の画面回転

キャプチャ対象が変更された場合はリビジョンを更新し、古い映像に基づく入力イベントを適切に破棄する。

### 8.7. 再接続

以下の状況からの復旧に対応する。

- Wi-Fiとモバイル回線の切り替え
- 一時的な通信切断
- Tailscaleの再接続
- ホストアプリケーションの再起動
- WebRTC接続の切断

ICE RestartおよびPeerConnectionの再生成を状況に応じて使い分ける。

切断時にはマウスボタンや修飾キーの押下状態を解除し、再接続時に以前の操作状態を持ち越さない。

## 9. セキュリティ

### 9.1. ネットワーク制限

- Tailscale経由の通信を基本とする。
- シグナリングサーバーをインターネットに公開しない。
- 利用するネットワークインターフェースおよび接続経路を制限する。
- Tailscale Grantsによる接続元・接続先の制限を推奨する。
- WebRTCの動的UDPポートを考慮してアクセス制御を設計する。
- 許可されたネットワーク経路を利用していることを確認できない場合は接続を拒否する。

TailscaleのIPアドレス範囲に該当することだけを、正当な端末であることの証明として扱わない。

### 9.2. ペアリング

Tailscaleに接続していることだけをkomadoの認証条件としない。

Mac上で明示的に発行したペアリング情報を使用してAndroid端末を登録する。

ペアリング用QRコードには以下の情報を含める。

- ホスト識別子
- 接続先アドレス
- ホスト証明書または公開鍵のフィンガープリント
- 有効期限付きのワンタイムペアリング情報

手動ペアリングでも、ホストの真正性を確認できる仕組みを設ける。

ペアリング情報は一度使用すると無効化し、総当たり攻撃に対する試行回数制限を設ける。

### 9.3. 端末認証

ペアリング済み端末ごとに認証情報を発行する。

macOS側はKeychain、Android側はAndroid Keystoreで保護された鍵や暗号化ストレージを利用して認証情報を管理する。

登録済み端末を削除した場合は、該当端末からの新規接続および既存セッションを無効化する。

### 9.4. 通信暗号化

以下の暗号化方式を利用する。

- TailscaleによるWireGuard暗号化
- シグナリング通信のTLS
- WebRTCのDTLS / SRTP

独自の暗号化アルゴリズムは実装しない。

### 9.5. 入力制御

認証済みかつ有効なセッションからの操作イベントのみを受け付ける。

受信メッセージの型、サイズ、値の範囲、操作対象の有効性を検証する。

接続終了時には画面キャプチャと入力受付を停止する。

操作中のキーやマウスボタンについては、必要に応じて解放イベントを生成する。

### 9.6. ログ

以下の情報を記録する。

- 接続・切断
- ペアリング
- 認証失敗
- 権限エラー
- キャプチャエラー
- 通信エラー
- パフォーマンス情報

画面内容、入力文字列、クリップボード内容、認証情報などはログに記録しない。

## 10. パフォーマンス

### 10.1. 映像設定

以下の画質プリセットを初期案とする。

| プリセット | 解像度上限（長辺×短辺） | FPS上限 | 目標ビットレート |
|---|---|---|---|
| 省データ | 1280×720 | 30 | 1〜3Mbps |
| 標準 | 1920×1080 | 30 | 3〜6Mbps |
| 高画質 | 2560×1440 | 30 | 6〜12Mbps |
| 高フレームレート | 1920×1080 | 60 | 8〜15Mbps |

解像度はキャプチャ対象のアスペクト比を維持する。

上記は初期設定の目安とし、通信状況や端末性能に応じて調整する。

具体的なプリセットは技術検証の結果を踏まえて確定する。

### 10.2. 自動画質調整

以下の情報に応じて画質を調整する。

- RTT
- パケットロス率
- 利用可能帯域幅
- 映像フレームレート
- エンコード処理時間
- デコード処理時間
- フレームドロップ率

WebRTCの輻輳制御を基本とし、必要に応じてキャプチャ解像度やフレームレートを調整する。

プログラミングやターミナル操作では文字の視認性を重視し、通信品質が悪化した場合はフレームレートを優先的に低下させる方針を検討する。

Intel Macや旧世代のAndroid端末についても、端末性能に応じた画質調整によって対応することを優先する。

CPUアーキテクチャやOSバージョンによって一律に画質を制限することは避ける。

### 10.3. 性能目標

| 項目 | 暫定目標 |
|---|---|
| 安定したLANでの操作反映遅延 | 100ms以下 |
| 良好な外部回線での操作反映遅延 | 200ms以下 |
| 標準フレームレート | 最大30fps |
| 最大フレームレート | 60fps |
| 初回接続 | 5秒以内 |
| 一時切断からの復旧 | 通信復旧後10秒以内 |
| 連続接続 | 8時間以上 |

操作反映遅延は、Androidで操作してから、その結果がAndroidの画面に表示されるまでの時間とする。

性能目標は実機検証時の評価基準であり、すべての端末および通信環境における保証値ではない。

具体的な目標値は技術検証の結果を踏まえて見直す。

### 10.4. 接続統計

開発者向けおよびトラブルシューティング用に以下の情報を表示できるようにする。

- RTT
- 送受信ビットレート
- 映像解像度
- FPS
- パケットロス率
- 使用中のコーデック
- ICE接続状態
- フレームドロップ率
- ハードウェアエンコード・デコードの利用状況

## 11. リポジトリ構成

```text
komado/
├── macos/
│   ├── Komado.xcodeproj/
│   ├── Komado/
│   │   ├── App/
│   │   ├── Features/
│   │   ├── Capture/
│   │   ├── Input/
│   │   ├── Window/
│   │   ├── Audio/
│   │   ├── Networking/
│   │   ├── WebRTC/
│   │   ├── Security/
│   │   └── Resources/
│   ├── KomadoTests/
│   └── KomadoUITests/
│
├── android/
│   ├── app/
│   │   └── src/
│   │       ├── main/
│   │       ├── test/
│   │       └── androidTest/
│   ├── gradle/
│   ├── gradlew
│   ├── gradlew.bat
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── protocol/
│   ├── schemas/
│   │   ├── signaling.schema.json
│   │   └── control.schema.json
│   ├── fixtures/
│   └── README.md
│
├── docs/
│   ├── architecture.md
│   ├── protocol.md
│   ├── security.md
│   ├── development.md
│   ├── testing.md
│   ├── compatibility.md
│   └── decisions/
│
├── .github/
│   └── workflows/
│       ├── macos-ci.yml
│       ├── android-ci.yml
│       └── release.yml
│
├── AGENTS.md
├── README.md
├── LICENSE
└── .gitignore
```

ディレクトリ構成は初期案とし、実装規模に応じて調整する。

過度なモジュール分割や抽象化は避け、実際に必要となった段階で分離する。

対応OS、CPUアーキテクチャ、既知の制約、実機検証結果は`docs/compatibility.md`で管理する。

## 12. 開発計画

### Phase 0：技術検証

主要な技術的リスクを早期に解消する。

- [ ] macOS側のWebRTCライブラリの導入
- [ ] Android側のWebRTCライブラリの導入
- [ ] macOS 13を最低対応OSとしたビルド
- [ ] macOS向けUniversal Binaryの生成
- [ ] Android API 26を最低対応APIとしたビルド
- [ ] Android向けARM64・ARM32バイナリの確認
- [ ] Android向けx86・x86_64バイナリの確認
- [ ] Tailscale経由でのICE接続
- [ ] Tailscale以外の経路を利用しない接続制御
- [ ] ScreenCaptureKitによる画面取得
- [ ] WebRTCによる映像転送
- [ ] H.264ハードウェアエンコード・デコード
- [ ] ウインドウ単位のキャプチャ
- [ ] Accessibility APIによるウインドウ制御
- [ ] CGEventによる入力反映
- [ ] Android IMEによる日本語入力
- [ ] システム音声のWebRTCへの取り込み
- [ ] OSバージョンによるAPIの利用可否の確認
- [ ] 最低対応OSおよびCPUアーキテクチャの決定

完了条件：

Macの画面を実機Androidへ転送でき、Tailscale経由で操作イベントを送信できること。

主要な技術的制約を整理し、採用ライブラリと対応環境を確定すること。

音声転送など、基本的なリモート操作とは独立した追加機能に課題が残る場合は、その課題を記録したうえで後続フェーズへ進めることを許容する。

### Phase 1：基本リモートデスクトップ

- [ ] macOSメニューバーアプリ
- [ ] Androidホーム画面
- [ ] ホスト登録
- [ ] ペアリング・端末認証
- [ ] シグナリングサーバー
- [ ] WebRTCセッション管理
- [ ] 機能ネゴシエーション
- [ ] デスクトップ全体の映像転送
- [ ] マウス操作
- [ ] キーボード操作
- [ ] 日本語入力
- [ ] 特殊キー入力
- [ ] 拡大・縮小
- [ ] 画面回転
- [ ] 接続・切断処理

完了条件：

AndroidからMacのデスクトップ全体を操作でき、ターミナルやコードエディタを利用できること。

### Phase 2：ウインドウモード

- [ ] アプリケーション一覧
- [ ] アプリケーションアイコン取得
- [ ] ウインドウ一覧
- [ ] ウインドウ単位の映像転送
- [ ] ウインドウへのフォーカス制御
- [ ] ウインドウ座標変換
- [ ] ウインドウの自動リサイズ
- [ ] リサイズ前の状態への復元
- [ ] 縦画面・横画面への追従
- [ ] アプリケーション切り替え
- [ ] ウインドウ切り替え
- [ ] メニュー・ダイアログ操作
- [ ] デスクトップモードとの切り替え

完了条件：

Androidの画面サイズに適した状態でMacの特定のアプリケーションを操作できること。

### Phase 3：実用性向上

- [ ] 音声転送
- [ ] テキストクリップボード共有
- [ ] 外付けキーボード対応
- [ ] 外付けマウス対応
- [ ] トラックパッドモード
- [ ] 複数ディスプレイ対応
- [ ] 複数ホスト登録
- [ ] ログイン時の自動起動
- [ ] スリープ抑制
- [ ] 画質設定
- [ ] 自動再接続
- [ ] 動的ビットレート調整
- [ ] 接続統計表示

完了条件：

日常的なリモート操作において、既存のリモートデスクトップアプリの代替として利用できること。

### Phase 4：品質向上・配布

- [ ] 長時間接続テスト
- [ ] メモリリーク検証
- [ ] CPU・GPU使用率の最適化
- [ ] バッテリー消費の最適化
- [ ] モバイル回線での動作検証
- [ ] 最低対応macOSでの動作検証
- [ ] Apple Silicon・Intel Macでの動作検証
- [ ] 最低対応Androidでの動作検証
- [ ] ARM64・ARM32端末での動作検証
- [ ] セキュリティレビュー
- [ ] UI・UX改善
- [ ] エラー処理の改善
- [ ] CI/CD構築
- [ ] macOS向けUniversal Binary DMG作成
- [ ] Android向けAPK作成
- [ ] ドキュメント整備
- [ ] GitHub Releasesによる配布

完了条件：

macOS版とAndroid版の双方を再現可能な手順でビルド・配布できること。

検証済みの対応環境と既知の制約が明文化されていること。

## 13. テスト計画

### 13.1. 自動テスト

以下を中心にユニットテストおよび統合テストを実装する。

- プロトコルのシリアライズ・デシリアライズ
- JSON Schemaの整合性
- 機能ネゴシエーション
- 座標変換
- 入力イベント変換
- キー押下状態の管理
- ペアリング
- 端末認証
- セッション状態遷移
- 再接続処理
- 不正メッセージの拒否
- クリップボード同期

通信仕様のテストデータは`protocol/fixtures/`で共有する。

### 13.2. 実機テスト

以下のアプリケーションを主な操作対象として検証する。

- Finder
- Terminal
- Visual Studio Code
- Android Studio
- Xcode
- Safari
- Google Chrome

### 13.3. 互換性テスト

以下の環境を検証対象とする。

#### macOS

- 最低対応macOS
- 最新macOS
- Apple Silicon Mac
- Intel Mac
- Retinaディスプレイ
- 非Retinaディスプレイ
- 複数ディスプレイ環境

#### Android

- 最低対応Android
- 最新Android
- ARM64端末
- ARM32端末
- スマートフォン
- タブレット
- 異なる画面解像度・縦横比
- 異なるSoC・映像デコーダーを搭載した端末
- x86 / x86_64エミュレーター（対応する場合）

利用可能な実機およびテスト環境に応じて検証する。

実機が確保できない環境については、ビルド可否や依存ライブラリの互換性確認と、実機での動作確認を区別して記録する。

### 13.4. ネットワークテスト

以下のネットワーク条件で検証する。

- 同一LAN内
- Tailscaleによる直接接続
- Tailscale DERP経由
- モバイル回線
- Wi-Fiとモバイル回線の切り替え
- 低速回線
- 高遅延環境
- パケットロス発生環境
- 一時切断
- Tailscale停止・再起動
- 未登録端末からの接続
- Tailscale以外のネットワーク経路からの接続

### 13.5. 端末・OS状態のテスト

- Macのスリープ・復帰
- Macの画面ロック
- Macのログアウト・再ログイン
- MacBookの蓋の開閉
- 外部ディスプレイの接続・取り外し
- ウインドウの移動・リサイズ・終了
- Androidの画面回転
- Androidのバックグラウンド移行
- Androidの画面ロック
- OS権限の取り消し
- 接続中のホストアプリ終了

対応できないOS状態については、制約として明示し、安全に接続を終了する。

## 14. CI/CD・配布

GitHub Actionsを利用する。

### 14.1. macOS

- Swiftコードのフォーマットチェック
- ビルド
- ユニットテスト
- arm64向けビルド
- x86_64向けビルド
- Universal Binaryの生成
- 依存フレームワークの対応アーキテクチャ確認
- DMG生成

最低対応macOSはDeployment Targetによって管理する。

### 14.2. Android

- Kotlinコードのフォーマットチェック
- Lint
- ビルド
- ユニットテスト
- 対応ABIの確認
- APK生成

対応ABIを含むUniversal APKを基本とする。

配布サイズが問題となる場合は、ABI別APKの生成も検討する。

### 14.3. リリース

Gitタグを基準にmacOS版とAndroid版のリリース成果物を生成し、GitHub Releasesに登録する。

macOS版のDeveloper ID署名・公証は、Apple Developer Programへの加入状況に応じて対応する。

Android版はリリース用署名鍵で署名し、署名鍵をリポジトリに含めない。

両プラットフォームのバージョン管理にはSemantic Versioningを利用する。

通信プロトコルのバージョンはアプリケーションのバージョンと区別して管理する。

リリース時には対応OS、対応CPUアーキテクチャ、検証済み環境、既知の制約を明記する。

## 15. AIコーディングにおける開発方針

`AGENTS.md`に以下の方針を定義する。

- macOSとAndroidの実装を一体として管理する。
- 通信仕様を変更する場合は、双方の実装とテストを更新する。
- OS標準APIを優先する。
- 非公開APIへの依存を避ける。
- 実在しないAPIやライブラリの機能を前提として実装しない。
- 必要性の低い依存ライブラリを追加しない。
- 過度な抽象化や将来のためだけの実装を避ける。
- セキュリティ機構を省略しない。
- 通信仕様はドキュメントとJSON Schemaに反映する。
- 主要な技術選定はADRとして記録する。
- ビルド・テスト結果と実機検証結果を区別する。
- 実機検証が必要な機能を、ビルド成功のみで完了扱いにしない。
- 依存ライブラリのバージョンを固定し、更新時は互換性を確認する。
- 認証情報や署名鍵をコミットしない。
- 共通実装で対応可能なOSやCPUを意図的に除外しない。
- 古い環境への対応のみを目的とした複雑な代替実装は避ける。
- 互換性維持のみを目的として、依存ライブラリを旧バージョンに固定しない。
- OSバージョンによる分岐より、APIや機能の利用可否に基づく制御を優先する。
- 最低対応OSを引き上げる場合は、具体的な技術的根拠を記録する。
- 実機検証が行われていない環境を動作保証済みと記載しない。

## 16. 初期開発の対象外

以下は初期開発では対応しない。

- Windowsホスト
- Linuxホスト
- iOSクライアント
- Webブラウザ・PWAクライアント
- 独自VPN機能
- 独自クラウド中継サーバー
- クラウドアカウント管理
- 課金システム
- 複数クライアントからの同時操作
- ファイル転送
- AndroidからMacへのマイク転送
- HDR映像転送
- 仮想ディスプレイの生成
- Macのログイン前・FileVault解除前の操作
- Wake-on-LANによる遠隔起動

必要性に応じて将来的な対応を検討する。

Intel Macおよび旧世代Androidへの対応は、初期開発の対象外とはしない。

## 17. 技術的な検証事項

特に以下については、仕様上の実現可能性だけで判断せず、初期段階で検証を行う。

| 項目 | 検証内容 |
|---|---|
| WebRTC over Tailscale | VPNインターフェースの認識、ICE Candidateの選択、通信経路 |
| WebRTCライブラリ | macOS・Android間の互換性、必要なAPIの提供状況 |
| macOS互換性 | macOS 13以降でのAPI利用可否 |
| Mac CPU互換性 | Apple Silicon・Intelでのビルドおよび動作 |
| Android互換性 | API 26以降での依存ライブラリ・標準APIの利用可否 |
| Android ABI | ARM64・ARM32・x86・x86_64への対応状況 |
| ハードウェア処理 | H.264のハードウェアエンコード・デコード |
| ウインドウキャプチャ | 非アクティブ時、最小化時、他のウインドウに隠れた場合の挙動 |
| ウインドウ操作 | フォーカス、リサイズ、アプリ固有の制約 |
| メニュー・ダイアログ | 単一ウインドウキャプチャでの表示と操作 |
| 日本語入力 | IMEの確定文字列、特殊キー、ターミナルとの互換性 |
| 音声転送 | ScreenCaptureKitとWebRTC音声ソースの接続 |
| スリープ | 接続待機時、接続中、MacBookの蓋を閉じた場合の挙動 |
| 権限管理 | macOSの画面収録・アクセシビリティ権限 |
| 長時間稼働 | メモリリーク、発熱、バッテリー消費 |

検証結果に基づき、必要に応じて技術選定、対応環境、機能要件を見直す。

## 18. 参考資料

### Apple

- [ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit)
- [Capturing screen content in macOS](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-in-macos)
- [Building a universal macOS binary](https://developer.apple.com/documentation/apple-silicon/building-a-universal-macos-binary)
- [Core Graphics](https://developer.apple.com/documentation/coregraphics)
- [Application Services](https://developer.apple.com/documentation/applicationservices)
- [VideoToolbox](https://developer.apple.com/documentation/videotoolbox)
- [Network.framework](https://developer.apple.com/documentation/network)
- [Service Management](https://developer.apple.com/documentation/servicemanagement)

### Android

- [Jetpack Compose](https://developer.android.com/compose)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android ABIs](https://developer.android.com/ndk/guides/abis)

### [WebRTC](https://webrtc.org/)

- [WebRTC](https://webrtc.org/)
- [WebRTC Source Code](https://webrtc.googlesource.com/src/)
- [stasel/WebRTC](https://github.com/stasel/WebRTC)
- [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android)

### Tailscale

- [Tailscale Documentation](https://tailscale.com/docs/)
- [Install Tailscale on macOS](https://tailscale.com/docs/install/mac)
- [Install Tailscale on Android](https://tailscale.com/docs/install/android)
- [Connection types](https://tailscale.com/docs/reference/connection-types)
- [MagicDNS](https://tailscale.com/kb/1081/magicdns)
- [Grants](https://tailscale.com/kb/1324/grants)
