package com.nithin.nightdrive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap

class AppInfo(val label: String, val component: ComponentName, val icon: Bitmap)

object AppLoader {

    const val THEMES_ENTRY = "themes"

    /** Call off the main thread: decodes every launcher icon. */
    fun load(context: Context, iconPx: Int): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            // Leave out the home screen itself, but keep the receiver that ships in this APK.
            .filter { it.activityInfo.name != LauncherActivity::class.java.name }
            .map {
                val packageName = it.activityInfo.packageName
                val label = it.loadLabel(pm).toString()
                AppInfo(
                    label,
                    ComponentName(packageName, it.activityInfo.name),
                    IconRenderer.render(
                        context, packageName, it.loadIcon(pm), iconPx, label,
                        isSystem(it.activityInfo.applicationInfo)
                    )
                )
            }
            .plus(
                // The launcher's own theme picker, shown alongside the real apps.
                AppInfo(
                    context.getString(R.string.themes),
                    ComponentName(context.packageName, THEMES_ENTRY),
                    IconRenderer.renderThemes(iconPx)
                )
            )
            .sortedBy { it.label.lowercase() }
    }

    fun packageIcon(context: Context, packageName: String, iconPx: Int): Bitmap? = try {
        val pm = context.packageManager
        val app = pm.getApplicationInfo(packageName, 0)
        IconRenderer.render(
            context, packageName, pm.getApplicationIcon(app), iconPx,
            pm.getApplicationLabel(app).toString(), isSystem(app)
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Icon of the single app that handles [intent], or null if there is none or a chooser. */
    fun handlerIcon(context: Context, intent: Intent, iconPx: Int): Bitmap? {
        val pm = context.packageManager
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (info == null || info.packageName == "android") return null
        return IconRenderer.render(
            context, info.packageName, info.loadIcon(pm), iconPx,
            info.loadLabel(pm).toString(), isSystem(info.applicationInfo)
        )
    }

    private fun isSystem(app: ApplicationInfo) = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
}
