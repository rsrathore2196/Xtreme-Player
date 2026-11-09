/* Android Keystore-backed identity for Xtreme addon authentication. */
package com.example.data.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.MessageDigest
import java.security.GeneralSecurityException

/**
 * Creates a hardware-attestable P-256 signing key.
 *
 * The private key is generated and retained by AndroidKeyStore. It is never
 * serialized, exported, or returned to callers. The server must verify the
 * returned X.509 chain before trusting the public key.
 *
 * This class is intentionally not wired into StreamingAddons yet; the
 * server-side attestation verifier and request-proof protocol must be deployed
 * before addon traffic is switched to authenticated requests.
 */
internal object AddonDeviceIdentity {
    private const val KEY_ALIAS = "xtreme.addon.device.signing.v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val EC_CURVE = "secp256r1"
    private const val CHALLENGE_MIN_BYTES = 32
    private const val CHALLENGE_MAX_BYTES = 128

    data class PublicJwk(
        val kty: String,
        val crv: String,
        val x: String,
        val y: String
    )

    data class Identity(
        val publicJwk: PublicJwk,
        val thumbprint: String,
        /** Leaf-first DER X.509 certificate chain, Base64URL encoded. */
        val certificateChain: List<String>
    )

    /**
     * Generates a new key whose attestation extension contains [challenge].
     * Calling this again rotates the local identity and invalidates the old key.
     */
    @Synchronized
    fun createForAttestation(context: Context, challenge: ByteArray): Identity {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            "Android Key Attestation requires Android 7.0 or later"
        }
        require(challenge.size in CHALLENGE_MIN_BYTES..CHALLENGE_MAX_BYTES) {
            "The server attestation challenge must be 32–128 bytes"
        }

        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.copyOf())
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()

        return readIdentity(context)
            ?: throw GeneralSecurityException("Android Keystore did not return an attestation certificate chain")
    }

    /** Reads the current certificate chain and public key without exporting private material. */
    @Synchronized
    fun current(context: Context): Identity? = readIdentity(context)

    /** Signs canonical request bytes using the non-exportable Android Keystore key. */
    @Synchronized
    fun sign(context: Context, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Request proof payload must not be empty" }
        val keyStore = loadKeyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw GeneralSecurityException("Xtreme addon device key is not enrolled on this device")

        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    @Synchronized
    fun delete(context: Context) {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun readIdentity(context: Context): Identity? {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) return null

        val certificates = keyStore.getCertificateChain(KEY_ALIAS)?.toList().orEmpty()
        if (certificates.isEmpty()) {
            throw GeneralSecurityException("Android Keystore returned no attestation certificate chain")
        }

        val publicKey = certificates.first().publicKey as? ECPublicKey
            ?: throw GeneralSecurityException("The attested device key is not an EC public key")
        if (publicKey.params.curve.field.fieldSize != 256 || publicKey.params.order.bitLength() != 256) {
            throw GeneralSecurityException("The attested device key is not P-256")
        }

        val x = base64Url(unsignedFixed(publicKey.w.affineX, 32))
        val y = base64Url(unsignedFixed(publicKey.w.affineY, 32))
        val jwk = PublicJwk(kty = "EC", crv = "P-256", x = x, y = y)
        val canonicalJwk = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val thumbprint = base64Url(
            MessageDigest.getInstance("SHA-256").digest(canonicalJwk.toByteArray(Charsets.UTF_8))
        )

        return Identity(
            publicJwk = jwk,
            thumbprint = thumbprint,
            certificateChain = certificates.map { base64Url(it.encoded) }
        )
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun unsignedFixed(value: BigInteger, size: Int): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size > 1 && encoded[0] == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        require(unsigned.size <= size) { "Invalid P-256 public key coordinate" }
        return ByteArray(size - unsigned.size) + unsigned
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
