package dev.stevanmosquetti.openplaud.service

import dev.stevanmosquetti.openplaud.client.EjabberdClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class AdminServiceTest {

    private val ejabberd = Mockito.mock(EjabberdClient::class.java)
    private val service = AdminService(ejabberd)

    @Test
    fun checkAdminAccessDelegatesToEjabberd() {
        service.checkAdminAccess()

        Mockito.verify(ejabberd).checkAdminAccess()
    }
}
