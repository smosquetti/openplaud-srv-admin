package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.GroupResponse
import dev.stevanmosquetti.openplaud.dto.OperatorResponse
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import dev.stevanmosquetti.openplaud.slugify.Slugifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.UUID

/**
 * Manages operators: registers them (a new XMPP user on ejabberd + a row in the
 * `operators` table), lists them from the database, and checks operator access.
 *
 * The ejabberd call happens before the database write. If the database write fails, the
 * freshly registered XMPP user is unregistered again so the database stays authoritative.
 */
@Service
class OperatorService(
    private val operatorRepository: OperatorRepository,
    private val operatorGroupRepository: OperatorGroupRepository,
    private val ejabberd: EjabberdClient,
) {

    private val log = LoggerFactory.getLogger(OperatorService::class.java)

    /**
     * Lists all operators from the database
     */
    fun getListOfOperators(): List<OperatorResponse> {
        ejabberd.checkOperatorAccess()
        return operatorRepository.findAll()
            .filterIsInstance<Operator>()
            .map {
                o -> OperatorResponse(o.id, o.name, o.email, o.username)
            }
            .toList()
    }

    fun getListOfGroupsForTheOperator(operatorId: UUID): List<GroupResponse> {
        ejabberd.checkOperatorAccess()
        return operatorGroupRepository.listGroupsByOperatorId(operatorId)
            .map {
                g -> GroupResponse(g.id, g.name, g.node, g.service)
            }
            .toList()
    }

    /**
     * Registers an operator: creates the XMPP user and inserts the `operators` row.
     * When no password is given, one is generated and returned (once) in the
     * response.
     */
    fun register(name: String, email: String, password: String?): OperatorResponse {
        ejabberd.checkAdminAccess()
        val xmppUser = slugify(name)
        requireXmppUserFree(xmppUser)
        val actualPassword = password ?: generatePassword()
        ejabberd.registerUser(xmppUser, actualPassword)
        val operator = try {
            operatorRepository.save(Operator(name = name, username = xmppUser, email = email))
        } catch (dbError: RuntimeException) {
            cleanupAfterDbFailure(xmppUser, dbError)
            throw dbError
        }
        log.info("Registered operator '$name' as XMPP user '$xmppUser'")
        return OperatorResponse(
            id = operator.id,
            name = operator.name,
            email = operator.email,
            xmppUser = xmppUser,
            initialPassword = actualPassword,
        )
    }

    /**
     * Throws ConflictException when another operator already produces the same
     * XMPP user.
     */
    private fun requireXmppUserFree(xmppUser: String) {
        val taken = operatorRepository.findAll().any { Slugifier.slugify(it.name) == xmppUser }
        if (taken) {
            throw ConflictException("An operator already exists for the XMPP user '$xmppUser'")
        }
    }

    private fun cleanupAfterDbFailure(xmppUser: String, cause: RuntimeException) {
        log.error("Could not persist operator '$xmppUser'; unregistering the XMPP user", cause)
        try {
            ejabberd.unregisterUser(xmppUser)
        } catch (e: Exception) {
            log.error("Compensating unregister for '$xmppUser' also failed; the user is orphaned on the XMPP server", e)
        }
    }
    
    /**
     * Removes an operator: deletes its membership rows and `operators` row, and
     * unregisters the XMPP user.
     */
    fun removeOperator(operatorId: UUID) {
        ejabberd.checkAdminAccess()
        val operator = requireOperator(operatorId)
        operatorGroupRepository.deleteByOperator(operator.id)
        operatorRepository.deleteById(operator.id)
        ejabberd.unregisterUser(operator.username)
        log.info("Removed operator $operatorId and unregistered XMPP user '${operator.username}'")
    }
    
    /**
     * Verifies the operator's Basic auth credentials against ejabberd
     * `check_password`.
     */
    fun checkAccess() {
        ejabberd.checkOperatorAccess()
    }

    /**
     * Returns the operator or throws NotFoundException.
     */
    private fun requireOperator(operatorId: UUID): Operator =
        operatorRepository.findById(operatorId)
            .orElseThrow { NotFoundException("Operator $operatorId not found") }

    /**
     * Slugifies the display name; an invalid localpart becomes
     * InvalidSlugException.
     */
    private fun slugify(name: String): String = try {
        Slugifier.slugify(name)
    } catch (e: IllegalArgumentException) {
        throw InvalidSlugException(e.message ?: "The operator name does not produce a valid XMPP user")
    }

    companion object {
        private const val PASSWORD_LENGTH = 24
        private const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val secureRandom = SecureRandom()

        /**
         * Generates a 24-character random alphanumeric password.
         */
        fun generatePassword(): String {
            val chars = CharArray(PASSWORD_LENGTH)
            for (i in chars.indices) {
                chars[i] = PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]
            }
            return String(chars)
        }
    }
}
