package fr.listes.beta

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FilterRuntime {
    val enabled=mutableStateOf(false)
    val status=mutableStateOf("Filtre en pause")
    val scans=mutableStateOf(0L)
    val masked=mutableStateOf(0)
    val lastMs=mutableStateOf(0L)
    var snapshot:Snapshot?=null
    var selected:Set<String> = emptySet()
    var consent=false
    fun initialize(context:Context) {
        val prefs=context.getSharedPreferences("filter",Context.MODE_PRIVATE)
        consent=prefs.getBoolean("consent",false)
        // One-time migration enables the requested automatic mode for existing users.
        if(!prefs.getBoolean("auto_ready_v1",false)) {
            prefs.edit().putBoolean("auto_ready_v1",true).apply {
                if(consent) putBoolean("enabled",true)
            }.apply()
        }
        enabled.value=consent && prefs.getBoolean("enabled",true)
        selected=prefs.getStringSet("networks",setOf("instagram","facebook","x","tiktok"))!!.toSet()
        if(snapshot==null) runCatching {
            val store=SecureStore(context).read()
            val raw=store.optString("snapshot").ifBlank { context.assets.open("manifest.json").bufferedReader().use { it.readText() } }
            snapshot=SignedLists.decode(raw,context.assets.open("list-public.der").use { it.readBytes() },store.optLong("sequence"))
        }
    }
    fun setEnabled(context:Context,value:Boolean) {
        enabled.value=value && consent
        context.getSharedPreferences("filter",Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled.value).apply()
        status.value=if(enabled.value) "En attente d’une application sélectionnée" else "Filtre en pause"
        DebugLog.add(context,"filter",JSONObject().put("enabled",enabled.value))
        LocalBridge.service?.refreshFilter()
        FilterSessionService.update(context)
    }
    fun accept(context:Context) {
        val firstConsent=!consent
        consent=true
        val edit=context.getSharedPreferences("filter",Context.MODE_PRIVATE).edit().putBoolean("consent",true)
        if(firstConsent) { enabled.value=true;edit.putBoolean("enabled",true) }
        edit.apply()
    }
    fun choose(context:Context,network:String,value:Boolean) {
        selected=if(value) selected+network else selected-network
        context.getSharedPreferences("filter",Context.MODE_PRIVATE).edit().putStringSet("networks",selected).apply()
        LocalBridge.service?.refreshFilter()
    }

}
object DebugLog {
    private const val LIMIT=256_000L
    @Synchronized fun add(context:Context,event:String,detail:JSONObject=JSONObject()) {
        // Diagnostics must never prevent filtering. No raw UI text, tokens or screenshots.
        runCatching {
            val f=File(context.filesDir,"filter-debug.jsonl")
            if(f.length()>LIMIT) {
                val rows=f.readLines().takeLast(300)
                f.writeText(rows.joinToString("\n",postfix="\n"))
            }
            f.appendText(JSONObject().put("at",System.currentTimeMillis()).put("event",event).put("data",detail).toString()+"\n")
        }
    }
    @Synchronized fun read(context:Context):JSONArray {
        val a=JSONArray();runCatching { File(context.filesDir,"filter-debug.jsonl").forEachLine { runCatching { a.put(JSONObject(it)) } } };return a
    }
    @Synchronized fun clear(context:Context) { runCatching { File(context.filesDir,"filter-debug.jsonl").delete() } }
}
