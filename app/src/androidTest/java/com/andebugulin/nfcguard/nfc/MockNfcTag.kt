package com.andebugulin.nfcguard.nfc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

/**
 * Fabricates the `android.nfc.Tag` parcelable that the NFC stack would hand
 * [com.andebugulin.nfcguard.ui.MainActivity] on a real tap, so the whole
 * intent → hex-encode → ViewModel → unlock-dialog path can be driven from a
 * test without a physical tag.
 *
 * There is no public API for this: `Tag` has no public constructor, and the
 * only factory is the `@hide` `Tag.createMockTag`. Its signature has changed
 * across platform versions (Android 14 added a trailing `long` cookie), so the
 * parameters are matched by type at runtime rather than pinned to one release.
 *
 * Reaching a hidden method needs the non-SDK-interface restrictions lifted:
 *   adb shell settings put global hidden_api_policy 1
 * `scripts/grant-test-permissions.sh` does this. [isSupported] reports whether
 * it took, so suites can [org.junit.Assume] out instead of failing on a device
 * where the policy cannot be changed.
 */
object MockNfcTag {

    /** `android.nfc.tech.TagTechnology.NFC_A` — the tech every NTAG/Mifare sticker reports. */
    private const val TECH_NFC_A = 1

    /** Hex string (as [MainActivity] derives it) → the id bytes the stack carries. */
    fun idBytes(tagId: String): ByteArray {
        require(tagId.length % 2 == 0) { "tag id must be whole bytes of hex: '$tagId'" }
        return ByteArray(tagId.length / 2) {
            tagId.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** The hex form [MainActivity] computes from a tag's raw id. */
    fun hexOf(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    fun create(tagId: String): Tag = create(idBytes(tagId))

    fun create(id: ByteArray): Tag {
        val factory = requireNotNull(findFactory()) {
            "Tag.createMockTag is unreachable. Lift hidden-API restrictions with: " +
                "adb shell settings put global hidden_api_policy 1"
        }
        val techList = intArrayOf(TECH_NFC_A)
        // One extras Bundle per tech entry; NfcA wants its SAK/ATQA, and an
        // empty Bundle is enough for a tag nothing reads the tech data off.
        val techExtras = arrayOf(Bundle())
        val args = factory.parameterTypes.map { type ->
            when {
                type == ByteArray::class.java -> id
                type == IntArray::class.java -> techList
                type.isArray && type.componentType == Bundle::class.java -> techExtras
                type == Long::class.javaPrimitiveType -> 0L   // API 34+ cookie
                type == Int::class.javaPrimitiveType -> 0      // service handle, if ever exposed
                else -> error("unexpected Tag.createMockTag parameter: $type")
            }
        }
        factory.isAccessible = true
        return factory.invoke(null, *args.toTypedArray()) as Tag
    }

    /**
     * The intent `enableForegroundDispatch` delivers to the foreground
     * activity, explicitly targeted at [target].
     *
     * `SINGLE_TOP` only — deliberately *not* `NEW_TASK`. Started from the
     * running Activity's own context this re-enters that instance through
     * `onNewIntent`, which is what a real tap does. Adding `NEW_TASK` (needed
     * only when starting from a non-Activity context) puts a second
     * MainActivity in its own task, which then outlives the one
     * `ActivityScenario` owns and hangs its teardown in PAUSED.
     */
    fun discoveryIntent(context: Context, target: Class<*>, tagId: String): Intent =
        Intent(NfcAdapter.ACTION_TECH_DISCOVERED).apply {
            component = ComponentName(context, target)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(NfcAdapter.EXTRA_ID, idBytes(tagId))
            putExtra(NfcAdapter.EXTRA_TAG, create(tagId))
        }

    /** True when a mock Tag can actually be built on this device. */
    fun isSupported(): Boolean = runCatching { create("04a1b2c3") }.isSuccess

    /** Diagnostic for failures: which overloads the platform actually exposes. */
    fun describeFactories(): String =
        Tag::class.java.declaredMethods
            .filter { it.name == "createMockTag" }
            .joinToString("; ") { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" }
            .ifEmpty { "no createMockTag overload found on this platform" }

    private fun findFactory() =
        Tag::class.java.declaredMethods
            .filter { it.name == "createMockTag" }
            // Prefer the shortest overload: the extra trailing params are ones
            // the older signature did without, so they are safe defaults.
            .minByOrNull { it.parameterTypes.size }
}
