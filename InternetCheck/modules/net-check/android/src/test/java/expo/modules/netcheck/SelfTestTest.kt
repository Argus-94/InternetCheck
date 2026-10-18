package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Встроенная самопроверка (кнопка в настройках) должна проходить и в обычном JUnit-прогоне. */
class SelfTestTest {
    @Test fun builtInSelfCheckPasses() {
        val rep = SelfTest.check()
        assertTrue("мало проверок: ${rep.total}", rep.total > 100)
        assertEquals(rep.failures.joinToString("\n"), 0, rep.failures.size)
    }
}
