package com.example.taskwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit
import android.os.Build
import android.text.format.DateFormat
import androidx.core.graphics.toColorInt

class TaskWidget : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME = "TaskWidgetPrefs"
        const val KEY_TASK_LISTS = "task_lists"
        const val KEY_CURRENT_LIST = "current_list"
        const val ACTION_TOGGLE_TASK = "TOGGLE_TASK"
        const val ACTION_DELETE_TASK = "DELETE_TASK"
        const val ACTION_ADD_TASK = "ADD_TASK"
        const val ACTION_EDIT_TASK = "EDIT_TASK"
        const val EXTRA_TASK_INDEX = "task_index"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE_TASK -> {
                val index = intent.getIntExtra(EXTRA_TASK_INDEX, -1)
                if (index != -1) {
                    toggleTask(context, index)
                }
            }
            ACTION_DELETE_TASK -> {
                val index = intent.getIntExtra(EXTRA_TASK_INDEX, -1)
                if (index != -1) {
                    deleteTask(context, index)
                }
            }
            ACTION_ADD_TASK -> {
                // Open main app to add tasks
                openAppToAddTask(context)
            }
            ACTION_EDIT_TASK -> {
                // Open main app to edit tasks
                openAppToAddTask(context)
            }
        }

        // Update all widgets
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, TaskWidget::class.java)
        )
        onUpdate(context, appWidgetManager, appWidgetIds)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get CURRENT list ID and name
        val currentListId = prefs.getString(KEY_CURRENT_LIST, "default") ?: "default"
        val allTaskLists = loadAllTaskLists(context)
        val currentList = allTaskLists.find { it.id == currentListId } ?: allTaskLists.firstOrNull()

        val currentListName = currentList?.name ?: "My Tasks"
        val tasks = currentList?.tasks ?: emptyList()

        // Limit tasks to prevent overflow - show max 8 tasks in widget
        val tasksToShow = tasks.take(8)

        // Update time - Force 12-hour format
        val timeFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use 12-hour format from system settings
            DateFormat.getTimeFormat(context)
        } else {
            // Force 12-hour format
            SimpleDateFormat("hh:mm a", Locale.getDefault())
        }
        val time = timeFormat.format(Date())
        views.setTextViewText(R.id.widget_time, time)

        // Update title with current list name and task count
        val completedCount = tasksToShow.count { it.completed }
        views.setTextViewText(R.id.widget_title, "$currentListName ($completedCount/${tasksToShow.size})")

        // Clear existing tasks
        views.removeAllViews(R.id.tasks_container)

        // Add limited number of tasks dynamically
        tasksToShow.forEachIndexed { index, task ->
            val taskView = RemoteViews(context.packageName, R.layout.task_item)

            // Set task text and checkbox
            taskView.setTextViewText(R.id.task_text, task.text)
            val checkbox = if (task.completed) "✓" else "○"
            val checkColor = if (task.completed) "#0066CC" else "#666666"
            taskView.setTextViewText(R.id.task_checkbox, checkbox)
            taskView.setTextColor(R.id.task_checkbox, checkColor.toColorInt())

            // Set alarm indicator - using the separate view now
            if (task.dueDate != null && task.dueDate!! > System.currentTimeMillis()) {
                val timeLeft = task.dueDate!! - System.currentTimeMillis()
                val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
                val hoursLeft = timeLeft / (1000 * 60 * 60)
                val minutesLeft = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)

                val alarmIndicator = when {
                    daysLeft >= 28 -> "${daysLeft / 7}w"
                    daysLeft >= 7 -> "${daysLeft / 7}w"
                    daysLeft >= 1 -> "${daysLeft}d"
                    hoursLeft >= 1 -> "${hoursLeft}h"
                    minutesLeft > 0 -> "${minutesLeft}m"
                    else -> "Now"
                }

                taskView.setTextViewText(R.id.task_alarm, alarmIndicator)
                // Use integer constants for visibility
                taskView.setInt(R.id.task_alarm, "setVisibility", 0) // 0 = VISIBLE
            } else {
                taskView.setInt(R.id.task_alarm, "setVisibility", 8) // 8 = GONE
            }

            // Strike through completed tasks
            if (task.completed) {
                taskView.setInt(R.id.task_text, "setPaintFlags", android.graphics.Paint.STRIKE_THRU_TEXT_FLAG)
                taskView.setTextColor(R.id.task_text, 0xFF666666.toInt())
            } else {
                taskView.setInt(R.id.task_text, "setPaintFlags", 0)
                taskView.setTextColor(R.id.task_text, 0xFF0066CC.toInt())
            }

            // Set click intents
            taskView.setOnClickPendingIntent(R.id.task_checkbox, getTogglePendingIntent(context, index))
            taskView.setOnClickPendingIntent(R.id.task_delete, getDeletePendingIntent(context, index))

            // Add to container
            views.addView(R.id.tasks_container, taskView)
        }

        // Show "more tasks" indicator if there are more tasks than shown
        if (tasks.size > tasksToShow.size) {
            val moreTasksView = RemoteViews(context.packageName, R.layout.task_item)
            val remaining = tasks.size - tasksToShow.size
            moreTasksView.setTextViewText(R.id.task_text, "... and $remaining more tasks")
            moreTasksView.setTextViewText(R.id.task_checkbox, "⋯")
            moreTasksView.setTextColor(R.id.task_checkbox, "#666666".toColorInt())
            moreTasksView.setInt(R.id.task_alarm, "setVisibility", 8) // 8 = GONE - Hide alarm for "more tasks"
            // Remove click actions for the "more tasks" indicator
            moreTasksView.setOnClickPendingIntent(R.id.task_checkbox, null)
            moreTasksView.setOnClickPendingIntent(R.id.task_delete, null)
            views.addView(R.id.tasks_container, moreTasksView)
        }

        // Set add task button click
        views.setOnClickPendingIntent(R.id.add_task_btn, getAddTaskPendingIntent(context))

        // Make ENTIRE WIDGET ROOT clickable to open app
        views.setOnClickPendingIntent(R.id.widget_root, getAddTaskPendingIntent(context))

        // Update widget
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun getTogglePendingIntent(context: Context, index: Int): PendingIntent {
        val intent = Intent(context, TaskWidget::class.java).apply {
            action = ACTION_TOGGLE_TASK
            putExtra(EXTRA_TASK_INDEX, index)
        }
        return PendingIntent.getBroadcast(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getDeletePendingIntent(context: Context, index: Int): PendingIntent {
        val intent = Intent(context, TaskWidget::class.java).apply {
            action = ACTION_DELETE_TASK
            putExtra(EXTRA_TASK_INDEX, index)
        }
        return PendingIntent.getBroadcast(
            context,
            index + 1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getAddTaskPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TaskWidget::class.java).apply {
            action = ACTION_ADD_TASK
        }
        return PendingIntent.getBroadcast(
            context,
            2000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppToAddTask(context: Context) {
        // Open the main app where users can add/edit tasks
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun loadAllTaskLists(context: Context): List<TaskList> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taskListsJson = prefs.getString(KEY_TASK_LISTS, null) ?: return emptyList()

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

    private fun toggleTask(context: Context, index: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentListId = prefs.getString(KEY_CURRENT_LIST, "default") ?: "default"
        val taskListsJson = prefs.getString(KEY_TASK_LISTS, null) ?: return

        try {
            val jsonArray = JSONArray(taskListsJson)
            val updatedJsonArray = JSONArray()
            var foundAndUpdated = false

            for (i in 0 until jsonArray.length()) {
                val taskList = TaskList.fromJson(jsonArray.getJSONObject(i))

                if (taskList.id == currentListId && index in taskList.tasks.indices) {
                    // Toggle the task in the current list
                    val task = taskList.tasks[index]
                    task.completed = !task.completed
                    foundAndUpdated = true
                }

                updatedJsonArray.put(taskList.toJson())
            }

            if (foundAndUpdated) {
                prefs.edit {
                    putString(KEY_TASK_LISTS, updatedJsonArray.toString())
                    apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteTask(context: Context, index: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentListId = prefs.getString(KEY_CURRENT_LIST, "default") ?: "default"
        val taskListsJson = prefs.getString(KEY_TASK_LISTS, null) ?: return

        try {
            val jsonArray = JSONArray(taskListsJson)
            val updatedJsonArray = JSONArray()
            var foundAndDeleted = false

            for (i in 0 until jsonArray.length()) {
                val taskList = TaskList.fromJson(jsonArray.getJSONObject(i))

                if (taskList.id == currentListId && index in taskList.tasks.indices) {
                    // Delete the task from the current list
                    taskList.tasks.removeAt(index)
                    foundAndDeleted = true
                }

                updatedJsonArray.put(taskList.toJson())
            }

            if (foundAndDeleted) {
                prefs.edit {
                    putString(KEY_TASK_LISTS, updatedJsonArray.toString())
                    apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}