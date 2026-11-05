package fr.listes.beta

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONObject

data class AccessibilityHealth(
    val registered:Boolean=false,
    val enabled:Boolean=false,
    val connected:Boolean=false,
    val readable:Boolean=false,
    val inspected:Boolean=false
) {
    val ready get()=inspected && registered && enabled && connected && readable
    val code get()=when {
        !inspected->"inspection_unavailable"
        !registered->"service_not_found"
        !enabled->"permission_required"
        !connected->"waiting_for_service"
        !readable->"read_capability_missing"
        else->"ready"
    }
    val label get()=when(code) {
        "service_not_found"->"Service non détecté par Android"
        "permission_required"->"Autorisation Android non activée"
        "waiting_for_service"->"Autorisé dans Android, mais service non connecté"
        "read_capability_missing"->"Service connecté, capacité de lecture absente"
        "ready"->"Autorisation active et service connecté"
        else->"État Android non vérifiable"
    }
    fun json()=JSONObject().put("status",code).put("registered",registered).put("enabled",enabled)
        .put("connected",connected).put("readable",readable).put("inspected",inspected)
}

data class SettingsRoute(val action:String,val component:String?=null,val packageUri:String?=null)
object SetupRoutes {
    const val DETAILS="android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    const val COMPONENT="android.intent.extra.COMPONENT_NAME"
    fun service(component:String)=listOf(SettingsRoute(DETAILS,component),SettingsRoute("android.settings.ACCESSIBILITY_SETTINGS"))
    fun app(packageName:String)=listOf(SettingsRoute("android.settings.APPLICATION_DETAILS_SETTINGS",packageUri="package:$packageName"))
    /** Successful launch is navigation only, never evidence of permission. */
    fun launch(routes:List<SettingsRoute>,start:(SettingsRoute)->Boolean):Boolean=routes.any { route ->
        try { start(route) } catch(_:Exception) { false }
    }
}
object AccessibilitySetup {
    fun component(context:Context)=ComponentName(context,LocalService::class.java)
    fun read(context:Context):AccessibilityHealth {
        return try {
            val manager=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val own=component(context)
            fun same(info:AccessibilityServiceInfo):Boolean {
                val s=info.resolveInfo?.serviceInfo ?: return false
                return ComponentName(s.packageName,s.name)==own
            }
            val installed=manager.installedAccessibilityServiceList.firstOrNull(::same)
            val bound=LocalBridge.connected.value && LocalBridge.service!=null
            val secure=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.mapNotNull { ComponentName.unflattenFromString(it) }?.any { it==own } == true
            val enabled=bound || secure || manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any(::same)
            val registered=installed!=null || bound || runCatching { context.packageManager.getServiceInfo(own,0) }.isSuccess
            val capabilities=installed?.capabilities ?: LocalBridge.service?.serviceInfo?.capabilities ?: 0
            AccessibilityHealth(registered,enabled,bound,
                capabilities.and(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)!=0,true)
        } catch(_:Exception) { AccessibilityHealth(connected=LocalBridge.service!=null) }
    }
    private fun open(context:Context,routes:List<SettingsRoute>)=SetupRoutes.launch(routes) { r ->
        val intent=Intent(r.action)
        r.component?.let { intent.putExtra(SetupRoutes.COMPONENT,it) }
        r.packageUri?.let { intent.data=Uri.parse(it) }
        if(context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }
    fun openService(context:Context)=open(context,SetupRoutes.service(component(context).flattenToString()))
    fun openServicesList(context:Context)=open(context,listOf(SettingsRoute(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
    fun openAppInfo(context:Context)=open(context,SetupRoutes.app(context.packageName))
    fun diagnostic(context:Context):JSONObject=read(context).json()
        .put("android_sdk",Build.VERSION.SDK_INT).put("android_release",Build.VERSION.RELEASE)
        .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
        .put("package",context.packageName).put("component",component(context).flattenToString())
}
