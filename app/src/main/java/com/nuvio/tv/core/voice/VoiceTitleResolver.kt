package com.nuvio.tv.core.voice

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a spoken title into a catalog item, using the same searchable movie/series catalogs of
 * the installed addons that the Search screen queries.
 *
 * The spoken text may carry hints, which Alexa cannot deliver as separate slots next to a
 * free-text title, so they are parsed out here:
 *   "dune 2021", "dune del 2021", "dune from 2021"  -> year 2021
 *   "il film dune", "the movie dune"               -> movies only
 *   "la serie the office", "the series the office" -> series only
 *
 * Ranking, best first:
 *   1. exact name match ("the bear" prefers "The Bear" over "Bear"), then ignoring a leading
 *      article, then names starting with / containing the title;
 *   2. +50 when the year matches (series: any year the show ran), -50 when it clearly doesn't;
 *   3. +20 when the type hint matches, -60 when it doesn't;
 *   ties: addon order as installed, then the addon's own (popularity) order.
 * The unparsed text is scored too, so titles that end in a number ("Blade Runner 2049") or
 * are a number ("1917") still match exactly.
 */
@Singleton
class VoiceTitleResolver @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
) {

    suspend fun resolve(query: String): VoiceCommand.OpenTitle? = coroutineScope {
        val parsed = parseQuery(query) ?: return@coroutineScope null

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        // The type hint only affects ranking, not which catalogs are searched: a title can
        // itself start with "the show" or "film".
        val targets = searchTargets(addons)

        // Search the bare title; also the full text when it differs, for titles with numbers.
        val searchTerms = listOf(parsed.title, parsed.full).distinct()
        val rows = targets.flatMapIndexed { index, (addon, catalog) ->
            searchTerms.map { term ->
                async {
                    val result = withTimeoutOrNull(PER_CATALOG_TIMEOUT_MS) {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            extraArgs = mapOf("search" to term),
                        ).first { it !is NetworkResult.Loading }
                    }
                    (result as? NetworkResult.Success)?.data?.let { index to it }
                }
            }
        }.awaitAll().filterNotNull()

        rows.flatMap { (catalogIndex, row) ->
            row.items.take(MAX_ITEMS_PER_CATALOG).mapIndexed { rank, item ->
                val years = releaseYears(item.releaseInfo)
                Candidate(
                    score = score(parsed, normalize(item.name), item.type, years),
                    catalogIndex = catalogIndex,
                    rank = rank,
                    title = VoiceCommand.OpenTitle(
                        id = item.id,
                        type = item.apiType,
                        name = item.name,
                        addonBaseUrl = row.addonBaseUrl,
                        year = years?.first,
                    ),
                )
            }
        }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.catalogIndex }
                    .thenBy { it.rank }
            )
            .firstOrNull()
            ?.title
    }

    private data class Candidate(
        val score: Int,
        val catalogIndex: Int,
        val rank: Int,
        val title: VoiceCommand.OpenTitle,
    )

    private fun searchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { it.type == ContentType.MOVIE || it.type == ContentType.SERIES }
                .filter { it.isSearchableWithQueryOnly() }
                .map { addon to it }
        }

    // Same rule as SearchViewModel: only catalogs that need nothing but the search term.
    private fun CatalogDescriptor.isSearchableWithQueryOnly(): Boolean =
        supportsExtra("search") &&
            extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) }

    /** [full] = the whole spoken text normalised; [title] = with year / type hints removed. */
    internal data class ParsedQuery(
        val full: String,
        val title: String,
        val year: Int?,
        val type: ContentType?,
    )

    internal companion object {
        const val PER_CATALOG_TIMEOUT_MS = 4_000L
        const val MAX_ITEMS_PER_CATALOG = 10

        private val LEADING_ARTICLE = Regex("^(the|a|an|il|lo|la|i|gli|le|l|un|uno|una)\\s+")
        private val YEAR = Regex("^(19|20)\\d{2}$")
        private val YEAR_CONNECTORS = setOf("del", "dal", "nel", "anno", "dell", "from", "of", "in", "year")
        private val MOVIE_PREFIXES = listOf("il film", "un film", "film", "the movie", "a movie", "movie")
        private val SERIES_PREFIXES = listOf(
            "la serie tv", "la serie", "serie tv", "serie", "the tv series", "the tv show", "the series",
            "the show", "tv series", "tv show", "series",
        )

        /** Lower-case, strip accents and punctuation, collapse spaces. */
        fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace('&', ' ')
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        fun stripArticle(normalized: String): String = normalized.replace(LEADING_ARTICLE, "")

        fun parseQuery(query: String): ParsedQuery? {
            val full = normalize(query)
            if (stripArticle(full).isEmpty()) return null
            var words = full.split(' ')
            var type: ContentType? = null

            fun stripPrefix(prefixes: List<String>, kind: ContentType) {
                if (type != null) return
                val joined = words.joinToString(" ")
                val prefix = prefixes.firstOrNull { joined.startsWith("$it ") } ?: return
                val rest = joined.removePrefix("$prefix ").split(' ')
                if (rest.isNotEmpty() && rest.first().isNotEmpty()) {
                    words = rest
                    type = kind
                }
            }
            stripPrefix(SERIES_PREFIXES, ContentType.SERIES)
            stripPrefix(MOVIE_PREFIXES, ContentType.MOVIE)

            var year: Int? = null
            if (words.size > 1 && YEAR.matches(words.last())) {
                year = words.last().toInt()
                words = words.dropLast(1)
                while (words.size > 1 && words.last() in YEAR_CONNECTORS) words = words.dropLast(1)
            }
            return ParsedQuery(full = full, title = words.joinToString(" "), year = year, type = type)
        }

        /** "2021" -> 2021..2021, "2019–2023" -> 2019..2023, "2019-" -> 2019..(open). */
        fun releaseYears(releaseInfo: String?): Pair<Int, Int>? {
            val years = Regex("(19|20)\\d{2}").findAll(releaseInfo.orEmpty()).map { it.value.toInt() }.toList()
            val start = years.firstOrNull() ?: return null
            val openEnded = releaseInfo.orEmpty().trimEnd().let { it.endsWith("-") || it.endsWith("–") }
            val end = when {
                years.size > 1 -> years[1]
                openEnded -> Int.MAX_VALUE
                else -> start
            }
            return start to end
        }

        /** Name similarity only; both arguments already [normalize]d. */
        fun nameScore(wanted: String, candidate: String): Int {
            if (candidate.isEmpty() || wanted.isEmpty()) return 0
            val bareWanted = stripArticle(wanted)
            val bareCandidate = stripArticle(candidate)
            return when {
                candidate == wanted -> 110
                bareCandidate == bareWanted -> 100
                bareCandidate.startsWith("$bareWanted ") -> 60
                bareCandidate.contains(bareWanted) -> 40
                bareWanted.contains(bareCandidate) -> 30
                else -> 10 // the addon still ranked it for this query
            }
        }

        fun score(parsed: ParsedQuery, candidate: String, type: ContentType, years: Pair<Int, Int>?): Int {
            val typeAdjust = when {
                parsed.type == null -> 0
                parsed.type == type -> 20
                else -> -60
            }
            val yearAdjust = when {
                parsed.year == null || years == null -> 0
                parsed.year in years.first..years.second -> 50
                else -> -50
            }
            val asSpoken = nameScore(parsed.full, candidate)
            val withHints = nameScore(parsed.title, candidate) + yearAdjust + typeAdjust
            return maxOf(asSpoken, withHints)
        }
    }
}
