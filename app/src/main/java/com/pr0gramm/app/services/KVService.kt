package com.pr0gramm.app.services

import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.pr0gramm.app.MoshiInstance
import com.pr0gramm.app.ui.base.retryUpTo
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Deferred
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.*

class KVService(okHttpClient: OkHttpClient) {
    private val api = Retrofit.Builder()
            .validateEagerly(true)
            .client(okHttpClient)
            .baseUrl("https://pr0.wibbly-wobbly.de/api/kv/v1/")
            .addConverterFactory(MoshiConverterFactory.create(MoshiInstance))
            .addCallAdapterFactory(CoroutineCallAdapterFactory())
            .build()
            .create(Api::class.java)

    suspend fun update(ident: String, key: String, fn: (previous: ByteArray?) -> ByteArray?): PutResult.Version? {
        return retryUpTo(3) {
            val previous = get(ident, key) as? GetResult.Value

            val response = fn(previous?.value)
                    ?.let { value -> put(ident, key, previous?.version ?: 0, value) }

            if (response == PutResult.Conflict) {
                throw VersionConflictException()
            }

            response as? PutResult.Version
        }
    }

    suspend fun get(ident: String, key: String): GetResult {
        return try {
            api.getValue(tokenOf(ident), key).await()
        } catch (err: HttpException) {
            if (err.code() != 404)
                throw err

            GetResult.NoValue
        }
    }

    suspend fun put(ident: String, key: String, version: Int, value: ByteArray): PutResult {
        // hash the ident to generate the token

        val body = RequestBody.create(MediaType.parse("application/octet"), value)

        return try {
            api.putValue(tokenOf(ident), key, version, body).await()
        } catch (err: HttpException) {
            if (err.code() != 409)
                throw err

            PutResult.Conflict
        }
    }

    private fun tokenOf(ident: String) = UUID.nameUUIDFromBytes("xxx$ident".toByteArray())

    private interface Api {
        @GET("token/{token}/key/{key}")
        fun getValue(
                @Path("token") token: UUID,
                @Path("key") key: String): Deferred<GetResult.Value>

        @POST("token/{token}/key/{key}/version/{version}")
        fun putValue(
                @Path("token") token: UUID,
                @Path("key") key: String,
                @Path("version") version: Int,
                @Body body: RequestBody): Deferred<PutResult.Version>
    }

    sealed class PutResult {
        @JsonClass(generateAdapter = true)
        class Version(val version: Int) : PutResult()

        object Conflict : PutResult()
    }

    sealed class GetResult {
        @JsonClass(generateAdapter = true)
        class Value(val version: Int, val value: ByteArray) : GetResult()

        object NoValue : GetResult()
    }

    class VersionConflictException : RuntimeException("version conflict during update")
}