package fr.listes.beta

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Pure state transitions: opening a profile never marks it as followed. */
object ManualFollowState {
    private const val FOLLOWED="manual_followed"
    private const val PENDING="manual_pending"
    private const val MIGRATED="manual_followed_migrated_v1"
    private const val MAX_AGE=2L*60*60*1000
    fun key(e:Entry)="${e.network}:${e.handle.trim().removePrefix("@").lowercase(Locale.ROOT)}"
    fun followed(state:JSONObject):Set<String> {
        val data=state.optJSONObject(FOLLOWED) ?: return emptySet()
        return data.keys().asSequence().filter { data.optBoolean(it,false) }.toSet()
    }
    fun migrate(state:JSONObject) {
        if(state.optBoolean(MIGRATED,false)) return
        val saved=state.optJSONObject(FOLLOWED) ?: JSONObject()
        val rows=state.optJSONArray("rows") ?: JSONArray()
        for(i in 0 until rows.length()) runCatching {
            val row=ResultRow.parse(rows.getJSONObject(i))
            if(row.entry.action=="follow" && row.status in setOf("already_compliant","applied_verified")) saved.put(key(row.entry),true)
        }
        state.put(FOLLOWED,saved).put(MIGRATED,true)
    }
    fun begin(state:JSONObject,e:Entry,now:Long) {
        require(e.action=="follow" && e.network in networks && e.handle.isNotBlank())
        state.put(PENDING,JSONObject().put("entry",e.json()).put("opened_at",now))
    }
    fun pending(state:JSONObject,now:Long):Entry? {
        val p=state.optJSONObject(PENDING) ?: return null
        val age=now-p.optLong("opened_at",0)
        if(age<0 || age>MAX_AGE) return null
        return runCatching {Entry.parse(p.getJSONObject("entry"))}.getOrNull()?.takeIf {
            it.action=="follow" && it.network in networks && it.handle.isNotBlank()
        }
    }
    fun confirm(state:JSONObject,expected:String,now:Long):Entry? {
        val entry=pending(state,now) ?: return null
        if(key(entry)!=expected) return null
        val saved=state.optJSONObject(FOLLOWED) ?: JSONObject()
        saved.put(key(entry),true);state.put(FOLLOWED,saved);state.remove(PENDING)
        val old=state.optJSONArray("rows") ?: JSONArray()
        val rows=JSONArray()
        for(i in 0 until old.length()) {
            val r=old.optJSONObject(i) ?: continue
            val oldEntry=runCatching {Entry.parse(r.getJSONObject("entry"))}.getOrNull()
            if(oldEntry==null || key(oldEntry)!=key(entry)) rows.put(r)
        }
        rows.put(ResultRow(entry,"manual_confirmed","Abonnement confirmé manuellement",now).json())
        state.put("rows",rows)
        return entry
    }
    fun cancel(state:JSONObject,expected:String?) {
        if(expected==null || state.optJSONObject(PENDING)?.optJSONObject("entry")?.let {runCatching {key(Entry.parse(it))}.getOrNull()}==expected)
            state.remove(PENDING)
    }
    fun ordered(entries:List<Entry>,network:String,followed:Set<String>):List<Entry> =
        entries.filter {it.action=="follow" && it.network==network}.sortedBy {key(it) in followed}
}
