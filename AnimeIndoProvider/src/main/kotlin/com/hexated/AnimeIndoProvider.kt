package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeIndoProvider : MainAPI() {
    override var mainUrl = "https://animeindo.quest"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "episode-terbaru" to "Episode Terbaru",
        "ongoing" to "Anime Ongoing",
        "populer" to "Anime Populer",
        "donghua-terbaru" to "Donghua Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select("main#main div.animposx").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("nonton/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> Regex("(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()
                (title.contains("-movie")) -> Regex("(.+)-movie").find(title)?.groupValues?.get(
                    1
                ).toString()
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val title = this.selectFirst("div.titlex, h2.entry-title, h4")?.text()?.trim() ?: ""
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("span.episode")?.ownText()?.replace(Regex("\\D"), "")?.trim()
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val anime = mutableListOf<SearchResponse>()
        (1..2).forEach { page ->
            val link = "$mainUrl/page/$page/?s=$query"
            val document = app.get(link).document
            val media = document.select(".site-main.relat > article").mapNotNull {
                val title = it.selectFirst("div.title > h2")!!.ownText().trim()
                val href = it.selectFirst("a")!!.attr("href")
                val posterUrl = it.selectFirst("img")!!.attr("src").toString()
                val type = getType(it.select("div.type").text().trim())
                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
            if(media.isNotEmpty()) anime.addAll(media)
        }
        return anime
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Subtitle Indonesia", "")
            ?.trim() ?: return null
        val poster = document.selectFirst("div.thumb > img[itemprop=image]")?.attr("src")
        val tags = document.select("div.genxed > a").map { it.text() }
        val type = document.selectFirst("div.info-content > div.spe > span:contains(Type:)")?.ownText()
            ?.trim()?.lowercase() ?: "tv"
        val year = document.selectFirst("div.info-content > div.spe > span:contains(Released:)")?.ownText()?.let {
            Regex("\\d,\\s(\\d*)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val status = getStatus(document.selectFirst("div.info-content > div.spe > span:nth-child(1)")!!.ownText().trim())
        val description = document.select("div[itemprop=description] > p").text()

        val trailer = document.selectFirst("div.player-embed iframe")?.attr("src")
        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val episode = header.text().trim().replace("Episode", "").trim().toIntOrNull()
            val link = fixUrl(header.attr("href"))
            Episode(link, header.text(), episode = episode)
        }.reversed()

        val recommendations = document.select("div.relat div.animposx").mapNotNull {
            it.toSearchResult()
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("div.itemleft > .mirror > option").mapNotNull {
            fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
        }.apmap {
            if (it.startsWith(mainUrl)) {
                app.get(it, referer = "$mainUrl/").document.select("iframe").attr("src")
            } else {
                it
            }
        }.apmap {
            loadExtractor(httpsify(it), data, subtitleCallback, callback)
        }

        return true
    }

}