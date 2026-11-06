package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class CustomerServiceTest {

    private val ejabberd = Mockito.mock(EjabberdClient::class.java)
    private val service = CustomerService(ejabberd)

    @Test
    fun registerCallsEjabberdAndReturnsGivenPassword() {
        val user = UUID.randomUUID()

        val response = service.register(user, "John Doe", "pw123")

        Mockito.verify(ejabberd).registerUser(user.toString(), "pw123")
        assertEquals(user.toString(), response.user)
        assertEquals("John Doe", response.name)
        assertEquals("pw123", response.password)
    }

    @Test
    fun registerGeneratesPasswordWhenOmitted() {
        val user = UUID.randomUUID()

        val response = service.register(user, "John Doe", null)

        val generated = response.password
        Mockito.verify(ejabberd).registerUser(user.toString(), generated)
        assertEquals(24, generated.length)
        assertTrue(generated.all { it.isLetterOrDigit() })
    }

    @Test
    fun removeCustomerUnregistersXmppUser() {
        val customerId = UUID.randomUUID()

        service.removeCustomer(customerId)

        Mockito.verify(ejabberd).unregisterUser(customerId.toString())
    }

    @Test
    fun generatedPasswordIsTwentyFourAlphanumericCharacters() {
        val password = CustomerService.generatePassword()

        assertEquals(24, password.length)
        assertTrue(password.all { it.isLetterOrDigit() })
    }
}
