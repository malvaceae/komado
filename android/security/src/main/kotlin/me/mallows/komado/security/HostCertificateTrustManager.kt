package me.mallows.komado.security

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Authenticates a host by its certificate's SHA-256 fingerprint. The expected
 * fingerprint must already have been obtained and confirmed out of band; never
 * initialize it from a certificate received over the same connection.
 * A successful TLS handshake must still be followed by device authentication.
 */
class HostCertificateTrustManager(expectedFingerprintSha256: ByteArray) : X509TrustManager {
    private val fingerprint = expectedFingerprintSha256.copyOf()

    init {
        require(fingerprint.size == 32) { "Expected a SHA-256 fingerprint" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain == null || chain.size != 1 || authType.isNullOrBlank()) {
            throw CertificateException("Invalid host certificate chain")
        }
        val certificate = chain[0]
        try {
            val actualFingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            if (!MessageDigest.isEqual(fingerprint, actualFingerprint)) {
                throw CertificateException("Host certificate fingerprint mismatch")
            }
            certificate.checkValidity()
            if (certificate.basicConstraints != -1 || certificate.issuerX500Principal != certificate.subjectX500Principal) {
                throw CertificateException("Expected a self-signed host certificate")
            }
            certificate.verify(certificate.publicKey)
            val keyUsage = certificate.keyUsage
            if (keyUsage != null && (keyUsage.isEmpty() || !keyUsage[0])) {
                throw CertificateException("Certificate cannot sign TLS handshakes")
            }
            val extendedKeyUsage = certificate.extendedKeyUsage
            if (extendedKeyUsage != null && SERVER_AUTH !in extendedKeyUsage) {
                throw CertificateException("Certificate is not valid for TLS servers")
            }
        } catch (error: CertificateException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw CertificateException("Invalid host certificate", error)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    }
}
