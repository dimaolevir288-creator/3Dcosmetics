package ru.hat1.cosmetics.config

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageServiceTest {

    @Test
    fun `ampersand codes become section signs`() {
        assertEquals("§aПривет", MessageService.color("&aПривет"))
        assertEquals("§c§lОшибка", MessageService.color("&c&lОшибка"))
    }

    @Test
    fun `hex codes are converted to the x-terminated form`() {
        val result = MessageService.color("&#FF0000red")
        assertEquals("§x§F§F§0§0§0§0red", result)
    }

    @Test
    fun `plain text is untouched`() {
        assertEquals("просто текст", MessageService.color("просто текст"))
    }

    @Test
    fun `hex and ampersand codes can be mixed`() {
        val result = MessageService.color("&l&#00FF00готово")
        assertEquals("§l§x§0§0§F§F§0§0готово", result)
    }
}
