package dev.stevanmosquetti.openplaud.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateCustomerRequest(
    @NotNull
    val user: UUID,

    @NotBlank
    val name: String,

    /**
     * XMPP login password for the new user. Optional — when omitted, the server
     * generates one and returns it (once) in the response.
     */
    val password: String? = null,)
