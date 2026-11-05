package fr.listes.beta

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

val networks = linkedMapOf("facebook" to "Facebook", "instagram" to "Instagram", "threads" to "Threads",
    "x" to "X", "linkedin" to "LinkedIn", "youtube" to "YouTube", "dailymotion" to "Dailymotion", "twitch" to "Twitch", "tiktok" to "TikTok")
private val domains = mapOf("facebook" to "facebook.com", "instagram" to "instagram.com", "threads" to "threads.com",
    "x" to "x.com", "linkedin" to "linkedin.com", "youtube" to "youtube.com", "dailymotion" to "dailymotion.com", "twitch" to "twitch.tv", "tiktok" to "tiktok.com")

data class Entry(val network: String, val id: String, val handle: String, val label: String,
    val url: String, val action: String, val verified: Boolean) {
    val key get() = "$network:${id.ifEmpty { handle.lowercase() }}"
    fun json() = JSONObject().put("network", network).put("platform_account_id", id).put("handle", handle)
        .put("display_name", label).put("profile_url", url).put("action", action).put("validation_status", if (verified) "verified" else "unverified")
    companion object {
        fun parse(j: JSONObject) = Entry(j.getString("network"), j.optString("platform_account_id"),
            j.getString("handle"), j.getString("display_name"), j.getString("profile_url"),
            j.getString("action"), j.optString("validation_status") == "verified")
    }
}
data class Snapshot(val revision: String, val sequence: Long, val expires: Long, val entries: List<Entry>)
object Rules {
    fun validate(entries: List<Entry>) {
        require(entries.size <= 4200) { "Capacité de 3 200 entrées dépassée" }
        val keys = mutableSetOf<String>()
        val handles = mutableSetOf<String>()
        entries.forEach {
            require(it.network in networks && it.action in setOf("follow", "block")) { "Réseau ou action inconnu" }
            require(it.handle.isNotBlank() && it.handle.length <= 128 && it.label.length <= 200) { "Identité invalide" }
            require(keys.add(it.key)) { "Doublon ou conflit entre listes : ${it.handle}" }
            require(handles.add("${it.network}:${it.handle.lowercase()}")) { "Pseudonyme en conflit" }
            require(!it.verified || it.id.isNotBlank()) { "Identifiant stable manquant" }
            if (it.verified && it.network == "youtube") require(it.id.matches(Regex("UC[A-Za-z0-9_-]{22}"))) { "Identifiant YouTube invalide" }
            if (it.verified && it.network == "twitch") require(it.id.matches(Regex("[0-9]+"))) { "Identifiant Twitch invalide" }
            val uri = URI(it.url)
            val host = uri.host?.lowercase().orEmpty()
            val domain = domains.getValue(it.network)
            require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                (host == domain || host.endsWith(".$domain"))) { "URL de profil incorrecte" }
        }
        require(entries.groupBy { it.network to it.action }.values.all { it.size <= 200 }) { "Limite de 200 par réseau et liste dépassée" }
    }
    fun supports(e: Entry) = (e.network == "youtube" && e.action == "follow")
    fun before(e: Entry, connected: Boolean, compliant: Boolean?): String = when {
        !supports(e) -> "unsupported"
        !e.verified -> "unverified_identity"
        !connected -> "auth_required"
        compliant == null -> "unknown"
        compliant -> "already_compliant"
        else -> "action_required"
    }
}
object SignedLists {
    fun decode(raw: String, publicKey: ByteArray, minimumSequence: Long = 0, now: Long = System.currentTimeMillis()): Snapshot {
        require(raw.length <= 4_000_000) { "Réponse trop volumineuse" }
        val envelope = JSONObject(raw)
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key); verifier.update(payload)
        require(verifier.verify(Base64.getDecoder().decode(envelope.getString("signature")))) { "Signature des listes invalide" }
        val j = JSONObject(String(payload, Charsets.UTF_8))
        require(j.getInt("schema_version") == 2 && j.getInt("min_client_version") <= 4) { "Mettre à jour l'application" }
        val seq = j.getLong("sequence")
        require(seq >= minimumSequence) { "Version ancienne refusée" }
        val expires = j.getLong("expires_at_ms")
        require(expires > now && j.getLong("published_at_ms") <= now + 300_000) { "Listes expirées ou horloge incorrecte" }
        require(!j.optBoolean("revoked", false)) { "Version révoquée" }
        val entries = mutableListOf<Entry>()
        for (kind in listOf("black", "white")) {
            val a = j.getJSONArray(kind)
            for (i in 0 until a.length()) {
                val entry = Entry.parse(a.getJSONObject(i))
                require(entry.action == if (kind == "black") "block" else "follow") { "Action incohérente" }
                entries += entry
            }
        }
        Rules.validate(entries)
        return Snapshot(j.getString("revision"), seq, expires, entries)
    }
}
data class ResultRow(val entry: Entry, val status: String = "pending", val detail: String = "", val at: Long = 0) {
    fun json() = JSONObject().put("entry", entry.json()).put("status", status).put("detail", detail).put("at", at)
    companion object {
        fun parse(j: JSONObject) = ResultRow(Entry.parse(j.getJSONObject("entry")), j.getString("status"), j.optString("detail"), j.optLong("at"))
    }
}
val statusLabels = mapOf("manual_confirmed" to "Abonné.e · confirmation manuelle", "missing" to "Profil indisponible", "paused" to "En pause", "request_pending" to "Demande en attente", "wrong_app" to "Application différente", "app_missing" to "Application absente", "local_permission" to "Autorisation Android requise", "pending" to "En attente", "checking" to "Vérification en cours", "already_compliant" to "Déjà conforme",
    "applied_verified" to "Effectué et vérifié", "action_required" to "Action nécessaire", "unsupported" to "Non pris en charge",
    "unknown" to "Résultat non vérifié", "auth_required" to "Connexion nécessaire", "rate_limited" to "Quota / restriction",
    "failed" to "Erreur", "unverified_identity" to "Identifiant à valider", "cancelled" to "Annulé")
