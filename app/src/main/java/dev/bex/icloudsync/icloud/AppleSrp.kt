package dev.bex.icloudsync.icloud

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Apple's SRP-6a variant: SHA-256, RFC 5054 padding, username omitted from x. */
internal class AppleSrp(private val username: String, secretBytes: ByteArray? = null) {
    private val random = SecureRandom()
    private val a = (secretBytes ?: ByteArray(256).also {
        random.nextBytes(it)
        it[0] = (it[0].toInt() or 0x80).toByte()
    }).also { require(it.size == 256) }.toPositiveBigInteger()
    private val publicA = G.modPow(a, N)

    fun publicA(): String = publicA.unsignedBytes().base64()

    fun complete(
        password: String,
        saltBase64: String,
        serverBBase64: String,
        iterations: Int,
        protocol: String,
    ): Proof {
        val salt = saltBase64.decodeBase64()
        val serverB = serverBBase64.decodeBase64().toPositiveBigInteger()
        require(serverB.mod(N) != BigInteger.ZERO) { "Unsafe SRP server value" }

        val passwordHash = sha256(password.encodeToByteArray())
        val pbkdfInput = when (protocol) {
            "s2k" -> passwordHash
            "s2k_fo" -> passwordHash.joinToString("") { "%02x".format(it) }.encodeToByteArray()
            else -> throw ICloudException.Protocol("Unsupported Apple SRP protocol")
        }
        val derivedPassword = pbkdf2Sha256(pbkdfInput, salt, iterations, 32)
        val x = hashToInteger(salt, sha256(byteArrayOf(':'.code.toByte()) + derivedPassword))
        val width = N.unsignedBytes().size
        val k = hashToInteger(N.padded(width), G.padded(width))
        val u = hashToInteger(publicA.padded(width), serverB.padded(width))
        require(u != BigInteger.ZERO) { "Unsafe SRP scrambling value" }

        val verifier = G.modPow(x, N)
        val base = serverB.subtract(k.multiply(verifier)).mod(N)
        val exponent = a.add(u.multiply(x))
        val session = base.modPow(exponent, N)
        val sessionKey = sha256(session.unsignedBytes())

        val hN = sha256(N.unsignedBytes())
        val hG = sha256(G.padded(width))
        val xor = ByteArray(hN.size) { hN[it].toInt().xor(hG[it].toInt()).toByte() }
        val m1 = sha256(
            xor,
            sha256(username.encodeToByteArray()),
            salt,
            publicA.unsignedBytes(),
            serverB.unsignedBytes(),
            sessionKey,
        )
        val m2 = sha256(publicA.unsignedBytes(), m1, sessionKey)
        return Proof(m1.base64(), m2.base64())
    }

    data class Proof(val m1: String, val m2: String)

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(password, "HmacSHA256"))
        }
        val output = ByteArray(length)
        var blockIndex = 1
        var offset = 0
        while (offset < length) {
            val index = byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            )
            var u = mac.doFinal(salt + index)
            val block = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in block.indices) block[i] = block[i].toInt().xor(u[i].toInt()).toByte()
            }
            val amount = minOf(block.size, length - offset)
            block.copyInto(output, offset, 0, amount)
            offset += amount
            blockIndex++
        }
        return output
    }

    private fun hashToInteger(vararg values: ByteArray): BigInteger = sha256(*values).toPositiveBigInteger()

    private fun sha256(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        values.forEach(::update)
        digest()
    }

    private fun BigInteger.padded(width: Int): ByteArray = unsignedBytes().let { bytes ->
        if (bytes.size >= width) bytes else ByteArray(width - bytes.size) + bytes
    }

    private fun BigInteger.unsignedBytes(): ByteArray = toByteArray().let { bytes ->
        if (bytes.size > 1 && bytes.first() == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun ByteArray.toPositiveBigInteger() = BigInteger(1, this)
    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)

    private companion object {
        val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855" +
                "F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97" +
                "B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523" +
                "B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7" +
                "303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803" +
                "D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
        val G = BigInteger.valueOf(2)
    }
}
