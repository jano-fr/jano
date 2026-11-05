package fr.listes.beta

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.app.PendingIntent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Confirmation remains explicit: opening a profile never clicks the social app. */
class ManualProfileOverlay(private val service:LocalService) {
    private val wm=service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler=Handler(Looper.getMainLooper())
    private var pending:Entry?=null
    private var banner:LinearLayout?=null
    private val retry=Runnable {reconcile()}
    fun restore() {pending=runCatching {ManualFollowStore.pending(service)}.getOrNull();reconcile()}
    fun prepare(entry:Entry) {
        hide();pending=entry
        handler.removeCallbacks(retry);handler.postDelayed(retry,400)
    }
    fun clear() {handler.removeCallbacks(retry);pending=null;hide()}
    fun cancel(entry:Entry?) {if(entry==null || pending?.let(ManualFollowState::key)==ManualFollowState.key(entry)) clear()}
    fun onEvent(event:AccessibilityEvent) {
        if(pending==null)return
        val overlayWindow=runCatching {service.windows.any {it.id==event.windowId && it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}}.getOrDefault(false)
        if(overlayWindow)return
        reconcile()
        if(banner==null && event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(retry);handler.postDelayed(retry,400)
        }
    }
    private fun currentPackage():String? {
        val focused=service.windows.firstOrNull {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused}
        return (focused?.root ?: service.rootInActiveWindow)?.packageName?.toString()
    }
    private fun reconcile() {
        val entry=pending ?: run {hide();return}
        val target=LocalRules.connectors[entry.network]?.packageName
        if(target==null || runCatching {currentPackage()}.getOrNull()!=target) {hide();return}
        if(banner!=null)return
        val row=LinearLayout(service).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(4),dp(6),dp(4));setBackgroundColor(0xFF173D35.toInt())
        }
        row.addView(TextView(service).apply {
            text="@${entry.handle}";setTextColor(Color.WHITE);textSize=14f
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        row.addView(Button(service).apply {
            text="Abonné.e !";isAllCaps=false;setOnClickListener {confirm(entry)}
        })
        row.addView(Button(service).apply {
            text="×";contentDescription="Fermer sans confirmer";isAllCaps=false
            setOnClickListener {runCatching {ManualFollowStore.cancel(service,entry)};clear()}
        })
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.BOTTOM;lp.y=dp(48)
        runCatching {wm.addView(row,lp);banner=row}.onFailure {
            DebugLog.add(service,"manual_banner_error",org.json.JSONObject().put("type",it.javaClass.simpleName))
        }
    }
    private fun confirm(entry:Entry) {
        val confirmed=runCatching {ManualFollowStore.confirm(service,entry)}.getOrDefault(false)
        clear()
        if(!confirmed) {Toast.makeText(service,"Confirmation expirée : rouvrez le profil dans JANÔ.",Toast.LENGTH_LONG).show();return}
        val intent=Intent(service,MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_LIST)
            .putExtra(MainActivity.EXTRA_NETWORK,entry.network)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The user just tapped a visible overlay; explicitly opt in on recent Android versions.
        val launched=runCatching {
            val creator=if(Build.VERSION.SDK_INT>=35) ActivityOptions.makeBasic().apply {
                pendingIntentCreatorBackgroundActivityStartMode=ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle() else null
            val pending=PendingIntent.getActivity(service,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,creator)
            val sender=if(Build.VERSION.SDK_INT>=34) ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode=ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle() else null
            pending.send(service,0,null,null,null,null,sender)
        }.isSuccess
        if(!launched) runCatching {service.startActivity(intent)}
        Toast.makeText(service,"Abonnement enregistré dans JANÔ.",Toast.LENGTH_SHORT).show()
    }
    private fun hide() {banner?.let {runCatching {wm.removeView(it)}};banner=null}
    private fun dp(x:Int)=(x*service.resources.displayMetrics.density).toInt()
}
