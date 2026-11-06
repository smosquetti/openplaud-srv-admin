package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.CreateTempRoomResponse
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import dev.stevanmosquetti.openplaud.slugify.Slugifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Optional
import java.util.UUID

/**
 * Manages temp rooms: creates a per-conversation MUC room and controls which group
 * operators keep access.
 */
@Service
class TempRoomService(
    private val operatorGroupRepository: OperatorGroupRepository,
    private val operatorRepository: OperatorRepository,
    private val ejabberd: EjabberdClient,
    @Value("\${ejabberd.muc-service}") var mucService: String,
) {

    private val log = LoggerFactory.getLogger(TempRoomService::class.java)

    /**
     * Creates a temp MUC room for `user` (affiliation `member`) and makes every
     * operator of the group an admin of the room so they can transfer the call.
     */
    fun create(user: UUID, name: String, groupId: UUID, service: String?): CreateTempRoomResponse {
        val room = "${user}_${groupId}"
        ejabberd.createRoom(room, name, service?: mucService)
        ejabberd.setRoomAffiliation(
            room,
            service?: mucService,
            user.toString(),
            "member")
        val operators: List<Operator> = operatorGroupRepository.listOperatorsByGroupId(groupId)
        operators
            .forEach { op ->
                setAffiliationAndSubscription(room, op.id, "admin", service?: mucService)
            }

        log.info("Created temporary MUC room '$room' for user '$user' in service '${service?: mucService}'")
        return CreateTempRoomResponse(room, service?: mucService)
    }

    /**
     * Sets the affiliation of a temp MUC room for `user` (affiliation `admin`) and  
     * subscribe the operator to the room so they can receive messages.
     */
    private fun setAffiliationAndSubscription(room: String, operatorId: UUID, affiliation: String, service: String?) {
        val operator: Optional<Operator> = operatorRepository.findById(operatorId)
        ejabberd.setRoomAffiliation(
            room,
            service?: mucService,
            operator.get().username,
            affiliation)
        ejabberd.setRoomSubscription(
            room,
            service?: mucService,
            operator.get().username,
            operator.get().name)
        log.info("Set room affiliation and subscription for user '${operator.get().username}' in MUC room '$room' service '${service?: mucService}'")
    }

    /**
     * Sets every operator of the group other than the joining one to `outcast`, so
     * only the joining operator keeps access to the room.
     */
    fun removeAllOtherOperators(operatorId: UUID, groupId: UUID, room: String, service: String?) {
        ejabberd.checkOperatorAccess()
        val operators: List<Operator> = operatorGroupRepository.listOperatorsByGroupId(groupId)
        val otherOperators = operators.filter { op -> op.id != operatorId }
        otherOperators
            .forEach { op ->
                ejabberd.setRoomAffiliation(
                    room,
                    service?: mucService,
                    operatorRepository.getUsernameById(op.id),
                    "outcast")
            }
        log.info("Operator $operatorId joined room '$room'; outcast ${otherOperators.size} other operator(s) of group $groupId")
    }

    fun transferToAnotherOperator(room: String, operatorId: UUID, previousOperatorId: UUID, service: String?) {
        ejabberd.checkOperatorAccess()
        setAffiliationAndSubscription(room, operatorId, "admin", service?: mucService)
        ejabberd.setRoomAffiliation(
            room,
            service?: mucService,
            operatorRepository.getUsernameById(previousOperatorId),
            "outcast")
    }

    /**
     * Destroys the temp MUC room on ejabberd.
     */
    fun removeRoom(room: String, service: String?) {
        ejabberd.destroyRoom(room, service?: mucService)
        log.info("Destroyed temporary MUC room '$room' in service '${service?: mucService}'")
    }

}