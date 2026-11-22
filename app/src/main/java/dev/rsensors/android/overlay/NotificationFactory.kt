package dev.rsensors.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.rsensors.android.R
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.Strip
import dev.rsensors.android.core.iconSpec
import dev.rsensors.android.ui.MainActivity
import dev.rsensors.android.ui.ShizukuHelpActivity
import dev.rsensors.android.ui.badgeBitmap

/**
 * Builds and owns the overlay's single notification (ANDROID.md §3, §8 T6).
 *
 * The notification is the foreground-service payment: silent, low importance,
 * ongoing, with the tray badge (the CPU number on a rounded square) as its
 * large icon. Its text mirrors the collapsed pill, so the shade and the pill
 * never disagree.
 *
 * The channel is created before the first `startForeground`, because on API 26+
 * a notification with an unknown channel is rejected.
 *
 * Side effects: [createChannel] touches the NotificationManager; [build] and
 * [placeholder] rasterise the badge bitmap on the calling thread.
 */
object NotificationFactory {
  /** Channel id kept stable: changing it would reset the user's channel settings. */
  const val CHANNEL_ID = "overlay"

  /** Notification id of the foreground-service notification. */
  const val NOTIFICATION_ID = 1

  private const val CHANNEL_NAME = "Overlay"
  private const val CHANNEL_DESCRIPTION = "Keeps the temperature overlay on screen"
  private const val TITLE = "Rsensors"
  private const val BADGE_DP = 64f

  /** Notification large icon edge length in pixels for the current display. */
  fun badgeSizePx(context: Context): Int = OverlayParams.dpToPx(context, BADGE_DP)

  /** Creates the silent, low-importance channel; idempotent. */
  fun createChannel(context: Context) {
    val channel = NotificationChannel(
      CHANNEL_ID,
      CHANNEL_NAME,
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = CHANNEL_DESCRIPTION
      setShowBadge(false)
      setSound(null, null)
      enableVibration(false)
      enableLights(false)
    }
    context.getSystemService(NotificationManager::class.java)
      ?.createNotificationChannel(channel)
  }

  /**
   * Notification shown between `startForeground` and the first poll.
   *
   * It carries the same identity as the live one, so the first poll replaces it
   * in place instead of stacking a second entry.
   */
  fun placeholder(context: Context): Notification =
    base(context)
      .setContentText("Starting\u2026")
      .build()

  /**
   * Live notification for a reading.
   *
   * @param context any context; supplies resources and the notification manager
   * @param strip collapsed-pill segments; their texts joined with spaces is the
   *   notification text
   * @param reading latest sample, or null before the first poll; only the badge
   *   uses it (CPU digits or "?")
   * @param now epoch millis for the badge's stale check
   * @param shizukuRunning when false, adds a "Shizuku help" action that opens the
   *   setup guide; the running state keeps the single Stop action (T15g)
   */
  fun build(
    context: Context,
    strip: Strip,
    reading: Reading?,
    now: Long,
    shizukuRunning: Boolean,
  ): Notification {
    val text = strip.segments.joinToString(" ") { it.text }
    val badge = badgeBitmap(badgeSizePx(context), iconSpec(reading, now))
    val builder = base(context)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setLargeIcon(badge)
    if (!shizukuRunning) {
      builder.addAction(
        NotificationCompat.Action.Builder(0, "Shizuku help", shizukuHelpIntent(context)).build(),
      )
    }
    return builder.build()
  }

  private fun base(context: Context): NotificationCompat.Builder =
    NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_rsensors)
      .setContentTitle(TITLE)
      .setContentIntent(openAppIntent(context))
      .addAction(
        NotificationCompat.Action.Builder(0, "Stop", stopIntent(context)).build(),
      )
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setPriority(NotificationCompat.PRIORITY_LOW)

  private fun openAppIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun stopIntent(context: Context): PendingIntent =
    PendingIntent.getService(
      context,
      1,
      Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  /**
   * Opens [ShizukuHelpActivity] from the degraded notification (T15g).
   *
   * Request code 2 is distinct from the content intent's 0 and the Stop action's
   * 1, so the three pending intents never overwrite each other.
   */
  private fun shizukuHelpIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      2,
      Intent(context, ShizukuHelpActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
