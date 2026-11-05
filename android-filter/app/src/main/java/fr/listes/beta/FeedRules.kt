package fr.listes.beta

/** Screen data stays in memory. Only role identifiers, counts and timings may be logged. */
data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    val width get()=right-left
    val height get()=bottom-top
    fun clip(other:Box):Box? = Box(maxOf(left,other.left),maxOf(top,other.top),minOf(right,other.right),minOf(bottom,other.bottom)).takeIf { it.width>0 && it.height>0 }
}
data class FeedNode(val index:Int,val parent:Int,val resource:String,val text:String,val description:String,
    val bounds:Box,val clickable:Boolean=false,val scrollable:Boolean=false)
data class FeedMatch(val entry:Entry,val bounds:Box,val scrollIndex:Int?, val cardIndex:Int = -1, val authorIndex:Int = -1, val footerIndex:Int? = null, val segmentIndices:List<Int> = emptyList())
object FeedRules {
    val packages=mapOf("com.facebook.katana" to "facebook", "com.instagram.android" to "instagram",
        "com.instagram.barcelona" to "threads", "com.twitter.android" to "x", "com.linkedin.android" to "linkedin",
        "com.google.android.youtube" to "youtube", "com.dailymotion.dailymotion" to "dailymotion",
        "tv.twitch.android.app" to "twitch", "com.zhiliaoapp.musically" to "tiktok", "com.ss.android.ugc.trill" to "tiktok")
    // These are candidate native view IDs, not a claim of compatibility with every app version.
    private val authorIds=setOf("row_feed_photo_profile_name","row_feed_profile_header_textview_username","username","user_name",
        "screen_name","author_name","author_username","channel_name","owner_name","video_author","tweet_user_name","demo_author")
    private val instagramMediaIds=setOf("video_container","media_group","row_feed_photo_imageview","carousel_image_media_group","carousel_image")
    private val cardIds=setOf("row_feed_photo","feed_item","feed_card","post_container","tweet","tweet_container","video_item","reel_container","demo_card")
    private val actionIds=setOf("row_feed_button_like","like","like_button","comment_button","reply","retweet","share_button","demo_like")
    private val forbidden=Regex("comment|reply|quote|mention|suggest|recommend|message|chat|search")
    fun id(resource:String)=resource.substringAfterLast('/').lowercase()
    private val profileLabel=Regex("^(?:profil de|profile of|photo de profil de|profile picture of) (@?[A-Za-z0-9_.-]{1,128})$",RegexOption.IGNORE_CASE)
    private val facebookAuthor=Regex("^Plus d[’\']options pour la publication de (.+)$")
    fun authorRole(n:FeedNode)=facebookAuthor.matches(n.description.trim()) || id(n.resource) in authorIds || profileLabel.matches(n.description.trim())
    fun authorMatches(n:FeedNode,e:Entry)=(e.network=="facebook" && facebookAuthor.matchEntire(n.description.trim())?.groupValues?.get(1)?.let { LocalRules.norm(it)==LocalRules.norm(e.label) }==true) || identity(n.text,e) || identity(n.description,e) ||
        profileLabel.matchEntire(n.description.trim())?.groupValues?.get(1)?.let {identity(it,e)}==true
    private fun socialAction(n:FeedNode):String? {
        val t=LocalRules.norm(n.text.ifBlank {n.description})
        return when(t) {
            "j'aime","like","aimer"->"like"
            "partager","share"->"share"
            "commenter","comment","commentaires","comments"->"comment"
            else->null
        }
    }
    fun identity(text:String,e:Entry):Boolean {
        val t=text.trim().lowercase();val h=e.handle.trim().removePrefix("@").lowercase()
        return h.isNotEmpty() && (t==h || t=="@$h" || t==e.url.lowercase().trimEnd('/') || t==e.url.lowercase().trimEnd('/')+"/")
    }
    fun detect(network:String,nodes:List<FeedNode>,entries:List<Entry>,viewport:Box):List<FeedMatch> {
        val byId=nodes.associateBy { it.index }
        fun ancestors(n:FeedNode):List<FeedNode> {
            val out=mutableListOf<FeedNode>();var p=byId[n.parent]
            repeat(24) { val current=p ?: return out;out+=current;p=byId[current.parent] };return out
        }
        val ancestorIndex=nodes.associate { it.index to ancestors(it).map { p->p.index }.toSet() }
        val descendantsIndex=mutableMapOf<Int,MutableList<FeedNode>>()
        for(n in nodes) for(parent in ancestorIndex.getValue(n.index)) descendantsIndex.getOrPut(parent){mutableListOf()}.add(n)
        fun descendants(card:FeedNode)=listOf(card)+descendantsIndex[card.index].orEmpty()
        val authors=nodes.filter(::authorRole)
        fun instagramCard(card:FeedNode):Boolean {
            if(network!="instagram" || byId[card.parent]?.scrollable!=true || id(byId[card.parent]?.resource.orEmpty())!="list") return false
            val inside=descendants(card)
            return inside.any { id(it.resource)=="row_feed_profile_header" } &&
                inside.any { id(it.resource) in instagramMediaIds }
        }
        val result=mutableListOf<FeedMatch>()
        for(author in authors) {
            val entry=entries.singleOrNull { it.network==network && authorMatches(author,it) } ?: continue
            val parents=ancestors(author)
            if(parents.any { forbidden.containsMatchIn(id(it.resource)) }) continue
            if(network=="facebook" && facebookAuthor.matches(author.description.trim())) {
                val card=parents.firstOrNull { candidate ->
                    !candidate.scrollable && candidate.bounds.width>=viewport.width/2 &&
                    descendants(candidate).any { it.clickable && LocalRules.norm(it.text)==LocalRules.norm(entry.label) && it.bounds.bottom<=author.bounds.bottom+100 } &&
                    descendants(candidate).any { it.description.startsWith("Bouton Partager.") } &&
                    descendants(candidate).any { LocalRules.norm(it.description)=="commenter" } &&
                    descendants(candidate).any { it.description.startsWith("Bouton J’aime.") } &&
                    descendants(candidate).filter { facebookAuthor.matches(it.description.trim()) }.all { authorMatches(it,entry) }
                }
                if(card!=null) card.bounds.clip(viewport)?.let { bounds ->
                    result+=FeedMatch(entry,bounds,ancestors(card).firstOrNull { it.scrollable }?.index,card.index,author.index)
                }
                continue
            }
            // Instagram photos expose header, media and action bar as separate list items.
            if(network=="instagram") {
                val headerItem=parents.firstOrNull { byId[it.parent]?.scrollable==true && id(byId[it.parent]?.resource.orEmpty())=="list" }
                if(headerItem!=null && descendants(headerItem).any { id(it.resource)=="row_feed_profile_header" }) {
                    val siblings=nodes.filter { it.parent==headerItem.parent }
                    val position=siblings.indexOfFirst { it.index==headerItem.index }
                    val media=siblings.getOrNull(position+1)
                    val mediaInside=media?.let(::descendants).orEmpty()
                    val separate=media!=null && kotlin.math.abs(media.bounds.top-headerItem.bounds.bottom)<=4 &&
                        mediaInside.any { id(it.resource) in instagramMediaIds } &&
                        mediaInside.none(::authorRole) &&
                        descendants(headerItem).filter(::authorRole).all { authorMatches(it,entry) }
                    if(separate) {
                        val footer=siblings.getOrNull(position+2)?.takeIf {
                            kotlin.math.abs(it.bounds.top-media!!.bounds.bottom)<=4 &&
                                descendants(it).any { n->id(n.resource)=="row_feed_view_group_buttons" } &&
                                descendants(it).none(::authorRole)
                        }
                        val area=Box(headerItem.bounds.left,headerItem.bounds.top,headerItem.bounds.right,footer?.bounds?.bottom ?: media!!.bounds.bottom)
                        val bounds=area.clip(viewport)
                        if(bounds!=null && bounds.width>=viewport.width/2 && bounds.height>=48)
                            result+=FeedMatch(entry,bounds,headerItem.parent,media!!.index,author.index,
                                segmentIndices=listOfNotNull(headerItem.index,footer?.index))
                        continue
                    }
                }
            }
            val card=parents.firstOrNull { candidate ->
                !candidate.scrollable && candidate.bounds.width>=viewport.width/2 &&
                    (id(candidate.resource) in cardIds || instagramCard(candidate) ||
                    (candidate.parent!=-1 && candidate.bounds.height<viewport.height*0.92 &&
                    descendants(candidate).mapNotNull(::socialAction).toSet().containsAll(setOf("like","share","comment"))))
            } ?: continue
            val inside=descendants(card)
            if(!instagramCard(card) && inside.none { id(it.resource) in actionIds } && !inside.mapNotNull(::socialAction).toSet().containsAll(setOf("like","share","comment"))) continue
            // A grouped feed or quoted author must never mask unrelated posts.
            if(inside.filter(::authorRole).any { !authorMatches(it,entry) }) continue
            val footer=if(instagramCard(card)) nodes.singleOrNull {
                it.parent==card.parent && id(it.resource)=="row_feed_view_group_buttons" &&
                    kotlin.math.abs(it.bounds.top-card.bounds.bottom)<=4
            } else null
            val area=if(footer!=null) Box(card.bounds.left,card.bounds.top,card.bounds.right,maxOf(card.bounds.bottom,footer.bounds.bottom)) else card.bounds
            val bounds=area.clip(viewport) ?: continue
            if(bounds.width<viewport.width/2 || bounds.height<48) continue
            result+=FeedMatch(entry,bounds,parents.firstOrNull { it.scrollable }?.index,card.index,author.index,footer?.index)
        }
        return result.distinctBy { it.bounds }.take(6)
    }
}
