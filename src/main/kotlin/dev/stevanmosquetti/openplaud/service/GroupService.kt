package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import dev.stevanmosquetti.openplaud.dto.GroupResponse
import dev.stevanmosquetti.openplaud.dto.OperatorResponse
import dev.stevanmosquetti.openplaud.entity.Group
import dev.stevanmosquetti.openplaud.entity.Operator
import dev.stevanmosquetti.openplaud.entity.OperatorGroup
import dev.stevanmosquetti.openplaud.entity.OperatorGroupId
import dev.stevanmosquetti.openplaud.repository.GroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorGroupRepository
import dev.stevanmosquetti.openplaud.repository.OperatorRepository
import dev.stevanmosquetti.openplaud.slugify.Slugifier
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Creates groups (persistent MUC rooms) and controls operator membership
 * (MUC affiliation + `operator_groups` rows).
 *
 * Each operation pairs the ejabberd call with its database write; on a database failure
 * after a successful ejabberd call, the remote state is compensated back so retries work.
 */
@Service
class GroupService(
    private val groupRepository: GroupRepository,
    private val operatorRepository: OperatorRepository,
    private val operatorGroupRepository: OperatorGroupRepository,
    private val ejabberd: EjabberdClient,
) {

    private val log = LoggerFactory.getLogger(GroupService::class.java)

    private companion object {
        const val GROUPS_CACHE = "groups"
    }

    /**
     * Served from the `groups` Redis cache; evicted by [create] and [removeRoom].
     */
    @Cacheable(GROUPS_CACHE)
    fun getListOfGroups(): List<GroupResponse> {
        return groupRepository.findAll()
            .filterIsInstance<Group>()
            .map {
                g -> GroupResponse(g.id, g.name, g.node, g.service)
            }
            .toList()
    }

    /**
     * Creates a group: creates the persistent MUC room and inserts the `groups` row.
     * Evicts the groups cache.
     */
    @CacheEvict(GROUPS_CACHE, allEntries = true)
    fun create(name: String, service: String): GroupResponse {
        ejabberd.checkAdminAccess()
        val room = slugify(name)
        requireRoomFree(room)
        ejabberd.createPersistentRoom(room, service)
        val groupEntity = Group(name = name, node = room, service = service)
        val group = try {
            groupRepository.save(groupEntity)
        } catch (dbError: RuntimeException) {
            cleanupAfterDbFailure(room, service, dbError)
            throw dbError
        }
        log.info("Created group '$name' with MUC room '$room' on service '$service'")
        return GroupResponse(id = group.id, name = group.name, room = room, service = service)
    }

    fun getListOfOperatorsForTheGroup(groupId: UUID): List<OperatorResponse> {
        ejabberd.checkOperatorAccess()
        return operatorGroupRepository.listOperatorsByGroupId(groupId)
            .map {
                o -> OperatorResponse(o.id, o.name, o.email, o.username)
            }
            .toList()
    }

    /**
     * Adds an operator to a group: sets the MUC affiliation to `member` and inserts
     * the membership row.
     */
    fun addOperatorToGroup(groupId: UUID, operatorId: UUID) {
        ejabberd.checkAdminAccess()
        val group = requireGroup(groupId)
        val operator = requireOperator(operatorId)
        val key = OperatorGroupId(operatorId, groupId)
        if (operatorGroupRepository.existsByOperatorIdAndGroupId(operatorId, groupId)) {
            throw ConflictException("Operator $operatorId is already a member of group $groupId")
        }
        val room = slugify(group.name)
        val user = slugify(operator.name)
        ejabberd.setRoomAffiliation(room, group.service, user, "admin")
        try {
            operatorGroupRepository.save(OperatorGroup(key))
        } catch (dbError: RuntimeException) {
            log.error("Could not persist membership of operator $operatorId in group $groupId; removing the affiliation", dbError)
            try {
                ejabberd.setRoomAffiliation(room, group.service, user, "none")
            } catch (e: Exception) {
                log.error("Compensating affiliation removal for $user in $room also failed", e)
            }
            throw dbError
        }
        log.info("Added operator $operatorId to group $groupId")
    }

    /**
     * Removes an operator from a group: sets the MUC affiliation to `none` and
     * deletes the membership row.
     */
    fun removeOperatorFromGroup(groupId: UUID, operatorId: UUID) {
        ejabberd.checkAdminAccess()
        val key = OperatorGroupId(operatorId, groupId)
        operatorGroupRepository.findById(key)
            .orElseThrow { NotFoundException("Operator $operatorId is not a member of group $groupId") }
        val group = requireGroup(groupId)
        val operator = requireOperator(operatorId)
        val room = slugify(group.name)
        val user = slugify(operator.name)
        ejabberd.setRoomAffiliation(room, group.service, user, "none")
        operatorGroupRepository.deleteById(key)
        log.info("Removed operator $operatorId from group $groupId")
    }

    /**
     * Removes a group: deletes membership rows and the `groups` row, and destroys
     * the MUC room. Evicts the groups cache.
     */
    @CacheEvict(GROUPS_CACHE, allEntries = true)
    fun removeRoom(groupId: UUID) {
        ejabberd.checkAdminAccess()
        val group = requireGroup(groupId)
        operatorGroupRepository.deleteByGroup(group.id)
        groupRepository.deleteById(group.id)
        ejabberd.destroyRoom(group.node, group.service)
        log.info("Removed group $groupId and destroyed MUC room '${group.node}' on service '${group.service}'")
    }

    /**
     * Returns the group or throws NotFoundException.
     */
    private fun requireGroup(groupId: UUID): Group =
        groupRepository.findById(groupId)
            .orElseThrow { NotFoundException("Group $groupId not found") }

    /**
     * Returns the operator or throws NotFoundException.
     */
    private fun requireOperator(operatorId: UUID): Operator =
        operatorRepository.findById(operatorId)
            .orElseThrow { NotFoundException("Operator $operatorId not found") }

    /**
     * Throws ConflictException when another group already uses the MUC room.
     */
    private fun requireRoomFree(room: String) {
        val taken = groupRepository.findAll().any { Slugifier.slugify(it.name) == room }
        if (taken) {
            throw ConflictException("A group already exists for the MUC room '$room'")
        }
    }

    /**
     * Destroys the freshly created room to compensate a failed database write.
     */
    private fun cleanupAfterDbFailure(room: String, service: String, cause: RuntimeException) {
        log.error("Could not persist group for room '$room'; destroying the room", cause)
        try {
            ejabberd.destroyRoom(room, service)
        } catch (e: Exception) {
            log.error("Compensating room destruction for '$room' also failed; the room is orphaned on the XMPP server", e)
        }
    }

    /**
     * Slugifies the group name; an invalid localpart becomes
     * InvalidSlugException.
     */
    private fun slugify(name: String): String = try {
        Slugifier.slugify(name)
    } catch (e: IllegalArgumentException) {
        throw InvalidSlugException(e.message ?: "The group name does not produce a valid MUC room name")
    }
}
