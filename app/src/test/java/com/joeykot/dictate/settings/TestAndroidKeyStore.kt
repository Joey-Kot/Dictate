package com.joeykot.dictate.settings

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/** Supplies only the Android keystore master key; encryption still uses real JCE AES/GCM. */
object TestAndroidKeyStore {
    fun install() {
        check(Security.getProvider("AndroidKeyStore") == null)
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "In-memory test keystore") {
            init { put("KeyStore.AndroidKeyStore", MasterKeyStore::class.java.name) }
        })
    }
    fun remove() { Security.removeProvider("AndroidKeyStore") }

    class MasterKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = error("Unexpected key generation")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = error("Unexpected key generation")
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = error("No certificates")
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases() = Collections.enumeration(listOf("dictate_api_key_v1"))
        override fun engineContainsAlias(alias: String?) = alias == "dictate_api_key_v1"
        override fun engineSize() = 1
        override fun engineIsKeyEntry(alias: String?) = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    }
}
