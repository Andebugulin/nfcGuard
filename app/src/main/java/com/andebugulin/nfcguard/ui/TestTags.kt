package com.andebugulin.nfcguard.ui

/**
 * Stable handles for the nodes tests drive.
 *
 * Selecting by visible text couples every test to presentation, and this UI is
 * unusually hostile to that:
 *
 *  - Names, permission titles and status messages all render `.uppercase()`,
 *    so a test must restate the transform and breaks if it changes.
 *  - Several entry points have two labels for the same action ("CREATE MODE" in
 *    an empty state, "+ NEW MODE" once a list exists), which forces a test to
 *    branch on which one is showing.
 *  - A dialog's button label usually also exists on the screen behind it — the
 *    mode card's DELETE under the delete dialog's DELETE, the schedule editor's
 *    CANCEL under the clock picker's CANCEL. Picking the wrong one does not
 *    fail: it dismisses the wrong thing and the test fails somewhere else.
 *
 * Tags fix identity, not wording. They are deliberately *not* applied to every
 * node: text is still the right selector for content a test is genuinely
 * asserting about (headings, explanatory copy, validation messages). These
 * cover identity and interaction targets only.
 *
 * Constants are shared with both test source sets, so a rename fails to compile
 * instead of failing at runtime.
 */
object TestTags {

    object Home {
        const val NAV_MODES = "home:nav:modes"
        const val NAV_SCHEDULES = "home:nav:schedules"
        const val NAV_NFC_TAGS = "home:nav:nfcTags"
        const val EMERGENCY_RESET = "home:emergencyReset"
        const val SETTINGS = "home:settings"
    }

    object Modes {
        /** One handle for both the empty-state and in-list add buttons. */
        const val ADD = "modes:add"
        fun card(modeId: String) = "modes:card:$modeId"
        fun activate(modeId: String) = "modes:activate:$modeId"
        fun edit(modeId: String) = "modes:edit:$modeId"
        fun delete(modeId: String) = "modes:delete:$modeId"

        const val NAME_INPUT = "modes:nameDialog:input"
        const val NAME_CONFIRM = "modes:nameDialog:confirm"
        const val DELETE_CONFIRM = "modes:deleteDialog:confirm"
        const val DELETE_CANCEL = "modes:deleteDialog:cancel"
        const val ACTIVATE_CONFIRM = "modes:activateDialog:confirm"
    }

    object Unlock {
        const val CONFIRM = "unlock:confirm"
        const val CANCEL = "unlock:cancel"
        fun modeRow(modeId: String) = "unlock:mode:$modeId"
        const val PERMANENT_OPTION = "unlock:permanent"
        const val HOURS = "unlock:hours"
        const val MINUTES = "unlock:minutes"
    }

    object NfcTags {
        /** One handle for both the empty-state and in-list register buttons. */
        const val REGISTER = "nfcTags:register"
        fun card(tagId: String) = "nfcTags:card:$tagId"
        fun rename(tagId: String) = "nfcTags:rename:$tagId"
        fun delete(tagId: String) = "nfcTags:delete:$tagId"
        fun linkModes(tagId: String) = "nfcTags:linkModes:$tagId"

        /** Mode-picker dialog reached from a tag card. */
        fun linkOption(modeId: String) = "nfcTags:linkDialog:mode:$modeId"
        const val LINK_SAVE = "nfcTags:linkDialog:save"
        const val LINK_CANCEL = "nfcTags:linkDialog:cancel"

        const val REGISTER_NAME_INPUT = "nfcTags:registerDialog:input"
        const val REGISTER_CONFIRM = "nfcTags:registerDialog:confirm"
        const val REGISTER_CANCEL = "nfcTags:registerDialog:cancel"
        const val RENAME_INPUT = "nfcTags:renameDialog:input"
        const val RENAME_SAVE = "nfcTags:renameDialog:save"
        const val DELETE_CONFIRM = "nfcTags:deleteDialog:confirm"
        const val DELETE_CANCEL = "nfcTags:deleteDialog:cancel"
    }

    object ModeEditor {
        const val SEARCH = "modeEditor:search"
        const val SAVE = "modeEditor:save"
        fun appRow(packageName: String) = "modeEditor:app:$packageName"
        fun tagRow(tagId: String) = "modeEditor:tag:$tagId"
        fun tagLimit(tagId: String) = "modeEditor:tagLimit:$tagId"

        const val LIMIT_PERMANENT = "modeEditor:limitDialog:permanent"
        const val LIMIT_TIMED = "modeEditor:limitDialog:timed"
        const val LIMIT_HOURS = "modeEditor:limitDialog:hours"
        const val LIMIT_MINUTES = "modeEditor:limitDialog:minutes"
        /** Register a brand-new tag without leaving the editor. */
        const val REGISTER_TAG = "modeEditor:registerTag"

        const val LIMIT_APPLY = "modeEditor:limitDialog:apply"
        const val LIMIT_CANCEL = "modeEditor:limitDialog:cancel"
        const val NO_PERMANENT_SAVE_ANYWAY = "modeEditor:noPermanentDialog:confirm"
        const val NO_PERMANENT_CANCEL = "modeEditor:noPermanentDialog:cancel"
    }

    object Schedules {
        /** One handle for both the empty-state and in-list create buttons. */
        const val ADD = "schedules:add"
        fun card(scheduleId: String) = "schedules:card:$scheduleId"
        fun edit(scheduleId: String) = "schedules:edit:$scheduleId"
        fun delete(scheduleId: String) = "schedules:delete:$scheduleId"
        fun linkTags(scheduleId: String) = "schedules:linkTags:$scheduleId"

        /** Tag-picker dialog reached from a schedule card. */
        fun tagOption(tagId: String) = "schedules:tagDialog:tag:$tagId"
        const val TAGS_SAVE = "schedules:tagDialog:save"
        const val TAGS_CANCEL = "schedules:tagDialog:cancel"

        const val EDITOR_NAME = "schedules:editor:name"
        const val EDITOR_CONFIRM = "schedules:editor:confirm"
        const val EDITOR_CANCEL = "schedules:editor:cancel"
        const val EDITOR_CUSTOM_END_TIMES = "schedules:editor:customEndTimes"
        fun day(day: Int) = "schedules:editor:day:$day"
        fun startTime(day: Int) = "schedules:editor:startTime:$day"
        fun endTime(day: Int) = "schedules:editor:endTime:$day"
        fun linkedMode(modeId: String) = "schedules:editor:mode:$modeId"

        const val DELETE_CONFIRM = "schedules:deleteDialog:confirm"
        const val DELETE_CANCEL = "schedules:deleteDialog:cancel"
    }

    object TimePicker {
        const val SET = "timePicker:set"
        const val CANCEL = "timePicker:cancel"
        const val HOUR_FIELD = "timePicker:hourField"
        const val MINUTE_FIELD = "timePicker:minuteField"
        fun mark(value: Int) = "timePicker:mark:$value"
    }

    object Settings {
        const val STATUS_MESSAGE = "settings:statusMessage"
        const val SAFE_REGIME_TOGGLE = "settings:safeRegimeToggle"
        const val CHALLENGE_DURATION_ROW = "settings:challengeDurationRow"
        const val EXPORT = "settings:export"
        const val IMPORT = "settings:import"
        const val DONE = "settings:done"

        const val EXPORT_JSON = "settings:exportFormat:json"
        const val EXPORT_YAML = "settings:exportFormat:yaml"
        const val IMPORT_MERGE = "settings:importConfirm:merge"
        const val IMPORT_REPLACE = "settings:importConfirm:replace"

        const val DURATION_MINUTES = "settings:duration:minutes"
        const val DURATION_SECONDS = "settings:duration:seconds"
        const val DURATION_APPLY = "settings:duration:apply"

        fun permissionRow(name: String) = "settings:permission:$name"
    }

    /**
     * Lost-tag recovery. Warning and tag selection used to be two dialogs with
     * a challenge wedged between them; they are one screen now, so there is a
     * single confirm and a single cancel.
     */
    object Emergency {
        const val TAG_SELECTION_CONFIRM = "emergency:tagSelection:confirm"
        const val TAG_SELECTION_CANCEL = "emergency:tagSelection:cancel"
        fun lostTag(tagId: String) = "emergency:tagSelection:tag:$tagId"
    }

    object Challenge {
        const val ROOT = "challenge:root"
        const val GIVE_UP = "challenge:giveUp"
        const val PRESS = "challenge:press"
        const val FAILED = "challenge:failed"
    }
}
