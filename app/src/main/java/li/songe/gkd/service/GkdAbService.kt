package li.songe.gkd.service

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.NetworkUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ServiceUtils
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.songe.gkd.composition.CompositionAbService
import li.songe.gkd.composition.CompositionExt.useLifeCycleLog
import li.songe.gkd.composition.CompositionExt.useScope
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.ClickLog
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.NodeInfo
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.SubscriptionRaw
import li.songe.gkd.data.getActionFc
import li.songe.gkd.db.DbSet
import li.songe.gkd.shizuku.useSafeGetTasksFc
import li.songe.gkd.util.Singleton
import li.songe.gkd.util.increaseClickCount
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.launchWhile
import li.songe.gkd.util.map
import li.songe.gkd.util.recordStoreFlow
import li.songe.gkd.util.storeFlow
import li.songe.gkd.util.subsIdToRawFlow
import li.songe.gkd.util.subsItemsFlow
import li.songe.selector.Selector
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


@OptIn(FlowPreview::class)
class GkdAbService : CompositionAbService({
    useLifeCycleLog()

    val context = this as GkdAbService

    val scope = useScope()

    service = context
    onDestroy {
        service = null
        topActivityFlow.value = null
    }

    ManageService.start(context)
    onDestroy {
        ManageService.stop(context)
    }

    val safeGetTasksFc = useSafeGetTasksFc(scope)
    fun getActivityIdByShizuku(): String? {
        return safeGetTasksFc()?.lastOrNull()?.topActivity?.className
    }

    fun isActivity(
        appId: String,
        activityId: String,
    ): Boolean {
        val r = (try {
            packageManager.getActivityInfo(
                ComponentName(
                    appId, activityId
                ), 0
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } != null)
        Log.d("isActivity", "$appId, $activityId, $r")
        return r
    }

    var lastTriggerShizukuTime = 0L
    onAccessibilityEvent { event -> // 根据事件获取 activityId, 概率不准确
        if (event == null) return@onAccessibilityEvent
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val newAppId = event.packageName?.toString() ?: return@onAccessibilityEvent
            val newActivityId = event.className?.toString() ?: return@onAccessibilityEvent
            val rightAppId =
                safeActiveWindow?.packageName?.toString() ?: return@onAccessibilityEvent
            if (rightAppId != newAppId) return@onAccessibilityEvent

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // tv.danmaku.bili, com.miui.home, com.miui.home.launcher.Launcher
                if (isActivity(newAppId, newActivityId)) {
                    topActivityFlow.value = TopActivity(
                        newAppId, newActivityId
                    )
                    activityChangeTimeFlow.value = System.currentTimeMillis()
                    return@onAccessibilityEvent
                }
            }
            lastTriggerShizukuTime =
                if (newActivityId.startsWith("android.view.") || newActivityId.startsWith("android.widget.")) {
                    val t = System.currentTimeMillis()
                    if (t - lastTriggerShizukuTime < if (currentRulesFlow.value.isNotEmpty()) 200 else 400) {
                        return@onAccessibilityEvent
                    }
                    t
                } else {
                    0L
                }
            val shizukuActivityId = getActivityIdByShizuku() ?: return@onAccessibilityEvent
            if (shizukuActivityId == newActivityId) {
                activityChangeTimeFlow.value = System.currentTimeMillis()
            }
            topActivityFlow.value = TopActivity(
                rightAppId, shizukuActivityId
            )
        }
    }

    scope.launchWhile(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(
            intent, 0
        )
        launcherActivityIdFlow.value = info?.activityInfo?.name
        delay(10 * 60_000)
    }

    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?

    scope.launchWhile(Dispatchers.Default) { // 屏幕无障碍信息轮询
        delay(200)

        if (km?.isKeyguardLocked == true) { // isScreenLock
            return@launchWhile
        }

        val rightAppId = safeActiveWindow?.packageName?.toString()
        if (rightAppId != null) {
            if (rightAppId != topActivityFlow.value?.appId) {
                topActivityFlow.value = topActivityFlow.value?.copy(
                    appId = rightAppId,
                    activityId = getActivityIdByShizuku() ?: launcherActivityIdFlow.value
                )
                return@launchWhile
            } else if (topActivityFlow.value?.activityId == null) {
                val rightId = getActivityIdByShizuku()
                if (rightId != null) {
                    topActivityFlow.value = topActivityFlow.value?.copy(
                        appId = rightAppId, activityId = rightId
                    )
                    return@launchWhile
                }
            }
        }

        if (!storeFlow.value.enableService) return@launchWhile

        val currentRules = currentRulesFlow.value
        val topActivity = topActivityFlow.value
        for (rule in currentRules) {
            if (!isAvailableRule(rule)) continue
            val nodeVal = safeActiveWindow ?: continue
            val target = rule.query(nodeVal) ?: continue

            if (currentRules !== currentRulesFlow.value || topActivity != topActivityFlow.value) break

            // 开始 action 延迟
            if (rule.actionDelay > 0 && rule.actionDelayTriggerTime == 0L) {
                rule.triggerDelay()
                LogUtils.d(
                    "触发延迟",
                    "subsId:${rule.subsItem.id}, gKey=${rule.group.key}, gName:${rule.group.name}, ruleIndex:${rule.index}, rKey:${rule.key}, delay:${rule.actionDelay}"
                )
                continue
            }

            // 如果节点在屏幕外部, click 的结果为 null

            val actionResult = rule.performAction(context, target)
            if (actionResult.result) {
                toastClickTip()
                rule.trigger()
                scope.launchTry(Dispatchers.IO) {
                    LogUtils.d(
                        *rule.matches.toTypedArray(), NodeInfo.abNodeToNode(
                            nodeVal, target
                        ).attr, actionResult
                    )
                    val clickLog = ClickLog(
                        appId = topActivityFlow.value?.appId,
                        activityId = topActivityFlow.value?.activityId,
                        subsId = rule.subsItem.id,
                        groupKey = rule.group.key,
                        ruleIndex = rule.index,
                        ruleKey = rule.key
                    )
                    DbSet.clickLogDao.insert(clickLog)
                    increaseClickCount()
                    if (recordStoreFlow.value.clickCount % 100 == 0) {
                        DbSet.clickLogDao.deleteKeepLatest()
                    }
                }
            }
        }
    }

    var lastUpdateSubsTime = System.currentTimeMillis()
    scope.launchWhile(Dispatchers.IO) { // 自动从网络更新订阅文件
        delay(10 * 60_000) // 每 10 分钟检查一次
        if (ScreenUtils.isScreenLock() // 锁屏
            || storeFlow.value.updateSubsInterval <= 0 // 暂停更新
            || System.currentTimeMillis() - lastUpdateSubsTime < storeFlow.value.updateSubsInterval.coerceAtLeast(
                60 * 60_000
            ) // 距离上次更新的时间小于更新间隔
        ) {
            return@launchWhile
        }
        if (!NetworkUtils.isAvailable()) {// 产生 io
            return@launchWhile
        }

        subsItemsFlow.value.forEach { subsItem ->
            if (subsItem.updateUrl == null) return@forEach
            try {
                val newSubsRaw = SubscriptionRaw.parse(
                    Singleton.client.get(subsItem.updateUrl).bodyAsText()
                )
                if (newSubsRaw.id != subsItem.id) {
                    return@forEach
                }
                val oldSubsRaw = subsIdToRawFlow.value[subsItem.id]
                if (oldSubsRaw != null && newSubsRaw.version <= oldSubsRaw.version) {
                    return@forEach
                }
                subsItem.subsFile.writeText(
                    SubscriptionRaw.stringify(
                        newSubsRaw
                    )
                )
                val newItem = subsItem.copy(
                    updateUrl = newSubsRaw.updateUrl ?: subsItem.updateUrl,
                    mtime = System.currentTimeMillis()
                )
                DbSet.subsItemDao.update(newItem)
                LogUtils.d("更新磁盘订阅文件:${newSubsRaw.name}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        lastUpdateSubsTime = System.currentTimeMillis()
    }

    scope.launch(Dispatchers.IO) {
        combine(
            topActivityFlow, currentRulesFlow
        ) { topActivity, currentRules ->
            topActivity to currentRules
        }.debounce(300).collect { (topActivity, currentRules) ->
            if (storeFlow.value.enableService) {
                LogUtils.d(topActivity, *currentRules.map { r ->
                    "subsId:${r.subsItem.id}, gKey=${r.group.key}, gName:${r.group.name}, ruleIndex:${r.index}, rKey:${r.key}, active:${
                        isAvailableRule(r)
                    }"
                }.toTypedArray())
            } else {
                LogUtils.d(
                    topActivity
                )
            }
        }
    }

    var aliveView: View? = null
    val wm by lazy { context.getSystemService(WINDOW_SERVICE) as WindowManager }
    onServiceConnected {
        scope.launchTry {
            storeFlow.map(scope) { s -> s.enableAbFloatWindow }.collect {
                if (aliveView != null) {
                    withContext(Dispatchers.Main) {
                        wm.removeView(aliveView)
                    }
                }
                if (it) {
                    aliveView = View(context)
                    val lp = WindowManager.LayoutParams().apply {
                        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        format = PixelFormat.TRANSLUCENT
                        flags =
                            flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        width = 1
                        height = 1
                    }
                    withContext(Dispatchers.Main) {
                        wm.addView(aliveView, lp)
                    }
                } else {
                    aliveView = null
                }
            }
        }
    }
    onDestroy {
        if (aliveView != null) {
            wm.removeView(aliveView)
        }
    }
}) {

    companion object {
        var service: GkdAbService? = null
        fun isRunning() = ServiceUtils.isServiceRunning(GkdAbService::class.java)

        fun execAction(gkdAction: GkdAction): ActionResult {
            val serviceVal = service ?: throw RpcError("无障碍没有运行")
            val selector = try {
                Selector.parse(gkdAction.selector)
            } catch (e: Exception) {
                throw RpcError("非法选择器")
            }

            val targetNode =
                serviceVal.safeActiveWindow?.querySelector(selector, gkdAction.quickFind)
                    ?: throw RpcError("没有选择到节点")

            return getActionFc(gkdAction.action)(serviceVal, targetNode)
        }


        suspend fun currentScreenshot() = service?.run {
            suspendCoroutine {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(Display.DEFAULT_DISPLAY,
                        application.mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                it.resume(
                                    Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer, screenshot.colorSpace
                                    )
                                )
                            }

                            override fun onFailure(errorCode: Int) = it.resume(null)
                        })
                } else {
                    it.resume(null)
                }
            }
        }
    }
}