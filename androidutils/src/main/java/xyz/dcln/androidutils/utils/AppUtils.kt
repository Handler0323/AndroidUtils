package xyz.dcln.androidutils.utils


import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import xyz.dcln.androidutils.AndroidUtils
import xyz.dcln.androidutils.utils.BusUtils.receive
import xyz.dcln.androidutils.utils.BusUtils.sendEventSticky
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.system.exitProcess


object AppUtils {
    private const val TAG_APP_STATE = "tag_app_state"

    private var isAppForeground = false

    internal fun init() {
        // Observe the process lifecycle to detect foreground/background transitions.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                // The app has entered the foreground.
                Lifecycle.Event.ON_START -> {
                    isAppForeground = true
                    sendEventSticky(TAG_APP_STATE, true)

                }
                // The app has entered the background.
                Lifecycle.Event.ON_STOP -> {
                    isAppForeground = false
                    sendEventSticky(TAG_APP_STATE, false)

                }
                // Do nothing for other events.
                else -> {}
            }
        })
    }

    fun LifecycleOwner.addAppStateListener(block: suspend CoroutineScope.(value: Boolean) -> Unit): Job {
        return receive(
            tags = arrayOf(TAG_APP_STATE),
            lifeEvent = Lifecycle.Event.ON_DESTROY,
            sticky = true,
            block = block
        )
    }


    /**
     * Check whether the application is currently in the foreground.
     *
     * @return True if the application is in the foreground, false otherwise.
     *
     * Note: Calling this function in `onCreate()` may return an incorrect result because the application has not finished initializing yet.
     * It is recommended to call this function in `onResume()` and `onPause()`, which will be called when the application enters or leaves the foreground.
     * If you need to check the foreground/background status of the application at other times, please use this function.
     */
    fun Any.isAppForeground(): Boolean = isAppForeground

    /**
     * Get the application.
     *
     * @return The application.
     */
    fun getApp(): Application = AndroidUtils.getApplication()

    /**
     * Get the application context.
     *
     * @return The application context.
     */
    fun getAppContext(): Context = getApp().applicationContext

    /**
     * Get the application package name.
     *
     * @return The application package name.
     */
    fun getAppPackageName(): String = getApp().packageName

    fun getAppPackageManager() = getAppContext().packageManager

    /**
     * Get the application icon.
     *
     * @return The application icon as a drawable.
     */
    fun getAppIcon(): Drawable? = getApp().applicationInfo.loadIcon(getApp().packageManager)

    /**
     * Get the application name.
     *
     * @return The application name as a string.
     */
    fun getAppName(): String {
        val appInfo = getApp().applicationInfo
        val packageManager = getApp().packageManager
        return appInfo.loadLabel(packageManager).toString()
    }


    /**
     * Get the application signatures.
     *
     * @return The application signatures as an array of Signature objects.
     */
    fun getAppSignatures(): Array<Signature>? {
        return try {
            val packageManager = getApp().packageManager
            val packageInfo = packageManager.getPackageInfo(
                getApp().packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
            )
            packageInfo.signatures
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }


    /**
     * Install an APK file
     * @param context The context object
     * @param path The path of the APK file
     */
    fun installApk(context: Context, path: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val file = File(path)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                UriUtils.file2Uri(file)
            } else {
                Uri.fromFile(file)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }

    /**
     * Uninstall an app.
     *
     * @param packageName The package name of the app.
     */
    fun uninstallApp(packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
        intent.data = "package:$packageName".toUri()
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        getAppContext().startActivity(intent)
    }

    /**
     * Determine whether an app is installed.
     *
     * @param packageName The package name of the app.
     * @return `true` if the app is installed, `false` otherwise.
     */
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            getAppContext().packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Exit the app.
     */
    @RequiresPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES)
    fun exitApp() {
        val activityManager =
            getAppContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.killBackgroundProcesses(getAppContext().packageName)
        exitProcess(0)
    }


    /**
     * Determine whether the app has root privileges.
     *
     * @return `true` if the app has root privileges, `false` otherwise.
     */
    fun isAppRoot(): Boolean {
        val process = Runtime.getRuntime().exec("su")
        val outputStream = process.outputStream
        outputStream.write("echo test".toByteArray())
        outputStream.flush()
        return try {
            process.waitFor()
            true
        } catch (e: InterruptedException) {
            false
        } finally {
            try {
                outputStream.close()
            } catch (e: IOException) {
                // Ignore exception
            }
            process.destroy()
        }
    }

    /**
     * Determine whether the app is a debug version.
     *
     * @return `true` if the app is a debug version, `false` otherwise.
     */
    fun isAppDebug(): Boolean {
        val ai = getAppContext().applicationInfo
        return ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /**
     * Determine whether the app is a system app.
     *
     * @return `true` if the app is a system app, `false` otherwise.
     */
    fun isAppSystem(): Boolean {
        val ai = getAppContext().applicationInfo
        return ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    /**
     * Determine whether the app is running.
     *
     * @return `true` if the app is running, `false` otherwise.
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    fun isAppRunning(): Boolean {
        val packageName = getAppContext().packageName
        return getAppProcesses().any { it.processName == packageName }
    }

    /**
     * Get the app processes.
     *
     * @return List of app processes.
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    fun getAppProcesses(): List<ActivityManager.RunningAppProcessInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getAppProcessesForAndroidQAndAbove()
        } else {
            getAppProcessesForBelowAndroidQ()
        }
    }

    private fun getAppProcessesForBelowAndroidQ(): List<ActivityManager.RunningAppProcessInfo> {
        val activityManager =
            getAppContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    private fun getAppProcessesForAndroidQAndAbove(): List<ActivityManager.RunningAppProcessInfo> {
        val context = getAppContext()
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - 1000 * 60 * 60 * 24,
            currentTime
        )

        val runningAppProcessInfos = mutableListOf<ActivityManager.RunningAppProcessInfo>()
        val packageManager = context.packageManager

        stats.forEach { usageStats ->
            try {
                val applicationInfo = packageManager.getApplicationInfo(usageStats.packageName, 0)
                val runningAppProcessInfo = ActivityManager.RunningAppProcessInfo(
                    usageStats.packageName,
                    applicationInfo.uid,
                    arrayOf<String>()
                )
                runningAppProcessInfos.add(runningAppProcessInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                // Ignore apps that cannot be found
            }
        }
        return runningAppProcessInfos
    }


    /**
     * Launch the app.
     */
    fun launchApp() {
        val launchIntent =
            getAppContext().packageManager.getLaunchIntentForPackage(getAppContext().packageName)
        launchIntent?.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        getAppContext().startActivity(launchIntent)
    }

    /**
     * Relaunch the app.
     */
    @RequiresPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES)
    fun relaunchApp() {
        val intent =
            getAppContext().packageManager.getLaunchIntentForPackage(getAppContext().packageName)
        val restartIntent = Intent.makeRestartActivityTask(intent?.component)
        getAppContext().startActivity(restartIntent)
        exitApp()
    }


    /**
     * Launch the app details settings.
     */
    fun launchAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:${getAppContext().packageName}".toUri()
        getAppContext().startActivity(intent)
    }


    /**
     * Get the app path.
     *
     * @return The app path.
     */
    fun getAppPath(): String {
        val applicationInfo = getAppContext().applicationInfo
        return applicationInfo.sourceDir
    }

    /**
     * Get the app version name.
     *
     * @return The app version name.
     */
    fun getAppVersionName(): String {
        val packageManager = getAppContext().packageManager
        return try {
            val info = packageManager.getPackageInfo(getAppContext().packageName, 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /**
     * Get the app version code.
     *
     * @return The app version code.
     */
    fun getAppVersionCode(): Long {
        val packageManager = getAppContext().packageManager
        return try {
            val info = packageManager.getPackageInfo(getAppContext().packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    /**
     * Get the app minimum SDK version.
     *
     * @return The app minimum SDK version.
     */
    fun getAppMinSdkVersion(): Int {
        val packageManager = getAppContext().packageManager
        return try {
            val packageInfo = packageManager.getPackageInfo(
                getAppContext().packageName,
                PackageManager.GET_ACTIVITIES
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                packageInfo.applicationInfo.minSdkVersion
            } else {
                packageInfo.applicationInfo.targetSdkVersion
            }
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    /**
     * Get the app target SDK version.
     *
     * @return The app target SDK version.
     */
    fun getAppTargetSdkVersion(): Int {
        val packageManager = getAppContext().packageManager
        return try {
            val packageInfo = packageManager.getPackageInfo(
                getAppContext().packageName,
                PackageManager.GET_ACTIVITIES
            )
            packageInfo.applicationInfo.targetSdkVersion
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }


    /**
     * Get the SHA1 signature of the app.
     *
     * @return The SHA1 signature of the app.
     */
    fun getAppSignaturesSHA1(): String {
        val signatures = getAppSignatures() ?: return ""
        return hashSignature(signatures, "SHA1")
    }

    /**
     * Get the SHA256 signature of the app.
     *
     * @return The SHA256 signature of the app.
     */
    fun getAppSignaturesSHA256(): String {
        val signatures = getAppSignatures() ?: return ""
        return hashSignature(signatures, "SHA256")
    }

    /**
     * Get the MD5 signature of the app.
     *
     * @return The MD5 signature of the app.
     */
    fun getAppSignaturesMD5(): String {
        val signatures = getAppSignatures() ?: return ""
        return hashSignature(signatures, "MD5")
    }

    /**
     * Get the app info.
     *
     * @return The app info.
     */
    fun getAppInfo(): String {
        val builder = StringBuilder()
        builder.appendln("App name: ${getAppName()}")
        builder.appendln("Package name: ${getAppPackageName()}")
        builder.appendln("Version name: ${getAppVersionName()}")
        builder.appendln("Version code: ${getAppVersionCode()}")
        builder.appendln("Minimum SDK version: ${getAppMinSdkVersion()}")
        builder.appendln("Target SDK version: ${getAppTargetSdkVersion()}")
        builder.appendln("SHA1 signature: ${getAppSignaturesSHA1()}")
        return builder.toString()
    }

    /**
     * Get info of all installed apps.
     *
     * @return A list of app info.
     */
    fun getAppsInfo(): List<String> {
        val packageManager = getAppContext().packageManager
        val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        val appsInfo = mutableListOf<String>()
        installedPackages.forEach { packageInfo ->
            val appName = packageInfo.applicationInfo.loadLabel(packageManager).toString()
            val packageName = packageInfo.packageName
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                packageInfo.versionCode.toString()
            }
            appsInfo.add("App name: $appName, Package name: $packageName, Version name: $versionName, Version code: $versionCode")
        }
        return appsInfo
    }

    /**
     * Get the APK info.
     *
     * @param apkPath The path of the APK file.
     * @return The APK info.
     */
    fun getApkInfo(apkPath: String): String {
        val packageManager = getAppContext().packageManager
        val packageInfo =
            packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA) ?: return ""
        val appName = packageManager.getApplicationLabel(packageInfo.applicationInfo).toString()
        val packageName = packageInfo.packageName
        val versionName = packageInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            packageInfo.versionCode.toString()
        }
        return "App name: $appName, Package name: $packageName, Version name: $versionName, Version code: $versionCode"
    }

    /**
     * Determine whether the app is installed for the first time.
     *
     * @return `true` if the app is installed for the first time, `false` otherwise.
     */
    fun isFirstTimeInstalled(): Boolean {
        val sharedPref = getAppContext().getSharedPreferences("AppUtils", Context.MODE_PRIVATE)
        val versionCode = getAppVersionCode()
        val savedVersionCode = sharedPref.getLong("version_code", -1)
        return if (savedVersionCode == -1L) {
            sharedPref.edit().putLong("version_code", versionCode).apply()
            true
        } else {
            savedVersionCode != versionCode
        }
    }

    /**
     * Hash the signatures using the specified algorithm.
     *
     * @param signatures The signatures to hash.
     * @param algorithm The hash algorithm to use.
     * @return The hashed signatures as a string.
     */
    private fun hashSignature(signatures: Array<Signature>, algorithm: String): String {
        val messageDigest = MessageDigest.getInstance(algorithm)
        signatures.forEach { signature ->
            messageDigest.update(signature.toByteArray())
        }
        val digest = messageDigest.digest()
        val hexString = StringBuilder()
        for (i in digest.indices) {
            val hex = Integer.toHexString(0xFF and digest[i].toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }
}