package com.agani.syncup.reminders

/** Shared keys/constants for the reminder alarm → receiver → notification → deep-link chain. */
object ReminderContract {
    const val ACTION_FIRE = "com.agani.syncup.action.REMINDER_FIRE"

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_BODY = "reminder_body"
    const val EXTRA_RECURRENCE = "reminder_recurrence"

    // These two match the FCM `data` keys, so MainActivity handles reminder taps and
    // push taps with the same code path.
    const val EXTRA_LINK_URL = "link_url"
    const val EXTRA_LINK_TITLE = "link_title"

    const val CHANNEL_ID = "syncup_reminders"
}
