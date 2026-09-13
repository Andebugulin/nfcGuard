package com.andebugulin.nfcguard.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one platform-private assumption the NFC end-to-end suite rests on:
 * that a `Tag` can be fabricated on this device. If this fails, every
 * simulated-tap test is meaningless rather than passing vacuously — so it
 * reports the overloads the platform actually exposes.
 */
class MockNfcTagProbeTest {

    @Test fun aTagCanBeFabricatedOnThisDevice() {
        assertTrue(
            "cannot build a mock Tag. Overloads present: ${MockNfcTag.describeFactories()}. " +
                "Lift hidden-API restrictions: adb shell settings put global hidden_api_policy 1",
            MockNfcTag.isSupported()
        )
    }

    @Test fun theFabricatedTagCarriesTheIdWeAskedFor() {
        val tag: Tag = MockNfcTag.create("04a1b2c3")
        assertEquals("04a1b2c3", MockNfcTag.hexOf(tag.id))
    }

    @Test fun theDiscoveryIntentLooksLikeTheRealDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = MockNfcTag.discoveryIntent(
            context, com.andebugulin.nfcguard.ui.MainActivity::class.java, "de'ad'be'ef".replace("'", "")
        )
        assertEquals(NfcAdapter.ACTION_TECH_DISCOVERED, intent.action)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        assertEquals("deadbeef", MockNfcTag.hexOf(tag!!.id))
    }
}
