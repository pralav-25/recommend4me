package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.tag.TagCatalogue
import io.github.vlsergey.recommend4me.tag.TagEntry
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
class WikidataCatalogue(
    private val json: JsonMapper,
    /** The API's address: Wikidata's own, another only in the tests. */
    private val api: String = API,
    /** The query service's address. */
    private val sparqlUrl: String = SPARQL,
) : UniverseCatalogue, TagCatalogue {
    override val id = "wikidata"

    /**
     * An item a tag stands for — "Quidditch", "angst", "slow burn" — by its names and aliases in
     * every language; a Q-id, or the address of an item's page, is that item.
     */
    override fun find(text: String, languages: List<String>): List<TagEntry> {
        val asked = ITEM_IN.find(text.trim())?.value
        if (asked != null) return entries(listOf(asked), languages)
        val found = languages.flatMap { lang ->
            get("$api?action=wbsearchentities&type=item&limit=$SEARCHED&format=json&language=$lang&uselang=$lang&search=${enc(text.trim())}")
                .path("search").mapNotNull { it.path("id").asString(null) }
        }
        return entries(found.distinct(), languages)
    }

    /** The items [ids] with their labels, aliases and descriptions in every one of [languages] that has them, in the order given. */
    override fun entries(ids: Collection<String>, languages: List<String>): List<TagEntry> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        return items.chunked(ENTITIES_ASKED).flatMap { chunk ->
            val entities = get(
                "$api?action=wbgetentities&props=labels%7Caliases%7Cdescriptions&format=json&ids=${chunk.joinToString("%7C")}&languages=${languages.joinToString("%7C")}",
            ).path("entities")
            chunk.mapNotNull { id ->
                val e = entities.path(id)
                if (e.isMissingNode || e.has("missing")) return@mapNotNull null
                fun byLanguage(field: String) = languages.mapNotNull { lang -> e.path(field).path(lang).path("value").asString(null)?.let { lang to it } }.toMap()
                val aliases = languages.associateWith { lang -> e.path("aliases").path(lang).mapNotNull { it.path("value").asString(null) } }.filterValues { it.isNotEmpty() }
                TagEntry(id, byLanguage("labels"), aliases, byLanguage("descriptions"), "$PAGE$id")
            }
        }
    }

    /**
     * The items right above each item: its classes (P279 "subclass of") and, of a fictional thing,
     * what it is the fictional analog of (P1074) — "quidditch" is a fictional sport, and of "sport"
     * only through its analog. Never its P31 "instance of": "satire" is an instance of "literary
     * genre", which says what kind of item it is, not what it is a kind of. Deprecated statements
     * left out.
     */
    override fun above(ids: Collection<String>): Map<String, List<String>> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        return items.chunked(ENTITIES_ASKED).flatMap { chunk ->
            val entities = get("$api?action=wbgetentities&props=claims&format=json&ids=${chunk.joinToString("%7C")}").path("entities")
            chunk.mapNotNull { id ->
                val claims = entities.path(id).path("claims")
                val parents = ABOVE.flatMap { p ->
                    claims.path(p).filter { it.path("rank").asString("normal") != "deprecated" }
                        .mapNotNull { it.path("mainsnak").path("datavalue").path("value").path("id").asString(null) }
                }.distinct().filter { it != id }
                parents.takeIf { it.isNotEmpty() }?.let { id to it }
            }
        }.toMap()
    }

    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

    /**
     * By the names and aliases of the items in every language first — what a short name like
     * "Гарри Поттер" is — then by their whole text, which finds a long one like "Роулинг Джоан
     * «Гарри Поттер»".
     */
    override fun findUniverses(name: String, languages: List<String>): List<UniverseEntry> {
        val byName = languages.flatMap { lang ->
            get("$api?action=wbsearchentities&type=item&limit=$SEARCHED&format=json&language=$lang&uselang=$lang&search=${enc(name)}")
                .path("search").mapNotNull { it.path("id").asString(null) }
        }
        val byText = get("$api?action=query&list=search&srnamespace=0&srlimit=$SEARCHED&format=json&srsearch=${enc(name)}")
            .path("query").path("search").mapNotNull { it.path("title").asString(null) }
        return universeEntries((byName + byText).distinct(), languages)
    }

    override fun universe(id: String, languages: List<String>, properties: Set<String>): UniverseEntry? {
        val entry = universeEntries(listOf(id), languages).firstOrNull() ?: return null
        // The statements of the universe and of every work, series and franchise within it or set in it
        // (P1434 "takes place in fictional universe": the series of a universe of its own) — but its
        // characters', which are said to be in it the same way
        val stated = statements("{ SELECT DISTINCT ?c WHERE { ?c (wdt:P179|wdt:P361|wdt:P8345|wdt:P1434)* wd:$id . FILTER NOT EXISTS { ?c wdt:P31/wdt:P279* wd:$CHARACTER } } }", properties)
        val merged = HashMap<String, MutableList<String>>()
        stated.values.forEach { byProperty -> byProperty.forEach { (p, values) -> merged.getOrPut(p) { ArrayList() } += values } }
        return UniverseEntry(entry.id, entry.labels, entry.descriptions, entry.url, entry.languages, merged.mapValues { it.value.distinct() })
    }

    override fun characters(id: String, languages: List<String>, properties: Set<String>): List<UniverseCharacter> {
        require(ITEM.matches(id)) { "Not an item of Wikidata: $id" }
        val langs = languages.joinToString(",") { "\"$it\"" }
        // The parts of the universe, walked once as its own statements are (P1434 "takes place in
        // fictional universe" too: a character only of a work set in the universe is one of its
        // characters), then its entries once from them; every later query is given the entries as
        // a list. A walk repeated in each query makes the service walk it each time, and a
        // universe with hundreds of works set in it then runs out of time
        val parts = sparql("SELECT DISTINCT ?part WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345|wdt:P1434)* wd:$id . }")
            .map { it.path("part").path("value").asString().substringAfterLast('/') }.filter { ITEM.matches(it) }
        val partList = parts.joinToString(" ") { "wd:$it" }
        // Two subqueries, each evaluated by itself: within one, the union of the two ways makes
        // the service join them for every character there is
        val ids = sparql(
            """
            SELECT DISTINCT ?c WHERE {
              { SELECT DISTINCT ?c WHERE { VALUES ?part { $partList } ?c (wdt:P1441|wdt:P1080|wdt:P8345) ?part . } }
              UNION
              { SELECT DISTINCT ?c WHERE { VALUES ?part { $partList } ?part wdt:P674 ?c . } }
            }
            """.trimIndent(),
        ).map { it.path("c").path("value").asString().substringAfterLast('/') }.filter { ITEM.matches(it) }.distinct()
        if (ids.isEmpty()) return emptyList()
        val entries = "VALUES ?c { ${ids.joinToString(" ") { "wd:$it" }} }"
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
        sparql("SELECT ?c ?class WHERE { $entries OPTIONAL { ?c wdt:P31 ?class . } }").forEach { row ->
            val cls = row.path("class").path("value").asString("").substringAfterLast('/')
            if (ITEM.matches(cls)) classes.getOrPut(row.path("c").path("value").asString().substringAfterLast('/')) { ArrayList() } += cls
        }
        // The sex or gender of each entry (P21), asked by itself the same way
        val sexes = HashMap<String, MutableSet<String>>()
        sparql("SELECT ?c ?sex WHERE { $entries OPTIONAL { ?c wdt:P21 ?sex . } }").forEach { row ->
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
        val stated = statements(entries, properties)
        return byCharacter.map { (character, n) ->
            UniverseCharacter(
                character, n.labels, n.aliases.groupBy({ it.first }, { it.second }), n.descriptions, "$PAGE$character", languages,
                classes[character].orEmpty(), sexes[character]?.let(::sexOf), stated[character].orEmpty(),
            )
        }.filter { it.names.isNotEmpty() }
    }

    /**
     * The statements of [properties] of every entry [entries] binds to `?c` — a list of them, or a
     * subquery selecting them — asked by themselves as the classes are: entry to property to its
     * values, an item by its id, any other value as its text. A list is joined as it is: wrapped in
     * a subquery of its own, thousands of entries take the service most of a minute.
     */
    private fun statements(entries: String, properties: Set<String>): Map<String, Map<String, List<String>>> {
        val asked = properties.filter { PROPERTY.matches(it) }.distinct()
        if (asked.isEmpty()) return emptyMap()
        val values = asked.joinToString(" ") { "wdt:$it" }
        val out = HashMap<String, MutableMap<String, MutableList<String>>>()
        sparql("SELECT ?c ?p ?v WHERE { $entries VALUES ?p { $values } ?c ?p ?v . }").forEach { row ->
            val character = row.path("c").path("value").asString().substringAfterLast('/')
            val property = row.path("p").path("value").asString().substringAfterLast('/')
            val v = row.path("v")
            val value = v.path("value").asString()
            val shown = if (v.path("type").asString("") == "uri" && value.startsWith(ENTITY)) value.removePrefix(ENTITY) else value
            out.getOrPut(character) { HashMap() }.getOrPut(property) { ArrayList() } += shown
        }
        return out
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
        val request = HttpRequest.newBuilder(URI.create(sparqlUrl)).timeout(TIMEOUT).header("User-Agent", USER_AGENT)
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
    private fun universeEntries(ids: List<String>, languages: List<String>): List<UniverseEntry> {
        val items = ids.filter { ITEM.matches(it) }
        if (items.isEmpty()) return emptyList()
        val entities = get(
            "$api?action=wbgetentities&props=labels%7Cdescriptions&format=json&ids=${items.joinToString("%7C")}&languages=${languages.joinToString("%7C")}",
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

        /** An item's id within a text: itself, or the address of its page. */
        private val ITEM_IN = Regex("(?<![A-Za-z0-9])Q\\d+(?![0-9])")

        /** Items asked for in one request: the API's limit. */
        private const val ENTITIES_ASKED = 50

        /** What an item is a kind of ([above]): P279 "subclass of", P1074 "fictional analog of". */
        private val ABOVE = listOf("P279", "P1074")

        /** A property of Wikidata: "P8419". */
        private val PROPERTY = Regex("P\\d+")

        /** The address an item of Wikidata is given by in the answers of the query service. */
        private const val ENTITY = "http://www.wikidata.org/entity/"

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
