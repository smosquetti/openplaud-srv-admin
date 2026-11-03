package dev.stevanmosquetti.openplaud.web

import dev.stevanmosquetti.openplaud.dto.CreateCustomerRequest
import dev.stevanmosquetti.openplaud.dto.CustomerResponse
import dev.stevanmosquetti.openplaud.service.CustomerService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class CustomerController(
    private val customerService: CustomerService
) {

    @PostMapping("/customers")
    fun createCustomer(@Valid @RequestBody request: CreateCustomerRequest): ResponseEntity<CustomerResponse>  =
        ResponseEntity.status(HttpStatus.CREATED).body(customerService.register(request.user, request.name, request.password))

    @DeleteMapping("/customers/{customerId}")
    fun deleteOperator(@PathVariable customerId: UUID): ResponseEntity<Unit> {
        customerService.removeCustomer(customerId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}