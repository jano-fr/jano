package fr.listes.beta

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualFollowStateTest {
    private fun account(handle:String,network:String="instagram")=Entry(network,"",handle,handle,"https://instagram.com/$handle","follow",false)
    @Test fun openingDoesNotMarkAccount() {
        val a=account("test");val s=JSONObject()
        ManualFollowState.begin(s,a,1000)
        assertTrue(ManualFollowState.followed(s).isEmpty())
        assertEquals(a,ManualFollowState.pending(s,1100))
    }
    @Test fun confirmationMovesOnlySelectedAccountAndClearsPending() {
        val a=account("a");val b=account("b");val s=JSONObject()
        ManualFollowState.begin(s,a,1000)
        assertNull(ManualFollowState.confirm(s,ManualFollowState.key(b),1100))
        assertNotNull(ManualFollowState.confirm(s,ManualFollowState.key(a),1200))
        assertNull(ManualFollowState.pending(s,1200))
        assertEquals(listOf(b,a),ManualFollowState.ordered(listOf(a,b),"instagram",ManualFollowState.followed(s)))
        assertEquals("manual_confirmed",s.getJSONArray("rows").getJSONObject(0).getString("status"))
    }
    @Test fun preservesOtherNetworksAndStableOrder() {
        val a=account("a");val b=account("b");val c=account("c","facebook")
        assertEquals(listOf(b,a),ManualFollowState.ordered(listOf(a,c,b),"instagram",setOf(ManualFollowState.key(a))))
    }
    @Test fun expiredOrCancelledProfileCannotBeConfirmed() {
        val a=account("a");val s=JSONObject();ManualFollowState.begin(s,a,1000)
        assertNull(ManualFollowState.confirm(s,ManualFollowState.key(a),1000+2L*60*60*1000+1))
        ManualFollowState.cancel(s,ManualFollowState.key(a));assertNull(ManualFollowState.pending(s,1100))
    }
    @Test fun migratesOnlyVerifiedEarlierResults() {
        val a=account("a");val b=account("b");val s=JSONObject().put("rows",JSONArray()
            .put(ResultRow(a,"applied_verified").json()).put(ResultRow(b,"unknown").json()))
        ManualFollowState.migrate(s)
        assertEquals(setOf(ManualFollowState.key(a)),ManualFollowState.followed(s))
    }
}
