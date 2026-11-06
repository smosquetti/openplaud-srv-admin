package dev.stevanmosquetti.openplaud.web

import dev.stevanmosquetti.openplaud.dto.CreateOperatorRequest
import dev.stevanmosquetti.openplaud.dto.GroupResponse
import dev.stevanmosquetti.openplaud.dto.OperatorResponse
import dev.stevanmosquetti.openplaud.service.OperatorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.UUID

class OperatorControllerTest {

    private val operatorService = Mockito.mock(OperatorService::class.java)
    private val controller = OperatorController(operatorService)

    @Test
    fun createOperatorReturnsCreatedWithServiceResult() {
        val response = OperatorResponse(
            id = UUID.randomUUID(),
            name = "John Doe",
            email = "johndoe@email.com",
            xmppUser = "johndoe",
            initialPassword = "pw123",
        )
        Mockito.`when`(operatorService.register("John Doe", "johndoe@email.com", "pw123")).thenReturn(response)

        val result = controller.createOperator(CreateOperatorRequest("John Doe", "johndoe@email.com", "pw123"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals(response, result.body)
        Mockito.verify(operatorService).register("John Doe", "johndoe@email.com", "pw123")
    }

    @Test
    fun createOperatorForwardsNullPassword() {
        val response = OperatorResponse(
            id = UUID.randomUUID(),
            name = "John Doe",
            email = "johndoe@email.com",
            xmppUser = "johndoe",
        )
        Mockito.`when`(operatorService.register("John Doe", "johndoe@email.com", null)).thenReturn(response)

        val result = controller.createOperator(CreateOperatorRequest("John Doe", "johndoe@email.com"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        Mockito.verify(operatorService).register("John Doe", "johndoe@email.com", null)
    }

    @Test
    fun deleteOperatorReturnsNoContent() {
        val operatorId = UUID.randomUUID()

        val result = controller.deleteOperator(operatorId)

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        Mockito.verify(operatorService).removeOperator(operatorId)
    }

    @Test
    fun checkPasswordReturnsOk() {
        val result = controller.checkPassword()

        assertEquals(HttpStatus.OK, result.statusCode)
        Mockito.verify(operatorService).checkAccess()
    }

    @Test
    fun getListOfOperatorsReturnsOk() {
        val response = OperatorResponse(
            id = UUID.randomUUID(),
            name = "John Doe",
            email = "johndoe@email.com",
            xmppUser = "johndoe",
        )
        Mockito.`when`(operatorService.getListOfOperators()).thenReturn(listOf(response))

        val result = controller.getListOfOperators()

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf(response), result.body)
    }

    @Test
    fun getListOfGroupsForTheOperatorReturnsOk() {
        val operatorId = UUID.randomUUID()
        val response = GroupResponse(
            id = UUID.randomUUID(),
            name = "Support Team",
            room = "supportteam",
            service = "conference",
        )
        Mockito.`when`(operatorService.getListOfGroupsForTheOperator(operatorId)).thenReturn(listOf(response))

        val result = controller.getListOfGroupsForTheOperator(operatorId)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf(response), result.body)
    }
}
