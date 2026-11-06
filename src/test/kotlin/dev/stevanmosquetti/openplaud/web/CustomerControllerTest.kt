package dev.stevanmosquetti.openplaud.web

import dev.stevanmosquetti.openplaud.dto.CreateCustomerRequest
import dev.stevanmosquetti.openplaud.dto.CustomerResponse
import dev.stevanmosquetti.openplaud.service.CustomerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.UUID

class CustomerControllerTest {

    private val customerService = Mockito.mock(CustomerService::class.java)
    private val controller = CustomerController(customerService)

    @Test
    fun createCustomerReturnsCreatedWithServiceResult() {
        val user = UUID.randomUUID()
        val response = CustomerResponse(user.toString(), "John Doe", "pw123")
        Mockito.`when`(customerService.register(user, "John Doe", "pw123")).thenReturn(response)

        val result = controller.createCustomer(CreateCustomerRequest(user, "John Doe", "pw123"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals(response.user, result.body!!.user)
        assertEquals(response.name, result.body!!.name)
        assertEquals(response.password, result.body!!.password)
    }

    @Test
    fun createCustomerForwardsNullPassword() {
        val user = UUID.randomUUID()
        val response = CustomerResponse(user.toString(), "John Doe", "generated")
        Mockito.`when`(customerService.register(user, "John Doe", null)).thenReturn(response)

        val result = controller.createCustomer(CreateCustomerRequest(user, "John Doe"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        Mockito.verify(customerService).register(user, "John Doe", null)
    }

    @Test
    fun deleteCustomerReturnsNoContent() {
        val customerId = UUID.randomUUID()

        val result = controller.deleteOperator(customerId)

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        Mockito.verify(customerService).removeCustomer(customerId)
    }
}
