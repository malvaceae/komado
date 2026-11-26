# ADR 0002: 端末署名のプリミティブ

- 状態: 提案中
- 日付: 2026-11-27

## 背景

同一LANおよびTailscale上で、ネットワークへの到達性とは別に端末を認証する必要がある。Androidの認証鍵はKeystoreで保護し、Macで署名を検証する。

## 選択肢と検証結果

初期実装として、Android KeystoreでP-256鍵を生成し、ドメインを付けた32バイトのチャレンジを`SHA256withECDSA`で署名する。公開鍵はSubjectPublicKeyInfo DER、署名はECDSA DER形式を使用し、macOSのCryptoKitで検証する。AndroidエミュレーターとmacOSで共通フィクスチャを検証し、変更したチャレンジが拒否されることを確認した。エミュレーターで生成したKeystore鍵の署名をMac側で検証する基礎検証も行った。

## 決定と理由

この署名プリミティブを初期実装として使い、両OSの公開APIと標準の鍵・署名形式による相互運用を継続して検証する。端末認証全体の方式は未確定である。

## 影響・制約・未解決事項

- 署名の検証だけでは認証は完了しない。公開鍵の安全な登録・失効、認証済みTLSセッションとの紐付け、チャレンジの安全な生成・期限・一回限りの利用を実装する。
- Android実機や各端末のハードウェアによる鍵保護は未検証。鍵をKeystoreに置くこととハードウェア保護の有無は区別する。
- プロトコルのフィールド、状態遷移、エラー、並行処理時の扱いは未定義。端末の登録解除や既存セッションの切断も必要。

## 関連資料

- [通信プロトコル](../protocol.md#端末署名のプリミティブ初期実装)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [CryptoKit](https://developer.apple.com/documentation/cryptokit)
