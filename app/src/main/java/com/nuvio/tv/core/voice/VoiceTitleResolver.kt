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
 * Turns a spoken title ("the bear") into a catalog item, using the same searchable
 * movie/series catalogs of the installed addons that the Search screen queries.
 *
 * Ranking: an exact (normalised) name match wins ("the bear" prefers "The Bear" over "Bear"),
 * then a match ignoring a leading article, then names starting with / containing the query;
 * ties go to the addon order (as installed) and then the addon's own result order.
 */
@Singleton
class VoiceTitleResolver @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
) {

    suspend fun resolve(query: String): VoiceCommand.OpenTitle? = coroutineScope {
        val wanted = normalize(query)
        if (wanted.isEmpty() || stripArticle(wanted).isEmpty()) return@coroutineScope null

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val targets = searchTargets(addons)

        val rows = targets.mapIndexed { index, (addon, catalog) ->
            async {
                val result = withTimeoutOrNull(PER_CATALOG_TIMEOUT_MS) {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        extraArgs = mapOf("search" to query),
                    ).first { it !is NetworkResult.Loading }
                }
                (result as? NetworkResult.Success)?.data?.let { index to it }
            }
        }.awaitAll().filterNotNull()

        rows.flatMap { (catalogIndex, row) ->
            row.items.take(MAX_ITEMS_PER_CATALOG).mapIndexed { rank, item ->
                Candidate(
                    score = score(wanted, normalize(item.name)),
                    catalogIndex = catalogIndex,
                    rank = rank,
                    title = VoiceCommand.OpenTitle(
                        id = item.id,
                        type = item.apiType,
                        name = item.name,
                        addonBaseUrl = row.addonBaseUrl,
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

    internal companion object {
        const val PER_CATALOG_TIMEOUT_MS = 4_000L
        const val MAX_ITEMS_PER_CATALOG = 10

        private val LEADING_ARTICLE = Regex("^(the|a|an|il|lo|la|i|gli|le|l|un|uno|una)\\s+")

        /** Lower-case, strip accents and punctuation, collapse spaces. */
        fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace('&', ' ')
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        fun stripArticle(normalized: String): String = normalized.replace(LEADING_ARTICLE, "")

        /** Both arguments already [normalize]d. */
        fun score(wanted: String, candidate: String): Int {
            if (candidate.isEmpty()) return 0
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
    }
}
