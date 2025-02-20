package eu.kanade.tachiyomi.extension.en.dragontea

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DragonTea : Madara(
    "DragonTea",
    "https://dragontea.ink/",
    "en",
    dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
) {
    // idk if this help with cloudflare issue
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; LM-Q710(FGN)) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36")
        .add("Referer", "$baseUrl")

    override val useNewChapterEndpoint = true

    override val chapterUrlSuffix = ""

    private val begonepeconSelector: String = "div.begonepecon"

    private val peconholderSelector: String = "div.peconholder"

    override fun pageListParse(document: Document): List<Page> {
        countViews(document)

        val hasSplitImages = document
            .select(begonepeconSelector)
            .firstOrNull() != null

        if (!hasSplitImages) {
            return super.pageListParse(document)
        }

        return document.select("div.page-break, li.blocks-gallery-item, $begonepeconSelector")
            .mapIndexed { index, element ->
                val imageUrl = if (element.select(peconholderSelector).firstOrNull() == null) {
                    element.select("img").first()?.let { it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src") }
                } else {
                    element.select("img").joinToString("|") { it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src") } + BEGONEPECON_SUFFIX
                }
                Page(index, document.location(), imageUrl)
            }
    }

    private fun begonepeconIntercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.toString().endsWith(BEGONEPECON_SUFFIX)) {
            return chain.proceed(chain.request())
        }

        val imageUrls = chain.request().url.toString()
            .removeSuffix(BEGONEPECON_SUFFIX)
            .split("%7C")

        var width = 0
        var height = 0

        val imageBitmaps = imageUrls.map { imageUrl ->
            val request = chain.request().newBuilder().url(imageUrl).build()
            val response = chain.proceed(request)

            val bitmap = BitmapFactory.decodeStream(response.body!!.byteStream())

            width += bitmap.width
            height = bitmap.height

            bitmap
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var left = 0

        imageBitmaps.forEach { bitmap ->
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = Rect(left, 0, left + bitmap.width, bitmap.height)

            canvas.drawBitmap(bitmap, srcRect, dstRect, null)

            left += bitmap.width
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, output)

        val responseBody = output.toByteArray().toResponseBody(PNG_MEDIA_TYPE)

        return Response.Builder()
            .code(200)
            .protocol(Protocol.HTTP_1_1)
            .request(chain.request())
            .message("OK")
            .body(responseBody)
            .build()
    }

    companion object {
        private const val BEGONEPECON_SUFFIX = "?begonepecon"
        private val PNG_MEDIA_TYPE = "image/png".toMediaType()
    }
}
