@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package team.bjtuss.bjtuselfservice.shared.security

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import team.bjtuss.bjtuselfservice.shared.auth.Credentials

/**
 * iOS 与 macOS 原生共用的 Keychain 保险库（两边都是同一套 `SecItem` C API）。
 *
 * [accessibleAfterFirstUnlock] 是唯一的平台分叉：`kSecAttrAccessible` 是 iOS 的概念
 * （iOS 侧必须带 `AfterFirstUnlockThisDeviceOnly`，这是既定的安全决定），
 * macOS 上不能带——现在跑在 Mac 上的 JVM 版走同一套 `SecItem`，其查询里也没有这一项。
 */
class AppleKeychainCredentialVault(
    private val service: String = "team.bjtuss.bjtuselfservice.kmp.credentials",
    private val account: String = "primary",
    private val accessibleAfterFirstUnlock: Boolean = false,
) : CredentialVault {
    override suspend fun save(credentials: Credentials) {
        val payload = encodeCredentialPayload(credentials).toNSData()
        val clearStatus = withKeychainQuery { SecItemDelete(it) }
        if (clearStatus != errSecSuccess && clearStatus != errSecItemNotFound) {
            throw vaultError(CredentialVaultOperation.SAVE, clearStatus)
        }

        val addStatus = withKeychainQuery(payload = payload) { SecItemAdd(it, null) }
        if (addStatus != errSecSuccess) {
            throw vaultError(CredentialVaultOperation.SAVE, addStatus)
        }
    }

    override suspend fun load(): Credentials? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withKeychainQuery(returnData = true) {
            SecItemCopyMatching(it, result.ptr)
        }
        if (status == errSecItemNotFound) return@memScoped null
        if (status != errSecSuccess) throw vaultError(CredentialVaultOperation.LOAD, status)

        val value = result.value ?: throw vaultError(CredentialVaultOperation.LOAD, status)
        val data = CFBridgingRelease(value) as? NSData
            ?: throw vaultError(CredentialVaultOperation.LOAD, status)
        decodeCredentialPayload(data.toByteArray())
            ?: throw vaultError(CredentialVaultOperation.LOAD, status)
    }

    override suspend fun clear() {
        val status = withKeychainQuery { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw vaultError(CredentialVaultOperation.CLEAR, status)
        }
    }

    private fun <T> withKeychainQuery(
        payload: NSData? = null,
        returnData: Boolean = false,
        block: (CFDictionaryRef) -> T,
    ): T {
        val retainedValues = mutableListOf<CFTypeRef>()
        fun retain(value: Any): CFTypeRef = CFBridgingRetain(value)
            ?.also(retainedValues::add)
            ?: error("Unable to bridge Keychain value")

        val dictionary: CFMutableDictionaryRef = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = null,
            valueCallBacks = null,
        ) ?: error("Unable to create Keychain query")

        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, retain(service))
        CFDictionaryAddValue(dictionary, kSecAttrAccount, retain(account))
        if (payload != null) {
            if (accessibleAfterFirstUnlock) {
                CFDictionaryAddValue(
                    dictionary,
                    kSecAttrAccessible,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                )
            }
            CFDictionaryAddValue(dictionary, kSecValueData, retain(payload))
        }
        if (returnData) {
            CFDictionaryAddValue(dictionary, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
        }

        return try {
            block(dictionary)
        } finally {
            CFRelease(dictionary)
            retainedValues.forEach(::CFRelease)
        }
    }
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

private fun NSData.toByteArray(): ByteArray {
    if (length == 0uL) return byteArrayOf()
    val pointer = bytes?.reinterpret<ByteVar>() ?: return byteArrayOf()
    return pointer.readBytes(length.toInt())
}

private fun vaultError(
    operation: CredentialVaultOperation,
    status: Int,
): CredentialVaultException = CredentialVaultException(
    operation = operation,
    platformStatus = status,
)
