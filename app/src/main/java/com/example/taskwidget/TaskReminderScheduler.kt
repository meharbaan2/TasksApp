package com.example.taskwidget

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray

object TaskReminderScheduler {
    private const val CHANNEL_ID = "task_due_reminders"
    private const val EXTRA_TASK_ID = "task_id"
    private const val EXTRA_LIST_ID = "list_id"

    fun schedule(context: Context, taskList: TaskList, task: Task) {
        val dueDate = task.dueDate ?: return
        if (task.completed || dueDate <= System.currentTimeMillis()) {
            cancel(context, task.id)
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = reminderPendingIntent(context, task.id, taskList.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueDate, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, dueDate, pendingIntent)
        }
    }

    fun scheduleAll(context: Context, taskLists: List<TaskList>) {
        taskLists.forEach { list ->
            list.tasks.flattenTasks().forEach { task ->
                schedule(context, list, task)
            }
        }
    }

    fun cancel(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(reminderPendingIntent(context, taskId, null))
    }

    fun cancelTree(context: Context, task: Task) {
        cancel(context, task.id)
        task.subtasks.forEach { cancelTree(context, it) }
    }

    fun showNotificationIfStillDue(context: Context, taskId: String, listId: String?) {
        createNotificationChannel(context)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val taskLists = loadTaskLists(context)
        val taskList = taskLists.find { it.id == listId } ?: taskLists.firstOrNull { list ->
            list.tasks.findTask(taskId) != null
        } ?: return
        val task = taskList.tasks.findTask(taskId) ?: return
        val dueDate = task.dueDate ?: return

        if (task.completed || dueDate > System.currentTimeMillis()) {
            return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_LIST_ID, taskList.id)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(task.id),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(task.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(task.id), notification)
    }

    private fun reminderPendingIntent(context: Context, taskId: String, listId: String?): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            if (listId != null) {
                putExtra(EXTRA_LIST_ID, listId)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun loadTaskLists(context: Context): List<TaskList> {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val taskListsJson = prefs.getString(MainActivity.KEY_TASK_LISTS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(taskListsJson)
            (0 until jsonArray.length()).map { index ->
                TaskList.fromJson(jsonArray.getJSONObject(index))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun notificationId(taskId: String): Int = taskId.hashCode()

    fun taskIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_TASK_ID)
    fun listIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_LIST_ID)
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = TaskReminderScheduler.taskIdFrom(intent) ?: return
        TaskReminderScheduler.showNotificationIfStillDue(
            context,
            taskId,
            TaskReminderScheduler.listIdFrom(intent)
        )
    }
}

class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val taskListsJson = prefs.getString(MainActivity.KEY_TASK_LISTS, null) ?: return
        val taskLists = try {
            val jsonArray = JSONArray(taskListsJson)
            (0 until jsonArray.length()).map { index ->
                TaskList.fromJson(jsonArray.getJSONObject(index))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        TaskReminderScheduler.scheduleAll(context, taskLists)
    }
}
