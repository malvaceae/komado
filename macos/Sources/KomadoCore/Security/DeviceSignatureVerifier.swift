import CryptoKit
import Foundation

/// Verifies the signature primitive used by a paired device. Session binding,
/// key registration, challenge generation, expiry, and replay prevention are
/// the caller's responsibility and are not implemented here.
public enum DeviceSignatureVerifier {
    private static let domain = Data("komado.device-auth.v1\0".utf8)

    /// The key is an X.509 SubjectPublicKeyInfo DER encoding of a P-256 public
    /// key; the signature is an ASN.1 DER ECDSA signature over SHA-256.
    public static func verify(publicKeyDER: Data, challenge: Data, signatureDER: Data) -> Bool {
        guard challenge.count == 32, (1...128).contains(publicKeyDER.count),
              (1...72).contains(signatureDER.count),
              let publicKey = try? P256.Signing.PublicKey(derRepresentation: publicKeyDER),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureDER) else {
            return false
        }
        var message = domain
        message.append(challenge)
        return publicKey.isValidSignature(signature, for: message)
    }
}
