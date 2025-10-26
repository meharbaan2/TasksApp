package com.example.taskwidget

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar
import android.text.format.DateFormat
import android.content.SharedPreferences
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity() {

    private lateinit var taskContainer: LinearLayout
    private lateinit var taskInput: EditText
    private lateinit var addButton: TextView
    private lateinit var appTime: TextView
    private lateinit var listSpinner: Spinner
    private lateinit var alarmContainer: LinearLayout
    private lateinit var tasksCount: TextView

    private var currentListId = "default"
    private val taskLists = mutableListOf<TaskList>()

    companion object {
        const val PREFS_NAME = "TaskWidgetPrefs"
        const val KEY_TASK_LISTS = "task_lists"
        const val KEY_CURRENT_LIST = "current_list"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListSpinner()
        setupDragAndDrop()

        // Register shared preferences listener
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)

        loadTaskLists()
        updateTime()

        updateWidget()
    }

    private fun initializeViews() {
        taskContainer = findViewById(R.id.tasks_container)
        taskInput = findViewById(R.id.task_input)
        addButton = findViewById(R.id.add_button)
        appTime = findViewById(R.id.app_time)
        listSpinner = findViewById(R.id.list_spinner)
        alarmContainer = findViewById(R.id.alarm_container)
        tasksCount = findViewById(R.id.tasks_count)
        val addListButton = findViewById<TextView>(R.id.add_list_btn)
        val manageListsButton = findViewById<TextView>(R.id.manage_lists_btn)

        addButton.setOnClickListener {
            val taskText = taskInput.text.toString().trim()
            if (taskText.isNotEmpty()) {
                addTask(taskText)
                taskInput.text.clear()
            } else {
                taskInput.error = "Please enter a task"
            }
        }

        addListButton.setOnClickListener {
            showAddListDialog()
        }

        manageListsButton.setOnClickListener {
            showListManagementDialog()
        }

        // Set up long press on list spinner to open management
        listSpinner.setOnLongClickListener {
            showListManagementDialog()
            true
        }
    }

    private fun setupListSpinner() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        listSpinner.adapter = adapter

        listSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (taskLists.isNotEmpty() && position < taskLists.size) {
                    currentListId = taskLists[position].id
                    saveCurrentList() // This now saves the ID and updates widget
                    refreshTasks()
                    // updateWidget() is called inside saveCurrentList()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun addTask(text: String, parentTask: Task? = null) {
        val currentList = taskLists.find { it.id == currentListId } ?: run {
            // Create default list if it doesn't exist
            val defaultList = TaskList(name = "Default Tasks", id = "default")
            taskLists.add(defaultList)
            currentListId = defaultList.id
            updateListSpinner()
            defaultList
        }

        val newTask = Task(
            text = text,
            listId = currentListId,
            parentTaskId = parentTask?.id
        )

        if (parentTask != null) {
            parentTask.subtasks.add(newTask)
        } else {
            currentList.tasks.add(newTask)
        }

        saveTaskLists()
        refreshTasks()
        updateWidget()
    }

    private fun refreshTasks() {
        taskContainer.removeAllViews()
        val currentList = taskLists.find { it.id == currentListId } ?: return

        if (currentList.tasks.isEmpty()) {
            // Show empty state
            val emptyView = TextView(this).apply {
                text = "No tasks yet. Add a task above!"
                setTextColor(Color.GRAY)
                textSize = 16f
                setPadding(0, 32, 0, 0)
            }
            taskContainer.addView(emptyView)
        } else {
            currentList.tasks.forEach { task ->
                addTaskToView(task, taskContainer)
            }
        }

        updateTasksCount()
    }

    private fun addTaskToView(task: Task, container: LinearLayout, level: Int = 0) {
        val taskView = layoutInflater.inflate(R.layout.task_item_enhanced, container, false) as LinearLayout
        setupTaskView(taskView, task, level)
        container.addView(taskView)

        // Add subtasks
        if (task.hasSubtasks()) {
            task.subtasks.forEach { subtask ->
                addTaskToView(subtask, container, level + 1)
            }
        }
    }

    private fun setupTaskView(taskView: LinearLayout, task: Task, level: Int) {
        val taskDragHandle = taskView.findViewById<TextView>(R.id.task_drag_handle)
        val taskCheckbox = taskView.findViewById<TextView>(R.id.task_checkbox)
        val taskTextview = taskView.findViewById<TextView>(R.id.task_text)
        val taskAlarmIndicator = taskView.findViewById<TextView>(R.id.task_alarm_indicator)
        val taskDueDate = taskView.findViewById<TextView>(R.id.task_due_date)
        val taskSubtasks = taskView.findViewById<TextView>(R.id.task_subtasks)
        val taskDelete = taskView.findViewById<TextView>(R.id.task_delete)
        val taskAddSubtask = taskView.findViewById<TextView>(R.id.task_add_subtask)
        val taskSetReminder = taskView.findViewById<TextView>(R.id.task_set_reminder)
        val taskMoveSubtask = taskView.findViewById<TextView>(R.id.task_move_subtask)
        val taskIndent = taskView.findViewById<LinearLayout>(R.id.task_indent)

        // Set indentation for subtasks
        val indentParams = taskIndent.layoutParams as LinearLayout.LayoutParams
        indentParams.width = level * 40 // 40dp per level
        taskIndent.layoutParams = indentParams

        taskTextview.text = task.text

        // Update checkbox appearance based on completion
        if (task.completed) {
            taskCheckbox.text = "✓"
            taskCheckbox.setTextColor(Color.GREEN)
        } else {
            taskCheckbox.text = "○"
            taskCheckbox.setTextColor("#0066CC".toColorInt())
        }

        // Due date
        if (task.dueDate != null) {
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            taskDueDate.text = getString(R.string.due_date_format, dateFormat.format(Date(task.dueDate!!)))
            taskDueDate.visibility = View.VISIBLE
        } else {
            taskDueDate.visibility = View.GONE
        }

        // Alarm/due date indicator
        if (task.dueDate != null && task.dueDate!! > System.currentTimeMillis()) {
            val timeLeft = task.dueDate!! - System.currentTimeMillis()
            val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
            val hoursLeft = timeLeft / (1000 * 60 * 60)
            val minutesLeft = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)

            val alarmText = when {
                daysLeft >= 28 -> "${daysLeft / 7}w" // 4+ weeks = weeks
                daysLeft >= 7 -> "${daysLeft / 7}w"  // 1+ weeks = weeks
                daysLeft >= 1 -> "${daysLeft}d"      // 1+ days = days
                hoursLeft >= 1 -> "${hoursLeft}h"    // 1+ hours = hours
                minutesLeft > 0 -> "${minutesLeft}m" // 1+ minutes = minutes
                else -> "Now"
            }

            taskAlarmIndicator.text = alarmText
            taskAlarmIndicator.visibility = View.VISIBLE
        } else {
            taskAlarmIndicator.visibility = View.GONE
        }

        // Subtasks progress
        if (task.hasSubtasks()) {
            val completed = task.completedSubtasks()
            val total = task.totalSubtasks()
            taskSubtasks.text = getString(R.string.subtasks_progress, completed, total)
            taskSubtasks.visibility = View.VISIBLE
        } else {
            taskSubtasks.visibility = View.GONE
        }

        // Show move button for subtasks (tasks that have a parent)
        taskMoveSubtask.visibility = if (task.parentTaskId != null) View.VISIBLE else View.GONE

        // Strike through completed tasks
        if (task.completed) {
            taskTextview.paintFlags = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            taskTextview.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        } else {
            taskTextview.paintFlags = 0
            taskTextview.setTextColor("#0066CC".toColorInt())
        }

        // Click listeners
        taskCheckbox.setOnClickListener {
            task.completed = !task.completed
            saveTaskLists()
            refreshTasks()
            updateTasksCount()
            updateWidget()
        }

        taskDelete.setOnClickListener {
            deleteTask(task)
        }

        taskAddSubtask.setOnClickListener {
            showAddSubtaskDialog(task)
        }

        taskSetReminder.setOnClickListener {
            showDateTimePicker(task)
        }

        // Drag handle for reordering - only for main tasks (not subtasks)
        taskDragHandle.setOnLongClickListener {
            if (task.parentTaskId == null) {
                startDragging(taskView, task)
                true
            } else {
                false
            }
        }

        // Move subtask to different parent or make standalone
        taskMoveSubtask.setOnClickListener {
            showMoveSubtaskDialog(task)
        }

        // Long press to edit
        taskView.setOnLongClickListener {
            showEditTaskDialog(task)
            true
        }
    }

    private fun updateTasksCount() {
        val currentList = taskLists.find { it.id == currentListId } ?: return
        val totalTasks = currentList.tasks.size
        val completedTasks = currentList.tasks.count { it.completed }

        tasksCount.text = if (totalTasks == 0) {
            "No tasks"
        } else {
            "$completedTasks/$totalTasks completed"
        }
    }

    private fun showAddSubtaskDialog(parentTask: Task) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        title.text = "Add Subtask to: ${parentTask.text}"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                addTask(text, parentTask)
                dialog.dismiss()
            } else {
                editText.error = "Please enter subtask text"
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditTaskDialog(task: Task) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        title.text = "Edit Task"
        editText.setText(task.text)
        positiveButton.text = "Save"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                task.text = text
                saveTaskLists()
                refreshTasks()
                updateWidget()
                dialog.dismiss()
            } else {
                editText.error = "Task cannot be empty"
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDateTimePicker(task: Task) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        // Hide the edit text and create option buttons
        editText.visibility = View.GONE

        // Create a container for our time options
        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val timeOptions = mutableListOf(
            "1 hour from now",
            "2 hours from now",
            "3 hours from now",
            "6 hours from now",
            "12 hours from now",
            "24 hours from now",
            "2 days from now",
            "1 week from now",
            "Custom date and time..."
        )

        // Add "Remove due date" option if task already has a due date
        if (task.dueDate != null) {
            timeOptions.add(0, "Remove due date") // Add at the beginning
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        timeOptions.forEach { option ->
            val optionButton = TextView(this).apply {
                text = option
                setTextColor("#0066CC".toColorInt())
                textSize = 14f
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(32, 16, 32, 16)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dialog_button_bg)
            }

            optionButton.setOnClickListener {
                when (option) {
                    "Remove due date" -> {
                        task.dueDate = null
                        saveTaskLists()
                        refreshTasks()
                        updateWidget()
                        Toast.makeText(this@MainActivity, "Due date removed", Toast.LENGTH_SHORT).show()
                    }
                    "1 hour from now" -> setDueDate(task, 1)
                    "2 hours from now" -> setDueDate(task, 2)
                    "3 hours from now" -> setDueDate(task, 3)
                    "6 hours from now" -> setDueDate(task, 6)
                    "12 hours from now" -> setDueDate(task, 12)
                    "24 hours from now" -> setDueDate(task, 24)
                    "2 days from now" -> setDueDate(task, 48)
                    "1 week from now" -> setDueDate(task, 168)
                    "Custom date and time..." -> {
                        dialog.dismiss()
                        showCustomDateTimePicker(task)
                        return@setOnClickListener
                    }
                }
                dialog.dismiss()
            }

            optionsContainer.addView(optionButton)

            // Add divider between options
            if (option != timeOptions.last()) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(32, 4, 32, 4)
                    }
                    setBackgroundColor("#200066CC".toColorInt())
                }
                optionsContainer.addView(divider)
            }
        }

        // Add the options container to the dialog
        val parent = dialogView as LinearLayout
        parent.addView(optionsContainer, 1) // Add after title, before buttons

        title.text = "Set Due Date"
        positiveButton.visibility = View.GONE // Hide the positive button
        negativeButton.text = "Cancel"

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun deleteTask(task: Task) {
        val currentList = taskLists.find { it.id == currentListId } ?: return

        // Remove from main tasks
        currentList.tasks.removeAll { it.id == task.id }

        // Also remove from any subtasks
        currentList.tasks.forEach { parentTask ->
            parentTask.subtasks.removeAll { it.id == task.id }
        }

        saveTaskLists()
        refreshTasks()
        updateWidget()
    }

    private fun loadTaskLists() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val listsJson = prefs.getString(KEY_TASK_LISTS, null) ?: "[]"

        try {
            val jsonArray = JSONArray(listsJson)
            taskLists.clear()

            // Check if we have any valid lists
            var hasValidLists = false
            for (i in 0 until jsonArray.length()) {
                try {
                    val taskList = TaskList.fromJson(jsonArray.getJSONObject(i))
                    if (taskList.name.isNotEmpty()) {
                        taskLists.add(taskList)
                        hasValidLists = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Create default list only if no valid lists exist
            if (!hasValidLists) {
                val defaultList = TaskList(name = "My Tasks", id = "default")
                taskLists.add(defaultList)
                saveTaskLists()
            }

            // Set current list to first one
            if (taskLists.isNotEmpty()) {
                currentListId = taskLists.first().id
                updateListSpinner()
                refreshTasks()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Initialize with default list on error
            if (taskLists.isEmpty()) {
                taskLists.add(TaskList(name = "My Tasks", id = "default"))
                saveTaskLists()
            }
        }
    }

    private fun saveTaskLists() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonArray = JSONArray()
        taskLists.forEach { list ->
            jsonArray.put(list.toJson())
        }
        prefs.edit {
            putString(KEY_TASK_LISTS, jsonArray.toString())
        }
    }

    private fun updateListSpinner() {
        val adapter = listSpinner.adapter as? ArrayAdapter<String> ?: return
        adapter.clear()
        taskLists.forEach { list ->
            adapter.add(list.name)
        }
        adapter.notifyDataSetChanged()

        // Select current list
        val currentIndex = taskLists.indexOfFirst { it.id == currentListId }
        if (currentIndex >= 0) {
            listSpinner.setSelection(currentIndex)
        }
    }

    private fun updateTime() {
        val timeFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use system time format (respects user's 12/24-hour preference)
            DateFormat.getTimeFormat(this)
        } else {
            // Fallback to forced 12-hour format for older devices
            SimpleDateFormat("hh:mm a", Locale.US)
        }
        val time = timeFormat.format(Date())
        appTime.text = time
    }

    private fun updateWidget() {
        try {
            val intent = Intent(this, TaskWidget::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val ids = AppWidgetManager.getInstance(this)
                .getAppWidgetIds(ComponentName(this, TaskWidget::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAddListDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        title.text = "Create New List"
        editText.hint = "Enter list name..."
        positiveButton.text = "Create"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val listName = editText.text.toString().trim()
            if (listName.isNotEmpty()) {
                createNewList(listName)
                dialog.dismiss()
            } else {
                editText.error = "Please enter a list name"
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createNewList(listName: String) {
        // Check if list name already exists
        if (taskLists.any { it.name.equals(listName, ignoreCase = true) }) {
            Toast.makeText(this, "List '$listName' already exists!", Toast.LENGTH_SHORT).show()
            return
        }

        val newList = TaskList(name = listName)
        taskLists.add(newList)
        saveTaskLists()
        updateListSpinner()

        // Switch to the new list
        currentListId = newList.id
        listSpinner.setSelection(taskLists.size - 1)
        refreshTasks()

        Toast.makeText(this, "List '$listName' created!", Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentList() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            putString(KEY_CURRENT_LIST, currentListId)
        }
        updateWidget()
    }

    private fun startDragging(taskView: View, task: Task) {
        val shadowBuilder = View.DragShadowBuilder(taskView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            taskView.startDragAndDrop(null, shadowBuilder, task, 0)
        } else {
            @Suppress("DEPRECATION")
            taskView.startDrag(null, shadowBuilder, task, 0)
        }
        taskView.alpha = 0.5f
    }

    private fun setupDragAndDrop() {
        taskContainer.setOnDragListener { view, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    view.setBackgroundColor("#200066CC".toColorInt())
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    view.setBackgroundColor(Color.TRANSPARENT)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val draggedTask = event.localState as? Task

                    draggedTask?.let { task ->
                        // Find the drop position
                        val dropY = event.y

                        var insertIndex = -1
                        for (i in 0 until taskContainer.childCount) {
                            val child = taskContainer.getChildAt(i)
                            if (dropY < child.bottom) {
                                insertIndex = i
                                break
                            }
                        }

                        if (insertIndex == -1) {
                            insertIndex = taskContainer.childCount
                        }

                        reorderTask(task, insertIndex)
                    }

                    view.setBackgroundColor(Color.TRANSPARENT)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    // Reset all task views alpha
                    for (i in 0 until taskContainer.childCount) {
                        val child = taskContainer.getChildAt(i)
                        child.alpha = 1.0f
                    }
                    view.setBackgroundColor(Color.TRANSPARENT)
                    true
                }
                else -> false
            }
        }
    }

    private fun reorderTask(task: Task, newIndex: Int) {
        val currentList = taskLists.find { it.id == currentListId } ?: return

        // Remove task from current position
        currentList.tasks.removeAll { it.id == task.id }

        // Insert at new position
        if (newIndex >= currentList.tasks.size) {
            currentList.tasks.add(task)
        } else {
            currentList.tasks.add(newIndex, task)
        }

        saveTaskLists()
        refreshTasks()
        updateWidget()
    }

    private fun showMoveSubtaskDialog(subtask: Task) {
        val currentList = taskLists.find { it.id == currentListId } ?: return

        val items = arrayOf(
            "Make standalone task",
            "Move to different task"
        ) + currentList.tasks
            .filter { it.id != subtask.parentTaskId && it.id != subtask.id && it.parentTaskId == null }
            .map { "Move to: ${it.text}" }

        AlertDialog.Builder(this)
            .setTitle("Move Subtask")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> makeSubtaskStandalone(subtask)
                    1 -> showTaskSelectionDialog(subtask)
                    else -> {
                        val targetTask = currentList.tasks.filter {
                            it.id != subtask.parentTaskId && it.id != subtask.id && it.parentTaskId == null
                        }[which - 2]
                        moveSubtaskToTask(subtask, targetTask)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeSubtaskStandalone(subtask: Task) {
        val currentList = taskLists.find { it.id == currentListId } ?: return
        val parentTask = currentList.tasks.find { it.id == subtask.parentTaskId }

        parentTask?.subtasks?.removeAll { it.id == subtask.id }
        subtask.parentTaskId = null
        currentList.tasks.add(subtask)

        saveTaskLists()
        refreshTasks()
        updateWidget()
        Toast.makeText(this, "Subtask made standalone", Toast.LENGTH_SHORT).show()
    }

    private fun moveSubtaskToTask(subtask: Task, targetTask: Task) {
        val currentList = taskLists.find { it.id == currentListId } ?: return
        val parentTask = currentList.tasks.find { it.id == subtask.parentTaskId }

        parentTask?.subtasks?.removeAll { it.id == subtask.id }
        subtask.parentTaskId = targetTask.id
        targetTask.subtasks.add(subtask)

        saveTaskLists()
        refreshTasks()
        updateWidget()
        Toast.makeText(this, "Subtask moved to ${targetTask.text}", Toast.LENGTH_SHORT).show()
    }

    private fun showTaskSelectionDialog(subtask: Task) {
        val currentList = taskLists.find { it.id == currentListId } ?: return
        val availableTasks = currentList.tasks.filter {
            it.id != subtask.parentTaskId && it.id != subtask.id && it.parentTaskId == null
        }

        if (availableTasks.isEmpty()) {
            Toast.makeText(this, "No other tasks available to move to", Toast.LENGTH_SHORT).show()
            return
        }

        val taskNames = availableTasks.map { it.text }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Target Task")
            .setItems(taskNames) { dialog, which ->
                val targetTask = availableTasks[which]
                moveSubtaskToTask(subtask, targetTask)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showListManagementDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)

        // Hide the edit text since we don't need it for this dialog
        editText.visibility = View.GONE

        // Create a list view for the lists
        val listView = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, taskLists.map { it.name })
            setDivider(ContextCompat.getDrawable(this@MainActivity, android.R.color.transparent))
            setDividerHeight(1)
        }

        // Add the list view below the title and above the buttons
        val parent = dialogView as LinearLayout
        parent.addView(listView, 1) // Add at position 1 (after title, before button layout)

        title.text = "Manage Lists"
        positiveButton.visibility = View.GONE // Hide positive button for this dialog
        negativeButton.text = "Close"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Set click listener AFTER dialog is created
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < taskLists.size) {
                val selectedList = taskLists[position]
                dialog.dismiss()
                showListOptionsDialog(selectedList)
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showListOptionsDialog(taskList: TaskList) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)

        // Hide the edit text since we don't need it for this dialog
        editText.visibility = View.GONE

        // Don't allow deleting the last list
        val canDelete = taskLists.size > 1

        val options = if (canDelete) {
            arrayOf("Switch to this list", "Rename list", "Delete list")
        } else {
            arrayOf("Switch to this list", "Rename list")
        }

        // Create a list view for the options
        val listView = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, options)
            setDivider(ContextCompat.getDrawable(this@MainActivity, android.R.color.transparent))
            setDividerHeight(1)
        }

        // Add the list view below the title and above the buttons
        val parent = dialogView as LinearLayout
        parent.addView(listView, 1) // Add at position 1 (after title, before button layout)

        title.text = "List: ${taskList.name}"
        positiveButton.visibility = View.GONE // Hide positive button for this dialog
        negativeButton.text = "Cancel"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Set click listener AFTER dialog is created
        listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> { // Switch to list
                    currentListId = taskList.id
                    listSpinner.setSelection(taskLists.indexOfFirst { it.id == taskList.id })
                    refreshTasks()
                    dialog.dismiss()
                }
                1 -> { // Rename list
                    dialog.dismiss()
                    showRenameListDialog(taskList)
                }
                2 -> { // Delete list (only if canDelete is true)
                    if (canDelete) {
                        dialog.dismiss()
                        showDeleteListConfirmationDialog(taskList)
                    }
                }
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRenameListDialog(taskList: TaskList) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        title.text = "Rename List"
        editText.setText(taskList.name)
        editText.setSelection(taskList.name.length) // Place cursor at end
        positiveButton.text = "RENAME"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty() && newName != taskList.name) {
                if (taskLists.any { it.name.equals(newName, ignoreCase = true) && it.id != taskList.id }) {
                    editText.error = "List '$newName' already exists!"
                } else {
                    taskList.name = newName
                    saveTaskLists()
                    updateListSpinner()
                    dialog.dismiss()
                    Toast.makeText(this, "List renamed to '$newName'", Toast.LENGTH_SHORT).show()
                }
            } else {
                editText.error = "Please enter a new list name"
            }
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDeleteListConfirmationDialog(taskList: TaskList) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_aero_style, null)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_task_input)

        // Hide the edit text and replace it with a message
        editText.visibility = View.GONE

        // Create a message text view
        val message = TextView(this).apply {
            text = "Are you sure you want to delete '${taskList.name}'?\n\nAll tasks in this list will be lost."
            setTextColor("#0066CC".toColorInt())
            textSize = 14f
            gravity = android.view.Gravity.CENTER  // Fixed: Added android.view prefix
            setPadding(0, 16, 0, 16)
        }

        // Add the message below the title and above the buttons
        val parent = dialogView as LinearLayout
        parent.addView(message, 1) // Add at position 1 (after title, before button layout)

        title.text = "Delete List"
        positiveButton.text = "DELETE"
        positiveButton.setTextColor(Color.RED)
        negativeButton.text = "CANCEL"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            taskLists.removeAll { it.id == taskList.id }

            // Switch to another list if we're deleting the current one
            if (currentListId == taskList.id && taskLists.isNotEmpty()) {
                currentListId = taskLists.first().id
            }

            saveTaskLists()
            updateListSpinner()
            refreshTasks()
            dialog.dismiss()
            Toast.makeText(this, "List '${taskList.name}' deleted", Toast.LENGTH_SHORT).show()
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setDueDate(task: Task, hoursToAdd: Int) {
        task.dueDate = System.currentTimeMillis() + (hoursToAdd * 60 * 60 * 1000L)
        saveTaskLists()
        refreshTasks()
        updateWidget()

        val timeText = when (hoursToAdd) {
            1 -> "1 hour"
            2 -> "2 hours"
            3 -> "3 hours"
            6 -> "6 hours"
            12 -> "12 hours"
            24 -> "1 day"
            48 -> "2 days"
            168 -> "1 week"
            else -> "$hoursToAdd hours"
        }

        Toast.makeText(this, "Due date set: $timeText from now", Toast.LENGTH_SHORT).show()
    }

    private fun showCustomDateTimePicker(task: Task) {
        val currentTime = Calendar.getInstance()
        showCustomDatePicker(task, currentTime)
    }

    private fun showCustomDatePicker(task: Task, currentTime: Calendar) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_date_picker, null)
        val datePicker = dialogView.findViewById<DatePicker>(R.id.date_picker)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        datePicker.init(
            currentTime.get(Calendar.YEAR),
            currentTime.get(Calendar.MONTH),
            currentTime.get(Calendar.DAY_OF_MONTH),
            null
        )
        datePicker.minDate = System.currentTimeMillis() - 1000

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val year = datePicker.year
            val month = datePicker.month
            val day = datePicker.dayOfMonth
            dialog.dismiss()
            showCustomTimePicker(task, year, month, day)
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCustomTimePicker(task: Task, year: Int, month: Int, day: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        val timePicker = dialogView.findViewById<TimePicker>(R.id.time_picker)
        val positiveButton = dialogView.findViewById<TextView>(R.id.dialog_positive_button)
        val negativeButton = dialogView.findViewById<TextView>(R.id.dialog_negative_button)

        val currentTime = Calendar.getInstance()
        timePicker.setIs24HourView(true)
        timePicker.hour = currentTime.get(Calendar.HOUR_OF_DAY)
        timePicker.minute = currentTime.get(Calendar.MINUTE)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        positiveButton.setOnClickListener {
            val hour = timePicker.hour
            val minute = timePicker.minute

            val calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }

            task.dueDate = calendar.timeInMillis
            saveTaskLists()
            refreshTasks()
            updateWidget()

            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            Toast.makeText(this, "Due date set: ${dateFormat.format(Date(task.dueDate!!))}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        negativeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_TASK_LISTS) {
            // Reload tasks when shared preferences change
            runOnUiThread {
                loadTaskLists()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the shared preferences listener
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    override fun onResume() {
        super.onResume()
        // Refresh tasks whenever the app comes to foreground
        loadTaskLists()
        updateTime()
    }
}