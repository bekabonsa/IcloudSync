package dev.bex.icloudsync.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.bex.icloudsync.data.model.AccountSecrets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface SecretStore {
    fun load(): AccountSecrets?
    fun save(secrets: AccountSecrets)
    fun clear()
}

@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : SecretStore {
    private val prefs = context.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE)

    override fun load(): AccountSecrets? = runCatching {
        val encrypted = prefs.getString(DATA, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        json.decodeFromString<AccountSecrets>(
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString(),
        )
    }.getOrNull()

    override fun save(secrets: AccountSecrets) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.encodeToString(secrets).encodeToByteArray())
        prefs.edit()
            .putString(DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "icloud_sync_account_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATA = "data"
        const val IV = "iv"
    }
}

