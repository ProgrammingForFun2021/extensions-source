package eu.kanade.tachiyomi.extension.ru.hotmanga

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.ru.hotmanga.dto.MangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HotManga :
    HttpSource(),
    ConfigurableSource {

    override val id = 2073023199372375753

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val baseOrig: String = "https://hotmanga.me"

    private val baseMirrSecond: String = "https://xn--80aaalhzvfe9b4a.xn--80asehdb" // https://хентайманга.онлайн

    private val apiPath = "/api"

    private val domain: String? = preferences.getString(DOMAIN_PREF, baseOrig)

    private val paidSymbol = "\uD83D\uDD12"

    override val baseUrl = domain.toString()

    override val lang = "ru"

    override val name = "HotManga"

    override val supportsLatest = true

    private val apiPathsMap = mapOf(
        baseOrig to apiPath,
        baseMirrSecond to apiPath,
    )

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).build()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    override fun popularMangaRequest(page: Int): Request {
        val pageF = page - 1
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/catalog?orderBy=-likes&page=$pageF"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val values = json.parseToJsonElement(response.body.string()).jsonArray
        val mangaListDto = mutableListOf<MangaDto>()
        for (item in values) {
            try {
                mangaListDto.add(json.decodeFromJsonElement<MangaDto>(item))
            } catch (e: Exception) {
                Log.i("HotManga", e.toString())
            }
        }
        var hasNextPage = true
        if (mangaListDto.isEmpty()) {
            hasNextPage = false
        }
        val mangas = mutableListOf<SManga>()
        for (mangaItem in mangaListDto) {
            val element = mangaItem.toSManga()
            mangas.add(element)
        }
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String {
        return baseUrl + cleanMangaUrlFromBookIdParameter(manga.url)
    }

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        title = titleEn ?: slug
        url = "/manga/$slug?bookId=$id" // TODO Use HttpUrlBuilder to escape arguments properly.
        // Original host does not work for some locations. Cloudflare protection. Need to change domain.
        // Parameters w and q need to be calculated.
        thumbnail_url = "$baseMirrSecond/_next/image?url=$baseMirrSecond$imageHigh&w=768&q=75"
        description = desc?.trim()
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw NotImplementedError("Unused")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val pageF = page - 1
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/catalog?orderBy=-id&page=$pageF"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = cleanMangaUrlFromBookIdParameter(manga.url)
        return GET("${baseUrl}$url", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            title = document.selectFirst("body > div.min-h-\\[calc\\(100vh_-_48px\\)\\].h-full.max-md\\:mt-11.grid.m-auto.md\\:max-w-7xl.w-full.md\\:px-4 > div > div.grid.content-start.max-md\\:relative.md\\:w-\\[300px\\].before\\:inset-0.before\\:z-10.md\\:before\\:z-\\[-1\\].md\\:before\\:content-none.before\\:w-full.before\\:absolute.before\\:min-h-full.bg-book-pattern > div.grid.gap-y-3.max-md\\:p-3.max-md\\:pb-4.z-10.content-start > div.max-md\\:contents.pt-4.absolute.grid.md\\:items-center.grid-flow-col.grid-cols-\\[1fr_auto\\].top-0.left-\\[calc\\(300px_\\+_1\\.5rem\\)\\].right-0 > div.grid.md\\:order-1.max-md\\:mt-2.max-md\\:text-center > h2")!!.text()
            thumbnail_url = document.selectFirst("body > div.min-h-\\[calc\\(100vh_-_48px\\)\\].h-full.max-md\\:mt-11.grid.m-auto.md\\:max-w-7xl.w-full.md\\:px-4 > div > div.grid.content-start.max-md\\:relative.md\\:w-\\[300px\\].before\\:inset-0.before\\:z-10.md\\:before\\:z-\\[-1\\].md\\:before\\:content-none.before\\:w-full.before\\:absolute.before\\:min-h-full.bg-book-pattern > div.md\\:rounded.md\\:relative.max-md\\:\\!absolute.max-md\\:top-0.max-md\\:w-full.md\\:\\[\\&_\\>_div\\]\\:rounded.dark\\:bg-black-700.bg-white.max-md\\:\\[\\&_\\>_img\\]\\:blur-\\[3px\\].md\\:h-\\[435px\\].max-md\\:h-full > img")?.absUrl("src")
            description = document.selectFirst("body > div.min-h-\\[calc\\(100vh_-_48px\\)\\].h-full.max-md\\:mt-11.grid.m-auto.md\\:max-w-7xl.w-full.md\\:px-4 > div > div.md\\:mt-\\[4\\.75rem\\].grid.grid-cols-1.lg\\:grid-cols-3.gap-4 > div.grid.ring-1.dark\\:ring-black-500.ring-gray-200.content-start.bg-white.dark\\:bg-black-600.md\\:rounded.lg\\:col-span-2 > div.px-3.md\\:px-4.mt-4 > div > div")?.text()
            genre = ""
            author = ""
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // TODO Add filters, use page param, investigate limit param
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/books/search?filter[query]=$query&limit=24"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = mutableListOf<SChapter>()
        val mangaUrl = manga.url
        val urlObj = "$baseUrl$mangaUrl".toHttpUrlOrNull()
        val bookId = urlObj?.queryParameter("bookId")
        val apiPathVal = apiPathsMap[baseUrl]
        val urlBase = "$baseUrl$apiPathVal/chapters/list?filter%5BbookId%5D=$bookId"
        val request = GET(urlBase.toHttpUrl(), headers)
        val body = client.newCall(request).execute().body.string()
        val values = json.parseToJsonElement(body).jsonArray
        for (item in values) {
            // TODO Use a DTO instead of this.
            val number = item.jsonObject["number"].toString().replace("\"", "")
            val createdAt = item.jsonObject["createdAt"]?.jsonPrimitive?.content
            val volume = item.jsonObject["volume"]?.jsonPrimitive?.content
            val id = item.jsonObject["id"].toString()
            val isSubscription = item.jsonObject["isSubscription"]?.jsonPrimitive?.content.toBoolean()
            val cleanUrl = cleanMangaUrlFromBookIdParameter(mangaUrl)
            val chapterUrl = "$cleanUrl/ch$id"
            val parseDate = parseDate(createdAt)
            var chapterName = "$volume. Глава $number"
            if (isSubscription) {
                chapterName += paidSymbol
            }
            val sChapter = SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = chapterName
                date_upload = parseDate
                chapter_number = number.toFloat()
            }
            chapters.add(sChapter)
        }
        return Observable.just(chapters)
    }

    private val simpleDateFormat by lazy {
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US,
        )
    }

    private fun parseDate(date: String?): Long {
        date ?: return Date().time
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val pageUrl = "$baseUrl${chapter.url}"
        return GET(pageUrl.toHttpUrl(), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val list = mutableListOf<Page>()
        val chapterPage = response.asJsoup()
        val elements = chapterPage.select("div.mx-auto.grid.justify-center > div.relative")
        for (elem in elements) {
            val imgElem = elem.select("img")
            val imgSrc = imgElem.attr("src")
            if (imgSrc.isNotEmpty()) {
                list.add(Page(list.size, "", imgSrc))
            }
        }
        return list
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!)

    override fun imageUrlParse(response: Response): String = ""

    private fun cleanMangaUrlFromBookIdParameter(url: String) = url.split("?")[0]

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "Выбор домена"
            entries = arrayOf("Основной (hotmanga.me)", "Зеркало 2 (хентайманга.онлайн)")
            entryValues = arrayOf(baseOrig, baseMirrSecond)
            summary = "%s"
            setDefaultValue(baseOrig)
            setOnPreferenceChangeListener { _, newValue ->
                val warning =
                    "Для смены домена необходимо перезапустить приложение с полной остановкой."
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val DOMAIN_PREF = "HMMangaDomain"
    }
}
