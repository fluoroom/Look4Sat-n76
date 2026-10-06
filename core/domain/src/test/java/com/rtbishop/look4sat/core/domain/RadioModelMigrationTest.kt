package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioModelMigrationTest {

    @Test
    fun `both N76 labels are recognised as the handheld`() {
        assertEquals("HYS N76", RadioControlSettings.LEGACY_MODEL_N76)
        assertEquals("VGC N76", RadioControlSettings.MODEL_N76)
        assertTrue(RadioControlSettings.isN76Model(RadioControlSettings.LEGACY_MODEL_N76))
        assertTrue(RadioControlSettings.isN76Model(RadioControlSettings.MODEL_N76))
        assertFalse(RadioControlSettings.isN76Model(RadioControlSettings.MODEL_YAESU_FT817))
        assertFalse(RadioControlSettings.isN76Model(null))
    }

    @Test
    fun `a stored N76 model falls back to a CAT radio, the handheld having moved to its own settings`() {
        assertEquals(
            RadioControlSettings.MODEL_YAESU_FT817,
            RadioControlSettings.normalizeModel(RadioControlSettings.LEGACY_MODEL_N76)
        )
        assertEquals(
            RadioControlSettings.MODEL_YAESU_FT817,
            RadioControlSettings.normalizeModel(RadioControlSettings.MODEL_N76)
        )
    }

    @Test
    fun `an unset model still defaults to the Yaesu`() {
        assertEquals(RadioControlSettings.MODEL_YAESU_FT817, RadioControlSettings.normalizeModel(null))
        assertEquals(RadioControlSettings.MODEL_YAESU_FT817, RadioControlSettings.normalizeModel(""))
        assertEquals(RadioControlSettings.MODEL_YAESU_FT817, RadioControlSettings.normalizeModel("  "))
    }

    @Test
    fun `every other stored model passes through untouched`() {
        for (model in RadioControlSettings.SUPPORTED_RADIOS) {
            assertEquals(model, RadioControlSettings.normalizeModel(model))
        }
        // An unknown string is left alone rather than guessed at.
        assertEquals("Kenwood TH-D74", RadioControlSettings.normalizeModel("Kenwood TH-D74"))
    }

    @Test
    fun `the CAT picker offers neither N76 label`() {
        assertTrue(RadioControlSettings.LEGACY_MODEL_N76 !in RadioControlSettings.SUPPORTED_RADIOS)
        assertTrue(RadioControlSettings.MODEL_N76 !in RadioControlSettings.SUPPORTED_RADIOS)
    }
}
