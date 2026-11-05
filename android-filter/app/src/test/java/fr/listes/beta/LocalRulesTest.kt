package fr.listes.beta

import org.junit.Assert.*
import org.junit.Test

class LocalRulesTest {
    private fun entry(n:String="instagram",action:String="follow")=Entry(n,"","tester","Test Person","https://${if(n=="x") "x.com" else "$n.com"}/tester",action,false)
    private fun screen(e:Entry,vararg texts:String)=LocalScreen(LocalRules.connectors.getValue(e.network).packageName,
        listOf(LocalNode(0,"@tester",false))+texts.mapIndexed { i,t->LocalNode(i+1,t,true) })
    @Test fun sixConnectorsPresent() { assertEquals(setOf("facebook","instagram","threads","x","linkedin","dailymotion","tiktok","youtube","twitch"),LocalRules.connectors.keys) }
    @Test fun followWorksForAllSix() { LocalRules.connectors.keys.forEach { n ->
        val e=entry(n);val d=LocalRules.step(e,screen(e,LocalRules.connectors.getValue(n).follow.first()),LocalPlan(),true)
        assertEquals("$n",1,d.click);assertEquals("verify_follow",d.plan.phase);assertTrue(d.plan.writeSent)
    } }
    @Test fun alreadyFollowingNeverClicks() { LocalRules.connectors.values.forEach { c ->
        val e=entry(c.network);val d=LocalRules.step(e,screen(e,c.following.first()),LocalPlan(),true)
        assertEquals("already_compliant",d.status);assertNull(d.click)
    } }
    @Test fun followIsVerifiedOnlyAfterTransition() {
        val e=entry();val p=LocalRules.step(e,screen(e,"Suivre"),LocalPlan(),true).plan
        assertNull(LocalRules.step(e,screen(e,"Suivre"),p,true).status)
        assertEquals("applied_verified",LocalRules.step(e,screen(e,"Abonné(e)"),p,true).status)
    }
    @Test fun ambiguousFollowButtonsNeverClick() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Suivre","Suivre"),LocalPlan(),true)
        assertEquals("unknown",d.status);assertNull(d.click)
    }
    @Test fun contradictoryRelationNeverClicks() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Suivre","Following"),LocalPlan(),true)
        assertEquals("unknown",d.status);assertNull(d.click)
    }
    @Test fun followDiagnosticNeverClicksRelation() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Suivre"),LocalPlan(),false)
        assertEquals("action_required",d.status);assertNull(d.click)
    }
    @Test fun wrongApplicationNeverClicks() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Suivre").copy(packageName="evil.app"),LocalPlan(),true)
        assertEquals("wrong_app",d.status);assertNull(d.click)
    }
    @Test fun homonymIsNotIdentity() {
        val e=entry();val s=LocalScreen("com.instagram.android",listOf(LocalNode(0,"Test Person",false),LocalNode(1,"Suivre",true)))
        val d=LocalRules.step(e,s,LocalPlan(),true);assertNull(d.click)
    }
    @Test fun handleInCommentIsNotProfileIdentity() {
        val e=entry();val s=LocalScreen("com.instagram.android",listOf(LocalNode(0,"@tester",false,false),LocalNode(1,"Suivre",true)))
        assertNull(LocalRules.step(e,s,LocalPlan(),true).click)
    }
    @Test fun requestPendingIsNotConfirmedSubscription() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Demandé"),LocalPlan("verify_follow",true,true),true)
        assertEquals("request_pending",d.status);assertNull(d.click)
    }
    @Test fun restrictionStopsAllClicks() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Suivre","Réessayez plus tard"),LocalPlan(),true)
        assertEquals("rate_limited",d.status);assertNull(d.click)
    }
    @Test fun inverseActionsRejected() {
        val e=entry(action="unfollow");assertEquals("unsupported",LocalRules.step(e,screen(e,"Se désabonner"),LocalPlan(),true).status)
    }
    @Test fun suggestedFollowButtonBelowHeaderIgnored() {
        val e=entry();val s=LocalScreen("com.instagram.android",listOf(LocalNode(0,"@tester",false),LocalNode(1,"Suivre",true,false)))
        assertNull(LocalRules.step(e,s,LocalPlan(),true).click)
    }
    @Test fun namedXButtonsSupported() {
        val e=entry("x");val d=LocalRules.step(e,screen(e,"Follow @tester"),LocalPlan(),true)
        assertNotNull(d.click)
        assertEquals("applied_verified",LocalRules.step(e,screen(e,"Following @tester"),d.plan,true).status)
    }
    @Test fun redlistCannotTriggerAnyRelationshipAction() {
        for(network in LocalRules.connectors.keys) for(phase in listOf("profile","block_menu","confirm_block","verify_block","follow_menu")) {
            val e=entry(network,"block")
            val d=LocalRules.step(e,screen(e,"Bloquer","Suivre"),LocalPlan(phase,true,true),true)
            assertEquals("unsupported",d.status);assertNull(d.click)
        }
    }
    @Test fun missingProfileIsSkippableWithoutClick() {
        val e=entry();val d=LocalRules.step(e,screen(e,"Ce contenu n’est pas disponible actuellement"),LocalPlan(),true)
        assertEquals("missing",d.status);assertNull(d.click)
    }
}
