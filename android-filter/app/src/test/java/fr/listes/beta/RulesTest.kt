package fr.listes.beta

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class RulesTest {
    private fun e(id:String="UC"+"a".repeat(22),action:String="follow",verified:Boolean=true)=
        Entry("youtube",id,id,"Test","https://www.youtube.com/channel/$id",action,verified)
    @Test fun alreadyFollowedDoesNotWrite() { assertEquals("already_compliant",Rules.before(e(),true,true)) }
    @Test fun unknownStateDoesNotWrite() { assertEquals("unknown",Rules.before(e(),true,null)) }
    @Test fun missingIdentityExcluded() { assertEquals("unverified_identity",Rules.before(e("",verified=false),true,false)) }
    @Test fun missingSessionExcluded() { assertEquals("auth_required",Rules.before(e(),false,false)) }
    @Test fun unsupportedBlockExcluded() { assertEquals("unsupported",Rules.before(e(action="block"),true,false)) }
    @Test fun alreadyBlockedDoesNotWrite() {
        val t=Entry("twitch","123","test","Test","https://twitch.tv/test","block",true)
        assertEquals("unsupported",Rules.before(t,true,true))
    }
    @Test fun followOnlyWhenNecessary() { assertEquals("action_required",Rules.before(e(),true,false)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsConflict() { Rules.validate(listOf(e(),e(action="block"))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsInverseAction() { Rules.validate(listOf(e(action="unfollow"))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsTooMany() {
        Rules.validate((1..201).map { e("UC"+it.toString().padStart(22,'0')) })
    }
    @Test fun accepts200() { Rules.validate((1..200).map { e("UC"+it.toString().padStart(22,'0')) }) }
    @Test(expected=IllegalArgumentException::class) fun rejectsMaliciousDomain() {
        Rules.validate(listOf(e().copy(url="https://youtube.com.attacker.example/test")))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsCredentialsInUrl() {
        Rules.validate(listOf(e().copy(url="https://evil@youtube.com/test")))
    }
    private val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun signed(sequence:Long=4,expires:Long=5000,revoked:Boolean=false):String {
        val payload=JSONObject().put("schema_version",2).put("min_client_version",2).put("sequence",sequence)
            .put("revision","test").put("published_at_ms",0).put("expires_at_ms",expires).put("revoked",revoked)
            .put("black",org.json.JSONArray()).put("white",org.json.JSONArray(listOf(e().json()))).toString().toByteArray()
        val signer=Signature.getInstance("SHA256withRSA").apply { initSign(pair.private);update(payload) }
        return JSONObject().put("payload",Base64.getEncoder().encodeToString(payload))
            .put("signature",Base64.getEncoder().encodeToString(signer.sign())).toString()
    }
    @Test fun validSignatureAccepted() { assertEquals(1,SignedLists.decode(signed(),pair.public.encoded,4,1000).entries.size) }
    @Test(expected=IllegalArgumentException::class) fun rollbackRejected() { SignedLists.decode(signed(),pair.public.encoded,5,1000) }
    @Test(expected=IllegalArgumentException::class) fun expiryRejected() { SignedLists.decode(signed(),pair.public.encoded,0,6000) }
    @Test(expected=IllegalArgumentException::class) fun revokedRejected() { SignedLists.decode(signed(revoked=true),pair.public.encoded,0,1000) }
    @Test(expected=IllegalArgumentException::class) fun tamperingRejected() {
        val j=JSONObject(signed())
        j.put("payload",Base64.getEncoder().encodeToString("tampered".toByteArray()))
        SignedLists.decode(j.toString(),pair.public.encoded,0,1000)
    }
    @Test fun bundledListSignatureAndTikTokEntriesAreValid() {
        val root=java.io.File("src/main/assets")
        val snapshot=SignedLists.decode(java.io.File(root,"manifest.json").readText(),java.io.File(root,"list-public.der").readBytes(),0,1793397600000L)
        assertEquals(41,snapshot.entries.size)
        assertTrue(snapshot.entries.any {it.network=="tiktok" && it.action=="block"})
        assertTrue(snapshot.entries.any {it.network=="tiktok" && it.action=="follow"})
    }
}
