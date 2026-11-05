package fr.listes.beta

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

object LocalBridge {
    private var reference=WeakReference<LocalService>(null)
    var service:LocalService?
        get()=reference.get()
        set(value) {reference=WeakReference(value)}
    val connected=mutableStateOf(false)
}

class LocalService:AccessibilityService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val feed by lazy {FeedOverlay(this)}
    private val manual by lazy {ManualProfileOverlay(this)}
    private var filterJob:Job?=null
    private var syncJob:Job?=null
    private var lastAutoSync=0L
    private var lastScanError=0L
    fun refreshFilter() {feed.clear();reconcileFilter()}
    fun prepareManual(entry:Entry) {manual.prepare(entry)}
    fun cancelManual(entry:Entry?) {manual.cancel(entry)}
    fun clearManual() {manual.clear()}

    override fun onServiceConnected() {
        super.onServiceConnected()
        LocalBridge.service=this;LocalBridge.connected.value=true
        FilterRuntime.initialize(this)
        DebugLog.add(this,"service_connected")
        FilterSessionService.update(this)
        manual.restore()
        autoSyncIfNeeded()
        reconcileFilter()
    }
    private fun autoSyncIfNeeded() {
        if(!FilterRuntime.enabled.value || !FilterRuntime.consent ||
            (FilterRuntime.snapshot?.expires ?: 0L)>System.currentTimeMillis()+86_400_000L || syncJob?.isActive==true) return
        val now=SystemClock.elapsedRealtime()
        if(lastAutoSync!=0L && now-lastAutoSync<900_000L) return
        lastAutoSync=now
        syncJob=scope.launch {
            try {
                val raw=Http.signed("https://vps-b23d685a.vps.ovh.net/social-listes/web-beta/manifest.json")
                val key=assets.open("list-public.der").use {it.readBytes()}
                var fresh:Snapshot?=null
                SecureStore(this@LocalService).update {data->
                    val minimum=maxOf(data.optLong("sequence"),FilterRuntime.snapshot?.sequence ?: 0L)
                    fresh=SignedLists.decode(raw,key,minimum)
                    data.put("snapshot",raw).put("sequence",fresh!!.sequence)
                }
                FilterRuntime.snapshot=fresh
                DebugLog.add(this@LocalService,"auto_sync")
                refreshFilter()
            } catch(_:Exception) {DebugLog.add(this@LocalService,"auto_sync_failed")}
        }
    }
    private fun reconcileFilter() {
        val active=runCatching {feed.targetActive()}.getOrDefault(false)
        if(!active) {filterJob?.cancel();filterJob=null;feed.clear();return}
        autoSyncIfNeeded()
        if(filterJob?.isActive==true)return
        filterJob?.cancel()
        filterJob=scope.launch {
            while(isActive && runCatching {feed.targetActive()}.getOrDefault(false)) {
                try {feed.tick()} catch(e:Exception) {
                    feed.clear()
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastScanError>15_000 || lastScanError==0L) {
                        DebugLog.add(this@LocalService,"scan_error",org.json.JSONObject().put("type",e.javaClass.simpleName))
                        lastScanError=now
                    }
                }
                delay(if(feed.tracking()) 50 else 300)
            }
            feed.clear()
        }
    }
    override fun onUnbind(intent:Intent?):Boolean {
        feed.clear();manual.clear();filterJob?.cancel();LocalBridge.service=null;LocalBridge.connected.value=false
        FilterSessionService.update(this)
        return super.onUnbind(intent)
    }
    fun probeOwnWindow():String {
        if(!LocalBridge.connected.value)return "Service non connecté."
        return try {
            val root=rootInActiveWindow
            when {
                root==null->"Service connecté, mais aucune fenêtre accessible. Revenez dans l’application puis retestez."
                root.packageName?.toString()==packageName->"Service connecté : la fenêtre de JANÔ est accessible."
                else->"Service connecté et fenêtre accessible."
            }
        } catch(_:Exception) {"Service connecté, mais lecture de fenêtre refusée. Désactivez puis réactivez le service dans Android."}
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event!=null && event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_SCROLLED,AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            runCatching {
                manual.onEvent(event)
                val isWindowChange=event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED
                val network=FeedRules.packages[event.packageName?.toString()]
                if(isWindowChange || filterJob?.isActive==true || (network!=null && network in FilterRuntime.selected)) {
                    reconcileFilter()
                    if(filterJob?.isActive==true)feed.onEvent(event)
                }
            }.onFailure {DebugLog.add(this,"window_event_error",org.json.JSONObject().put("type",it.javaClass.simpleName))}
        }
    }
    override fun onInterrupt() {feed.clear();manual.clear()}
    override fun onDestroy() {
        feed.clear();manual.clear();scope.cancel();LocalBridge.service=null;LocalBridge.connected.value=false
        super.onDestroy()
    }
}
