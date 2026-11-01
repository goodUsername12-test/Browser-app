package com.fable.browser.engine

import com.fable.browser.data.model.SuperDeviceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperIdentityFactoryTest {
    @Test
    fun everyRangeMakesUsableIdentities() {
        SuperDeviceRange.values().forEach { range ->
            repeat(40) {
                val id = SuperIdentityFactory.create(range, "", true)
                assertTrue(id.ua.startsWith("Mozilla/5.0 ("))
                assertTrue(id.fonts.isNotEmpty())
                assertTrue(id.languages.isNotEmpty())
                assertTrue(id.cores > 0)
                assertTrue(id.screenWidth > 0 && id.screenHeight > 0)
                assertTrue(id.timeZone.isNotEmpty())
                assertTrue(id.canvasNoise in 1..3)
                when (range) {
                    SuperDeviceRange.PHONES -> assertTrue(id.ua.contains("Android") && id.ua.contains("Mobile"))
                    SuperDeviceRange.COMPUTERS -> assertTrue(id.desktop)
                    SuperDeviceRange.IPHONES -> assertTrue(id.ua.contains("iPhone"))
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun pickedDeviceIsUsedButDetailsStayRandom() {
        val label = SuperIdentityFactory.deviceChoices().first { it.group == "Windows" }.label
        val ids = (1..30).map { SuperIdentityFactory.create(SuperDeviceRange.PHONES, label, false) }
        ids.forEach {
            assertTrue(it.label.startsWith(label))
            assertTrue(it.ua.contains("Windows NT"))
            assertEquals("", it.timeZone)
        }
        assertTrue(ids.map { it.seed }.toSet().size > 25)
        assertTrue(ids.map { it.fonts }.toSet().size > 5)
    }

    @Test
    fun avoidsForbiddenUserAgents() {
        val first = SuperIdentityFactory.create(SuperDeviceRange.COMPUTERS, "", true)
        val again = SuperIdentityFactory.create(SuperDeviceRange.ANY, "", true, setOf(first.ua))
        assertFalse(again.ua == first.ua && again.label == first.label)
    }

    @Test
    fun acceptLanguageHasQualityValues() {
        val id = SuperIdentityFactory.create(SuperDeviceRange.ANY, "", true)
        val header = id.acceptLanguage()
        assertTrue(header.startsWith(id.languages.first()))
        if (id.languages.size > 1) assertTrue(header.contains(";q=0."))
    }
}
