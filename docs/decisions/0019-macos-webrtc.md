# ADR 0019: macOSのWebRTCライブラリ

- 状態: 採用
- 日付: 2026-11-28

## 背景と決定

音声転送には画面収録で得た音声をWebRTCへ渡す必要がある。従来のstasel/WebRTC 158.0.0はmacOS向けにカスタム音声デバイスのヘッダーを公開していなかったため、公開APIを含むLiveKitWebRTC 150.7871.08に切り替える。AndroidはGetStream/webrtc-android 1.3.10を継続する。

## 検証と制約

合成音声を使った一時テストで、MacからAndroid API 26・37エミュレーターにOpus音声とH.264映像、DataChannelのメッセージを同じ接続で送信した。切り替え後の製品のWebRTC処理でも、合成映像の受信とAndroidからのクリックイベントの到達を両エミュレーターで確認した。macOSの自動テストとアプリのビルドも実行した。

音声転送の実装と検証範囲は[ADR 0020](0020-audio-capture.md)を参照する。実際の画面のAndroidへの転送とAndroid実機、別端末での接続は引き続き検証する。解像度の扱いは[ADR 0013](0013-capture-resolution.md)を参照する。
