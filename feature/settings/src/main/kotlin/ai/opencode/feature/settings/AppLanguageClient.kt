package ai.opencode.feature.settings

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.Normalizer
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/**
 * The app's own language, through Android's per-app language setting, so Settings › Apps › OpenCode ›
 * Language shows the same choice.
 */
class AppLanguageClient(
    /** The languages the app is translated into, from its generated locale config. */
    val supported: () -> List<Locale> = { emptyList() },
    /** The app's language, or null when it follows the system. */
    val current: () -> Locale? = { null },
    /** The phone's languages, in the order set in Android's settings. */
    val systemLocales: () -> List<Locale> = { listOf(Locale.ENGLISH) },
    /** Null follows the system again. Android recreates the activity when the language changes. */
    val set: (Locale?) -> Unit = {},
) {
    companion object Key : DependencyKey<AppLanguageClient> {
        override val testValue: AppLanguageClient
            get() = AppLanguageClient()

        override val liveValue: AppLanguageClient by lazy {
            val context = dependency(AppContext)
            // LocaleManager and LocaleConfig are API 33; appcompat wraps both (and stores the
            // choice through AppLocalesMetadataHolderService on 32 and below). The 33+ manager
            // must resolve only inside the guarded branch: the class literal itself is NewApi.
            if (Build.VERSION.SDK_INT >= 33) {
                val manager = context.getSystemService(LocaleManager::class.java)
                AppLanguageClient(
                    supported = {
                        val locales = LocaleConfig(context).supportedLocales
                        val list = mutableListOf<Locale>()
                        if (locales != null) {
                            for (index in 0 until locales.size()) list.add(locales.get(index))
                        }
                        list
                    },
                    current = {
                        val locales = manager.applicationLocales
                        if (locales.isEmpty) null else locales.get(0)
                    },
                    systemLocales = {
                        val locales = manager.systemLocales
                        val list = mutableListOf<Locale>()
                        for (index in 0 until locales.size()) list.add(locales.get(index))
                        if (list.isEmpty()) list.add(Locale.getDefault())
                        list
                    },
                    set = { locale ->
                        manager.applicationLocales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
                    },
                )
            } else {
                AppLanguageClient(
                    supported = { localesConfigLocales(context) },
                    current = {
                        val locales = AppCompatDelegate.getApplicationLocales()
                        if (locales.isEmpty) null else locales.get(0)
                    },
                    systemLocales = {
                        // The app's own choice overrides LocaleList.getDefault(); the system
                        // resources keep the phone's locales.
                        val locales = Resources.getSystem().configuration.locales
                        val list = mutableListOf<Locale>()
                        for (index in 0 until locales.size()) list.add(locales.get(index))
                        if (list.isEmpty()) list.add(Locale.getDefault())
                        list
                    },
                    set = { locale ->
                        AppCompatDelegate.setApplicationLocales(
                            if (locale == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.create(locale),
                        )
                    },
                )
            }
        }

        /**
         * The generated locale config (the list the system Settings shows on 33+) read back on 32
         * and below, where `LocaleConfig` doesn't exist yet; falls back to the packaged locales'
         * qualifiers if the resource can't be resolved.
         */
        private fun localesConfigLocales(context: Context): List<Locale> {
            val resources = context.resources
            val id = sequenceOf("_generated_res_locale_config", "locales_config")
                .map { resources.getIdentifier(it, "xml", context.packageName) }
                .firstOrNull { it != 0 }
            if (id != null) {
                try {
                    val parser = resources.getXml(id)
                    val locales = mutableListOf<Locale>()
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "locale") continue
                        val tag = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name") ?: continue
                        locales.add(Locale.forLanguageTag(tag))
                    }
                    parser.close()
                    if (locales.isNotEmpty()) return locales
                } catch (_: Exception) {
                    // Fall through to the packaged qualifiers.
                }
            }
            return context.resources.assets.locales
                .filter { tag -> Regex("[a-z]{2,3}([-_][A-Za-z0-9]+)*").matches(tag) }
                .map { tag -> qualifierToLocale(tag) }
                .distinctBy { it.toLanguageTag() }
        }

        /** The asset manager reports resource qualifiers (`zh-rCN`), not BCP 47 tags. */
        private fun qualifierToLocale(tag: String): Locale {
            val bcp47 = if (tag.startsWith("b+")) {
                tag.removePrefix("b+").replace('+', '-')
            } else {
                tag.replace(Regex("-r([A-Za-z]{2})$"), "-$1")
            }
            return Locale.forLanguageTag(bcp47)
        }
    }
}

/** The language's name in itself (“Deutsch”, “日本語”, “简体中文”). */
val Locale.nativeName: String get() = languageName(this)

fun Locale.languageName(display: Locale): String {
    if (language == "zh") {
        val script = ULocale.addLikelySubtags(ULocale.forLocale(this)).script
        return Locale.Builder().setLanguage(language).setScript(script).build().getDisplayScript(display)
    }
    return getDisplayName(display).replaceFirstChar { it.titlecase(display) }
}

object AppLanguages {
    fun suggested(system: List<Locale>, supported: List<Locale>): List<Locale> {
        val result = mutableListOf<Locale>()
        for (wanted in system) {
            for (language in supported) {
                if (covers(language, wanted)) {
                    if (language !in result) result.add(language)
                    break
                }
            }
        }
        return result
    }

    fun matching(languages: List<Locale>, query: String, appLocale: Locale): List<Locale> {
        val wanted = folded(query.trim())
        if (wanted.isEmpty()) return languages
        val result = mutableListOf<Locale>()
        for (language in languages) {
            val names = listOf(
                language.nativeName,
                language.languageName(appLocale),
                language.languageName(Locale.ENGLISH),
                language.getDisplayLanguage(appLocale),
                language.getDisplayLanguage(Locale.ENGLISH),
                language.toLanguageTag(),
            )
            for (name in names) {
                if (folded(name).contains(wanted)) {
                    result.add(language)
                    break
                }
            }
        }
        return result
    }

    private fun covers(supported: Locale, wanted: Locale): Boolean {
        val full = ULocale.addLikelySubtags(ULocale.forLocale(supported))
        val wantedFull = ULocale.addLikelySubtags(ULocale.forLocale(wanted))
        if (full.language != wantedFull.language || full.script != wantedFull.script) return false
        return supported.country.isEmpty() || supported.country == wanted.country
    }

    private fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}
