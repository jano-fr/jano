package fr.listes.beta

import java.text.Normalizer

/** Pure deterministic screen interpreter; never uses coordinates as click targets. */
data class LocalNode(val key:Int,val text:String,val actionable:Boolean,val header:Boolean=true)
data class LocalScreen(val packageName:String,val nodes:List<LocalNode>)
data class LocalPlan(val phase:String="profile",val identitySeen:Boolean=false,val writeSent:Boolean=false)
data class LocalDecision(val plan:LocalPlan,val click:Int?=null,val status:String?=null,val reason:String="",val reopen:Boolean=false)
data class LocalConnector(val network:String,val packageName:String,val follow:Set<String>,val following:Set<String>,val menu:Set<String>)

object LocalRules {
    fun norm(s:String)=Normalizer.normalize(s,Normalizer.Form.NFD).replace(Regex("\\p{M}"),"")
        .lowercase().replace('’','\'').replace(Regex("\\s+")," ").trim()
    private fun words(vararg s:String)=s.map(::norm).toSet()
    val connectors=listOf(
        LocalConnector("tiktok","com.zhiliaoapp.musically",words("Suivre","S'abonner","Follow"),words("Abonné","Abonné(e)","Following","Amis","Friends"),emptySet()),
        LocalConnector("youtube","com.google.android.youtube",words("S'abonner","Subscribe"),words("Abonné","Abonné(e)","Subscribed"),emptySet()),
        LocalConnector("twitch","tv.twitch.android.app",words("Suivre","Follow"),words("Suivi","Following"),emptySet()),
        LocalConnector("facebook","com.facebook.katana",words("Suivre","S'abonner","Follow"),words("Abonné(e)","Abonné","Abonnée","Suivi","Following"),words("Plus","Plus d'options","Plus d’options de profil","More","More options","Profile options")),
        LocalConnector("instagram","com.instagram.android",words("Suivre","S'abonner","Follow"),words("Abonné(e)","Abonné","Abonnée","Following"),words("Options","Plus d'options","More options","Options du profil")),
        LocalConnector("threads","com.instagram.barcelona",words("Suivre","S'abonner","Follow"),words("Suivi","Abonné(e)","Following"),words("Plus","Plus d'options","More","More options")),
        LocalConnector("x","com.twitter.android",words("Suivre","Follow"),words("Abonné","Abonné(e)","Suivi","Following"),words("Plus d'options","Plus","More options","More")),
        LocalConnector("linkedin","com.linkedin.android",words("Suivre","Follow"),words("Suivi","Abonné(e)","Following"),words("Plus","Plus d'options","More","More options")),
        LocalConnector("dailymotion","com.dailymotion.dailymotion",words("Suivre","S'abonner","Follow","Subscribe"),words("Abonné","Abonné(e)","Following","Subscribed"),words("Plus","Options","Plus d'options","More","More options"))
    ).associateBy { it.network }
    
    
    
    private val requested=words("Demandé","Demandé(e)","Demande envoyée","Requested","Request sent")
    private val guards=words("captcha","confirmez votre identité","confirm your identity","verify your identity","try again later",
        "réessayez plus tard","action bloquée","action blocked","unusual activity","activité inhabituelle","temporairement bloqué","temporarily blocked")
    private val login=words("Se connecter","Connexion","Log in","Sign in")
    private fun labelMatches(text:String,labels:Set<String>,e:Entry):Boolean {
        val t=norm(text);val h=norm(e.handle).removePrefix("@");val name=norm(e.label)
        return labels.any { l -> t==l || t=="$l @$h" || t=="$l $h" || t=="$l $name" }
    }
    private fun hasHandle(n:LocalNode,e:Entry):Boolean {
        val h=norm(e.handle).removePrefix("@");val t=norm(n.text)
        // An exact handle in the profile header; display-name-only matching is intentionally excluded.
        return n.header && (t==h || t=="@$h" || t==norm(e.url) || t=="${norm(e.label)} @$h")
    }
    fun step(e:Entry,s:LocalScreen,p:LocalPlan,write:Boolean):LocalDecision {
        val c=connectors[e.network] ?: return LocalDecision(p,status="unsupported")
        fun done(status:String,reason:String)=LocalDecision(p,status=status,reason=reason)
        if(s.packageName!=c.packageName) return done("wrong_app","L'application active a changé")
        if(s.nodes.any { n -> guards.any { norm(n.text).contains(it) } }) return done("rate_limited","Contrôle de sécurité ou restriction : arrêt")
        if(s.nodes.any { norm(it.text) in login } && !s.nodes.any { hasHandle(it,e) }) return done("auth_required","Connectez-vous dans l'application officielle")
        fun candidates(labels:Set<String>,header:Boolean=false)=s.nodes.filter { it.actionable && (!header || it.header) && labelMatches(it.text,labels,e) }.distinctBy { it.key }
        fun one(nodes:List<LocalNode>,next:String,writing:Boolean=false):LocalDecision {
            if(nodes.size!=1) return done("unknown","Bouton absent ou ambigu : aucun clic")
            return LocalDecision(p.copy(phase=next,identitySeen=true,writeSent=p.writeSent||writing),click=nodes.single().key)
        }
        if(s.nodes.any { norm(it.text) in words("Ce contenu n’est pas disponible actuellement","Cette page n’est pas disponible","This content isn't available right now","This page isn't available","Compte introuvable","Account not found") })
            return done("missing","Profil indisponible : aucune action")
        val identified=s.nodes.any { hasHandle(it,e) }
        val isFollow=e.action=="follow"
        if(e.action != "follow") return done("unsupported","Action inverse interdite")
        if(p.phase in setOf("profile","verify_follow","reopened")) {
            if(!identified) return LocalDecision(p,reason="Attente de l'identifiant du profil dans l'en-tête")
            val seen=p.copy(identitySeen=true)
            if(isFollow) {
                val yes=candidates(c.following,true);val no=candidates(c.follow,true)
                if(yes.isNotEmpty() && no.isNotEmpty()) return done("unknown","États d'abonnement contradictoires")
                if(yes.size==1) return LocalDecision(seen,status=if(p.writeSent) "applied_verified" else "already_compliant",reason="État d'abonnement visible")
                if(s.nodes.any { it.header && labelMatches(it.text,requested,e) }) return LocalDecision(seen,status="request_pending",reason="Demande envoyée ; abonnement pas encore accepté")
                if(p.phase=="verify_follow") return LocalDecision(seen,reason="Attente de la confirmation d'abonnement")
                if(no.size==1) return if(!write) LocalDecision(seen,status="action_required",reason="Abonnement nécessaire") else one(no,"verify_follow",true)
                if(no.size>1 || yes.size>1) return done("unknown","Plusieurs boutons d'abonnement")
                // LinkedIn can put Follow inside its profile menu.
                if(c.network=="linkedin") return one(candidates(c.menu,true),"follow_menu")
                return LocalDecision(seen,reason="Bouton d'abonnement non reconnu")
            }
        }
        if(!p.identitySeen) return done("unverified_identity","Identité non vérifiée")
        if(p.phase=="follow_menu") {
            if(candidates(c.following).size==1) return done("already_compliant","Abonnement déjà actif")
            val f=candidates(c.follow)
            return if(f.size==1 && !write) done("action_required","Abonnement nécessaire") else if(write) one(f,"verify_follow",true) else done("unknown","Menu non reconnu")
        }
        return done("unknown","Écran non reconnu")
    }
}
