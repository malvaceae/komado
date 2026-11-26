# ADR 0002: 端末署名のプリミティブ

- 状態: 提案中
- 日付: 2026-11-27

## 背景

同一LANおよびTailscale上で、ネットワークへの到達性とは別に端末を認証する必要がある。Androidの認証鍵はKeystoreで保護し、Macで署名を検証する。

## 選択肢と検証結果

初期実装として、Android KeystoreでP-256鍵を生成し、ドメインを付けた32バイトのチャレンジを`SHA256withECDSA`で署名する。公開鍵はSubjectPublicKeyInfo DER、署名はECDSA DER形式を使用し、macOSのCryptoKitで検証する。AndroidエミュレーターとmacOSで共通フィクスチャを検証し、変更したチャレンジが拒否されることを確認した。エミュレーターで生成したKeystore鍵の署名をMac側で検証する基礎検証も行った。

## 決定と理由

この署名プリミティブを初期実装として使い、両OSの公開APIと標準の鍵・署名形式による相互運用を継続して検証する。端末登録と再認証にも組み込み、エミュレーターで確認した。実機での鍵の保護と相互接続は引き続き検証する。

## 影響・制約・未解決事項

- 署名の検証だけでは認証は完了しない。初回の公開鍵登録は、Macのフィンガープリントを確認したTLS接続とワンタイム情報を前提とする。登録解除時は接続と保留中のチャレンジを無効化する。
- Android実機や各端末のハードウェアによる鍵保護は未検証。鍵をKeystoreに置くこととハードウェア保護の有無は区別する。
- 初期認証のフィールドと順序は定義済み。メディア接続を含む状態遷移やエラーは引き続き設計する。

## 関連資料

- [通信プロトコル](../protocol.md#端末署名のプリミティブ初期実装)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [CryptoKit](https://developer.apple.com/documentation/cryptokit)
