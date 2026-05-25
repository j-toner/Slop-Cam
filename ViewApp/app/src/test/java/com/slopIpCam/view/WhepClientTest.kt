package com.slopIpCam.view

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class WhepClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `postOffer returns SDP answer on 201`() {
        val fakeSdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\n"
        server.enqueue(MockResponse().setResponseCode(201).setBody(fakeSdp))

        val client = WhepClient(server.url("/").toString(), OkHttpClient())
        val answer = client.postOffer("fake-offer-sdp")

        assertNotNull(answer)
        assertEquals(fakeSdp, answer)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("application/sdp", req.getHeader("Content-Type"))
    }

    @Test
    fun `postOffer returns null on 500`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = WhepClient(server.url("/").toString(), OkHttpClient())
        assertNull(client.postOffer("fake"))
    }
}
