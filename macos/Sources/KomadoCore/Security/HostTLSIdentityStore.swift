import CryptoKit
import Foundation
import Security
import X509

/// A locally created TLS identity. The certificate fingerprint must be conveyed
/// to the client through an authenticated pairing ceremony before it is trusted.
public struct HostTLSIdentity {
    public let identity: SecIdentity
    public let certificateDER: Data
    public let fingerprintSHA256: Data
}

/// Stores the host's private key in Keychain and keeps its certificate stable.
/// Creation is explicit and atomic at the reservation step: an incomplete or
/// damaged record is never silently replaced with a different identity.
public struct HostTLSIdentityStore {
    public enum StoreError: Error, Equatable {
        case alreadyExists
        case incompleteOrInvalid
        case keychain(OSStatus)
        case keyGenerationFailed
    }

    private let service: String
    private let keyTag: Data
    private let account = "certificate-v1"

    public init() {
        self.init(service: "me.mallows.komado.host-tls")
    }

    // Each test uses a unique service and key tag, isolated from the application.
    init(service: String) {
        self.service = service
        self.keyTag = Data("\(service).private-key".utf8)
    }

    /// Creates exactly one identity. On partial failure, the reservation remains
    /// so a future launch cannot silently create a different trusted identity.
    public func create() throws -> HostTLSIdentity {
        var reservation = certificateQuery()
        reservation[kSecValueData as String] = Data([0])
        let status = SecItemAdd(reservation as CFDictionary, nil)
        guard status != errSecDuplicateItem else { throw StoreError.alreadyExists }
        guard status == errSecSuccess else { throw StoreError.keychain(status) }

        let previousKeyStatus = SecItemCopyMatching(keyQuery() as CFDictionary, nil)
        guard previousKeyStatus != errSecSuccess else { throw StoreError.incompleteOrInvalid }
        guard previousKeyStatus == errSecItemNotFound else { throw StoreError.keychain(previousKeyStatus) }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw StoreError.keyGenerationFailed
        }
        let signingKey = try Certificate.PrivateKey(key)
        let name = try DistinguishedName { CommonName("komado host") }
        let now = Date()
        let certificate = try Certificate(
            version: .v3,
            serialNumber: .init(),
            publicKey: signingKey.publicKey,
            notValidBefore: now.addingTimeInterval(-300),
            notValidAfter: now.addingTimeInterval(365 * 5 * 24 * 60 * 60),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
                Critical(KeyUsage(digitalSignature: true))
                try ExtendedKeyUsage([.serverAuth])
            },
            issuerPrivateKey: signingKey
        )
        let secCertificate = try SecCertificate.makeWithCertificate(certificate)
        let der = SecCertificateCopyData(secCertificate) as Data
        let result = try assemble(certificateDER: der, expectedKey: key)
        let update = [kSecValueData as String: der]
        let updateStatus = SecItemUpdate(certificateQuery() as CFDictionary, update as CFDictionary)
        guard updateStatus == errSecSuccess else { throw StoreError.keychain(updateStatus) }
        return result
    }

    /// Returns nil only when no identity has ever been reserved. Corrupt,
    /// incomplete, expired, or mismatched records cause a failure instead.
    public func load() throws -> HostTLSIdentity? {
        var query = certificateQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw StoreError.keychain(status) }
        guard let der = result as? Data, der.count > 1 else { throw StoreError.incompleteOrInvalid }
        var queryForKey = keyQuery()
        queryForKey[kSecReturnRef as String] = true
        var keyResult: CFTypeRef?
        let keyStatus = SecItemCopyMatching(queryForKey as CFDictionary, &keyResult)
        guard keyStatus != errSecItemNotFound else { throw StoreError.incompleteOrInvalid }
        guard keyStatus == errSecSuccess else { throw StoreError.keychain(keyStatus) }
        guard let keyResult, CFGetTypeID(keyResult) == SecKeyGetTypeID() else { throw StoreError.incompleteOrInvalid }
        let key = keyResult as! SecKey
        return try assemble(certificateDER: der, expectedKey: key)
    }

    private func assemble(certificateDER: Data, expectedKey: SecKey) throws -> HostTLSIdentity {
        guard let secCertificate = SecCertificateCreateWithData(nil, certificateDER as CFData),
              let certificate = try? Certificate(secCertificate),
              certificate.notValidBefore <= Date(), certificate.notValidAfter > Date(),
              certificate.issuer == certificate.subject,
              certificate.publicKey.isValidSignature(certificate.signature, for: certificate) else {
            throw StoreError.incompleteOrInvalid
        }
        var identity: SecIdentity?
        guard SecIdentityCreateWithCertificate(nil, secCertificate, &identity) == errSecSuccess,
              let identity else { throw StoreError.incompleteOrInvalid }
        var identityKey: SecKey?
        guard SecIdentityCopyPrivateKey(identity, &identityKey) == errSecSuccess,
              let identityKey,
              let actualPublic = SecKeyCopyPublicKey(identityKey),
              let expectedPublic = SecKeyCopyPublicKey(expectedKey),
              let actualData = SecKeyCopyExternalRepresentation(actualPublic, nil) as Data?,
              let expectedData = SecKeyCopyExternalRepresentation(expectedPublic, nil) as Data?,
              actualData == expectedData else { throw StoreError.incompleteOrInvalid }
        return HostTLSIdentity(identity: identity, certificateDER: certificateDER,
                               fingerprintSHA256: Data(SHA256.hash(data: certificateDER)))
    }

    private func certificateQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func keyQuery() -> [String: Any] {
        [kSecClass as String: kSecClassKey,
         kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
         kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
         kSecAttrApplicationTag as String: keyTag,
         kSecMatchLimit as String: kSecMatchLimitOne]
    }
}
