package dev.stevanmosquetti.openplaud.client

import com.sun.net.httpserver.HttpServer
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the mod_http_api wire contract: POST /api/<command>, Basic auth, flat JSON body.
 * A local HTTP server captures the outgoing requests; no real ejabberd is needed.
 */
class EjabberdClientTest {

    private class CapturedRequest(val path: String, val authorization: String?, val body: String)

    private val objectMapper = ObjectMapper()
    private val captured = AtomicReference<CapturedRequest?>(null)
    private val nextStatus = java.util.concurrent.atomic.AtomicInteger(200)
    private val nextBody = AtomicReference("")
    private var server: HttpServer? = null
    private lateinit var client: EjabberdClient
    private val authorization: String
        get() = "Basic " + Base64.getEncoder().encodeToString("admin@localhost:admin".toByteArray())

    @BeforeEach
    fun startServer() {
        val mockServletRequest = MockHttpServletRequest()
        mockServletRequest.addHeader("Authorization", authorization)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockServletRequest))

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.reader(Charsets.UTF_8).readText()
                captured.set(
                    CapturedRequest(
                        exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        body,
                    ),
                )
                val payload = nextBody.get().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(nextStatus.get(), payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server!!.start()
        val address = server!!.address
        client = EjabberdClient(
            "http://${address.hostName}:${address.port}/api",
            "localhost",
            "admin@localhost",
            "admin"
        )

    }

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    private fun lastRequest(): CapturedRequest = captured.get()
        ?: error("No request captured")

    @Test
    fun registerPostsFlatJsonWithBasicAuth() {
        client.registerUser("johndoe", "secret")

        val request = lastRequest()
        assertEquals("/api/register", request.path)
        assertEquals(authorization, request.authorization)

        val json = objectMapper.readTree(request.body)
        assertEquals("johndoe", json.get("user")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        assertEquals("secret", json.get("password")?.asText())
    }

    @Test
    fun createRoomPostsPersistentOption() {
        client.createPersistentRoom("support", "Support Service", "conference")

        val request = lastRequest()
        assertEquals("/api/create_room_with_opts", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("support", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        val option1 = json.get("options")?.get(0)
        assertEquals("persistent", option1?.get("name")?.asText())
        assertEquals("true", option1?.get("value")?.asText())
        val option2 = json.get("options")?.get(1)
        assertEquals("title", option2?.get("name")?.asText())
        assertEquals("Support Service", option2?.get("value")?.asText())
        val option3 = json.get("options")?.get(2)
        assertEquals("members_only", option3?.get("name")?.asText())
        assertEquals("true", option3?.get("value")?.asText())
        val option4 = json.get("options")?.get(3)
        assertEquals("allow_subscription", option4?.get("name")?.asText())
        assertEquals("true", option4?.get("value")?.asText())
        val option5 = json.get("options")?.get(4)
        assertEquals("logging", option5?.get("name")?.asText())
        assertEquals("true", option5?.get("value")?.asText())
    }

    @Test
    fun setRoomAffiliationPostsRoomUserAndAffiliation() {
        client.setRoomAffiliation("support", "conference", "johndoe")

        val request = lastRequest()
        assertEquals("/api/set_room_affiliation", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("support", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
        assertEquals("johndoe", json.get("user")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        assertEquals("member", json.get("affiliation")?.asText())
    }

    @Test
    fun errorResponseThrowsWithServerMessage() {
        nextStatus.set(500)
        nextBody.set("""{"message":"User already exists"}""")

        val error = assertFailsWith<EjabberdException> { client.registerUser("johndoe", "secret") }
        assertEquals("User already exists", error.message)
    }

    @Test
    fun errorWithoutJsonBodyFallsBackToUnknownMessage() {
        nextStatus.set(500)
        nextBody.set("not json at all")

        val error = assertFailsWith<EjabberdException> { client.registerUser("johndoe", "secret") }
        assertEquals("Unknown ejabberd error", error.message)
    }

    @Test
    fun unregisterPostsUserAndHost() {
        client.unregisterUser("johndoe")

        val request = lastRequest()
        assertEquals("/api/unregister", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("johndoe", json.get("user")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
    }

    @Test
    fun createRoomPostsMembersOnlyOption() {
        client.createRoom("johndoe", "Support", "conference")

        val request = lastRequest()
        assertEquals("/api/create_room_with_opts", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("johndoe", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        val option1 = json.get("options")?.get(0)
        assertEquals("members_only", option1?.get("name")?.asText())
        assertEquals("true", option1?.get("value")?.asText())
        val option2 = json.get("options")?.get(1)
        assertEquals("title", option2?.get("name")?.asText())
        assertEquals("Support", option2?.get("value")?.asText())
        val option3 = json.get("options")?.get(2)
        assertEquals("allow_subscription", option3?.get("name")?.asText())
        assertEquals("true", option3?.get("value")?.asText())
        val option4 = json.get("options")?.get(3)
        assertEquals("logging", option4?.get("name")?.asText())
        assertEquals("true", option4?.get("value")?.asText())
        assertEquals(4, json.get("options").size())
    }

    @Test
    fun destroyPostsRoomAndService() {
        client.destroyRoom("support", "conference")

        val request = lastRequest()
        assertEquals("/api/destroy_room", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("support", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
    }

    @Test
    fun getRoomAffiliationPostsJidAndStripsQuotes() {
        nextBody.set("\"member\"")

        val result = client.getRoomAffiliation("support", "conference", "johndoe@localhost")

        assertEquals("member", result)
        val request = lastRequest()
        assertEquals("/api/get_room_affiliation", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("support", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
        assertEquals("johndoe@localhost", json.get("jid")?.asText())
    }

    @Test
    fun setRoomSubscriptionPostsUserHostNickAndNodes() {
        client.setRoomSubscription("support", "conference", "johndoe", "John Doe")

        val request = lastRequest()
        assertEquals("/api/subscribe_room", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("johndoe", json.get("user")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        assertEquals("John Doe", json.get("nick")?.asText())
        assertEquals("support", json.get("room")?.asText())
        assertEquals("conference", json.get("service")?.asText())
        assertEquals("urn:xmpp:mucsub:nodes:messages", json.get("nodes")?.get(0)?.asText())
    }

    @Test
    fun checkOperatorAccessPostsCheckPasswordForDecodedUser() {
        nextBody.set("0")

        client.checkOperatorAccess()

        val request = lastRequest()
        assertEquals("/api/check_password", request.path)
        val json = objectMapper.readTree(request.body)
        assertEquals("admin", json.get("user")?.asText())
        assertEquals("localhost", json.get("host")?.asText())
        assertEquals("admin", json.get("password")?.asText())
    }

    @Test
    fun checkOperatorAccessThrows401WhenPasswordInvalid() {
        nextBody.set("1")

        val error = assertFailsWith<EjabberdException> { client.checkOperatorAccess() }

        assertEquals(401, error.statusCode.value())
        assertEquals("Unauthorized", error.status)
    }

    @Test
    fun checkAdminAccessAcceptsConfiguredAdmin() {
        nextBody.set("0")

        client.checkAdminAccess()

        val json = objectMapper.readTree(lastRequest().body)
        assertEquals("admin", json.get("user")?.asText())
        assertEquals("admin", json.get("password")?.asText())
    }

    @Test
    fun checkAdminAccessRejectsNonAdminUser() {
        val otherUser = MockHttpServletRequest().apply {
            addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("operator@localhost:pw".toByteArray()))
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(otherUser))

        val error = assertFailsWith<EjabberdException> { client.checkAdminAccess() }

        assertEquals(401, error.statusCode.value())
        assertEquals("Unauthorized", error.status)
    }

    @Test
    fun checkAdminAccessThrows401WhenPasswordInvalid() {
        nextBody.set("1")

        val error = assertFailsWith<EjabberdException> { client.checkAdminAccess() }

        assertEquals(401, error.statusCode.value())
        assertEquals("Unauthorized", error.status)
    }

    @Test
    fun extractCredentialsThrows401WhenAuthorizationHeaderMissing() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))

        val error = assertFailsWith<EjabberdException> { client.extractCredentialsFromHeader() }

        assertEquals(401, error.statusCode.value())
        assertEquals("Unauthorized", error.status)
    }

    @Test
    fun getAuthorizationHeaderReturnsNullWithoutServletRequestAttributes() {
        val previous = RequestContextHolder.getRequestAttributes()
        RequestContextHolder.resetRequestAttributes()

        assertNull(client.getAuthorizationHeader())

        RequestContextHolder.setRequestAttributes(previous)
    }

    @Test
    fun checkOperatorAccessThrowsEjabberdExceptionWhenServerReturnsError() {
        nextStatus.set(500)
        nextBody.set("""{"message":"Server error"}""")

        val error = assertFailsWith<EjabberdException> { client.checkOperatorAccess() }

        assertEquals("Server error", error.message)
    }

    @Test
    fun getRoomAffiliationReturnsEmptyStringWhenBodyIsMissing() {
        nextStatus.set(200)
        nextBody.set("")

        assertEquals("", client.getRoomAffiliation("support", "conference", "johndoe@localhost"))
    }
}
