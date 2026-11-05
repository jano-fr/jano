package fr.listes.beta

import android.content.Context
import org.json.JSONObject

/** Uses the existing encrypted store for local confirmations and the pending profile. */
object ManualFollowStore {
    fun migrate(context:Context) { SecureStore(context).update {ManualFollowState.migrate(it)} }
    fun followed(context:Context):Set<String> = ManualFollowState.followed(SecureStore(context).read())
    fun pending(context:Context):Entry? = ManualFollowState.pending(SecureStore(context).read(),System.currentTimeMillis())
    fun begin(context:Context,e:Entry) { SecureStore(context).update {ManualFollowState.begin(it,e,System.currentTimeMillis())} }
    fun confirm(context:Context,e:Entry):Boolean {
        var confirmed:Entry?=null
        SecureStore(context).update {confirmed=ManualFollowState.confirm(it,ManualFollowState.key(e),System.currentTimeMillis())}
        if(confirmed!=null) DebugLog.add(context,"manual_follow_confirmed",JSONObject().put("network",e.network).put("handle",e.handle))
        return confirmed!=null
    }
    fun cancel(context:Context,e:Entry?=null) { SecureStore(context).update {ManualFollowState.cancel(it,e?.let(ManualFollowState::key))} }
}
