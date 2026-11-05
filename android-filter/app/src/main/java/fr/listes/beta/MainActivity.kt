package fr.listes.beta

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity:ComponentActivity() {
    companion object {
        const val ACTION_SHOW_LIST="fr.listes.filtre.SHOW_LIST"
        const val EXTRA_NETWORK="network"
    }
    private val model:AppModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        handleRoute(intent)
        setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF69E3AF),background=Color(0xFF101211),surface=Color(0xFF1C211E))) {App(model)}}
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);handleRoute(intent)}
    private fun handleRoute(intent:Intent?) {if(intent?.action==ACTION_SHOW_LIST)model.showList(intent.getStringExtra(EXTRA_NETWORK))}
    override fun onResume() {super.onResume();model.resumed();FilterSessionService.update(this)}
}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge);content()
    }}
}
@Composable fun ProfileCard(e:Entry,followed:Boolean,onOpen:()->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(e.label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(followed) Text("Abonné.e",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium,maxLines=1)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("@${e.handle}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick=onOpen,modifier=Modifier.height(36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {
                    Text("Ouvrir le profil",maxLines=1)
                }
            }
        }
    }
}
@Composable fun App(m:AppModel) {
    val context=LocalContext.current
    var disclosure by remember {mutableStateOf(false)}
    fun openProfile(entry:Entry) {
        val pkg=LocalRules.connectors[entry.network]?.packageName
        if(pkg==null) {Toast.makeText(context,"Application non prise en charge.",Toast.LENGTH_LONG).show();return}
        if(!m.beginManual(entry)) {Toast.makeText(context,m.message,Toast.LENGTH_LONG).show();return}
        runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(entry.url)).setPackage(pkg))}.onFailure {
            m.cancelManual(entry);m.message="Application officielle absente ou lien non pris en charge."
            Toast.makeText(context,m.message,Toast.LENGTH_LONG).show()
        }.onSuccess {
            if(!m.accessibility.ready) Toast.makeText(context,"Service non connecté : confirmez votre abonnement au retour dans JANÔ.",Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {while(true) {m.refresh();delay(1500)}}
    Scaffold(bottomBar={NavigationBar {
        listOf("Filtre","Listes","Journal").forEachIndexed {i,label->NavigationBarItem(selected=m.page==i,onClick={m.page=i},icon={Text(listOf("◈","≡","◷")[i])},label={Text(label)})}
    }}) {padding->
        val green=m.snapshot?.entries?.filter {it.action=="follow"}.orEmpty()
        val available=networks.filterKeys {n->green.any {it.network==n}}
        val current=m.listNetwork.takeIf {it in available} ?: available.keys.firstOrNull().orEmpty()
        val visible=ManualFollowState.ordered(green,current,m.followed)
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(if(m.page==1) 4.dp else 14.dp)) {
            item {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Image(painterResource(R.drawable.jano_icon),contentDescription="Logo JANÔ",modifier=Modifier.size(66.dp).clip(RoundedCornerShape(15.dp)))
                    Column {Text("JANÔ",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.headlineLarge)
                        Text("Choisis ton fil. Fais circuler tes idées.",style=MaterialTheme.typography.bodyMedium)}
                }
            }
            if(m.message.isNotBlank() || m.busy) item {if(m.message.isNotBlank()) Text(m.message);if(m.busy) LinearProgressIndicator(Modifier.fillMaxWidth())}
            when(m.page) {
                0->{
                    item {Panel("1 · Autoriser") {
                        Text(m.accessibility.label)
                        if(m.accessibility.code=="waiting_for_service") Text("Le téléphone a interrompu le service. Dans les paramètres Android, désactivez puis réactivez JANÔ.")
                        Button(onClick={disclosure=true}) {Text("Activer l’accessibilité")}
                        TextButton(onClick={m.help=!m.help}) {Text("Android refuse l’autorisation ?")}
                        if(m.help) {
                            Text("Ouvrez la fiche de l’application, puis ⋮ → Autoriser les paramètres restreints. Revenez ensuite activer l’accessibilité.")
                            OutlinedButton(onClick={AccessibilitySetup.openAppInfo(context)}) {Text("Ouvrir la fiche de l’application")}
                            OutlinedButton(onClick={AccessibilitySetup.openServicesList(context)}) {Text("Ouvrir les services d’accessibilité")}
                        }
                        OutlinedButton(onClick=m::testService) {Text("Vérifier le service")}
                        if(m.probe.isNotBlank())Text(m.probe)
                    }}
                    item {Panel("2 · Réseaux") {
                        networks.forEach {(n,label)->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(label,Modifier.padding(top=12.dp));Checkbox(n in m.selected,{m.choose(n,it)})
                        }}
                    }}
                    item {Panel("3 · Activer le filtre") {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(if(FilterRuntime.enabled.value) "Filtre activé" else "Filtre en pause",Modifier.weight(1f).padding(top=12.dp))
                            Switch(FilterRuntime.enabled.value,{m.filter(it)})
                        }
                        Text("Redlist = masque noir.\nGreenlist = Poing levé. Likez, commentez, partagez !\nLes contenus non reconnus restent visibles.")
                        OutlinedButton(onClick=m::sync,enabled=!m.busy) {Text("Actualiser les listes")}
                        Text("Liste : ${m.snapshot?.revision ?: "indisponible"}",style=MaterialTheme.typography.bodySmall)
                    }}
                }
                1->{
                    item {
                        Text("Comptes à suivre",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=8.dp))
                        if(available.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            items(available.entries.toList(),key={it.key}) {(n,label)->
                                FilterChip(selected=current==n,onClick={m.chooseListNetwork(n)},label={Text(label)})
                            }
                        } else Text("Aucun compte disponible.")
                    }
                    items(visible,key={it.key}) {e->ProfileCard(e,ManualFollowState.key(e) in m.followed) {openProfile(e)}}
                }
                2->{
                    item {Panel("Test et diagnostic") {
                        TextButton(onClick=m::clearJournal) {Text("Effacer le log")}
                        Text("Le log conserve les états, durées, identifiants techniques des vues et comptes greenlist testés. Aucun texte de publication, message privé, capture ou mot de passe.")
                    }}
                    items(m.rows,key={it.entry.key}) {r->Panel(r.entry.label) {Text(statusLabels[r.status] ?: r.status);Text(r.detail)}}
                }
            }
            item {Spacer(Modifier.height(16.dp))}
        }
    }
    if(disclosure) AlertDialog(onDismissRequest={disclosure=false},title={Text("Autoriser le filtre Android")},text={Text("JANÔ lit les éléments visibles des applications sociales sélectionnées pour reconnaître les comptes des listes et recouvrir leurs publications. Cette lecture reste sur votre téléphone. Les abonnements se font manuellement sur chaque réseau. Vous pouvez arrêter le filtre ou retirer cette autorisation à tout moment.")},confirmButton={TextButton(onClick={disclosure=false;m.accept(context)}) {Text("J’accepte · ouvrir Android")}},dismissButton={TextButton(onClick={disclosure=false}) {Text("Annuler")}})
    m.pendingPrompt?.let {entry->AlertDialog(onDismissRequest={m.cancelManual(entry)},title={Text("Abonnement à @${entry.handle}")},text={Text("As-tu suivi ce compte ?")},confirmButton={TextButton(onClick={m.confirmManual(entry)}) {Text("Abonné.e !")}},dismissButton={TextButton(onClick={m.cancelManual(entry)}) {Text("Plus tard")}})}
}
