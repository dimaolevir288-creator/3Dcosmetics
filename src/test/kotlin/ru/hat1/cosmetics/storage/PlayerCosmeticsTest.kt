package ru.hat1.cosmetics.storage

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerCosmeticsTest {

    private fun state() = PlayerCosmetics(UUID.randomUUID())

    @Test
    fun `owned cosmetics are tracked case-insensitively`() {
        val state = state()
        assertTrue(state.addOwned("Crown"))
        assertTrue(state.owns("crown"))
        assertTrue(state.owns("CROWN"))
    }

    @Test
    fun `adding the same cosmetic twice is a no-op`() {
        val state = state()
        assertTrue(state.addOwned("crown"))
        assertFalse(state.addOwned("crown"))
        assertEquals(1, state.owned.size)
    }

    @Test
    fun `equipping a second cosmetic in the same category swaps the previous one`() {
        val state = state()
        state.addOwned("top_hat")
        state.addOwned("crown")

        assertNull(state.equip("hats", "top_hat"))
        assertEquals("top_hat", state.equippedIn("hats"))

        val previous = state.equip("hats", "crown")
        assertEquals("top_hat", previous)
        assertEquals("crown", state.equippedIn("hats"))
        assertEquals(1, state.equipped.size)
    }

    @Test
    fun `different categories stack`() {
        val state = state()
        state.equip("hats", "crown")
        state.equip("capes", "red_cape")
        state.equip("wings", "angel_wings")

        assertEquals(3, state.equipped.size)
        assertTrue(state.isEquipped("crown"))
        assertTrue(state.isEquipped("red_cape"))
        assertTrue(state.isEquipped("angel_wings"))
    }

    @Test
    fun `unequip removes only the matching cosmetic`() {
        val state = state()
        state.equip("hats", "crown")
        state.equip("capes", "red_cape")

        assertTrue(state.unequip("crown"))
        assertNull(state.equippedIn("hats"))
        assertEquals("red_cape", state.equippedIn("capes"))
    }

    @Test
    fun `unequipping a cosmetic that is not worn returns false`() {
        val state = state()
        state.equip("hats", "crown")
        assertFalse(state.unequip("red_cape"))
    }

    @Test
    fun `removing an owned cosmetic also unequips it`() {
        val state = state()
        state.addOwned("crown")
        state.equip("hats", "crown")

        assertTrue(state.removeOwned("crown"))
        assertFalse(state.owns("crown"))
        assertNull(state.equippedIn("hats"))
    }
}
