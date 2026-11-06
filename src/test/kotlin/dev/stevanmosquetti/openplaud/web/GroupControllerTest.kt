package dev.stevanmosquetti.openplaud.web

import dev.stevanmosquetti.openplaud.dto.CreateGroupRequest
import dev.stevanmosquetti.openplaud.dto.GroupResponse
import dev.stevanmosquetti.openplaud.dto.OperatorResponse
import dev.stevanmosquetti.openplaud.service.GroupService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.UUID

class GroupControllerTest {

    private val groupService = Mockito.mock(GroupService::class.java)
    private val controller = GroupController(groupService)

    @Test
    fun createGroupReturnsCreatedWithServiceResult() {
        val response = GroupResponse(
            id = UUID.randomUUID(),
            name = "Support Team",
            room = "supportteam",
            service = "conference",
        )
        Mockito.`when`(groupService.create("Support Team", "conference")).thenReturn(response)

        val result = controller.createGroup(CreateGroupRequest("Support Team", "conference"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals(response, result.body)
        Mockito.verify(groupService).create("Support Team", "conference")
    }

    @Test
    fun addOperatorReturnsCreated() {
        val groupId = UUID.randomUUID()
        val operatorId = UUID.randomUUID()

        val result = controller.addOperatorToGroup(groupId, operatorId)

        assertEquals(HttpStatus.CREATED, result.statusCode)
        Mockito.verify(groupService).addOperatorToGroup(groupId, operatorId)
    }

    @Test
    fun removeOperatorReturnsNoContent() {
        val groupId = UUID.randomUUID()
        val operatorId = UUID.randomUUID()

        val result = controller.removeOperatorFromGroup(groupId, operatorId)

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        Mockito.verify(groupService).removeOperatorFromGroup(groupId, operatorId)
    }

    @Test
    fun removeRoomReturnsNoContent() {
        val groupId = UUID.randomUUID()

        val result = controller.removeRoom(groupId)

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        Mockito.verify(groupService).removeRoom(groupId)
    }

    @Test
    fun getListOfGroupsReturnsOk() {
        val response = GroupResponse(
            id = UUID.randomUUID(),
            name = "Support Team",
            room = "supportteam",
            service = "conference",
        )
        Mockito.`when`(groupService.getListOfGroups()).thenReturn(listOf(response))

        val result = controller.getListOfGroups()

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf(response), result.body)
    }

    @Test
    fun getListOfOperatorsForTheGroupReturnsOk() {
        val groupId = UUID.randomUUID()
        val response = OperatorResponse(
            id = UUID.randomUUID(),
            name = "John Doe",
            email = "johndoe@email.com",
            xmppUser = "johndoe",
        )
        Mockito.`when`(groupService.getListOfOperatorsForTheGroup(groupId)).thenReturn(listOf(response))

        val result = controller.getListOfOperatorsForTheGroup(groupId)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf(response), result.body)
    }
}
