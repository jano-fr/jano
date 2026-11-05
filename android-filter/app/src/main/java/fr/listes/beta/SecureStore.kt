package fr.listes.beta

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SecureStore(context: Context) {
    companion object { private val stateLock=Any() }
    private val file = AtomicFile(File(context.filesDir, "private-state.bin"))
    private val key: SecretKey
    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "listes-sociales-beta-v2"
        if (!ks.containsAlias(alias)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            gen.generateKey()
        }
        key = ks.getKey(alias, null) as SecretKey
    }
    fun read(): JSONObject = synchronized(stateLock) { readUnlocked() }
    private fun readUnlocked(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        val data = file.openRead().use { it.readBytes() }
        require(data.size > 28) { "Stockage incomplet" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    fun write(json: JSONObject) = synchronized(stateLock) { writeUnlocked(json) }
    fun update(change:(JSONObject)->Unit) = synchronized(stateLock) {
        val state=readUnlocked();change(state);writeUnlocked(state)
    }
    private fun writeUnlocked(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(data); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun clear() = synchronized(stateLock) { file.delete() }
}
