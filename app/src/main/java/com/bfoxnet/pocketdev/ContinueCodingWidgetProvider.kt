package com.bfoxnet.pocketdev

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.File

/**
 * Home-screen AppWidget ("Continue Coding").
 */
class ContinueCodingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val EXTRA_WIDGET_PROJECT_PATH = "extra_widget_project_path"
        const val EXTRA_WIDGET_FILE_PATH = "extra_widget_file_path"

        fun recordActiveSessionAndRefresh(context: Context, projectDir: File?, activeFile: OpenedFile?) {
            val settings = Settings(context)
            if (projectDir != null) {
                settings.lastWidgetProjectPath = projectDir.absolutePath
            }
            if (activeFile != null) {
                settings.lastWidgetFileName = activeFile.displayName
                settings.lastWidgetFilePath = activeFile.localFile?.absolutePath ?: ""
            }
            refreshAllWidgets(context)
        }

        fun refreshAllWidgets(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, ContinueCodingWidgetProvider::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateSingleWidget(context, mgr, id)
                    }
                }
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val settings = Settings(context)
            val projName = ProjectManager.activeProjectName(context)
            val fileName = settings.lastWidgetFileName?.ifBlank { "Main.kt" } ?: "Main.kt"
            val filePath = settings.lastWidgetFilePath
            val projPath = settings.lastWidgetProjectPath

            val views = RemoteViews(context.packageName, R.layout.widget_continue_coding)
            views.setTextViewText(R.id.widgetTitle, projName)
            views.setTextViewText(R.id.widgetSubtitle, fileName)
            views.setTextViewText(R.id.widgetMeta, "Tap to resume editing")

            val openEditorIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_WIDGET_PROJECT_PATH, projPath)
                putExtra(EXTRA_WIDGET_FILE_PATH, filePath)
            }
            val editorPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                openEditorIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, editorPi)
            views.setOnClickPendingIntent(R.id.widgetResumeBtn, editorPi)

            val openTermIntent = Intent(context, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!projPath.isNullOrBlank()) {
                    putExtra(TerminalActivity.EXTRA_WORKING_DIR, projPath)
                }
            }
            val termPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 2,
                openTermIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTerminalBtn, termPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
