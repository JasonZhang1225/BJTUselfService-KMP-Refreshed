package team.bjtuss.bjtuselfservice.shared.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoolTlsTrustManagerTest {
    @Test
    fun bundledIntermediateMatchesPinnedFingerprintAndChainsToSystemRoot() {
        val intermediates = defaultSchoolSupplementaryIntermediates()
        assertEquals(1, intermediates.size)
        val intermediate = intermediates.single()

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(intermediate.encoded)
            .joinToString(":") { "%02X".format(it) }
        assertEquals(
            "05:DC:9E:DC:0F:DD:FA:97:5A:14:32:EF:80:6E:C7:80:07:8B:53:62:AD:45:AF:76:DB:15:C9:07:63:0D:B2:5D",
            sha256,
        )
        assertTrue(intermediate.basicConstraints >= 0)
        assertEquals(
            listOf("http://cacerts.digicert.cn/DigiCertGlobalRootG2.crt"),
            extractAiaCaIssuersUrls(intermediate),
        )

        val systemTrust = systemDefaultX509TrustManager()
        val trustManager = SchoolTlsTrustManager.default()
        val systemIssuerSet = systemTrust.acceptedIssuers.map { it.encoded.contentHashCode() }.toSet()
        assertEquals(systemTrust.acceptedIssuers.size, trustManager.acceptedIssuers.size)
        assertFalse(trustManager.acceptedIssuers.any { it.encoded.contentEquals(intermediate.encoded) })
        assertTrue(systemIssuerSet.isNotEmpty())

        // 验证内置中间证书可以直接链接到系统的 DigiCert Global Root G2 信任锚。
        val rootTrustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            systemTrust.acceptedIssuers.forEachIndexed { idx, cert ->
                setCertificateEntry("sys-$idx", cert)
            }
        }
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(listOf(intermediate))
        val validator = CertPathValidator.getInstance("PKIX")
        val params = PKIXParameters(rootTrustStore).apply { isRevocationEnabled = false }
        validator.validate(certPath, params)
    }

    @Test
    fun bundledPemStartsWithDashAndParserNormalizesPreambleAndDer() {
        // 旧版 Android Conscrypt 严格要求 PEM 流第 0 字节是 '-'，不能有前导换行或空白。
        assertTrue(GEOTRUST_G2_TLS_CN_2022_CA1_PEM.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertEquals('-', GEOTRUST_G2_TLS_CN_2022_CA1_PEM.first())
        assertFalse('\r' in GEOTRUST_G2_TLS_CN_2022_CA1_PEM)

        val expected = defaultSchoolSupplementaryIntermediates().single()
        val pemWithPreamble = (
            "\uFEFF\n  \r\n# leading comment before PEM\r\n" +
                GEOTRUST_G2_TLS_CN_2022_CA1_PEM.replace("\n", "\r\n") +
                "\r\n# trailing text\r\n"
            ).toByteArray(Charsets.UTF_8)
        val normalized = normalizeX509CertificateBytes(pemWithPreamble)
        assertEquals('-'.code.toByte(), normalized.first())
        val parsedPem = parseX509Certificates(pemWithPreamble)
        assertEquals(1, parsedPem.size)
        assertTrue(expected.encoded.contentEquals(parsedPem.single().encoded))

        // 原始 DER 字节（以 ASN.1 SEQUENCE 0x30 开头）与多段 PEM 均应正常解析。
        val derBytes = expected.encoded
        assertEquals(0x30.toByte(), derBytes.first())
        val parsedDer = parseX509Certificates(derBytes)
        assertEquals(1, parsedDer.size)
        assertTrue(expected.encoded.contentEquals(parsedDer.single().encoded))

        val twoPem = (
            "\n# cert 1\n$GEOTRUST_G2_TLS_CN_2022_CA1_PEM\n# cert 2\n$GEOTRUST_G2_TLS_CN_2022_CA1_PEM"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(2, parseX509Certificates(twoPem).size)
    }

    @Test
    fun completesEligibleBjtuChainAndRejectsUntrustedOrForeignOrInvalidCertificates() {
        val dir = Files.createTempDirectory("school-tls-test").toFile()
        try {
            val pki = buildSyntheticPki(dir)
            val trustedDelegate = trustManagerForRoots(pki.trustedRoot)
            val manager = SchoolTlsTrustManager(
                delegate = trustedDelegate,
                supplementaryIntermediates = listOf(pki.trustedIntermediate),
                peerIntermediateLoader = { emptyList() },
                aiaCertificateFetcher = { emptyList() },
            )

            // 1. 完整链直接通过（不触发补链也正常）。
            manager.checkServerTrusted(arrayOf(pki.bjtuLeaf, pki.trustedIntermediate), "RSA")

            // 2. 仅下发 leaf 的 *.bjtu.edu.cn 链在系统信任该根 CA 时自动补链并通过。
            manager.checkServerTrusted(arrayOf(pki.bjtuLeaf), "RSA")

            // 3. 根 CA 不被信任时，即使补入同 Subject 中间证书或孤立中间证书，也必须抛出异常（绝不当作 TrustAnchor）。
            val untrustedRootManager = SchoolTlsTrustManager(
                delegate = trustManagerForRoots(pki.unrelatedRoot),
                supplementaryIntermediates = listOf(pki.trustedIntermediate),
                peerIntermediateLoader = { emptyList() },
                aiaCertificateFetcher = { emptyList() },
            )
            assertFailsWith<CertificateException> {
                untrustedRootManager.checkServerTrusted(arrayOf(pki.bjtuLeaf), "RSA")
            }

            // 4. 非 bjtu.edu.cn 域名（含形似后缀/前缀域名）不完整链不补链、直接拒绝。
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(pki.foreignLeaf), "RSA")
            }

            // 5. 签名不匹配（伪造公钥但同名 Issuer）的 *.bjtu.edu.cn leaf 被拒绝。
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(pki.forgedBjtuLeaf), "RSA")
            }

            // 6. 已过期的 *.bjtu.edu.cn leaf 即使补链也必须被系统 TrustManager 拒绝。
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(pki.expiredBjtuLeaf), "RSA")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun learnsIntermediatesPassivelyAndFromPeersAndFromAiaWithCooldown() {
        val dir = Files.createTempDirectory("school-tls-layers").toFile()
        try {
            val pki = buildSyntheticPki(dir)
            val trustedDelegate = trustManagerForRoots(pki.trustedRoot)

            // 第 1/2 层被动学习：完整链通过后缓存中间证书，随后仅下发 leaf 时 0ms 命中缓存，不触发外部探测。
            var passivePeerCalls = 0
            var passiveAiaCalls = 0
            val passiveManager = SchoolTlsTrustManager(
                delegate = trustedDelegate,
                supplementaryIntermediates = emptyList(),
                peerIntermediateLoader = { passivePeerCalls++; emptyList() },
                aiaCertificateFetcher = { passiveAiaCalls++; emptyList() },
            )
            passiveManager.checkServerTrusted(arrayOf(pki.bjtuLeaf, pki.trustedIntermediate), "RSA")
            passiveManager.checkServerTrusted(arrayOf(pki.bjtuLeaf), "RSA")
            assertEquals(0, passivePeerCalls)
            assertEquals(0, passiveAiaCalls)

            // 第 2 层同校 HTTPS 节点学习：无内置且未缓存时探测同校节点，学到后缓存并用于后续请求。
            var peerCalls = 0
            var peerAiaCalls = 0
            val peerManager = SchoolTlsTrustManager(
                delegate = trustedDelegate,
                supplementaryIntermediates = emptyList(),
                peerIntermediateLoader = {
                    peerCalls++
                    listOf(pki.trustedIntermediate)
                },
                aiaCertificateFetcher = { peerAiaCalls++; emptyList() },
            )
            peerManager.checkServerTrusted(arrayOf(pki.bjtuLeaf), "RSA")
            peerManager.checkServerTrusted(arrayOf(pki.bjtuLeaf), "RSA")
            assertEquals(1, peerCalls)
            assertEquals(0, peerAiaCalls)

            // 第 3 层受限 AIA 动态补链：前两层未命中时解析叶证书 AIA URL 并验证下载的中间证书。
            var aiaCalls = 0
            val fetchedUrls = mutableListOf<String>()
            val leafWithAia = LeafWithAiaUrls(
                pki.bjtuLeaf,
                listOf("https://ca.example.com/geotrust-inter.crt"),
            )
            val aiaManager = SchoolTlsTrustManager(
                delegate = trustedDelegate,
                supplementaryIntermediates = emptyList(),
                peerIntermediateLoader = { emptyList() },
                aiaCertificateFetcher = { url ->
                    aiaCalls++
                    fetchedUrls += url
                    listOf(pki.trustedIntermediate)
                },
            )
            aiaManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            aiaManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            assertEquals(1, aiaCalls)
            assertEquals(listOf("https://ca.example.com/geotrust-inter.crt"), fetchedUrls)

            // 即使 AIA 返回由不相关/不被信任根签发的中间证书，依然必须被系统根信任校验拒绝。
            val untrustedAiaManager = SchoolTlsTrustManager(
                delegate = trustManagerForRoots(pki.unrelatedRoot),
                supplementaryIntermediates = emptyList(),
                peerIntermediateLoader = { emptyList() },
                aiaCertificateFetcher = { listOf(pki.trustedIntermediate) },
            )
            assertFailsWith<CertificateException> {
                untrustedAiaManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            }

            // 失败冷却：5 分钟内重复失败不再打同校节点或同一失败 AIA URL，超时后恢复探测。
            var now = 1_000_000L
            var cooldownPeerCalls = 0
            var cooldownAiaCalls = 0
            val cooldownManager = SchoolTlsTrustManager(
                delegate = trustedDelegate,
                supplementaryIntermediates = emptyList(),
                peerIntermediateLoader = { cooldownPeerCalls++; emptyList() },
                aiaCertificateFetcher = { cooldownAiaCalls++; emptyList() },
                nowMillis = { now },
            )
            assertFailsWith<CertificateException> {
                cooldownManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            }
            assertEquals(1, cooldownPeerCalls)
            assertEquals(1, cooldownAiaCalls)
            now += 60_000L
            assertFailsWith<CertificateException> {
                cooldownManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            }
            assertEquals(1, cooldownPeerCalls)
            assertEquals(1, cooldownAiaCalls)
            now += 5 * 60_000L
            assertFailsWith<CertificateException> {
                cooldownManager.checkServerTrusted(arrayOf(leafWithAia), "RSA")
            }
            assertEquals(2, cooldownPeerCalls)
            assertEquals(2, cooldownAiaCalls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aiaUrlAndAddressGuardsRejectPrivateLocalOrNonHttpTargets() {
        assertTrue(isAllowedAiaUrl("http://cacerts.digicert.cn/GeoTrustG2TLSCNRSA4096SHA2562022CA1.crt"))
        assertTrue(isAllowedAiaUrl("https://cacerts.digicert.com/GeoTrust.crt"))

        val rejectedUrls = listOf(
            "file:///etc/passwd",
            "ldap://ldap.example.com/cn=ca",
            "http://127.0.0.1/ca.crt",
            "http://[::1]/ca.crt",
            "http://2130706433/ca.crt",
            "http://localhost/ca.crt",
            "http://ca.local/ca.crt",
            "http://ca.internal/ca.crt",
            "http://user:pass@cacerts.digicert.cn/ca.crt",
            "http://cacerts.digicert.cn:8080/ca.crt",
            "https://cacerts.digicert.cn:8443/ca.crt",
        )
        rejectedUrls.forEach { url ->
            assertFalse(isAllowedAiaUrl(url), "Expected rejected AIA URL: $url")
        }

        val privateAddresses = listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.168.1.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.5",
        )
        privateAddresses.forEach { ip ->
            assertFalse(isPublicRoutableAddress(InetAddress.getByName(ip)), "Expected private/reserved IP rejected: $ip")
        }
        assertTrue(isPublicRoutableAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicRoutableAddress(InetAddress.getByName("117.131.198.1")))
    }

    private class LeafWithAiaUrls(
        private val base: X509Certificate,
        urls: List<String>,
    ) : X509Certificate() {
        private val aiaExtensionValue: ByteArray = encodeAiaExtensionValue(urls)

        override fun getExtensionValue(oid: String?): ByteArray? =
            if (oid == "1.3.6.1.5.5.7.1.1") aiaExtensionValue.copyOf() else base.getExtensionValue(oid)

        override fun checkValidity() = base.checkValidity()
        override fun checkValidity(date: Date?) = base.checkValidity(date)
        override fun getVersion(): Int = base.version
        override fun getSerialNumber(): BigInteger = base.serialNumber
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getIssuerDN(): Principal = base.issuerDN
        override fun getIssuerX500Principal(): X500Principal = base.issuerX500Principal
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getSubjectDN(): Principal = base.subjectDN
        override fun getSubjectX500Principal(): X500Principal = base.subjectX500Principal
        override fun getNotBefore(): Date = base.notBefore
        override fun getNotAfter(): Date = base.notAfter
        override fun getTBSCertificate(): ByteArray = base.tbsCertificate
        override fun getSignature(): ByteArray = base.signature
        override fun getSigAlgName(): String = base.sigAlgName
        override fun getSigAlgOID(): String = base.sigAlgOID
        override fun getSigAlgParams(): ByteArray? = base.sigAlgParams
        override fun getIssuerUniqueID(): BooleanArray? = base.issuerUniqueID
        override fun getSubjectUniqueID(): BooleanArray? = base.subjectUniqueID
        override fun getKeyUsage(): BooleanArray? = base.keyUsage
        override fun getBasicConstraints(): Int = base.basicConstraints
        override fun getSubjectAlternativeNames() = base.subjectAlternativeNames
        override fun getEncoded(): ByteArray = base.encoded
        override fun verify(key: PublicKey?) = base.verify(key)
        override fun verify(key: PublicKey?, sigProvider: String?) = base.verify(key, sigProvider)
        override fun toString(): String = base.toString()
        override fun getPublicKey(): PublicKey = base.publicKey
        override fun hasUnsupportedCriticalExtension(): Boolean = base.hasUnsupportedCriticalExtension()
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = base.criticalExtensionOIDs
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = base.nonCriticalExtensionOIDs
    }

    private data class SyntheticPki(
        val trustedRoot: X509Certificate,
        val unrelatedRoot: X509Certificate,
        val trustedIntermediate: X509Certificate,
        val bjtuLeaf: X509Certificate,
        val foreignLeaf: X509Certificate,
        val forgedBjtuLeaf: X509Certificate,
        val expiredBjtuLeaf: X509Certificate,
    )

    private fun trustManagerForRoots(vararg roots: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            roots.forEachIndexed { index, cert ->
                setCertificateEntry("root-$index", cert)
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun buildSyntheticPki(dir: File): SyntheticPki {
        val ks = File(dir, "pki.p12").absolutePath
        val keytool = File(System.getProperty("java.home"), "bin/keytool").let {
            if (it.isFile) it.absolutePath else "keytool"
        }

        fun runKeytool(vararg args: String) {
            val process = ProcessBuilder(keytool, *args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                error("keytool ${args.joinToString(" ")} failed ($exit):\n$output")
            }
        }

        fun readCert(name: String): X509Certificate {
            val factory = CertificateFactory.getInstance("X.509")
            return File(dir, name).inputStream().use { factory.generateCertificate(it) as X509Certificate }
        }

        // 受信任 Root CA
        runKeytool(
            "-genkeypair", "-alias", "root", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Test Trusted Root CA", "-validity", "3650",
            "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-exportcert", "-alias", "root", "-keystore", ks, "-storepass", "changeit", "-rfc", "-file", "root.pem")

        // 不相关 Root CA
        runKeytool(
            "-genkeypair", "-alias", "otherroot", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Test Unrelated Root CA", "-validity", "3650",
            "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-exportcert", "-alias", "otherroot", "-keystore", ks, "-storepass", "changeit", "-rfc", "-file", "otherroot.pem")

        // 中间 CA（由 root 签发）
        runKeytool(
            "-genkeypair", "-alias", "inter", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Test GeoTrust Intermediate CA", "-validity", "3650",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-certreq", "-alias", "inter", "-keystore", ks, "-storepass", "changeit", "-file", "inter.csr")
        runKeytool(
            "-gencert", "-alias", "root", "-infile", "inter.csr", "-outfile", "inter.pem", "-rfc", "-validity", "3650",
            "-ext", "bc:c=ca:true,pathlen:0", "-ext", "ku:c=keyCertSign,cRLSign",
            "-keystore", ks, "-storepass", "changeit",
        )
        File(dir, "inter-chain.pem").writeText(File(dir, "inter.pem").readText() + File(dir, "root.pem").readText())
        runKeytool("-importcert", "-alias", "inter", "-noprompt", "-file", "inter-chain.pem", "-keystore", ks, "-storepass", "changeit")

        // 有效 *.bjtu.edu.cn 叶子证书
        runKeytool(
            "-genkeypair", "-alias", "bjtu", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=*.bjtu.edu.cn", "-validity", "365",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-certreq", "-alias", "bjtu", "-keystore", ks, "-storepass", "changeit", "-file", "bjtu.csr")
        runKeytool(
            "-gencert", "-alias", "inter", "-infile", "bjtu.csr", "-outfile", "bjtu.pem", "-rfc", "-validity", "365",
            "-ext", "san=dns:*.bjtu.edu.cn,dns:bjtu.edu.cn", "-ext", "eku=serverAuth",
            "-ext", "ku:c=digitalSignature,keyEncipherment",
            "-keystore", ks, "-storepass", "changeit",
        )

        // 非学校域名叶子证书（测试不会对非 bjtu.edu.cn 或形似域名补链）
        runKeytool(
            "-genkeypair", "-alias", "foreign", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=example.com", "-validity", "365",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-certreq", "-alias", "foreign", "-keystore", ks, "-storepass", "changeit", "-file", "foreign.csr")
        runKeytool(
            "-gencert", "-alias", "inter", "-infile", "foreign.csr", "-outfile", "foreign.pem", "-rfc", "-validity", "365",
            "-ext", "san=dns:example.com,dns:evilbjtu.edu.cn,dns:bjtu.edu.cn.evil.com", "-ext", "eku=serverAuth",
            "-ext", "ku:c=digitalSignature,keyEncipherment",
            "-keystore", ks, "-storepass", "changeit",
        )

        // 伪造签名的 *.bjtu.edu.cn（由 rogue 同名中间 CA 签发）
        runKeytool(
            "-genkeypair", "-alias", "rogueinter", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Test GeoTrust Intermediate CA", "-validity", "3650",
            "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool(
            "-genkeypair", "-alias", "forged", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=*.bjtu.edu.cn", "-validity", "365",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-certreq", "-alias", "forged", "-keystore", ks, "-storepass", "changeit", "-file", "forged.csr")
        runKeytool(
            "-gencert", "-alias", "rogueinter", "-infile", "forged.csr", "-outfile", "forged.pem", "-rfc", "-validity", "365",
            "-ext", "san=dns:*.bjtu.edu.cn", "-ext", "eku=serverAuth",
            "-keystore", ks, "-storepass", "changeit",
        )

        // 已过期的 *.bjtu.edu.cn 叶子证书
        runKeytool(
            "-genkeypair", "-alias", "expired", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=*.bjtu.edu.cn", "-validity", "365",
            "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        )
        runKeytool("-certreq", "-alias", "expired", "-keystore", ks, "-storepass", "changeit", "-file", "expired.csr")
        runKeytool(
            "-gencert", "-alias", "inter", "-infile", "expired.csr", "-outfile", "expired.pem", "-rfc",
            "-startdate", "-30d", "-validity", "1",
            "-ext", "san=dns:*.bjtu.edu.cn", "-ext", "eku=serverAuth",
            "-keystore", ks, "-storepass", "changeit",
        )

        return SyntheticPki(
            trustedRoot = readCert("root.pem"),
            unrelatedRoot = readCert("otherroot.pem"),
            trustedIntermediate = readCert("inter.pem"),
            bjtuLeaf = readCert("bjtu.pem"),
            foreignLeaf = readCert("foreign.pem"),
            forgedBjtuLeaf = readCert("forged.pem"),
            expiredBjtuLeaf = readCert("expired.pem"),
        )
    }

    private companion object {
        fun encodeDerTlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            if (value.size < 0x80) {
                out.write(value.size)
            } else {
                val lenBytes = ByteArrayOutputStream()
                var v = value.size
                while (v > 0) {
                    lenBytes.write(v and 0xFF)
                    v = v ushr 8
                }
                val rawLen = lenBytes.toByteArray().reversedArray()
                out.write(0x80 or rawLen.size)
                out.write(rawLen)
            }
            out.write(value)
            return out.toByteArray()
        }

        fun encodeAiaExtensionValue(urls: List<String>): ByteArray {
            val caIssuersOid = encodeDerTlv(
                0x06,
                byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02),
            )
            val seqBody = ByteArrayOutputStream()
            for (url in urls) {
                val uriTlv = encodeDerTlv(0x86, url.toByteArray(Charsets.US_ASCII))
                val desc = encodeDerTlv(0x30, caIssuersOid + uriTlv)
                seqBody.write(desc)
            }
            val outerSeq = encodeDerTlv(0x30, seqBody.toByteArray())
            return encodeDerTlv(0x04, outerSeq)
        }
    }
}
