package dev.stevanmosquetti.openplaud.client

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import kotlin.io.encoding.Base64

/**
 * Thin client over ejabberd's mod_http_api (POST /api/<command>, Basic auth, flat JSON body).
 *
 * `set_room_affiliation` / `get_room_affiliation` fail with "Room doesn't exists"
 * when the room does not exist on the server — e.g. a non-persistent room that has
 * been destroyed while empty.
 */
@Component
class EjabberdClient(
    @Value("\${ejabberd.base-url}") baseUrl: String,
    @Value("\${ejabberd.host}") val host: String,
    @Value("\${ejabberd.username}") val adminUsername: String,
    @Value("\${ejabberd.password}") val adminPassword: String,
) {

    private val log = LoggerFactory.getLogger(EjabberdClient::class.java)

    private val authorization: String
        get() = "Basic ${java.util.Base64.getEncoder().encodeToString("$adminUsername:$adminPassword".toByteArray())}"
    
    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .build()

    /**
     * Returns the caller's `Authorization` header value, or null when the request
     * has no servlet attributes.
     */
    fun getAuthorizationHeader(): String? {
        val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val request: HttpServletRequest? = requestAttributes?.request
        val authorizationHeaderValue = request?.getHeader(HttpHeaders.AUTHORIZATION)
        return authorizationHeaderValue
    }

    /**
     * Registers a new XMPP user (mod_http_api `register`).
     */
    fun registerUser(user: String, userPassword: String) {
        log.debug("Registering XMPP user '{}'", user)
        postForStatus(
            "register",
            mapOf("user" to user, "host" to host, "password" to userPassword)
        )
    }

    /**
     * Unregisters a user from the server (mod_http_api `unregister`). Used to compensate
     * a registration whose database row could not be persisted.
     */
    fun unregisterUser(user: String) {
        log.debug("Unregistering XMPP user '{}'", user)
        postForStatus(
            "unregister",
            mapOf("user" to user, "host" to host)
        )
    }

    /**
     * Creates a non-persistent, members-only MUC room (mod_http_api
     * `create_room_with_opts`).
     */
    fun createRoom(room: String, title: String, service: String) {
        log.debug("Creating MUC room '{}' on service '{}'", room, service)
        postForStatus(
            "create_room_with_opts",
            mapOf(
                "room" to room,
                "service" to service,
                "host" to host,
                "options" to listOf(
                    mapOf("name" to "members_only", "value" to "true"),
                    mapOf("name" to "title", "value" to title),
                    mapOf("name" to "allow_subscription", "value" to "true"),
                    mapOf("name" to "logging", "value" to "true")),
            )
        )
    }

    /**
     * Creates a persistent, members-only MUC room (mod_http_api
     * `create_room_with_opts`).
     */
    fun createPersistentRoom(room: String, title: String, service: String) {
        log.debug("Creating persistent MUC room '{}' on service '{}'", room, service)
        postForStatus(
            "create_room_with_opts",
            mapOf(
                "room" to room,
                "service" to service,
                "host" to host,
                "options" to listOf(
                    mapOf("name" to "persistent", "value" to "true"),
                    mapOf("name" to "title", "value" to title),
                    mapOf("name" to "members_only", "value" to "true"),
                    mapOf("name" to "allow_subscription", "value" to "true"),
                    mapOf("name" to "logging", "value" to "true")),
            )
        )
    }

    /**
     * Destroys a MUC room (mod_http_api `destroy_room`). Used when removing a group
     * or temp room, and to compensate a room creation whose database row could not
     * be persisted.
     */
    fun destroyRoom(room: String, service: String) {
        log.debug("Destroying MUC room '{}' on service '{}'", room, service)
        postForStatus(
            "destroy_room",
            mapOf("room" to room, "service" to service)
        )
    }

    /**
     * Sets the MUC affiliation of `user` in `room` (mod_http_api
     * `set_room_affiliation`).
     */
    fun setRoomAffiliation(room: String, service: String, user: String, affiliation: String = "member") {
        log.debug("Setting affiliation '{}' for user '{}' in room '{}' on service '{}'", affiliation, user, room, service)
        postForStatus(
            "set_room_affiliation",
            mapOf(
                "room" to room,
                "service" to service,
                "user" to user,
                "host" to host,
                "affiliation" to affiliation,
            )
        )
    }

    /**
     * Returns the MUC affiliation of `jid` in `room` (mod_http_api
     * `get_room_affiliation`).
     */
    fun getRoomAffiliation(room: String, service: String, jid: String): String {
        log.debug("Getting affiliation for jid '{}' in room '{}' on service '{}'", jid, room, service)
        return postForString(
            "get_room_affiliation",
            mapOf("room" to room, "service" to service, "jid" to jid)
        ).replace("\"", "")
    }

    /**
     * Subscribe `user` to `room` (mod_http_api `subscribe_room`).
     */
    fun setRoomSubscription(room: String, service: String, user: String, name: String) {
        log.debug("Subscribing user '{}' in room '{}' on service '{}'", user, room, service)
        val nodes = arrayOf("urn:xmpp:mucsub:nodes:messages")
        postForStatus(
            "subscribe_room",
            mapOf(
                "user" to user,
                "host" to host,
                "nick" to name,
                "room" to room,
                "service" to service,
                "nodes" to nodes
            )
        )

    }
    
    /**
     * Verifies the caller's operator credentials via `check_password` and returns
     * the raw ejabberd response.
     */
    fun checkOperatorAccess() {
        val credentials = extractCredentialsFromHeader()
        val response = postForString(
            "check_password",
            mapOf(
                "user" to credentials["user"]?.split("@")[0],
                "host" to host, 
                "password" to credentials["password"]
            )
        ).replace("\"", "")
        if (Integer.valueOf(response) == 1) {
            throw EjabberdException(
                "Authentication failure",
                HttpStatusCode.valueOf(401),
                "Unauthorized"
            )
        }
    }
    
    /**
     * Verifies the caller is the configured admin account and its credentials pass
     * `check_password`; throws a 401 EjabberdException otherwise.
     */
    fun checkAdminAccess(): String {
        val credentials = extractCredentialsFromHeader()
        if (credentials["user"] != adminUsername) {
            throw EjabberdException("Authentication failure", HttpStatusCode.valueOf(401), "Unauthorized")
        }
        return postForString(
            "check_password",
            mapOf(
                "user" to credentials["user"], 
                "host" to host, 
                "password" to credentials["password"]
            )
        ).replace("\"", "")
    }
    
    /**
     * Decodes the Basic `Authorization` header into a user/password pair; throws a
     * 401 EjabberdException when the header is missing.
     */
    private fun extractCredentialsFromHeader(): Map<String, String> {
        val auth = getAuthorizationHeader() ?: throw EjabberdException(
            "Authentication failure",
            HttpStatusCode.valueOf(401),
            "Unauthorized"
        )
        val decodedBytes: ByteArray = Base64.decode(auth.split(" ")[1])
        val decodedString = decodedBytes.decodeToString()
        val user = decodedString.split(":")[0]
        val password = decodedString.split(":")[1]
        return mapOf("user" to user, "password" to password)
    }

    /**
     * Posts a command to mod_http_api and throws EjabberdException on a non-2xx
     * response.
     */
    private fun postForStatus(command: String, body: Any) {
        restClient.post()
            .uri("/$command")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .body(objectToJsonString(body))
            .exchange { _, response ->
                if (!response.statusCode.is2xxSuccessful()) {
                    throw errorFromResponse(command, response)
                }
            }
    }

    /**
     * Posts a command to mod_http_api and returns the response body as a string.
     */
    private fun postForString(command: String, body: Any): String {
        return restClient.post()
            .uri("/$command")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .body(objectToJsonString(body))
            .exchange { _, response ->
                if (!response.statusCode.is2xxSuccessful()) {
                    throw errorFromResponse(command, response)
                }
                response.bodyTo<String>(String::class.java) ?: ""
            }
    }

    /**
     * Serializes a command body to a JSON string.
     */
    private fun objectToJsonString(map: Any): String {
        val mapper: ObjectMapper = ObjectMapper()
        return mapper.writeValueAsString(map)
    }

    /**
     * Builds an EjabberdException from a failed ejabberd response.
     */
    private fun errorFromResponse(command: String, response: ConvertibleClientHttpResponse): EjabberdException {
        val errorBody: Map<*, *>? = try {
            response.bodyTo(Map::class.java)
        } catch (e: Exception) {
            null
        }
        val message = (errorBody?.get("message") as? String) ?: "Unknown ejabberd error"
        log.warn(
            "ejabberd mod_http_api command '{}' failed: HTTP {} ({}): {}",
            command, response.statusCode, response.statusText, message,
        )
        return EjabberdException(message, response.statusCode, response.statusText)
    }
}
