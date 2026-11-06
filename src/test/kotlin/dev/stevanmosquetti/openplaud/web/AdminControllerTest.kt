package dev.stevanmosquetti.openplaud.web

import dev.stevanmosquetti.openplaud.service.AdminService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class AdminControllerTest {

    private val adminService = Mockito.mock(AdminService::class.java)
    private val controller = AdminController(adminService)

    @Test
    fun checkPasswordReturnsOk() {
        val result = controller.checkPassword()

        assertEquals(HttpStatus.OK, result.statusCode)
        Mockito.verify(adminService).checkAdminAccess()
    }
}
