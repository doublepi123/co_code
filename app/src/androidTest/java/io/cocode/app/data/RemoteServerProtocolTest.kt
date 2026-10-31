package io.cocode.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteServerProtocolTest {
    private fun withStore(test: (android.content.SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("protocol-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        try {
            test(prefs)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun oldEntriesRemainT3() = withStore { prefs ->
        prefs.edit().putString("servers", """[{"id":"old","displayName":"Old","baseUrl":"https://example.com"}]""").commit()
        assertEquals(ServerKind.T3, EncryptedRemoteServerStore(prefs).servers.value.single().kind)
    }

    @Test
    fun openChamberProtocolSurvivesReload() = withStore { prefs ->
        val server = ServerTarget.Remote("oc", "OpenChamber", "https://example.com", null, kind = ServerKind.OpenChamber)
        EncryptedRemoteServerStore(prefs).upsert(server)
        assertEquals(server, EncryptedRemoteServerStore(prefs).servers.value.single())
    }

    @Test
    fun unknownProtocolIsNotGuessedFromItsAddress() = withStore { prefs ->
        prefs.edit().putString("servers", """[{"id":"future","displayName":"Future","baseUrl":"https://example.com","kind":"unknown"}]""").commit()
        assertEquals(emptyList<ServerTarget.Remote>(), EncryptedRemoteServerStore(prefs).servers.value)
    }
}
