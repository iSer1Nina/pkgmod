package com.pkgmod.sign

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSigning {

    fun sign(
        input: File,
        output: File,
        keystoreStream: InputStream,
        alias: String,
        storePassword: CharArray,
        minSdk: Int
    ) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keystoreStream.use { keyStore.load(it, storePassword) }

        val key = keyStore.getKey(alias, storePassword) as? PrivateKey
            ?: throw IllegalStateException("keystore 中找不到私钥")

        val chain = keyStore.getCertificateChain(alias)
            ?.map { it as X509Certificate }
            ?: emptyList()
        require(chain.isNotEmpty()) { "keystore 中找不到证书链" }

        val config = ApkSigner.SignerConfig.Builder("pkgmod", key, chain).build()

        val signer = ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdk)
            .setOtherSignersSignaturesPreserved(false)
            .build()

        signer.sign()
    }
}
