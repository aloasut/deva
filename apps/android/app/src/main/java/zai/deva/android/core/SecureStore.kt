package zai.deva.android.core

/**
 * EncryptedSharedPreferences 封装（设计 §4）：按 deviceId 存 token。
 * P2 起实现（引入 androidx.security:security-crypto 依赖后）；v1 先只定接口形状。
 */
interface SecureStore {
    fun saveToken(deviceId: String, token: String)
    fun loadToken(deviceId: String): String?
    fun removeToken(deviceId: String)
}
