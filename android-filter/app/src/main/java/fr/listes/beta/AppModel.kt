package fr.listes.beta

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AppModel(app:Application):AndroidViewModel(app) {
    private val store=SecureStore(app)
    private val key by lazy {app.assets.open("list-public.der").use {it.readBytes()}}
    var snapshot by mutableStateOf<Snapshot?>(null)
    var selected by mutableStateOf(setOf<String>())
    var message by mutableStateOf("")
    var page by mutableIntStateOf(0)
    var listNetwork by mutableStateOf(app.getSharedPreferences("filter",Context.MODE_PRIVATE).getString("list_network","").orEmpty())
    var followed by mutableStateOf(setOf<String>())
    var pendingPrompt by mutableStateOf<Entry?>(null)
    var busy by mutableStateOf(false)
    var rows by mutableStateOf(listOf<ResultRow>())
    var accessibility by mutableStateOf(AccessibilityHealth())
    var help by mutableStateOf(false)
    var probe by mutableStateOf("")
    private var sequence=0L
    private var raw=""
    init {
        FilterRuntime.initialize(app);selected=FilterRuntime.selected
        try {
            ManualFollowStore.migrate(app)
            val data=store.read();sequence=data.optLong("sequence")
            raw=data.optString("snapshot").ifBlank {app.assets.open("manifest.json").bufferedReader().use {it.readText()}}
            snapshot=SignedLists.decode(raw,key,sequence);sequence=snapshot!!.sequence;FilterRuntime.snapshot=snapshot
            readLocalState(data)
        } catch(_:Exception) {message="Listes absentes ou expirées : synchronisez avant de filtrer."}
    }
    private fun readLocalState(data:JSONObject) {
        followed=ManualFollowState.followed(data)
        data.optJSONArray("rows")?.let { a->rows=(0 until a.length()).mapNotNull {i->runCatching {ResultRow.parse(a.getJSONObject(i))}.getOrNull()}.filter {it.entry.action=="follow"} }
            ?: run {rows=emptyList()}
    }
    fun refresh() {
        accessibility=AccessibilitySetup.read(getApplication())
        val latest=FilterRuntime.snapshot
        if(latest!=null && latest !== snapshot) runCatching {
            val state=store.read();val newRaw=state.optString("snapshot")
            if(newRaw.isNotBlank()) {raw=newRaw;sequence=latest.sequence;snapshot=latest;message="Listes actualisées."}
        }
    }
    fun resumed() {
        refresh()
        runCatching {val data=store.read();readLocalState(data);pendingPrompt=ManualFollowState.pending(data,System.currentTimeMillis())}
    }
    fun showList(network:String?) {
        page=1
        if(!network.isNullOrBlank()) chooseListNetwork(network)
    }
    fun chooseListNetwork(network:String) {
        listNetwork=network
        getApplication<Application>().getSharedPreferences("filter",Context.MODE_PRIVATE).edit().putString("list_network",network).apply()
    }
    fun beginManual(e:Entry):Boolean {
        val live=try {SignedLists.decode(raw,key,sequence)} catch(_:Exception) {message="Liste expirée ou invalide : actualisez-la.";return false}
        if(e.action!="follow" || e !in live.entries) {message="Compte indisponible dans la liste actuelle.";return false}
        return runCatching {
            ManualFollowStore.begin(getApplication(),e)
            pendingPrompt=null
            LocalBridge.service?.prepareManual(e)
            true
        }.getOrElse {message="Impossible de préparer le profil.";false}
    }
    fun cancelManual(e:Entry?=null) {
        runCatching {ManualFollowStore.cancel(getApplication(),e)}
        LocalBridge.service?.cancelManual(e)
        pendingPrompt=null
    }
    fun confirmManual(e:Entry) {
        val confirmed=runCatching {ManualFollowStore.confirm(getApplication(),e)}.getOrDefault(false)
        LocalBridge.service?.clearManual()
        pendingPrompt=null
        if(confirmed) {showList(e.network);runCatching {readLocalState(store.read())};message="Abonnement confirmé."}
        else message="La confirmation a expiré : rouvrez le profil."
    }
    fun choose(network:String,value:Boolean) {FilterRuntime.choose(getApplication(),network,value);selected=FilterRuntime.selected}
    fun accept(context:Context) {FilterRuntime.accept(context);AccessibilitySetup.openService(context)}
    fun testService() {refresh();probe=LocalBridge.service?.probeOwnWindow() ?: accessibility.label}
    fun filter(value:Boolean) {
        refresh()
        if(value && (!accessibility.ready || !FilterRuntime.consent)) {message="Activez et vérifiez d’abord l’autorisation Android.";return}
        if(value && (snapshot==null || snapshot!!.expires<=System.currentTimeMillis())) {message="Synchronisez les listes avant de démarrer.";return}
        FilterRuntime.setEnabled(getApplication(),value)
    }
    fun sync() {
        if(busy)return
        busy=true
        viewModelScope.launch {
            val begin=SystemClock.elapsedRealtime()
            try {
                val downloaded=Http.signed("https://vps-b23d685a.vps.ovh.net/social-listes/web-beta/manifest.json")
                val fresh=SignedLists.decode(downloaded,key,sequence)
                store.update {data->
                    require(fresh.sequence>=data.optLong("sequence")) {"Version ancienne refusée"}
                    data.put("snapshot",downloaded).put("sequence",fresh.sequence)
                }
                raw=downloaded;snapshot=fresh;sequence=fresh.sequence;FilterRuntime.snapshot=fresh
                message="Listes actualisées."
                LocalBridge.service?.refreshFilter()
                DebugLog.add(getApplication(),"sync",JSONObject().put("duration_ms",SystemClock.elapsedRealtime()-begin).put("revision",fresh.revision))
            } catch(_:Exception) {message="Synchronisation impossible. La dernière liste valide est conservée.";DebugLog.add(getApplication(),"sync_failed")}
            finally {busy=false}
        }
    }
    fun clearJournal() {
        DebugLog.clear(getApplication());rows=emptyList()
        runCatching {store.update {it.put("rows",JSONArray())}}
        message="Journal effacé."
    }
    fun report():String=JSONObject().put("version","0.7.0-beta.1").put("exported_at",System.currentTimeMillis())
        .put("accessibility",AccessibilitySetup.diagnostic(getApplication())).put("revision",snapshot?.revision)
        .put("selected",JSONArray(selected.toList())).put("filter_enabled",FilterRuntime.enabled.value)
        .put("scans",FilterRuntime.scans.value).put("last_scan_ms",FilterRuntime.lastMs.value)
        .put("results",JSONArray(rows.map {it.json()})).put("events",DebugLog.read(getApplication())).toString(2)
}
