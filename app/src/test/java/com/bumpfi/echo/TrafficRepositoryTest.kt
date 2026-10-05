package com.bumpfi.echo

import com.bumpfi.echo.data.TrafficRepository
import com.bumpfi.echo.data.model.*
import org.junit.Assert.*
import org.junit.Test

class TrafficRepositoryTest {

    private fun conn(dest: String, sent: Long, encrypted: Boolean? = null) = ConnectionInfo(
        timestamp = 0, destinationAddress = dest, destinationPort = 0,
        protocol = Protocol.OTHER, bytesSent = sent, bytesReceived = 0, isEncrypted = encrypted
    )

    @Test
    fun publishBatchesAndAbsorbsUnattributedBytes() {
        val repo = TrafficRepository()
        repo.recordConnection("pkg", "App", null, 1, conn(UNATTRIBUTED, 100))

        // Nothing reaches observers until publish()
        assertTrue(repo.appTrafficList.value.isEmpty())

        repo.recordConnection("pkg", "App", null, 1, conn("1.2.3.4", 50))
        repo.publish()

        val app = repo.appTrafficList.value.single()
        val c = app.connections.single()
        assertEquals("1.2.3.4", c.destinationAddress)
        assertEquals(150, c.bytesSent)
        assertNull(c.isEncrypted)
        assertEquals(1, repo.connectionCount.value)
        assertEquals(150, repo.totalBytesSent.value)

        // Published snapshot is a copy, unaffected by later writes
        repo.recordConnection("pkg", "App", null, 1, conn("5.6.7.8", 1, encrypted = true))
        assertEquals(1, app.connections.size)
    }
}
