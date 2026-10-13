package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.i18n.Label
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WIKIDATA as the catalogue of fictional universes: a universe is any item fan fiction is written
 * about — a franchise, a series, a work, a fictional universe — and its characters are whatever is
 * said to be in it (P1441 "present in work", P1080 "from narrative universe", P8345 "media
 * franchise", or listed by it with P674 "characters"), of it or of any part of it down the parts
 * (P179 "part of the series", P361 "part of", P8345). Not only people are said to be in a universe:
 * its places, groups, spells and things are too. Every entry comes with its classes (P31
 * "instance of"), and a class tells whether its entries are characters, for the user to keep the
 * characters and whatever else they want. Its sex or gender (P21) comes with it too.
 *
 * Every name is asked for in the user's languages: the label and every alias, so that "Невилл
 * Лонгботтом" of the fans is found as an alias of "Невилл Долгопупс" of the translation — and the
 * labels and descriptions are answered in each of them that has them.
 */
class WikidataCatalogue(private val json: JsonMapper) : UniverseCatalogue {
    override val id = "wikidata"
    override val title = Label("Wikidata", "Викиданные", "Vikipodaci")

    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

    /**
     * By the names and aliases of the items in every language first — what a short name like
     * "Гарри Поттер" is — then by their whole text, which finds a long one like "Роулинг Джоан
     * «Гарри Поттер»".
     */
    override fun findUniverses(name: String, languages: List<String>): List<UniverseEntry> {
        val byName = languages.flatMap { lang ->
            get("$API?action=wbsearchentities&type=item&limit=$SEARCHED&format=json&language=$lang&uselang=$lang&search=${enc(name)}")
                .path("search").mapNotNull { it.path("id").asString(null) }
        }
        val byText = get("$API?action=query&list=search&srnamespace=0&srlimit=$SEARCHED&format=json&srsearch=${enc(name)}")
            .path("query").path("search").mapNotNull { it.path("title").asString(null) }
        return entries((byName + byText).distinct(), languages)
    }

    override fun universe(id: String, languages: List<String>): UniverseEntry? = entries(listOf(id), languages).firstOrNull()

    override fun characters(id: String, languages: List<String>): List<UniverseCharacter> {
        require(ITEM.matches(id)) { "Not an item of Wikidata: $id" }
        val langs = languages.joinToString(",") { "\"$it\"" }
        // Two subqueries, each evaluated by itself: within one, the union of the two ways makes
        // the service walk the parts for every character there is
        val entries = """
            { SELECT DISTINCT ?c WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345)* wd:$id . ?c (wdt:P1441|wdt:P1080|wdt:P8345) ?part . } }
            UNION
            { SELECT DISTINCT ?c WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345)* wd:$id . ?part wdt:P674 ?c . } }
        """.trimIndent()
        val query = """
            SELECT ?c ?kind ?text WHERE {
              $entries
              { ?c rdfs:label ?text . BIND("label" AS ?kind) }
              UNION { ?c skos:altLabel ?text . BIND("alias" AS ?kind) }
              UNION { ?c schema:description ?text . BIND("description" AS ?kind) }
              FILTER(LANG(?text) IN ($langs))
            }
        """.trimIndent()
        class Names(val labels: MutableMap<String, String> = HashMap(), val aliases: MutableList<Pair<String, String>> = ArrayList(), val descriptions: MutableMap<String, String> = HashMap())
        val byCharacter = LinkedHashMap<String, Names>()
        // What each entry is (P31 "instance of"), asked by itself: joined to the names, it multiplies
        // them. OPTIONAL keeps the order — the entries first, then their classes: a plain join lets
        // the service start from every P31 statement there is and run out of time
        val classes = HashMap<String, MutableList<String>>()
        sparql("SELECT ?c ?class WHERE { { SELECT DISTINCT ?c WHERE { $entries } } OPTIONAL { ?c wdt:P31 ?class . } }").forEach { row ->
            val cls = row.path("class").path("value").asString("").substringAfterLast('/')
            if (ITEM.matches(cls)) classes.getOrPut(row.path("c").path("value").asString().substringAfterLast('/')) { ArrayList() } += cls
        }
        // The sex or gender of each entry (P21), asked by itself the same way
        val sexes = HashMap<String, MutableSet<String>>()
        sparql("SELECT ?c ?sex WHERE { { SELECT DISTINCT ?c WHERE { $entries } } OPTIONAL { ?c wdt:P21 ?sex . } }").forEach { row ->
            val sex = row.path("sex").path("value").asString("").substringAfterLast('/')
            if (ITEM.matches(sex)) sexes.getOrPut(row.path("c").path("value").asString().substringAfterLast('/')) { HashSet() } += sex
        }
        sparql(query).forEach { row ->
            val character = row.path("c").path("value").asString().substringAfterLast('/')
            val text = row.path("text")
            val lang = text.path("xml:lang").asString("")
            val value = text.path("value").asString()
            val names = byCharacter.getOrPut(character) { Names() }
            when (row.path("kind").path("value").asString()) {
                "label" -> names.labels[lang] = value
                "alias" -> names.aliases += lang to value
                "description" -> names.descriptions[lang] = value
            }
        }
        return byCharacter.map { (character, n) ->
            UniverseCharacter(
                character, n.labels, n.aliases.groupBy({ it.first }, { it.second }), n.descriptions, "$PAGE$character", languages,
                classes[character].orEmpty(), sexes[character]?.let(::sexOf),
            )
        }.filter { it.names.isNotEmpty() }
    }

    /** One sex of the values of P21: male or female when that is all they say, anything else otherwise. */
    private fun sexOf(values: Set<String>): CharacterSex {
        val read = values.map { SEXES[it] ?: CharacterSex.OTHER }.toSet()
        return read.singleOrNull() ?: CharacterSex.OTHER
    }

    /**
     * A class is of characters when "character" (Q95074) is among its superclasses (P279, any
     * steps up). Every superclass of every class is asked for in one query and the answer is
     * found here: up from the classes the paths are short, while a path to a given class from
     * hundreds of them makes the service search every way down and run out of time.
     */
    override fun classes(ids: Collection<String>, languages: List<String>): List<UniverseClass> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        if (items.isEmpty()) return emptyList()
        val langs = languages.joinToString(",") { "\"$it\"" }
        val characters = HashSet<String>()
        val names = HashMap<String, MutableMap<String, String>>()
        items.chunked(CLASSES_ASKED).forEach { chunk ->
            val values = chunk.joinToString(" ") { "wd:$it" }
            sparql("SELECT ?class ?super WHERE { VALUES ?class { $values } ?class wdt:P279* ?super . }").forEach { row ->
                if (row.path("super").path("value").asString().substringAfterLast('/') == CHARACTER) {
                    characters += row.path("class").path("value").asString().substringAfterLast('/')
                }
            }
            sparql("SELECT ?class ?name WHERE { VALUES ?class { $values } ?class rdfs:label ?name . FILTER(LANG(?name) IN ($langs)) }").forEach { row ->
                val name = row.path("name")
                names.getOrPut(row.path("class").path("value").asString().substringAfterLast('/')) { HashMap() }[name.path("xml:lang").asString("")] =
                    name.path("value").asString()
            }
        }
        return items.map { id -> UniverseClass(id, names[id].orEmpty(), id in characters, languages) }
    }

    /** The rows a query of the query service answers; sent in the body, as a list of hundreds of items does not fit an address. */
    private fun sparql(query: String): List<JsonNode> {
        val request = HttpRequest.newBuilder(URI.create(SPARQL)).timeout(TIMEOUT).header("User-Agent", USER_AGENT)
            .header("Accept", "application/sparql-results+json").header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("query=${enc(query)}")).build()
        return json.readTree(send(request)).path("results").path("bindings").toList()
    }

    /**
     * The body of the answer to [request]. Asked too often, Wikidata answers 429 with how long to
     * wait (Retry-After): the request is sent again after that, for as long as the waits add up to
     * no more than [TIMEOUT]; an answer of 429 without the header, or past it, fails as any other.
     */
    private fun send(request: HttpRequest): String {
        var waited = Duration.ZERO
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) return response.body()
            val wait = response.takeIf { it.statusCode() == TOO_MANY_REQUESTS }?.headers()?.firstValue("Retry-After")?.orElse(null)?.let(::retryAfter)
            check(wait != null && waited + wait <= TIMEOUT) { "Wikidata answered ${response.statusCode()}" }
            Thread.sleep(wait.toMillis())
            waited += wait
        }
    }

    /** Retry-After as seconds, or as the date to retry at; null when it is neither. */
    private fun retryAfter(value: String): Duration? =
        value.trim().toLongOrNull()?.let { Duration.ofSeconds(it.coerceAtLeast(0)) }
            ?: runCatching { Duration.between(Instant.now(), ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)).coerceAtLeast(Duration.ZERO) }.getOrNull()

    /** The items [ids] with their names and descriptions in every one of [languages] that has them, in the order given. */
    private fun entries(ids: List<String>, languages: List<String>): List<UniverseEntry> {
        val items = ids.filter { ITEM.matches(it) }
        if (items.isEmpty()) return emptyList()
        val entities = get(
            "$API?action=wbgetentities&props=labels%7Cdescriptions&format=json&ids=${items.joinToString("%7C")}&languages=${languages.joinToString("%7C")}",
        ).path("entities")
        return items.mapNotNull { id ->
            val e = entities.path(id)
            if (e.isMissingNode || e.has("missing")) return@mapNotNull null
            fun byLanguage(field: String) = languages.mapNotNull { lang -> e.path(field).path(lang).path("value").asString(null)?.let { lang to it } }.toMap()
            UniverseEntry(id, byLanguage("labels"), byLanguage("descriptions"), "$PAGE$id", languages)
        }
    }

    private fun get(url: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build()
        return json.readTree(send(request))
    }

    companion object {
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val SPARQL = "https://query.wikidata.org/sparql"
        private const val PAGE = "https://www.wikidata.org/wiki/"

        /** "character": the class every class of characters is a subclass of. */
        private const val CHARACTER = "Q95074"

        /** The values of P21 that say male or female: of a person, and of an organism. */
        private val SEXES = mapOf(
            "Q6581097" to CharacterSex.MALE, "Q44148" to CharacterSex.MALE,
            "Q6581072" to CharacterSex.FEMALE, "Q43445" to CharacterSex.FEMALE,
        )

        /** Classes asked for in one query: the length of a request, not meaning. */
        private const val CLASSES_ASKED = 500

        /** Entries a search answers with: a page of them for the user to pick from. */
        private const val SEARCHED = 10

        /** The Wikimedia user-agent policy: who asks, and where to find them. */
        private const val USER_AGENT = "recommend4me (https://github.com/vlsergey/recommend4me)"

        private val TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val TOO_MANY_REQUESTS = 429
        private val ITEM = Regex("Q\\d+")

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
