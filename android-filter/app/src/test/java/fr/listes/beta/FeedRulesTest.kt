package fr.listes.beta
import org.junit.Assert.*
import org.junit.Test

class FeedRulesTest {
    private val viewport=Box(0,0,1000,1800)
    private val red=Entry("instagram","","alice","Alice","https://www.instagram.com/alice/","block",false)
    private fun tree(handle:String="@alice")=listOf(
        FeedNode(0,-1,"feed","","",viewport,scrollable=true),
        FeedNode(1,0,"app:id/row_feed_photo","","",Box(0,100,1000,900)),
        FeedNode(2,1,"app:id/row_feed_photo_profile_name",handle,"",Box(0,100,300,180),true),
        FeedNode(3,1,"app:id/row_feed_button_like","Like","",Box(0,800,100,900),true))
    @Test fun redAuthorMasksWholeCardAndKeepsScrollTarget() {
        val m=FeedRules.detect("instagram",tree(),listOf(red),viewport).single()
        assertEquals(Box(0,100,1000,900),m.bounds);assertEquals(0,m.scrollIndex);assertEquals("block",m.entry.action)
    }
    @Test fun greenAuthorIsDistinguished() {assertEquals("follow",FeedRules.detect("instagram",tree(),listOf(red.copy(action="follow")),viewport).single().entry.action)}
    @Test fun mentionInBodyDoesNotMatch() {
        val nodes=tree("@bob")+FeedNode(4,1,"body","@alice","",Box(0,200,900,700))
        assertTrue(FeedRules.detect("instagram",nodes,listOf(red),viewport).isEmpty())
    }
    @Test fun substringAndHomonymRejected() {for(h in listOf("Alice Smith","@alice2","@alice hello","alicé")) assertTrue(FeedRules.detect("instagram",tree(h),listOf(red),viewport).isEmpty())}
    @Test fun wrongNetworkRejected() {assertTrue(FeedRules.detect("facebook",tree(),listOf(red),viewport).isEmpty())}
    @Test fun missingCardRoleRejected() {assertTrue(FeedRules.detect("instagram",tree().map {if(it.index==1)it.copy(resource="unknown") else it},listOf(red),viewport).isEmpty())}
    @Test fun missingActionsRejected() {assertTrue(FeedRules.detect("instagram",tree().dropLast(1),listOf(red),viewport).isEmpty())}
    @Test fun commentAncestorRejected() {assertTrue(FeedRules.detect("instagram",tree().map {if(it.index==0)it.copy(resource="comment_list") else it},listOf(red),viewport).isEmpty())}
    @Test fun multipleAuthorsRejected() {
        val n=tree()+FeedNode(4,1,"username","@bob","",Box(0,300,300,400))
        assertTrue(FeedRules.detect("instagram",n,listOf(red),viewport).isEmpty())
    }
    @Test fun recycledAuthorRemovesMatch() {assertEquals(1,FeedRules.detect("instagram",tree(),listOf(red),viewport).size);assertTrue(FeedRules.detect("instagram",tree("@bob"),listOf(red),viewport).isEmpty())}
    @Test fun boundsClippedToWindow() {assertEquals(Box(0,300,1000,900),FeedRules.detect("instagram",tree(),listOf(red),Box(0,300,1000,1200)).single().bounds)}
    @Test fun offScreenRejected() {assertTrue(FeedRules.detect("instagram",tree(),listOf(red),Box(0,1000,1000,1800)).isEmpty())}
    @Test fun feedRootCannotBeCard() {assertTrue(FeedRules.detect("instagram",tree().map {if(it.index==1)it.copy(scrollable=true) else it},listOf(red),viewport).isEmpty())}
    @Test fun sameNameDifferentCaseMatches() {assertEquals(1,FeedRules.detect("instagram",tree("@ALICE"),listOf(red),viewport).size)}
    @Test fun duplicateListIdentityRejected() {assertTrue(FeedRules.detect("instagram",tree(),listOf(red,red.copy(action="follow")),viewport).isEmpty())}
    @Test fun malformedCyclicTreeTerminates() {assertTrue(FeedRules.detect("instagram",tree().map {it.copy(parent=it.index)},listOf(red),viewport).isEmpty())}
    @Test fun semanticProfileLabelWithActionGroupMatches() {
        val n=tree().map { when(it.index) {
            1->it.copy(resource="native_container")
            2->it.copy(resource="",text="",description="Profile of @alice")
            3->it.copy(resource="",text="Like")
            else->it
        }}+listOf(FeedNode(4,1,"","Share","",Box(0,800,100,900)),FeedNode(5,1,"","Comment","",Box(0,800,100,900)))
        assertEquals(1,FeedRules.detect("instagram",n,listOf(red),viewport).size)
    }
    @Test fun freeTextIsNotSemanticProfileLabel() {
        val n=tree().map {if(it.index==2) it.copy(resource="",description="hello Profile of @alice",text="") else it}
        assertTrue(FeedRules.detect("instagram",n,listOf(red),viewport).isEmpty())
    }

    @Test fun anchorReferencesCardAndAuthorAcrossMovement() {
        val initial=FeedRules.detect("instagram",tree(),listOf(red),viewport).single()
        val shifted=tree().map { if(it.index==0) it else it.copy(bounds=Box(it.bounds.left,it.bounds.top+70,it.bounds.right,it.bounds.bottom+70)) }
        val moved=FeedRules.detect("instagram",shifted,listOf(red),viewport).single()
        assertEquals(1,initial.cardIndex);assertEquals(2,initial.authorIndex)
        assertEquals(initial.cardIndex,moved.cardIndex);assertEquals(initial.authorIndex,moved.authorIndex)
        assertEquals(initial.bounds.top+70,moved.bounds.top)
        assertTrue(FeedRules.detect("instagram",shifted.map { if(it.index==2)it.copy(text="@bob") else it },listOf(red),viewport).isEmpty())
    }

    private fun nativeInstagram()=listOf(
        FeedNode(0,-1,"android:id/list","","",Box(0,265,1080,2324),scrollable=true),
        FeedNode(1,0,"","","",Box(0,395,1080,2315)),
        FeedNode(2,1,"row_feed_profile_header","","",Box(0,395,1080,545)),
        FeedNode(3,2,"row_feed_photo_profile_name","alice  ","",Box(141,395,953,470)),
        FeedNode(4,1,"video_container","","",Box(0,395,1080,2315)),
        FeedNode(5,0,"row_feed_view_group_buttons","","",Box(0,2315,1080,2324)))
    @Test fun nativeInstagramAnonymousCardAndFooterMatch() {
        val m=FeedRules.detect("instagram",nativeInstagram(),listOf(red),Box(0,0,1080,2460)).single()
        assertEquals(1,m.cardIndex);assertEquals(5,m.footerIndex)
        assertEquals(Box(0,395,1080,2324),m.bounds)
    }
    @Test fun nativeCardRuleDoesNotGeneralizeToFacebook() {
        assertTrue(FeedRules.detect("facebook",nativeInstagram(),listOf(red.copy(network="facebook")),Box(0,0,1080,2460)).isEmpty())
    }
    @Test fun nativeCardWithoutMediaIsRejected() {
        assertTrue(FeedRules.detect("instagram",nativeInstagram().filter { it.index!=4 },listOf(red),Box(0,0,1080,2460)).isEmpty())
    }
    @Test fun nativeCardDoesNotAttachUnrelatedFooter() {
        val n=nativeInstagram().map {if(it.index==5)it.copy(bounds=Box(0,2400,1080,2450)) else it}
        assertNull(FeedRules.detect("instagram",n,listOf(red),Box(0,0,1080,2460)).single().footerIndex)
    }

    private fun splitPhoto()=listOf(
        FeedNode(0,-1,"android:id/list","","",Box(0,265,1080,2324),scrollable=true),
        FeedNode(1,0,"","","",Box(0,265,1080,415)),
        FeedNode(2,1,"row_feed_profile_header","","",Box(0,265,1080,415)),
        FeedNode(3,2,"row_feed_photo_profile_name","alice","",Box(141,265,953,415)),
        FeedNode(4,0,"","","",Box(0,415,1080,1839)),
        FeedNode(5,4,"row_feed_photo_imageview","","",Box(0,415,1080,1839)),
        FeedNode(6,0,"","","",Box(0,1839,1080,2061)),
        FeedNode(7,6,"row_feed_view_group_buttons","","",Box(0,1839,1080,1972)))
    @Test fun splitPhotoIncludesHeaderMediaAndActions() {
        val m=FeedRules.detect("instagram",splitPhoto(),listOf(red),Box(0,0,1080,2460)).single()
        assertEquals(Box(0,265,1080,2061),m.bounds);assertEquals(4,m.cardIndex);assertEquals(listOf(1,6),m.segmentIndices)
    }
    @Test fun splitPhotoCannotCrossAnotherAuthor() {
        val n=splitPhoto()+FeedNode(8,4,"username","@bob","",Box(0,500,300,550))
        assertTrue(FeedRules.detect("instagram",n,listOf(red),Box(0,0,1080,2460)).isEmpty())
    }
    @Test fun splitPhotoCannotCrossGap() {
        val n=splitPhoto().map {if(it.index==4)it.copy(bounds=Box(0,500,1080,1839)) else it}
        assertTrue(FeedRules.detect("instagram",n,listOf(red),Box(0,0,1080,2460)).isEmpty())
    }
    @Test fun splitCarouselMatchesBothLists() {
        for(mediaId in listOf("carousel_image_media_group","carousel_image")) {
            val nodes=splitPhoto().map {if(it.index==5) it.copy(resource=mediaId) else it}
            for(action in listOf("block","follow")) {
                val match=FeedRules.detect("instagram",nodes,listOf(red.copy(action=action)),Box(0,0,1080,2460)).single()
                assertEquals(action,match.entry.action)
                assertEquals(Box(0,265,1080,2061),match.bounds)
            }
        }
    }
    @Test fun combinedCarouselMatches() {
        val nodes=nativeInstagram().map {if(it.index==4) it.copy(resource="carousel_image_media_group") else it}
        assertEquals(1,FeedRules.detect("instagram",nodes,listOf(red),Box(0,0,1080,2460)).size)
    }
    @Test fun carouselWithDifferentAuthorIsRejected() {
        val nodes=splitPhoto().map {if(it.index==5) it.copy(resource="carousel_image") else it}+
            FeedNode(8,4,"username","@bob","",Box(0,500,300,550))
        assertTrue(FeedRules.detect("instagram",nodes,listOf(red),Box(0,0,1080,2460)).isEmpty())
    }
    private fun facebookPost()=listOf(
        FeedNode(0,-1,"","","",Box(0,200,1080,1930),scrollable=true),
        FeedNode(1,0,"","","",Box(0,232,1080,1491)),
        FeedNode(2,1,"","","Plus d’options pour la publication de Cyril Hanouna",Box(959,249,1080,369),true),
        FeedNode(3,1,"","Cyril Hanouna","",Box(173,266,552,343),true),
        FeedNode(4,1,"","","Bouton J’aime. Appuyer deux fois et maintenir pour réagir au commentaire.",Box(0,1352,235,1479),true),
        FeedNode(5,1,"","","Commenter",Box(235,1352,484,1479),true),
        FeedNode(6,1,"","","Bouton Partager. Appuyez deux fois pour partager la publication.",Box(484,1352,677,1479),true))
    @Test fun facebookSemanticPublicationMatches() {
        val e=red.copy(network="facebook",handle="cyrilhanounaofficiel",label="Cyril Hanouna")
        assertEquals(Box(0,232,1080,1491),FeedRules.detect("facebook",facebookPost(),listOf(e),Box(0,0,1080,2460)).single().bounds)
    }
    @Test fun facebookMentionWithoutPublicationAuthorRejected() {
        val e=red.copy(network="facebook",label="Cyril Hanouna")
        assertTrue(FeedRules.detect("facebook",facebookPost().filter {it.index!=2},listOf(e),Box(0,0,1080,2460)).isEmpty())
        assertTrue(FeedRules.detect("facebook",facebookPost().map {if(it.index==3)it.copy(text="Autre auteur") else it},listOf(e),Box(0,0,1080,2460)).isEmpty())
    }
}
