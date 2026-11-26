# ADR 0003: シグナリングのTLSとホスト確認

- 状態: 提案中
- 日付: 2026-11-27

## 背景

同一LANでもTailscale経由でも、端末が接続したMacの真正性を確認してからペアリング情報を送信する必要がある。接続先はIPアドレスやLANのホスト名、MagicDNSなどで変わり得るため、それらをMacの身元の根拠にはできない。

## 選択肢と検証結果

- 一般の公開CAの証明書を使う方法は、ローカルIPや任意のホスト名を使う場合の証明書取得・更新が課題となる。
- MacごとのTLS証明書を作成し、初回ペアリング時にQRコードなどの別経路から取得した証明書のフィンガープリントと照合する方法を候補とする。Androidはこの確認が成功したTLS接続でのみワンタイム情報を送る。
- AppleのNetwork frameworkはKeychainの証明書と秘密鍵を組にしたidentityを使い、TLSの`NWListener`を構成できる。Appleの`swift-certificates` 1.21.2でKeychainのP-256秘密鍵を使った自己署名証明書を生成し、再取得する初期実装を追加した。現在の証明書の有効期間は5年で、更新方式は未確定。
- Androidの`SSLSocket`はホスト名を自動検証しない。一般的な公開CA向けのホスト名検証を省き、独自の信頼管理にする場合は、事前に安全に取得した証明書の照合を必須とし、通信中に提示された証明書を無条件で信頼しない。

証明書と鍵の作成・再取得および欠損時の拒否はmacOS上でテストした。Androidエミュレーター内のTLS通信では、あらかじめ確認済みのフィンガープリントと一致する証明書を受理し、異なる値を拒否した。MacとAndroid間のTLS通信やペアリングは未検証。

## 決定と理由

証明書フィンガープリントを初回ペアリングの信頼の起点とする方式を検討する。QRコードによる取得と手動照合、フィンガープリントの形式、TLS証明書の生成・保管方式、更新・失効は相互接続の検証後に確定する。手動入力したIPアドレスやホスト名の保存を、ペアリングまたは証明書の信頼設定とみなさない。

## 影響・制約・未解決事項

- 初回の信頼情報はMacの画面で明示的に提示し、Androidでの確認前にワンタイム情報をネットワークへ送らない。表示値のすり替えや誤入力に対する扱いを設計する。
- 登録済みMacの証明書が変わった場合は自動的に受け入れない。期限切れ、更新、端末の紛失・再登録時の復旧手順を定義する。
- 証明書の照合、TLSでの秘密鍵の保持証明、有効期間・用途の検査、端末署名によるクライアント認証を組み合わせる。信頼情報の破損や不一致は接続を拒否する。
- シグナリング待ち受けはインターネット公開を避ける範囲とアクセス制御を別途検証する。TLSや到達元IPだけでこの要件を満たしたとは判断しない。
- 認証されたシグナリングとWebRTCのDTLS接続先の結び付け、メッセージ形式、接続・切断時の状態管理は未確定。

## 関連資料

- [Apple: Creating an Identity for Local Network TLS](https://developer.apple.com/documentation/network/creating-an-identity-for-local-network-tls)
- [Apple: swift-certificates](https://github.com/apple/swift-certificates)
- [Android: Security with network protocols](https://developer.android.com/privacy-and-security/security-ssl)
- [セキュリティ](../security.md)
