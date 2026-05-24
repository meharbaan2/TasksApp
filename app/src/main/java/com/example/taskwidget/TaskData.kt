package com.example.taskwidget

import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class TaskList(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var color: Int = 0xFF3399FF.toInt(),
    val created: Long = System.currentTimeMillis(),
    var tasks: MutableList<Task> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val tasksArray = JSONArray()
        tasks.forEach { task ->
            tasksArray.put(task.toJson())
        }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("color", color)
            put("created", created)
            put("tasks", tasksArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TaskList {
            val taskList = TaskList(
                id = json.getString("id"),
                name = json.getString("name"),
                color = json.optInt("color", 0xFF3399FF.toInt()),
                created = json.optLong("created", System.currentTimeMillis())
            )
            val tasksArray = json.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until tasksArray.length()) {
                taskList.tasks.add(Task.fromJson(tasksArray.getJSONObject(i)))
            }
            return taskList
        }
    }
}

data class Task(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var completed: Boolean = false,
    var dueDate: Long? = null,
    var reminder: Long? = null,
    var parentTaskId: String? = null, // For subtasks
    var subtasks: MutableList<Task> = mutableListOf(),
    var created: Long = System.currentTimeMillis(),
    var listId: String = "default"
) {
    fun toJson(): JSONObject {
        val subtasksArray = JSONArray()
        subtasks.forEach { subtask ->
            subtasksArray.put(subtask.toJson())
        }
        return JSONObject().apply {
            put("id", id)
            put("text", text)
            put("completed", completed)
            put("dueDate", dueDate ?: JSONObject.NULL)
            put("reminder", reminder ?: JSONObject.NULL)
            put("parentTaskId", parentTaskId ?: JSONObject.NULL)
            put("subtasks", subtasksArray)
            put("created", created)
            put("listId", listId)
        }
    }

    fun hasSubtasks(): Boolean = subtasks.isNotEmpty()
    fun completedSubtasks(): Int = subtasks.count { it.completed }
    fun totalSubtasks(): Int = subtasks.size

    companion object {
        fun fromJson(json: JSONObject): Task {
            val task = Task(
                id = json.getString("id"),
                text = json.getString("text"),
                completed = json.getBoolean("completed"),
                dueDate = if (json.has("dueDate") && !json.isNull("dueDate")) json.getLong("dueDate") else null,
                reminder = if (json.has("reminder") && !json.isNull("reminder")) json.getLong("reminder") else null,
                parentTaskId = if (json.has("parentTaskId") && !json.isNull("parentTaskId")) json.getString("parentTaskId") else null,
                created = json.optLong("created", System.currentTimeMillis()),
                listId = json.optString("listId", "default")
            )
            val subtasksArray = json.optJSONArray("subtasks") ?: JSONArray()
            for (i in 0 until subtasksArray.length()) {
                task.subtasks.add(fromJson(subtasksArray.getJSONObject(i)))
            }
            return task
        }
    }
}

fun Task.findTask(taskId: String): Task? {
    if (id == taskId) return this
    subtasks.forEach { subtask ->
        subtask.findTask(taskId)?.let { return it }
    }
    return null
}

fun List<Task>.findTask(taskId: String): Task? {
    forEach { task ->
        task.findTask(taskId)?.let { return it }
    }
    return null
}

fun MutableList<Task>.removeTaskById(taskId: String): Boolean {
    val iterator = iterator()
    while (iterator.hasNext()) {
        val task = iterator.next()
        if (task.id == taskId) {
            iterator.remove()
            return true
        }
        if (task.subtasks.removeTaskById(taskId)) {
            return true
        }
    }
    return false
}

fun List<Task>.flattenTasks(): List<Task> {
    val result = mutableListOf<Task>()

    fun collect(tasks: List<Task>) {
        tasks.forEach { task ->
            result.add(task)
            collect(task.subtasks)
        }
    }

    collect(this)
    return result
}

fun Task.isOverdue(now: Long = System.currentTimeMillis()): Boolean {
    val due = dueDate ?: return false
    return !completed && due < now
}

fun formatDueIndicator(dueDate: Long, now: Long = System.currentTimeMillis()): String {
    val diff = dueDate - now
    if (diff <= 0L) return "Overdue"

    val minutesLeft = diff / (1000 * 60)
    val hoursLeft = diff / (1000 * 60 * 60)
    val daysLeft = diff / (1000 * 60 * 60 * 24)

    return when {
        daysLeft >= 7 -> "${daysLeft / 7}w"
        daysLeft >= 1 -> "${daysLeft}d"
        hoursLeft >= 1 -> "${hoursLeft}h"
        minutesLeft >= 1 -> "${minutesLeft}m"
        else -> "Now"
    }
}
