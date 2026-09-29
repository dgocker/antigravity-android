package com.antigravity.client

import com.antigravity.client.security.KeystoreManager
import org.junit.Assert.*
import org.junit.Test

class TokenStoreTest {

    @Test
    fun testKeystoreEncryptionAndDecryption() {
        val keystoreManager = KeystoreManager(null)
        val originalToken = "agy_android_9f8e7d6c5b4a3210abcdef0123456789"

        val encrypted = keystoreManager.encrypt(originalToken)
        assertNotNull(encrypted)
        assertNotEquals(originalToken, encrypted)

        val decrypted = keystoreManager.decrypt(encrypted)
        assertEquals(originalToken, decrypted)
    }

    @Test
    fun testKeystoreEmptyString() {
        val keystoreManager = KeystoreManager(null)
        val encrypted = keystoreManager.encrypt("")
        assertEquals("", encrypted)

        val decrypted = keystoreManager.decrypt("")
        assertEquals("", decrypted)
    }
}
