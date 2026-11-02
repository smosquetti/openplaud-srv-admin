package dev.stevanmosquetti.openplaud

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.entity.OperatorGroupId
import dev.stevanmosquetti.openplaud.repository.GroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID

/**
 * End-to-end test against the real stack (PostgreSQL + ejabberd mod_http_api):
 * register operator, create group, add/remove membership — and verify the MUC
 * affiliation on the XMPP server itself. Cleans up everything it creates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiIntegrationTest @Autowired constructor(
    private val applicationContext: ApplicationContext,
    private val ejabberd: EjabberdClient,
    private val operatorRepository: OperatorRepository,
    private val groupRepository: GroupRepository,
    private val operatorGroupRepository: OperatorGroupRepository,
    @Value("\${ejabberd.username}") var username: String,
    @Value("\${ejabberd.password}") val password: String,
) {

    private val log = LoggerFactory.getLogger(AdminApiIntegrationTest::class.java)
    private val http = HttpClient.newHttpClient()
    private val objectMapper = ObjectMapper()
    private val base: String
        get() = "http://localhost:${applicationContext.environment.getProperty("local.server.port")}"
    private val authorization: String
        get() = "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"

    private var xmppUser: String? = null
    private var room: String? = null
    private var service: String? = null
    private var operatorId: UUID? = null
    private var groupId: UUID? = null

    private fun send(method: HttpMethod, path: String, json: String? = null): Pair<Int, JsonNode> {
        val publisher = if (json == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(json)
        }
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .method(method.name(), publisher)
            .header("Content-Type", "application/json")
            .header("Authorization", authorization)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = if (response.body().isBlank()) "{}" else response.body()
        return response.statusCode() to objectMapper.readTree(body)
    }

    @Test
    fun operatorGroupLifecycle() {
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val operatorName = "It Op $suffix"
        val groupName = "It Group $suffix"
        val serviceName = "conference"

        // 1. Register operator: XMPP user + operators row
        val (operatorStatus, operatorBody) = send(HttpMethod.POST, "/api/v1/operators", """{"name":"$operatorName", "email":"test@email.com"}""")
        assertEquals(201, operatorStatus)
        operatorId = UUID.fromString(operatorBody.path("id").asText())
        xmppUser = operatorBody.path("xmppUser").asText()
        assertTrue(operatorBody.path("initialPassword").asText().isNotEmpty())
        assertTrue(operatorRepository.existsById(operatorId!!))

        // 2. Create group: persistent MUC room + groups row
        val (groupStatus, groupBody) = send(HttpMethod.POST, "/api/v1/groups", """{"name":"$groupName", "service":"$serviceName"}""")
        assertEquals(201, groupStatus)
        groupId = UUID.fromString(groupBody.path("id").asText())
        room = groupBody.path("room").asText()
        service = groupBody.path("service").asText()
        assertTrue(groupRepository.existsById(groupId!!))

        // 3. Add operator to group: MUC affiliation + operator_groups row
        val (addStatus, _) = send(HttpMethod.POST, "/api/v1/groups/$groupId/operators/$operatorId", null)
        assertEquals(201, addStatus)
        assertTrue(operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId!!, groupId!!))

        // 4. Membership is visible on the XMPP server
        assertEquals("admin", ejabberd.getRoomAffiliation(room!!, service!!, "$xmppUser@${ejabberd.host}"))

        // 5. Remove operator from group: affiliation cleared + row deleted
        val (removeStatus, _) = send(HttpMethod.DELETE, "/api/v1/groups/$groupId/operators/$operatorId")
        assertEquals(204, removeStatus)
        assertTrue(!operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId!!, groupId!!))
        assertEquals("none", ejabberd.getRoomAffiliation(room!!, service!!, "$xmppUser@${ejabberd.host}"))
    }

    @AfterEach
    fun cleanup() {
        if (operatorId == null || groupId == null) {
            return
        }
        try {
            operatorGroupRepository.deleteById(OperatorGroupId(operatorId!!, groupId!!))
        } catch (e: Exception) {
            log.warn("Cleanup: could not delete operator_groups row", e)
        }
        try {
            operatorRepository.deleteById(operatorId!!)
        } catch (e: Exception) {
            log.warn("Cleanup: could not delete operator row", e)
        }
        try {
            groupRepository.deleteById(groupId!!)
        } catch (e: Exception) {
            log.warn("Cleanup: could not delete group row", e)
        }
        xmppUser?.let {
            try {
                ejabberd.unregisterUser(it)
            } catch (e: Exception) {
                log.warn("Cleanup: could not unregister XMPP user $it", e)
            }
        }
        room?.let {
            try {
                ejabberd.destroyRoom(it, service!!)
            } catch (e: Exception) {
                log.warn("Cleanup: could not destroy MUC room $it", e)
            }
        }
    }
}
