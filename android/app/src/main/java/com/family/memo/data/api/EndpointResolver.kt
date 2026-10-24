package com.family.memo.data.api

/**
 * 双地址（内网 / 外网）端点决策：纯逻辑，不依赖 Android，便于单元测试。
 *
 * 规则：
 *  1. 未配置任何地址 → NotConfigured
 *  2. 缓存未过期且缓存地址仍在候选里 → 直接用它（不探测，省电/省时；失败时由调用方 invalidate）
 *  3. 否则按 主地址 → 备用地址 顺序探测，第一个可用即采用
 *  4. 全部不可用 → Failed（带尝试过的地址列表，便于提示）
 */
class EndpointResolver(
    private val probe: suspend (baseUrl: String) -> Boolean,
    private val cacheTtlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    data class Candidate(val url: String, val label: String)

    sealed interface Result {
        /** 可用地址；switched 表示用的是备用地址而非主地址 */
        data class Ready(val url: String, val label: String, val switched: Boolean, val probed: Boolean) : Result

        data class Failed(val tried: List<String>) : Result
        data object NotConfigured : Result
    }

    fun candidates(primary: String, alt: String): List<Candidate> {
        val p = primary.trim().trimEnd('/')
        val a = alt.trim().trimEnd('/')
        return buildList {
            if (p.isNotEmpty()) add(Candidate(p, "主地址"))
            if (a.isNotEmpty() && a != p) add(Candidate(a, "备用地址"))
        }
    }

    suspend fun resolve(
        primary: String,
        alt: String,
        cachedUrl: String,
        cachedAt: Long,
        forceProbe: Boolean = false,
    ): Result {
        val list = candidates(primary, alt)
        if (list.isEmpty()) return Result.NotConfigured

        if (!forceProbe && cachedUrl.isNotBlank()) {
            val age = now() - cachedAt
            if (age in 0 until cacheTtlMs) {
                val cached = list.firstOrNull { it.url == cachedUrl.trim().trimEnd('/') }
                if (cached != null) {
                    return Result.Ready(cached.url, cached.label, cached.url != list[0].url, probed = false)
                }
            }
        }

        for (c in list) {
            val ok = try {
                probe(c.url)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                return Result.Ready(c.url, c.label, c.url != list[0].url, probed = true)
            }
        }
        return Result.Failed(list.map { it.url })
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000 // 10 分钟
    }
}
