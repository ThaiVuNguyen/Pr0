package com.pr0gramm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.collection.LruCache
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.api.pr0gramm.ApiProvider
import com.pr0gramm.app.api.pr0gramm.LoginCookieHandler
import com.pr0gramm.app.io.Cache
import com.pr0gramm.app.services.proxy.HttpProxyService
import com.pr0gramm.app.services.proxy.ProxyService
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.AndroidUtility.checkNotMainThread
import com.squareup.picasso.Downloader
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.*
import okhttp3.internal.Util
import org.kodein.di.Kodein
import org.kodein.di.erased.bind
import org.kodein.di.erased.eagerSingleton
import org.kodein.di.erased.instance
import org.kodein.di.erased.singleton
import org.xbill.DNS.*
import rx.Single
import rx.schedulers.Schedulers
import rx.util.async.Async
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


const val TagApiURL = "api.baseurl"

/**
 */
fun httpModule(app: ApplicationClass) = Kodein.Module("http") {
    bind<LoginCookieHandler>() with singleton { LoginCookieHandler(app, instance()) }

    bind<Dns>() with singleton { FallbackDns() }

    bind<String>(TagApiURL) with instance("https://pr0gramm.com/")

    bind<OkHttpClient>() with singleton {
        val executor: ExecutorService = instance()
        val cookieHandler: LoginCookieHandler = instance()

        val cacheDir = File(app.cacheDir, "imgCache")

        val spec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS).run {
            tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)

            cipherSuites(
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,

                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,

                    // and more from https://github.com/square/okhttp/issues/3894
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA)
            build()
        }

        OkHttpClient.Builder()
                .cache(okhttp3.Cache(cacheDir, (64 * 1024 * 1024).toLong()))
                .socketFactory(SmallBufferSocketFactory())

                .cookieJar(cookieHandler)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .dispatcher(Dispatcher(executor))
                .dns(instance())
                .connectionSpecs(listOf(spec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))

                .apply {
                    debug {
                        if (Debug.debugInterceptor) {
                            addInterceptor(DebugInterceptor())
                        }
                    }
                }

                .addInterceptor(DoNotCacheInterceptor("vid.pr0gramm.com", "img.pr0gramm.com", "full.pr0gramm.com"))
                .addNetworkInterceptor(UserAgentInterceptor("pr0gramm-app/v" + BuildConfig.VERSION_CODE))
                .addNetworkInterceptor(LoggingInterceptor())
                .addNetworkInterceptor(UpdateServerTimeInterceptor())
                .build()
    }

    bind<Downloader>() with singleton {
        val fallback = OkHttp3Downloader(instance<OkHttpClient>())
        val cache = instance<Cache>()
        PicassoDownloader(cache, fallback)
    }

    bind<Single<ProxyService>>(tag = "proxyServiceSingle") with eagerSingleton {
        Async.start({
            checkNotMainThread()

            val logger = logger("ProxyServiceFactory")
            repeat(10) {
                val port = HttpProxyService.randomPort()
                logger.debug { "Trying port $port" }

                try {
                    val proxy = HttpProxyService(instance<Cache>(), port)
                    proxy.start()

                    // return the proxy
                    return@start proxy

                } catch (ioError: IOException) {
                    logger.warn { "Could not open proxy on port $port: $ioError" }
                }
            }

            logger.warn { "Stop trying, using no proxy now." }
            return@start object : ProxyService {
                override fun proxy(url: Uri): Uri {
                    return url
                }
            }
        }, Schedulers.io()).toSingle()
    }

    bind<ProxyService>() with singleton {
        instance<Single<ProxyService>>(tag = "proxyServiceSingle").toBlocking().value()
    }

    bind<Cache>() with singleton {
        Cache(app, instance<OkHttpClient>())
    }

    bind<Picasso>() with singleton {
        Picasso.Builder(app)
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .memoryCache(GuavaPicassoCache.defaultSizedGuavaCache())
                .downloader(instance<Downloader>())
                .build()
    }

    bind<ExecutorService>() with instance(ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 8, TimeUnit.SECONDS,
            SynchronousQueue(), Util.threadFactory("OkHttp Dispatcher", false)))

    bind<Api>() with singleton {
        val base = instance<String>(TagApiURL)
        ApiProvider(base, instance(), instance(), instance()).api
    }
}

private class PicassoDownloader(val cache: Cache, val fallback: OkHttp3Downloader) : Downloader {
    val logger = logger("Picasso.Downloader")

    private val memoryCache = object : LruCache<String, ByteArray>(1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    override fun load(request: Request): Response {
        // load thumbnails normally
        val url = request.url()
        if (url.host().contains("thumb.pr0gramm.com") || url.encodedPath().contains("/thumb.jpg")) {
            // try memory cache first.
            memoryCache.get(url.toString())?.let {
                return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_0)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(MediaType.parse("image/jpeg"), it))
                        .build()
            }

            // do request using fallback - network or disk.
            val response = fallback.load(request)

            // check if we want to cache the response in memory
            response.body()?.let { body ->
                if (body.contentLength() in (1 until 20 * 1024)) {
                    val bytes = body.bytes()

                    memoryCache.put(url.toString(), bytes)
                    return response.newBuilder()
                            .body(ResponseBody.create(body.contentType(), bytes))
                            .build()
                }
            }

            return response
        } else {
            logger.debug { "Using cache to download image $url" }
            cache.get(Uri.parse(url.toString())).use { entry ->
                return entry.toResponse(request)
            }
        }
    }

    override fun shutdown() {
        fallback.shutdown()
    }
}

private class UpdateServerTimeInterceptor : Interceptor {
    private val format by threadLocal {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ROOT)
        format.isLenient = false
        format
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val requestStartTime = System.currentTimeMillis()
        val response = chain.proceed(request)

        // we need the time of the network request
        val requestTime = System.currentTimeMillis() - requestStartTime

        if (response.isSuccessful && request.url().host() == "pr0gramm.com") {
            response.header("Date")?.let { dateValue ->
                val serverTime = try {
                    format.parse(dateValue)
                } catch (err: Exception) {
                    null
                }

                if (serverTime != null) {
                    val serverTimeApprox = serverTime.time + requestTime / 2
                    TimeFactory.updateServerTime(Instant(serverTimeApprox))
                }
            }
        }

        return response
    }

}

private class DebugInterceptor : Interceptor {
    private val logger = logger("DebugInterceptor")

    override fun intercept(chain: Interceptor.Chain): Response {
        checkNotMainThread()

        val request = chain.request()

        val watch = Stopwatch.createStarted()
        try {
            if ("pr0gramm.com" in request.url().toString()) {
                TimeUnit.MILLISECONDS.sleep(750)
            } else {
                TimeUnit.MILLISECONDS.sleep(500)
            }

            val response = chain.proceed(request)
            logger.debug { "Delayed request to ${request.url()} took $watch (status=${response.code()})" }
            return response
        } catch (err: Throwable) {
            logger.debug { "Delayed request to ${request.url()} took $watch, error $err" }
            throw err
        }
    }
}

private class UserAgentInterceptor(val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()

        return chain.proceed(request)
    }
}

private class DoNotCacheInterceptor(vararg domains: String) : Interceptor {
    private val logger = logger("DoNotCacheInterceptor")
    private val domains: Set<String> = domains.toSet()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (domains.contains(request.url().host())) {
            logger.debug { "Disable caching for ${request.url()}" }
            response.header("Cache-Control", "no-store")
        }

        return response
    }
}

private class LoggingInterceptor : Interceptor {
    val okLogger = logger("OkHttpClient")

    override fun intercept(chain: Interceptor.Chain): Response {
        val watch = Stopwatch.createStarted()
        val request = chain.request()

        okLogger.info { "performing ${request.method()} http request for ${request.url()}" }
        try {
            val response = chain.proceed(request)
            okLogger.info { "${request.url()} (${response.code()}) took $watch" }
            return response

        } catch (error: Exception) {
            okLogger.warn { "${request.url()} produced error: $error" }
            throw error
        }
    }
}

private class FallbackDns : Dns {
    val logger = logger("FallbackDns")

    val resolver = SimpleResolver("8.8.8.8")
    val cache = org.xbill.DNS.Cache()

    override fun lookup(hostname: String): MutableList<InetAddress> {
        if (hostname == "127.0.0.1" || hostname == "localhost") {
            return mutableListOf(InetAddress.getByName("127.0.0.1"))
        }

        val resolved = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (err: UnknownHostException) {
            emptyList<InetAddress>()
        }

        val resolvedFiltered = resolved
                .filterNot { it.isAnyLocalAddress }
                .filterNot { it.isLinkLocalAddress }
                .filterNot { it.isLoopbackAddress }
                .filterNot { it.isMulticastAddress }
                .filterNot { it.isSiteLocalAddress }
                .filterNot { it.isMCSiteLocal }
                .filterNot { it.isMCGlobal }
                .filterNot { it.isMCLinkLocal }
                .filterNot { it.isMCNodeLocal }
                .filterNot { it.isMCOrgLocal }

        if (resolvedFiltered.isNotEmpty()) {
            debug {
                logger.info { "System resolver for $hostname returned $resolved" }
            }

            return resolved.toMutableList()
        } else {
            val fallback = try {
                fallbackLookup(hostname)
            } catch (r: Throwable) {
                if (r.causalChain.containsType<Error>()) {
                    // sometimes the Lookup class does not initialize correctly, in that case, we'll
                    // just return an empty array here and delegate back to the systems resolver.
                    arrayListOf<InetAddress>()
                } else {
                    throw r
                }
            }

            debug {
                logger.info { "Fallback resolver for $hostname returned $fallback" }
            }

            if (fallback.isNotEmpty()) {
                return fallback
            }

            // still nothing? lets just return whatever the system told us
            return resolved.toMutableList()
        }
    }

    private fun fallbackLookup(hostname: String): MutableList<InetAddress> {
        val lookup = Lookup(hostname, Type.A, DClass.IN)
        lookup.setResolver(resolver)
        lookup.setCache(cache)

        val records: Array<Record> = lookup.run() ?: return mutableListOf()
        return records.filterIsInstance<ARecord>().mapTo(mutableListOf()) { it.address }
    }
}