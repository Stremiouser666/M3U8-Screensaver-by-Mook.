package com.livescreensaver.tv

import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class DownloaderImpl : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NPRequest): NPResponse {
        val builder = Request.Builder().url(request.url())
        val response = client.newCall(builder.build()).execute()

        if (response.code == 429) {
            throw ReCaptchaException("Captcha required", request.url())
        }

        return NPResponse(
            response.code,
            response.message,
            mutableMapOf(),
            response.body?.string() ?: "",
            request.url()
        )
    }
}