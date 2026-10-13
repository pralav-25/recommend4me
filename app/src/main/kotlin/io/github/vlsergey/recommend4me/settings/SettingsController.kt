package io.github.vlsergey.recommend4me.settings

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.SettingsApi
import io.github.vlsergey.recommend4me.api.model.SettingValue
import io.github.vlsergey.recommend4me.api.model.SourceSettings
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Source
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The settings the sources declare, editable in the interface; a secret is never sent back. */
@RestController
class SettingsController(private val plugins: Plugins, private val settings: Settings) : SettingsApi {

    override fun getSettings(): ResponseEntity<List<SourceSettings>> =
        ResponseEntity.ok(plugins.sources.filter { it.settings.isNotEmpty() }.map(::of))

    override fun updateSourceSettings(source: String, requestBody: Map<String, String>): ResponseEntity<SourceSettings> {
        val s = plugins.source(source) ?: return ResponseEntity.notFound().build()
        val store = settings.of(source)
        requestBody.forEach { (key, value) ->
            if (s.settings.none { it.key == key }) {
                throw Refusal.NO_SETTING.refuse(HttpStatus.BAD_REQUEST, "The source $source has no setting '$key'", "setting" to key)
            }
            store.set(key, value.trim().takeIf { it.isNotEmpty() })
        }
        return ResponseEntity.ok(of(s))
    }

    private fun of(source: Source): SourceSettings {
        val store = settings.of(source.id)
        return SourceSettings(
            source = source.id,
            propertyValues = source.settings.map { def ->
                val value = store.get(def.key)
                SettingValue(
                    key = def.key, labelKey = I18nKeys.qualified(source.id, I18nKeys.setting(def.key)),
                    kind = SettingValue.Kind.valueOf(def.kind.name), secret = def.secret,
                    set = value != null, value = if (def.secret) null else value ?: def.default,
                    hintKey = if (def.hint) I18nKeys.qualified(source.id, I18nKeys.settingHint(def.key)) else null,
                )
            },
        )
    }
}
