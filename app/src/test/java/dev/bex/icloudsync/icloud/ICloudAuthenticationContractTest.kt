package dev.bex.icloudsync.icloud

import dev.bex.icloudsync.data.model.AccountSecrets
import dev.bex.icloudsync.security.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ICloudAuthenticationContractTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `password SRP login and trusted 2FA session follow sanitized Apple contract`() = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var accountLogins = 0
        val serviceRoot = server.url("/").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.path!!.startsWith("/appleauth/auth/authorize/signin") -> json("{}")
                    request.path == "/appleauth/auth" -> MockResponse().setResponseCode(500)
                    request.path == "/appleauth/auth/signin/init" -> json(
                        """{
                          "salt":"${Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })}",
                          "b":"${Base64.getEncoder().encodeToString(byteArrayOf(2))}",
                          "iteration":1,
                          "protocol":"s2k",
                          "c":"sanitized-challenge"
                        }""".trimIndent(),
                    )
                    request.path!!.startsWith("/appleauth/auth/signin/complete") -> json("{}").setResponseCode(409)
                        .addHeader("X-Apple-Session-Token", "sanitized-session-token")
                        .addHeader("X-Apple-ID-Session-Id", "sanitized-session-id")
                        .addHeader("X-Apple-ID-Account-Country", "NO")
                        .addHeader("X-Apple-Auth-Attributes", "sanitized-auth-attributes")
                        .addHeader("scnt", "sanitized-scnt")
                    request.path == "/appleauth/auth/verify/trusteddevice" -> json("{}")
                    request.path == "/appleauth/auth/verify/trusteddevice/securitycode" -> MockResponse().setResponseCode(204)
                    request.path == "/appleauth/auth/2sv/trust" -> MockResponse().setResponseCode(204)
                    request.path!!.startsWith("/setup/ws/1/accountLogin") -> {
                        accountLogins++
                        accountResponse(serviceRoot, trusted = accountLogins > 1)
                    }
                    request.path!!.startsWith("/setup/ws/1/storageUsageInfo") -> json(
                        """{
                          "storageUsageInfo":{"usedStorageInBytes":3750,"totalStorageInBytes":5000},
                          "quotaStatus":{"overQuota":false,"almost-full":true},
                          "storageUsageByMedia":[
                            {"mediaKey":"photos","displayLabel":"Photos","displayColor":"#3478F6","usageInBytes":2500},
                            {"mediaKey":"docs","displayLabel":"iCloud Drive","displayColor":"#7656D8","usageInBytes":1250}
                          ]
                        }""".trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val secrets = MemorySecretStore()
        val gateway = UnofficialICloudGateway(
            secrets,
            Json { ignoreUnknownKeys = true; explicitNulls = false },
            UnofficialICloudGateway.EndpointOverrides(
                auth = server.url("/appleauth/auth").toString(),
                setup = server.url("/setup/ws/1").toString(),
                home = server.url("/").toString(),
            ),
        )

        assertEquals(AuthResult.RequiresTwoFactor, gateway.signIn("person@example.com", "correct horse battery staple"))
        assertTrue(secrets.value!!.requiresTwoFactor)
        assertTrue(requests.any { it.path == "/appleauth/auth/verify/trusteddevice" })
        assertEquals(AuthResult.Authenticated, gateway.verifyTwoFactor("123456"))
        assertFalse(secrets.value!!.requiresTwoFactor)
        assertEquals("sanitized-session-token", secrets.value!!.sessionToken)
        assertEquals("sanitized-auth-attributes", secrets.value!!.authAttributes)
        assertEquals("123456", requests.first { it.path!!.contains("securitycode") }.body.readUtf8().let {
            Json.parseToJsonElement(it).jsonObject["securityCode"]!!.jsonObject["code"]!!.jsonPrimitive.content
        })
        val storage = gateway.storageUsage()
        assertEquals(5_000, storage.totalBytes)
        assertEquals(3_750, storage.usedBytes)
        assertEquals(1_250, storage.availableBytes)
        assertTrue(storage.almostFull)
        assertFalse(storage.overQuota)
        assertEquals(listOf("Photos", "iCloud Drive"), storage.categories.map { it.label })
        assertTrue(requests.any { it.path!!.startsWith("/setup/ws/1/storageUsageInfo") && it.method == "POST" })
        assertTrue(requests.none { it.body.clone().readUtf8().contains("correct horse battery staple") })
    }

    private fun accountResponse(serviceRoot: String, trusted: Boolean) = json(
        """{
          "dsInfo":{"dsid":"123456789"},
          "webservices":{
            "ckdatabasews":{"url":"$serviceRoot"},
            "uploadimagews":{"url":"$serviceRoot"},
            "drivews":{"url":"$serviceRoot"},
            "docws":{"url":"$serviceRoot"}
          },
          "hsaTrustedBrowser":$trusted,
          "hsaChallengeRequired":${!trusted}
        }""".trimIndent(),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private class MemorySecretStore : SecretStore {
        var value: AccountSecrets? = null
        override fun load() = value
        override fun save(secrets: AccountSecrets) { value = secrets }
        override fun clear() { value = null }
    }
}
