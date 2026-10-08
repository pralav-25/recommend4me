package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.api.CaptureApi
import io.github.vlsergey.recommend4me.api.model.CapturePatterns
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CaptureResult
import io.github.vlsergey.recommend4me.api.model.CapturedPart as ApiCapturedPart
import io.github.vlsergey.recommend4me.api.model.CapturedPageInfo
import io.github.vlsergey.recommend4me.api.model.ExtensionInfo
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset

@RestController
class CaptureController(
    private val captures: Captures,
    private val stores: Stores,
    private val json: JsonMapper,
    /** The folder of the extension: the distribution's extension/, or the repository's when run from a build. */
    @Value("\${recommend4me.extension-dir:}") private val extensionDir: String,
) : CaptureApi {

    override fun getExtension(): ResponseEntity<ExtensionInfo> {
        val manifest = extensionDir.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize().resolve("manifest.json") }
            ?.takeIf { Files.isRegularFile(it) } ?: return ResponseEntity.ok(ExtensionInfo())
        val version = runCatching { json.readTree(manifest.toFile()).get("version")?.asString() }.getOrNull()
        return ResponseEntity.ok(ExtensionInfo(manifest.toString(), version))
    }

    override fun getCapturePatterns(): ResponseEntity<List<CapturePatterns>> =
        ResponseEntity.ok(captures.patterns().map { (source, patterns) -> CapturePatterns(source, patterns) })

    override fun capturePage(captureRequest: CaptureRequest): ResponseEntity<CaptureResult> {
        val captured = captures.capture(captureRequest.url, captureRequest.html)
        val items = captured.items.mapNotNull { key ->
            stores.source(key.source)?.items?.find(key.id)?.let { LinkedItem(key.source, key.id, it.title, it.url) }
        }
        val parts = captured.parts.map { p ->
            ApiCapturedPart(p.item.source, p.item.id, p.length, stores.source(p.item.source)?.items?.find(p.item.id)?.title, p.title)
        }
        return ResponseEntity.ok(CaptureResult(items, captured.source, parts))
    }

    override fun recentCaptures(): ResponseEntity<List<CapturedPageInfo>> =
        ResponseEntity.ok(captures.recent(RECENT).map { (source, page) ->
            CapturedPageInfo(
                source = source, url = page.url, capturedAt = page.capturedAt.atOffset(ZoneOffset.UTC), item = page.itemId,
                title = page.itemId?.let { stores.source(source)?.items?.find(it)?.title },
            )
        })

    companion object {
        private const val RECENT = 30
    }
}
