package tvbox.engine.server

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class YingxiaAccountsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `keeps existing state and adopts only complete provider fields`() {
        val file = directory.resolve("cloud-accounts.json").toFile()
        val store = YingxiaAccounts(file)
        fun adopt(text: String) = store.adopt(JsonParser.parseString(text).asJsonObject)
        adopt("""{"quarkCookie":"__pus=sample","wexPreferences":{"one":"first"},"signedIn":true}""")
        assertFalse(YingxiaAccounts.read(file)!!.has("quarkCookie"))
        adopt("""{"quarkCookie":"__pus=sample","quarkServiceTicket":"fixture","wexPreferences":{"two":"second"},"ignored":"test"}""")
        val accounts = YingxiaAccounts.read(file)!!
        assertEquals("__pus=sample", accounts.get("quarkCookie").asString)
        assertEquals("fixture", accounts.get("quarkServiceTicket").asString)
        assertEquals("first", accounts.getAsJsonObject("wexPreferences").get("one").asString)
        assertEquals("second", accounts.getAsJsonObject("wexPreferences").get("two").asString)
        assertFalse(accounts.has("signedIn"))
        assertFalse(accounts.has("ignored"))
    }
}
