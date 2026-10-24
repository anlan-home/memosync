package com.family.memo

import com.family.memo.data.api.EndpointResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双地址切换策略的纯逻辑测试。 */
class EndpointResolverTest {

    private class FakeProbe(private vararg val healthy: String) {
        var calls = mutableListOf<String>()
        suspend fun probe(url: String): Boolean {
            calls.add(url)
            return healthy.contains(url)
        }
    }

    @Test
    fun `主地址可用时不切换且只探测一次`() = runTest {
        val probe = FakeProbe("http://lan:5231")
        val r = EndpointResolver(probe::probe).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "", cachedAt = 0,
        )
        assertTrue(r is EndpointResolver.Result.Ready)
        r as EndpointResolver.Result.Ready
        assertEquals("http://lan:5231", r.url)
        assertFalse(r.switched)
        assertEquals(listOf("http://lan:5231"), probe.calls)
    }

    @Test
    fun `主地址不可用时自动切到备用地址`() = runTest {
        val probe = FakeProbe("http://wan:5231")
        val r = EndpointResolver(probe::probe).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "", cachedAt = 0,
        )
        r as EndpointResolver.Result.Ready
        assertEquals("http://wan:5231", r.url)
        assertTrue(r.switched)
        assertEquals("备用地址", r.label)
        assertEquals(2, probe.calls.size)
    }

    @Test
    fun `缓存新鲜时直接复用不再探测`() = runTest {
        val probe = FakeProbe("http://wan:5231")
        val now = 1_000_000L
        val r = EndpointResolver(probe::probe, now = { now }).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "http://wan:5231", cachedAt = now - 60_000,
        )
        r as EndpointResolver.Result.Ready
        assertEquals("http://wan:5231", r.url)
        assertFalse("缓存命中不应探测", r.probed)
        assertTrue(probe.calls.isEmpty())
    }

    @Test
    fun `缓存过期后重新探测并纠正结果`() = runTest {
        val probe = FakeProbe("http://lan:5231")
        val now = 1_000_000L
        val r = EndpointResolver(probe::probe, now = { now }).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "http://wan:5231", cachedAt = now - 3_600_000, // 1 小时前
        )
        r as EndpointResolver.Result.Ready
        assertEquals("http://lan:5231", r.url)
        assertFalse(r.switched)
    }

    @Test
    fun `强制探测时忽略缓存`() = runTest {
        val probe = FakeProbe("http://lan:5231")
        val now = 1_000_000L
        val r = EndpointResolver(probe::probe, now = { now }).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "http://wan:5231", cachedAt = now,
            forceProbe = true,
        )
        r as EndpointResolver.Result.Ready
        assertEquals("http://lan:5231", r.url)
        assertEquals(1, probe.calls.size)
    }

    @Test
    fun `两个地址都不可用时报失败并列出尝试过的地址`() = runTest {
        val probe = FakeProbe()
        val r = EndpointResolver(probe::probe).resolve(
            primary = "http://lan:5231", alt = "http://wan:5231",
            cachedUrl = "", cachedAt = 0,
        )
        assertTrue(r is EndpointResolver.Result.Failed)
        r as EndpointResolver.Result.Failed
        assertEquals(listOf("http://lan:5231", "http://wan:5231"), r.tried)
    }

    @Test
    fun `只配置主地址且不可用时报失败`() = runTest {
        val probe = FakeProbe()
        val r = EndpointResolver(probe::probe).resolve(
            primary = "http://lan:5231", alt = "",
            cachedUrl = "", cachedAt = 0,
        )
        assertTrue(r is EndpointResolver.Result.Failed)
        assertEquals(1, (r as EndpointResolver.Result.Failed).tried.size)
    }

    @Test
    fun `未配置地址`() = runTest {
        val r = EndpointResolver({ true }).resolve(primary = "", alt = "  ", cachedUrl = "", cachedAt = 0)
        assertTrue(r is EndpointResolver.Result.NotConfigured)
    }

    @Test
    fun `主备地址相同只保留一个`() {
        val list = EndpointResolver({ true }).candidates("http://nas:5231/", "http://nas:5231")
        assertEquals(1, list.size)
        assertEquals("http://nas:5231", list[0].url)
    }

    @Test
    fun `探测抛异常视为不可用`() = runTest {
        val r = EndpointResolver({ throw java.io.IOException("boom") }).resolve(
            primary = "http://lan:5231", alt = "",
            cachedUrl = "", cachedAt = 0,
        )
        assertTrue(r is EndpointResolver.Result.Failed)
    }
}
