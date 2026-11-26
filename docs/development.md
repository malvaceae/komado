# 開発ガイド

## 現状

macOSの共通コードをSwift Packageとして、Androidの共通コードをKotlinのJVMモジュールとして実装しています。macOSにはメニューバー常駐アプリの初期実装があり、Macの確認情報と登録端末を表示します。Androidアプリには接続先の名前とアドレスを端末内に保存・編集・削除する初期画面があります。アドレスの保存はペアリングではなく、両アプリとも接続機能は未実装です。CI・配布手順はまだ整備されていません。技術検証用のコードは[検証手順](../experiments/README.md)を参照してください。

## 開発の進め方

1. [開発計画書](development-plan.md)のPhase 0で、両側のWebRTCライブラリ、画面取得、同一LAN・Tailscale経由の接続、入力、互換性を検証する。
2. 検証条件と結果を記録し、ライブラリ、最低対応OS、ABI、実現上の制約を決める。主要な選定とトレードオフは[ADR](decisions/README.md)に残す。
3. Phase 0の完了条件を満たしたうえで、基本リモートデスクトップ、ウインドウモード、実用性向上、配布へ進む。音声など基本操作と独立した課題は記録したうえで後続フェーズに持ち越せる。
4. プロトコルを定義した際は、両側の実装、[スキーマ・フィクスチャ](../protocol/README.md)、ドキュメントを合わせて更新する。
5. ビルドと自動テスト、エミュレーター、実機を区別して結果を記録し、未検証の環境を保証しない。

フェーズごとの作業と完了条件は[開発計画書 §12](development-plan.md#12-開発計画)を参照してください。

## 環境の確認（2026-11-27）

- Xcode 27.2が`/Applications/Xcode.app`にインストールされ、macOS 27.2 SDKを認識できる。システムの選択先はCommand Line Toolsのため、Xcodeのツールを使うコマンドには`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`を指定できる。
- Android SDK、Java、Androidエミュレーターがある。Android実機はADBに接続されていない。
- [macOSのWebRTC検証用プログラム](../experiments/README.md)を追加し、Apple Silicon上でビルド・起動を確認した。候補のWebRTC配布物について、含まれるCPU・ABIとAndroidのminSdk宣言を確認した。結果と検証範囲は[互換性](compatibility.md)を参照する。ライブラリの相互接続や採用は未確定。

## 共通コードのテスト

XcodeのCommand Line Toolsが選択されている場合も含め、Xcodeを指定して実行できます。

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path macos
```

Macアプリの開発用ビルドは次のコマンドで作成できます。`.build/komado.app`はローカル確認用のアドホック署名であり、配布用の署名や公証は行いません。

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./macos/build-app.sh
open macos/.build/komado.app
```

Android側はJDKを用いて実行します。

```sh
./android/gradlew -p android :core:test
ANDROID_SERIAL=emulator-5554 ./android/gradlew -p android :security:connectedDebugAndroidTest :security:lintDebug
./android/gradlew -p android :app:assembleDebug :app:lintDebug
```

後者は起動済みのエミュレーターが必要です。シリアルは対象の環境に合わせて指定します。

Androidの`VideoViewport`は映像をアスペクト比を維持して表示した際の余白、倍率、移動を考慮し、表示範囲内のタッチを正規化します。macOSの`CaptureGeometry`はCore Graphicsのグローバル座標（ポイント単位）のキャプチャ領域と正規化座標から入力先の座標を求め、無効な座標や現在と異なるキャプチャリビジョンを拒否します。実際の映像表示との座標の一致、対象のフォーカス確認、入力送信はまだ実装・検証していません。端末認証については[署名プリミティブ](protocol.md#端末署名のプリミティブ初期実装)を実装していますが、認証フロー全体は未実装です。

## 環境と手順の整備予定

macOSはXcode / Swift / Swift Package Manager、AndroidはAndroid Studio / Kotlin / Gradle Kotlin DSLを候補とします。最新安定版の利用を基本とし、実際の対応状況を確認してバージョンを固定します。Tailscale経由で接続する場合は各端末にTailscaleが必要です。

プロジェクト作成後、以下を実際に確認して追記します。

- 必要なツールのバージョンとインストール手順。
- ローカルでのビルド、フォーマット、静的解析、自動テスト、実機実行のコマンド。
- macOS権限の設定、Tailscaleの設定、接続・ペアリングの手順。
- CIとリリース成果物の作成、署名、公証に必要な設定。秘密情報や署名鍵はリポジトリに含めない。

ディレクトリ構成とCI/CDは[開発計画書 §11](development-plan.md#11-リポジトリ構成)および[§14](development-plan.md#14-cicd配布)の初期案を参照してください。記載されたファイルやワークフローはまだ存在しません。
