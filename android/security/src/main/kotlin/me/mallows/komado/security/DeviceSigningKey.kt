package me.mallows.komado.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/** Keystore-backed signing primitive. Pairing and replay prevention are separate. */
class DeviceSigningKey(private val alias: String) {
    init {
        val prefix = "me.mallows.komado."
        require(alias.startsWith(prefix) && alias.length in (prefix.length + 1)..128 &&
            alias.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }
        ) { "Invalid key alias" }
    }

    /** Creates a key if needed and returns its X.509 SubjectPublicKeyInfo DER. */
    fun getOrCreatePublicKeyDER(): ByteArray = synchronized(keyLock) {
        val store = keyStore()
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val parameters = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(parameters)
            generator.generateKeyPair()
        }
        val key = store.getCertificate(alias)?.publicKey as? ECPublicKey
            ?: throw KeyStoreException("Device public key unavailable")
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val actual = key.params
        if (actual.curve != expected.curve || actual.generator != expected.generator ||
            actual.order != expected.order || actual.cofactor != expected.cofactor
        ) throw KeyStoreException("Unexpected device key")
        key.encoded ?: throw KeyStoreException("Device public key is not exportable")
    }

    /** Signs the protocol domain and a 32-byte challenge with SHA-256 / P-256. */
    fun signChallenge(challenge: ByteArray): ByteArray = synchronized(keyLock) {
        require(challenge.size == 32) { "Challenge must contain 32 bytes" }
        val key = keyStore().getKey(alias, null) as? PrivateKey
            ?: throw KeyStoreException("Device signing key unavailable")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(DOMAIN)
        signature.update(challenge.copyOf())
        signature.sign()
    }

    fun delete() = synchronized(keyLock) {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private val DOMAIN = "komado.device-auth.v1\u0000".toByteArray(Charsets.UTF_8)
        private val keyLock = Any()
    }
}
