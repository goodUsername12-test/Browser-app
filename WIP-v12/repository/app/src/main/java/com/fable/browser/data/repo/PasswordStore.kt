package com.fable.browser.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SavedLogin(
    val guid: String,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
    val created: Long,
    val lastUsed: Long,
    val timesUsed: Int
) {
    val host: String get() = PasswordStore.hostOf(origin)

    fun toJson(): JSONObject = JSONObject().apply {
        put("guid", guid)
        put("origin", origin)
        put("formActionOrigin", formActionOrigin ?: JSONObject.NULL)
        put("httpRealm", httpRealm ?: JSONObject.NULL)
        put("username", username)
        put("password", password)
        put("created", created)
        put("lastUsed", lastUsed)
        put("timesUsed", timesUsed)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedLogin? {
            val origin = o.optString("origin").takeIf { it.isNotBlank() } ?: return null
            return SavedLogin(
                guid = o.optString("guid").ifBlank { UUID.randomUUID().toString() },
                origin = origin,
                formActionOrigin = o.optString("formActionOrigin").takeIf { !o.isNull("formActionOrigin") && it.isNotBlank() },
                httpRealm = o.optString("httpRealm").takeIf { !o.isNull("httpRealm") && it.isNotBlank() },
                username = o.optString("username"),
                password = o.optString("password"),
                created = o.optLong("created", System.currentTimeMillis()),
                lastUsed = o.optLong("lastUsed", 0L),
                timesUsed = o.optInt("timesUsed", 0)
            )
        }
    }
}

@Singleton
class PasswordStore @Inject constructor(
    private val secureStore: SecureStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var loaded = false
    private val _logins = MutableStateFlow<List<SavedLogin>>(emptyList())
    val logins: StateFlow<List<SavedLogin>> = _logins.asStateFlow()

    init {
        scope.launch { ensureLoaded() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val text = secureStore.read(FILE)
            val list = runCatching {
                val arr = JSONArray(text ?: "[]")
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(SavedLogin::fromJson) }
            }.getOrDefault(emptyList())
            _logins.value = list.sortedBy { it.host }
            loaded = true
        }
    }

    fun all(): List<SavedLogin> {
        ensureLoaded()
        return _logins.value
    }

    fun forDomain(domain: String): List<SavedLogin> {
        val wanted = hostOf(domain).removePrefix("www.")
        if (wanted.isEmpty()) return emptyList()
        return all().filter {
            val h = it.host.removePrefix("www.")
            h == wanted || h.endsWith(".$wanted") || wanted.endsWith(".$h")
        }.sortedByDescending { it.lastUsed }
    }

    fun find(guid: String?): SavedLogin? = guid?.let { g -> all().firstOrNull { it.guid == g } }

    fun matching(origin: String, username: String): SavedLogin? {
        val host = hostOf(origin)
        return all().firstOrNull { it.host == host && it.username == username }
    }

    fun upsert(
        guid: String?,
        origin: String,
        formActionOrigin: String?,
        httpRealm: String?,
        username: String,
        password: String
    ): SavedLogin {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val result: SavedLogin
        synchronized(lock) {
            val list = _logins.value.toMutableList()
            val index = list.indexOfFirst { (guid != null && it.guid == guid) || (it.host == hostOf(origin) && it.username == username) }
            result = if (index >= 0) {
                list[index].copy(
                    origin = origin,
                    formActionOrigin = formActionOrigin ?: list[index].formActionOrigin,
                    httpRealm = httpRealm ?: list[index].httpRealm,
                    username = username,
                    password = password
                ).also { list[index] = it }
            } else {
                SavedLogin(
                    guid = guid?.takeIf { g -> list.none { it.guid == g } } ?: UUID.randomUUID().toString(),
                    origin = origin,
                    formActionOrigin = formActionOrigin,
                    httpRealm = httpRealm,
                    username = username,
                    password = password,
                    created = now,
                    lastUsed = now,
                    timesUsed = 0
                ).also { list.add(it) }
            }
            commit(list)
        }
        return result
    }

    fun markUsed(guid: String?) {
        guid ?: return
        ensureLoaded()
        synchronized(lock) {
            val list = _logins.value.map {
                if (it.guid == guid) it.copy(lastUsed = System.currentTimeMillis(), timesUsed = it.timesUsed + 1) else it
            }
            commit(list)
        }
    }

    fun delete(guid: String) {
        ensureLoaded()
        synchronized(lock) { commit(_logins.value.filter { it.guid != guid }) }
    }

    fun deleteAll() {
        ensureLoaded()
        synchronized(lock) { commit(emptyList()) }
    }

    fun exportJson(): JSONArray = JSONArray().apply { all().forEach { put(it.toJson()) } }

    fun importJson(arr: JSONArray): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)?.let(SavedLogin::fromJson) ?: continue
            upsert(item.guid, item.origin, item.formActionOrigin, item.httpRealm, item.username, item.password)
            count++
        }
        return count
    }

    private fun commit(list: List<SavedLogin>) {
        _logins.value = list.sortedBy { it.host }
        scope.launch {
            synchronized(lock) {
                val text = JSONArray().apply { _logins.value.forEach { put(it.toJson()) } }.toString()
                secureStore.write(FILE, text)
            }
        }
    }

    companion object {
        private const val FILE = "passwords"

        fun hostOf(value: String): String {
            val v = value.trim().lowercase()
            val noScheme = v.substringAfter("://", v)
            return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        }
    }
}
