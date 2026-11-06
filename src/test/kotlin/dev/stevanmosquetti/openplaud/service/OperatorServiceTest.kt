package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.GroupResponse
import dev.stevanmosquetti.openplaud.dto.OperatorResponse
import dev.stevanmosquetti.openplaud.entity.Group
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class OperatorServiceTest {

    private val operatorRepository = Mockito.mock(OperatorRepository::class.java)
    private val operatorGroupRepository = Mockito.mock(OperatorGroupRepository::class.java)
    private val ejabberd = Mockito.mock(EjabberdClient::class.java)
    private lateinit var service: OperatorService
    private val operatorId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val group = Group(name = "Support Team", node = "supportteam", service = "conference", id = groupId)

    @BeforeEach
    fun setUp() {
        service = OperatorService(operatorRepository, operatorGroupRepository, ejabberd)
    }

    @Test
    fun registersXmppUserBeforePersistingOperator() {
        Mockito.`when`(operatorRepository.findAll()).thenReturn(emptyList())
        val saved = Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")
        Mockito.`when`(operatorRepository.save(Mockito.any(Operator::class.java))).thenReturn(saved)

        val response = service.register("John Doe", "johndoe@email.com", "pw123")

        val inOrder = Mockito.inOrder(ejabberd, operatorRepository, operatorGroupRepository)
        inOrder.verify(ejabberd).registerUser("johndoe", "pw123")
        inOrder.verify(operatorRepository).save(Mockito.any(Operator::class.java))
        assertEquals(saved.id, response.id)
        assertEquals("John Doe", response.name)
        assertEquals("johndoe@email.com", response.email)
        assertEquals("johndoe", response.xmppUser)
        assertEquals("pw123", response.initialPassword)
    }

    @Test
    fun generatesPasswordWhenOmitted() {
        Mockito.`when`(operatorRepository.findAll()).thenReturn(emptyList())
        val saved = Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")
        Mockito.`when`(operatorRepository.save(Mockito.any(Operator::class.java))).thenReturn(saved)

        val response = service.register("John Doe", "johndoe@email.com", null)

        val generated = checkNotNull(response.initialPassword)
        Mockito.verify(ejabberd).registerUser("johndoe", generated)
        assertEquals(24, generated.length)
    }

    @Test
    fun rejectsOperatorWithTakenXmppUser() {
        Mockito.`when`(operatorRepository.findAll()).thenReturn(listOf(
            Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")))

        assertFailsWith<ConflictException> { service.register("john doe", "johndoe@email.com", "pw") }

        Mockito.verify(operatorRepository, Mockito.never()).save(Mockito.any(Operator::class.java))
    }

    @Test
    fun rejectsNameWithoutValidLocalpart() {
        assertFailsWith<InvalidSlugException> { service.register("a", "email", "pw") }
        assertFailsWith<InvalidSlugException> { service.register("!!?", "email", "pw") }
    }

    @Test
    fun unregistersXmppUserWhenPersistenceFails() {
        Mockito.`when`(operatorRepository.findAll()).thenReturn(emptyList())
        val dbError = RuntimeException("database down")
        Mockito.`when`(operatorRepository.save(Mockito.any(Operator::class.java))).thenThrow(dbError)

        val error = assertFailsWith<RuntimeException> { service.register("John Doe", "johndoe@email.com", "pw") }

        assertSame(dbError, error)
        val inOrder = Mockito.inOrder(ejabberd)
        inOrder.verify(ejabberd).registerUser("johndoe", "pw")
        inOrder.verify(ejabberd).unregisterUser("johndoe")
    }

    @Test
    fun removeOperatorDeletesRowsThenUnregistersXmppUser() {
        val saved = Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")
        Mockito.`when`(operatorRepository.findById(saved.id)).thenReturn(Optional.of(saved))

        service.removeOperator(saved.id)

        val inOrder = Mockito.inOrder(operatorGroupRepository, operatorRepository, ejabberd)
        inOrder.verify(operatorGroupRepository).deleteByOperator(saved.id)
        inOrder.verify(operatorRepository).deleteById(saved.id)
        inOrder.verify(ejabberd).unregisterUser("johndoe")
    }

    @Test
    fun removeOperatorRejectsUnknownOperator() {
        val operatorId = UUID.randomUUID()
        Mockito.`when`(operatorRepository.findById(operatorId)).thenReturn(Optional.empty())

        assertFailsWith<NotFoundException> { service.removeOperator(operatorId) }

        Mockito.verifyNoInteractions(operatorGroupRepository)
    }

    @Test
    fun removeOperatorDeletesRowsBeforeUnregisterFailure() {
        val saved = Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")
        Mockito.`when`(operatorRepository.findById(saved.id)).thenReturn(Optional.of(saved))
        Mockito.`doThrow`(RuntimeException("xmpp down")).`when`(ejabberd).unregisterUser("johndoe")

        assertFailsWith<RuntimeException> { service.removeOperator(saved.id) }

        Mockito.verify(operatorGroupRepository).deleteByOperator(saved.id)
        Mockito.verify(operatorRepository).deleteById(saved.id)
    }

    @Test
    fun registerPropagatesDatabaseErrorWhenCompensationUnregisterFails() {
        Mockito.`when`(operatorRepository.findAll()).thenReturn(emptyList())
        val dbError = RuntimeException("database down")
        Mockito.`when`(operatorRepository.save(Mockito.any(Operator::class.java))).thenThrow(dbError)
        Mockito.`doThrow`(RuntimeException("xmpp down")).`when`(ejabberd).unregisterUser("johndoe")

        val error = assertFailsWith<RuntimeException> { service.register("John Doe", "johndoe@email.com", "pw") }

        assertSame(dbError, error)
    }

    @Test
    fun checkAccessForwardsToEjabberdWhenOperatorExists() {
        Mockito.`when`(ejabberd.extractCredentialsFromHeader()).thenReturn(mapOf("user" to "johndoe", "password" to "pw"))
        Mockito.`when`(operatorRepository.existsByOperatorUsername("johndoe")).thenReturn(true)

        service.checkAccess()

        Mockito.verify(ejabberd).checkOperatorAccess()
    }

    @Test
    fun checkAccessRejectsUnknownOperator() {
        Mockito.`when`(ejabberd.extractCredentialsFromHeader()).thenReturn(mapOf("user" to "unknown", "password" to "pw"))
        Mockito.`when`(operatorRepository.existsByOperatorUsername("unknown")).thenReturn(false)

        assertFailsWith<ResponseStatusException> { service.checkAccess() }

        Mockito.verify(ejabberd, Mockito.never()).checkOperatorAccess()
    }

    @Test
    fun checkAccessRejectsMissingCredentials() {
        Mockito.`when`(ejabberd.extractCredentialsFromHeader()).thenReturn(mapOf("password" to "pw"))

        assertFailsWith<ResponseStatusException> { service.checkAccess() }
    }

    @Test
    fun getListOfOperatorsMapsEntities() {
        val saved = Operator(name = "John Doe", email = "johndoe@email.com", username = "johndoe")
        Mockito.`when`(operatorRepository.findAll()).thenReturn(listOf(saved))

        val response = service.getListOfOperators()

        Mockito.verify(ejabberd).checkOperatorAccess()
        assertEquals(listOf(OperatorResponse(saved.id, "John Doe", "johndoe@email.com", "johndoe")), response)
    }

    @Test
    fun getListOfGroupsForTheOperatorMapsEntities() {
        Mockito.`when`(operatorGroupRepository.listGroupsByOperatorId(operatorId)).thenReturn(listOf(group))

        val response = service.getListOfGroupsForTheOperator(operatorId)

        Mockito.verify(ejabberd).checkOperatorAccess()
        assertEquals(listOf(GroupResponse(group.id, "Support Team", "supportteam", "conference")), response)
    }
}
