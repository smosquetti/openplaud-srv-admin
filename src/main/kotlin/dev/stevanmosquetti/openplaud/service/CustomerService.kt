package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.CustomerResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.UUID

@Service
class CustomerService(
    private val ejabberd: EjabberdClient
) {

    private val log = LoggerFactory.getLogger(CustomerService::class.java)

    fun register(user: UUID, name: String, password: String?): CustomerResponse {
        val actualPassword = password ?: generatePassword()
        ejabberd.registerUser(user.toString(), actualPassword)
        log.info("Registered customer '$name' as XMPP user '$user'")
        return CustomerResponse(
            user = user.toString(),
            name = name,
            password = actualPassword,
        )

    }

    fun removeCustomer(id: UUID) {
        ejabberd.unregisterUser(id.toString())
        log.info("Removed customer and unregistered XMPP user '${id}'")
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