package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.PasswordStore
import com.fable.browser.data.repo.SavedLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordManager @Inject constructor(
    val store: PasswordStore,
    private val settingsStore: SettingsStore
) : Autocomplete.StorageDelegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var settings: Settings = Settings()
    @Volatile var privateActive: Boolean = false

    init {
        scope.launch { settingsStore.settings.collect { settings = it } }
    }

    fun current(): Settings = settings

    fun canSave(host: String, incognito: Boolean): Boolean {
        val s = settings
        if (!s.passwordSave) return false
        if (incognito && !s.passwordIncognito) return false
        return PasswordStore.hostOf(host) !in s.passwordNeverSites
    }

    fun canFill(incognito: Boolean): Boolean {
        val s = settings
        return s.passwordAutofill && (!incognito || s.passwordIncognito)
    }

    fun neverForSite(host: String) {
        scope.launch { settingsStore.setPasswordNever(PasswordStore.hostOf(host), true) }
    }

    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? {
        if (!canFill(privateActive)) return GeckoResult.fromValue(emptyArray())
        val list = runCatching { store.forDomain(domain).map { it.toEntry() }.toTypedArray() }.getOrDefault(emptyArray())
        return GeckoResult.fromValue(list)
    }

    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>>? {
        if (!canFill(privateActive)) return GeckoResult.fromValue(emptyArray())
        val list = runCatching { store.all().map { it.toEntry() }.toTypedArray() }.getOrDefault(emptyArray())
        return GeckoResult.fromValue(list)
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        val origin = login.origin
        if (origin.isBlank() || login.password.isBlank()) return
        if (!canSave(origin, privateActive)) return
        runCatching {
            store.upsert(login.guid, origin, login.formActionOrigin, login.httpRealm, login.username, login.password)
        }
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        runCatching { store.markUsed(login.guid) }
    }

    fun save(entry: Autocomplete.LoginEntry): SavedLogin? = runCatching {
        store.upsert(entry.guid, entry.origin, entry.formActionOrigin, entry.httpRealm, entry.username, entry.password)
    }.getOrNull()

    fun existing(entry: Autocomplete.LoginEntry): SavedLogin? =
        store.find(entry.guid) ?: store.matching(entry.origin, entry.username)

    companion object {
        fun SavedLogin.toEntry(): Autocomplete.LoginEntry =
            Autocomplete.LoginEntry.Builder()
                .guid(guid)
                .origin(origin)
                .formActionOrigin(formActionOrigin)
                .httpRealm(httpRealm)
                .username(username)
                .password(password)
                .build()
    }
}
