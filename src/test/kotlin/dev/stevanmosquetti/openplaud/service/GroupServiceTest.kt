package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.entity.Group
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.entity.OperatorGroup
import dev.stevanmosquetti.openplaud.entity.OperatorGroupId
import dev.stevanmosquetti.openplaud.repository.GroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.util.Optional
import java.util.UUID

class GroupServiceTest {

    private val groupRepository = Mockito.mock(GroupRepository::class.java)
    private val operatorRepository = Mockito.mock(OperatorRepository::class.java)
    private val operatorGroupRepository = Mockito.mock(OperatorGroupRepository::class.java)
    private val ejabberd = Mockito.mock(EjabberdClient::class.java)
    private lateinit var service: GroupService

    private val operatorId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val operator = Operator(name = "John Doe", id = operatorId, username = "johndoe", email = "johndoe@email.com")
    private val group = Group(name = "Support Team", node = "supportteam", service = "conference", id = groupId)

    @BeforeEach
    fun setUp() {
        service = GroupService(groupRepository, operatorRepository, operatorGroupRepository, ejabberd)
    }

    @Test
    fun createsRoomBeforePersistingGroup() {
        Mockito.`when`(groupRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(groupRepository.save(Mockito.any(Group::class.java))).thenReturn(group)

        val response = service.create("Support Team", "conference")

        val inOrder = Mockito.inOrder(ejabberd, groupRepository)
        inOrder.verify(ejabberd).createPersistentRoom("supportteam", "Support Team", "conference")
        inOrder.verify(groupRepository).save(Mockito.any(Group::class.java))
        assertEquals(group.id, response.id)
        assertEquals("Support Team", response.name)
        assertEquals("supportteam", response.room)
        assertEquals("conference", response.service)
    }

    @Test
    fun rejectsGroupWithTakenRoom() {
        Mockito.`when`(groupRepository.findAll()).thenReturn(listOf(
            Group(name = "Support Team", node = "supportteam", service = "conference")))

        assertFailsWith<ConflictException> { service.create("Support Team", "conference") }
    }

    @Test
    fun rejectsGroupNameWithoutValidRoom() {
        assertFailsWith<InvalidSlugException> { service.create("a", "conference") }
        assertFailsWith<InvalidSlugException> { service.create("!!?", "conference") }
    }

    @Test
    fun destroysRoomWhenPersistenceFails() {
        Mockito.`when`(groupRepository.findAll()).thenReturn(emptyList())
        val dbError = RuntimeException("database down")
        Mockito.`when`(groupRepository.save(Mockito.any(Group::class.java))).thenThrow(dbError)

        val error = assertFailsWith<RuntimeException> { service.create("Support Team", "conference") }

        assertSame(dbError, error)
        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).createPersistentRoom("supportteam", "Support Team", "conference")
        inOrder.verify(ejabberd).destroyRoom("supportteam", "conference")
    }

    @Test
    fun addOperatorSetsMemberAffiliationThenPersistsMembership() {
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))
        Mockito.`when`(operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId, groupId)).thenReturn(false)

        service.addOperatorToGroup(groupId, operatorId)

        val inOrder = Mockito.inOrder(ejabberd, operatorGroupRepository)
        inOrder.verify(ejabberd).setRoomAffiliation("supportteam", "conference", "johndoe", "admin")
        inOrder.verify(operatorGroupRepository).save(Mockito.any(OperatorGroup::class.java))

        val captor = ArgumentCaptor.forClass(OperatorGroup::class.java)
        Mockito.verify(operatorGroupRepository).save(captor.capture())
        assertEquals(operatorId, captor.value.id.operatorId)
        assertEquals(groupId, captor.value.id.groupId)
    }

    @Test
    fun addOperatorRejectsUnknownOperator() {
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.empty())

        assertFailsWith<NotFoundException> { service.addOperatorToGroup(groupId, operatorId) }
    }

    @Test
    fun addOperatorRejectsUnknownGroup() {
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.empty())

        assertFailsWith<NotFoundException> { service.addOperatorToGroup(groupId, operatorId) }
    }

    @Test
    fun addOperatorRejectsDuplicateMembership() {
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))
        Mockito.`when`(operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId, groupId)).thenReturn(true)

        assertFailsWith<ConflictException> { service.addOperatorToGroup(groupId, operatorId) }
    }

    @Test
    fun addOperatorRevertsAffiliationWhenPersistenceFails() {
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))
        Mockito.`when`(operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId, groupId)).thenReturn(false)
        val dbError = RuntimeException("database down")
        Mockito.`when`(operatorGroupRepository.save(Mockito.any(OperatorGroup::class.java))).thenThrow(dbError)

        val error = assertFailsWith<RuntimeException> { service.addOperatorToGroup(groupId, operatorId) }

        assertSame(dbError, error)
        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).setRoomAffiliation("supportteam", "conference", "johndoe", "admin")
        inOrder.verify(ejabberd).setRoomAffiliation("supportteam", "conference", "johndoe", "none")
    }

    @Test
    fun removeOperatorClearsAffiliationThenDeletesRow() {
        val key = OperatorGroupId(operatorId, groupId)
        val membership = OperatorGroup(key)
        Mockito.`when`(operatorGroupRepository.findById(key)).thenReturn(Optional.of(membership))
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator))
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))

        service.removeOperatorFromGroup(groupId, operatorId)

        val inOrder = Mockito.inOrder(ejabberd, operatorGroupRepository)
        inOrder.verify(ejabberd).setRoomAffiliation("supportteam", "conference", "johndoe", "none")
        inOrder.verify(operatorGroupRepository).deleteById(key)
    }

    @Test
    fun removeOperatorRejectsUnknownMembership() {
        val key = OperatorGroupId(operatorId, groupId)
        Mockito.`when`(operatorGroupRepository.findById(key)).thenReturn(Optional.empty())

        assertFailsWith<NotFoundException> { service.removeOperatorFromGroup(groupId, operatorId) }
    }

    @Test
    fun removeRoomClearsMembershipsThenDestroysRoomByNode() {
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))

        service.removeRoom(groupId)

        val inOrder = Mockito.inOrder(operatorGroupRepository, groupRepository, ejabberd)
        inOrder.verify(operatorGroupRepository).deleteByGroup(groupId)
        inOrder.verify(groupRepository).deleteById(groupId)
        inOrder.verify(ejabberd).destroyRoom("supportteam", "conference")
    }

    @Test
    fun removeRoomRejectsUnknownGroup() {
        Mockito.`when`(groupRepository.findById(groupId)).thenReturn(Optional.empty())

        assertFailsWith<NotFoundException> { service.removeRoom(groupId) }

        Mockito.verifyNoInteractions(operatorGroupRepository)
    }

    @Test
    fun createPropagatesDatabaseErrorWhenCompensationDestroyFails() {
        Mockito.`when`(groupRepository.findAll()).thenReturn(emptyList())
        val dbError = RuntimeException("database down")
        Mockito.`when`(groupRepository.save(Mockito.any(Group::class.java))).thenThrow(dbError)
        Mockito.`doThrow`(RuntimeException("xmpp down")).`when`(ejabberd).destroyRoom("supportteam", "conference")

        val error = assertFailsWith<RuntimeException> { service.create("Support Team", "conference") }

        assertSame(dbError, error)
    }
}
