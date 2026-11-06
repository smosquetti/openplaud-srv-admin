package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.CreateTempRoomResponse
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Base64
import java.util.Optional
import java.util.UUID

class TempRoomServiceTest {

    private val operatorGroupRepository = Mockito.mock(OperatorGroupRepository::class.java)
    private val operatorRepository = Mockito.mock(OperatorRepository::class.java)
    private val ejabberd = Mockito.mock(EjabberdClient::class.java)
    private val service = TempRoomService(
        operatorGroupRepository,
        operatorRepository,
        ejabberd,
        "conference"
    )

    private val userId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val operatorId = UUID.randomUUID()
    private val room = "${userId.toString()}_${groupId}"
    private val operator = Operator(operatorId, "Operator", "operator", "operator@email.com")
    private val otherOperatorA = Operator(UUID.randomUUID(), "otherOperatorA", "other_a", "othera@email.com")
    private val otherOperatorB = Operator(UUID.randomUUID(), "otherOperatorB", "other_b", "otherb@email.com")

    @Test
    fun createOpensRoomAndAffiliatesUserAndGroupOperators() {
        Mockito.`when`(operatorGroupRepository.listOperatorsByGroupId(groupId))
            .thenReturn(listOf(otherOperatorA, otherOperatorB))
        Mockito.`when`(operatorRepository.findById(otherOperatorA.id)).thenReturn(Optional.of(otherOperatorA))
        Mockito.`when`(operatorRepository.findById(otherOperatorB.id)).thenReturn(Optional.of(otherOperatorB))

        val response = service.create(userId,"John Doe", groupId, "conference")

        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).createRoom(room, "John Doe", "conference")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", userId.toString(), "member")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", otherOperatorA.username, "admin")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", otherOperatorB.username, "admin")
        assertEquals(CreateTempRoomResponse(room, "conference"), response)
        assertEquals(room, response.room)
        assertEquals("conference", response.service)
    }

    @Test
    fun createFallsBackToDefaultMucServiceWhenServiceIsNull() {
        Mockito.`when`(operatorGroupRepository.listOperatorsByGroupId(groupId)).thenReturn(emptyList())

        val response = service.create(userId, "John Doe", groupId, null)

        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).createRoom(room, "John Doe", "conference")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", userId.toString(), "member")
        assertEquals(CreateTempRoomResponse(room, "conference"), response)
    }

    @Test
    fun removeAllOtherOperatorsClearsAffiliationOnlyForOtherOperators() {
        Mockito.`when`(operatorGroupRepository.listOperatorsByGroupId(groupId))
            .thenReturn(listOf(operator, otherOperatorA, otherOperatorB))
        Mockito.`when`(operatorRepository.getUsernameById(otherOperatorA.id)).thenReturn("other_a")
        Mockito.`when`(operatorRepository.getUsernameById(otherOperatorB.id)).thenReturn("other_b")

        service.removeAllOtherOperators(operatorId, groupId, "johndoe", "conference")

        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).setRoomAffiliation("johndoe", "conference", "other_a", "outcast")
        inOrder.verify(ejabberd).setRoomAffiliation("johndoe", "conference", "other_b", "outcast")
        Mockito.verify(operatorRepository, Mockito.never()).getUsernameById(operatorId)
    }

    @Test
    fun removeAllOtherOperatorsFallsBackToDefaultMucServiceWhenServiceIsNull() {
        Mockito.`when`(operatorGroupRepository.listOperatorsByGroupId(groupId)).thenReturn(listOf(otherOperatorA))
        Mockito.`when`(operatorRepository.getUsernameById(otherOperatorA.id)).thenReturn("other_a")

        service.removeAllOtherOperators(operatorId, groupId, "johndoe", null)

        Mockito.verify(ejabberd).setRoomAffiliation("johndoe", "conference", "other_a", "outcast")
    }

    @Test
    fun removeRoomDestroysRoomOnGivenService() {
        service.removeRoom(room, "conference")
        Mockito.verify(ejabberd).destroyRoom(room, "conference")
    }

    @Test
    fun removeRoomFallsBackToDefaultMucServiceWhenServiceIsNull() {
        service.removeRoom(room, null)
        Mockito.verify(ejabberd).destroyRoom(room, "conference")
    }

    @Test
    fun transferGrantsAdminAffiliationAndSubscriptionThenOutcastsPrevious() {
        val previousOperatorId = UUID.randomUUID()
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(operatorRepository.getUsernameById(previousOperatorId)).thenReturn("other_a")

        service.transferToAnotherOperator(room, operatorId, previousOperatorId, "conference")

        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).checkOperatorAccess()
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", "operator", "admin")
        inOrder.verify(ejabberd).setRoomSubscription(room, "conference", "operator", "Operator")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", "other_a", "outcast")
    }

    @Test
    fun transferFallsBackToDefaultMucServiceWhenServiceIsNull() {
        val previousOperatorId = UUID.randomUUID()
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(operatorRepository.getUsernameById(previousOperatorId)).thenReturn("other_a")

        service.transferToAnotherOperator(room, operatorId, previousOperatorId, null)

        Mockito.verify(ejabberd).setRoomAffiliation(room, "conference", "operator", "admin")
        Mockito.verify(ejabberd).setRoomSubscription(room, "conference", "operator", "Operator")
        Mockito.verify(ejabberd).setRoomAffiliation(room, "conference", "other_a", "outcast")
    }

    @Test
    fun createFallsBackToDefaultMucServiceForGroupOperatorsWhenServiceIsNull() {
        Mockito.`when`(operatorGroupRepository.listOperatorsByGroupId(groupId))
            .thenReturn(listOf(otherOperatorA))
        Mockito.`when`(operatorRepository.findById(otherOperatorA.id)).thenReturn(Optional.of(otherOperatorA))

        val response = service.create(userId, "John Doe", groupId, null)

        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).createRoom(room, "John Doe", "conference")
        inOrder.verify(ejabberd).setRoomAffiliation(room, "conference", otherOperatorA.username, "admin")
        inOrder.verify(ejabberd).setRoomSubscription(room, "conference", otherOperatorA.username, otherOperatorA.name)
        assertEquals(CreateTempRoomResponse(room, "conference"), response)
    }
}
