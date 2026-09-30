package com.family.memo.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

interface MemoApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginReqDto): LoginRespDto

    @POST("api/v1/auth/pair")
    suspend fun pair(@Body body: PairReqDto): LoginRespDto

    @GET("api/v1/auth/me")
    suspend fun me(): UserDto

    @POST("api/v1/auth/password")
    suspend fun changePassword(@Body body: PasswordReqDto): OkRespDto

    @GET("api/v1/spaces")
    suspend fun spaces(): SpacesRespDto

    @POST("api/v1/spaces")
    suspend fun createSpace(@Body body: CreateSpaceReqDto): com.family.memo.data.api.SpaceDto

    @GET("api/v1/spaces/{id}/members")
    suspend fun spaceMembers(@Path("id") id: String): SpaceMembersRespDto

    @POST("api/v1/spaces/{id}/members")
    suspend fun addSpaceMember(@Path("id") id: String, @Body body: AddMemberReqDto): SpaceMemberDto

    @DELETE("api/v1/spaces/{id}/members/{uid}")
    suspend fun removeSpaceMember(@Path("id") id: String, @Path("uid") uid: String): OkRespDto

    @DELETE("api/v1/spaces/{id}")
    suspend fun deleteSpace(@Path("id") id: String): OkRespDto

    @POST("api/v1/sync/push")
    suspend fun push(@Body body: PushReqDto): PushRespDto

    @GET("api/v1/sync/pull")
    suspend fun pull(@Query("since") since: Long, @Query("limit") limit: Int): PullRespDto

    @POST("api/v1/attachments/check")
    suspend fun check(@Body body: CheckReqDto): CheckRespDto

    @Multipart
    @POST("api/v1/attachments")
    suspend fun upload(@Part file: MultipartBody.Part): UploadRespDto

    @GET("api/v1/attachments/{id}")
    suspend fun downloadRaw(@Path("id") id: String): ResponseBody

    @GET("api/v1/health")
    suspend fun health(): OkRespDto
}

/** 网络错误统一为 ApiError，UI 与同步引擎据此区分离线/服务器错误。 */
class ApiError(message: String, val offline: Boolean = false) : Exception(message)

class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val req = if (token.isNotBlank()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        return chain.proceed(req)
    }
}

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun create(baseUrl: String, tokenProvider: () -> String): MemoApi {
        val cleanBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(cleanBase)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MemoApi::class.java)
    }
}

/** 把 retrofit/okhttp 异常翻译成统一的 ApiError。 */
fun Throwable.toApiError(): ApiError = when (this) {
    is ApiError -> this
    is HttpException -> {
        val body = try { response()?.errorBody()?.string() } catch (_: Exception) { null }
        val msg = try {
            body?.let { ApiClient.json.decodeFromString(ErrWrapper.serializer(), it).error }
        } catch (_: Exception) { null }
        ApiError(msg ?: "服务器错误 (HTTP ${code()})", code() in 500..599 || code() == 0)
    }
    is IOException -> ApiError("网络不可用，更改已保存在本机", offline = true)
    else -> ApiError(message ?: "未知错误")
}

@kotlinx.serialization.Serializable
private data class ErrWrapper(val error: String? = null)

/** 附件上传辅助：从本地文件构建 multipart。 */
fun buildFilePart(file: File, mime: String, partName: String = "file"): MultipartBody.Part {
    val rb = file.asRequestBody(mime.toMediaType())
    return MultipartBody.Part.createFormData(partName, file.name, rb)
}
