# ADR 0003: シグナリングのTLSとホスト確認

- 状態: 提案中
- 日付: 2026-11-27

## 背景

どのネットワークを利用する場合も、端末が接続したMacの真正性を確認してからペアリング情報を送信する必要がある。接続先はIPアドレスやホスト名などで変わり得るため、それらをMacの身元の根拠にはできない。

## 選択肢と検証結果

- 一般の公開CAの証明書を使う方法は、ローカルIPや任意のホスト名を使う場合の証明書取得・更新が課題となる。
- MacごとのTLS証明書を作成し、初回ペアリング時にQRコードなどの別経路から取得した証明書のフィンガープリントと照合する方法を候補とする。Androidはこの確認が成功したTLS接続でのみワンタイム情報を送る。
- AppleのNetwork frameworkはKeychainの証明書と秘密鍵を組にしたidentityを使い、TLSの`NWListener`を構成できる。Appleの`swift-certificates` 1.21.2でKeychainのP-256秘密鍵を使った自己署名証明書を生成し、再取得する初期実装を追加した。現在の証明書の有効期間は5年で、更新方式は未確定。
- Androidの`SSLSocket`はホスト名を自動検証しない。一般的な公開CA向けのホスト名検証を省き、独自の信頼管理にする場合は、事前に安全に取得した証明書の照合を必須とし、通信中に提示された証明書を無条件で信頼しない。

証明書と鍵の作成・再取得および欠損時の拒否はmacOS上でテストした。製品の待ち受けとAndroidアプリの画面を使い、同じMac上のエミュレーターからWi-Fiアドレスに接続して、不一致のフィンガープリントの拒否、ペアリングと再認証を確認した。別端末からのLAN・VPN接続は未検証。

## 決定と理由

証明書フィンガープリントを初回ペアリングの信頼の起点とする初期実装を採用する。Mac画面のSHA-256値をAndroidで手動入力またはQRコードから取得して照合し、証明書と鍵をMacのKeychainに保存する。証明書の更新・失効は未実装。手動入力したIPアドレスやホスト名の保存を、ペアリングまたは証明書の信頼設定とみなさない。QRコードの方式は[ADR 0004](0004-qr-pairing.md)を参照する。

## 影響・制約・未解決事項

- 初回の信頼情報はMacの画面で明示的に提示し、Androidでの確認前にワンタイム情報をネットワークへ送らない。表示値のすり替えや誤入力に対する扱いを設計する。
- 登録済みMacの証明書が変わった場合は自動的に受け入れない。期限切れ、更新、端末の紛失・再登録時の復旧手順を定義する。
- 証明書の照合、TLSでの秘密鍵の保持証明、有効期間・用途の検査、端末署名によるクライアント認証を組み合わせる。信頼情報の破損や不一致は接続を拒否する。
- シグナリング待ち受けはインターネット公開を避ける範囲とアクセス制御を別途検証する。TLSや到達元IPだけでこの要件を満たしたとは判断しない。
- 認証済みTLSでSDPを交換し、DTLSフィンガープリントで接続先を照合する。Mac・Android間の改変試験と実ネットワークでの検証は未実施。[セキュリティ](../security.md#シグナリングとwebrtcの結び付き)

## 関連資料

- [Apple: Creating an Identity for Local Network TLS](https://developer.apple.com/documentation/network/creating-an-identity-for-local-network-tls)
- [Apple: swift-certificates](https://github.com/apple/swift-certificates)
- [Android: Security with network protocols](https://developer.android.com/privacy-and-security/security-ssl)
- [セキュリティ](../security.md)
