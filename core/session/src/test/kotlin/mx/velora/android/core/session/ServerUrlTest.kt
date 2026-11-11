package mx.velora.android.core.session

import mx.velora.android.core.session.ServerUrl.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {

    @Test
    fun normalizesWhatTheUserTypes() {
        assertEquals(Result.Valid("https://velora.example"), ServerUrl.parse("  https://Velora.Example/ ", allowCleartext = false))
        assertEquals(Result.Valid("http://10.0.2.2:8090"), ServerUrl.parse("http://10.0.2.2:8090", allowCleartext = true))
        assertEquals(Result.Valid("http://192.168.1.20:8090"), ServerUrl.parse("HTTP://192.168.1.20:8090/", allowCleartext = true))
    }

    @Test
    fun rejectsIncompleteOrUnexpectedAddresses() {
        listOf("", "velora.example", "10.0.2.2:8090", "ftp://velora.example", "https://", "https://velora.example/api/v1",
            "https://usuario@velora.example", "https://velora.example?x=1", "https://velo ra.example")
            .forEach { assertEquals(it, Result.Invalid, ServerUrl.parse(it, allowCleartext = true)) }
    }

    @Test
    fun cleartextOnlyWhereAllowed() {
        assertEquals(Result.CleartextNotAllowed, ServerUrl.parse("http://192.168.1.20:8090", allowCleartext = false))
    }
}
