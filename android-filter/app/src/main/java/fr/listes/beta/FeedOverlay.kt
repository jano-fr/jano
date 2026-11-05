package fr.listes.beta

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

private class MaskLayout(context: Context) : LinearLayout(context) {
    var onSwipe: ((Boolean) -> Unit)? = null
    private var startY = 0f
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startY = event.y
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(event.y - startY) > 48 * resources.displayMetrics.density) {
                    onSwipe?.invoke(event.y < startY)
                } else {
                    performClick()
                }
            }
        }
        return true
    }
}
class FeedOverlay(private val service:LocalService) {
    private val wm=service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private data class Anchor(val view: MaskLayout, val entry: Entry,
        var card: AccessibilityNodeInfo, var author: AccessibilityNodeInfo,
        var scroll: AccessibilityNodeInfo?, var footer: AccessibilityNodeInfo?, var segments: List<AccessibilityNodeInfo>, var bounds: Box, val pkg: String)
    private val anchors=mutableListOf<Anchor>()
    private val views get() = anchors.map { it.view }
    private var lastDiscovery=0L
    private var lastScroll=0L
    private var contentDirty=false
    private var created=0
    private var moved=0
    private var signature=""
    private var shownRed=0
    private var overlayError=false
    private var lastLog=0L
    private var lastState=""
    private var contentWindowId = -1
    fun onEvent(event: AccessibilityEvent) {
        // Our own overlays emit window events too. They must not invalidate themselves.
        if (service.windows.any { it.id == event.windowId &&
                it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                event.windowId == contentWindowId) {
            lastScroll=SystemClock.elapsedRealtime()
            tick()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.windowId == contentWindowId) contentDirty=true
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scan()
    }
    private fun contentRoot(): AccessibilityNodeInfo? {
        val focused = service.windows.firstOrNull { it.isFocused }
        if (focused != null) {
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            return focused.root
        }
        val root = service.rootInActiveWindow ?: return null
        return root.takeIf { it.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }
    fun targetActive():Boolean {
        if(!FilterRuntime.enabled.value || !FilterRuntime.consent ||
            (service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked ||
            !(service.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) return false
        val pkg=contentRoot()?.packageName?.toString() ?: return false
        val network=FeedRules.packages[pkg] ?: return false
        return network in FilterRuntime.selected
    }
    fun clear() {
        anchors.toList().forEach(::remove)
        signature="";shownRed=0;overlayError=false;FilterRuntime.masked.value=0
        lastDiscovery=0;contentDirty=false
    }
    private fun remove(anchor: Anchor) {
        runCatching { wm.removeView(anchor.view) }
        anchors.remove(anchor)
    }
    private fun move(anchor: Anchor, bounds: Box) {
        if(anchor.bounds == bounds) return
        val lp=anchor.view.layoutParams as WindowManager.LayoutParams
        lp.x=bounds.left;lp.y=bounds.top;lp.width=bounds.width;lp.height=bounds.height
        try { wm.updateViewLayout(anchor.view,lp);anchor.bounds=bounds;moved++ }
        catch(e: Exception) { remove(anchor);DebugLog.add(service,"anchor_error",JSONObject().put("type",e.javaClass.simpleName)) }
    }
    private fun nodeBox(node: AccessibilityNodeInfo): Box {
        val r=Rect();node.getBoundsInScreen(r)
        return Box(r.left,r.top,r.right,r.bottom)
    }
    private fun cardBox(anchor: Anchor): Box {
        val card=nodeBox(anchor.card)
        val footer=anchor.footer
        var area=if(footer!=null && footer.refresh() && kotlin.math.abs(nodeBox(footer).top-card.bottom)<=4)
            Box(card.left,card.top,card.right,maxOf(card.bottom,nodeBox(footer).bottom)) else card
        for(segment in anchor.segments) {
            if(segment.refresh() && segment.isVisibleToUser && segment.windowId==anchor.card.windowId) {
                val box=nodeBox(segment)
                if(kotlin.math.abs(box.bottom-area.top)<=4 || kotlin.math.abs(box.top-area.bottom)<=4)
                    area=Box(minOf(area.left,box.left),minOf(area.top,box.top),maxOf(area.right,box.right),maxOf(area.bottom,box.bottom))
            }
        }
        return area
    }
    fun tracking() = anchors.isNotEmpty() || contentDirty
    fun tick() {
        if(!FilterRuntime.enabled.value || !FilterRuntime.consent ||
            (service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { clear();return }
        val root=contentRoot() ?: run { clear();return }
        val pkg=root.packageName?.toString().orEmpty()
        val network=FeedRules.packages[pkg]
        if(network==null || network !in FilterRuntime.selected ||
            FilterRuntime.snapshot?.expires?.let { it>System.currentTimeMillis() }!=true) { clear();return }
        if(root.windowId!=contentWindowId) { clear();scan();return }
        val start=SystemClock.elapsedRealtime()
        val viewport=nodeBox(root)
        for(anchor in anchors.toList()) {
            val valid=anchor.pkg==pkg && anchor.card.refresh() && anchor.author.refresh() &&
                anchor.card.windowId==root.windowId && anchor.card.isVisibleToUser &&
                anchor.author.packageName?.toString()==pkg && !anchor.author.isPassword && !anchor.author.isEditable
            val author=if(valid) FeedNode(0,-1,anchor.author.viewIdResourceName.orEmpty(),
                anchor.author.text?.toString().orEmpty(),anchor.author.contentDescription?.toString().orEmpty(),nodeBox(anchor.author)) else null
            // A recycled view is not a permanent identity: validate its author before moving.
            val bounds=if(author!=null && FeedRules.authorRole(author) && FeedRules.authorMatches(author,anchor.entry))
                cardBox(anchor).clip(viewport)?.let { box ->
                    val scroll=anchor.scroll
                    if(scroll!=null && scroll.refresh()) box.clip(nodeBox(scroll)) else box
                } else null
            if(bounds==null) remove(anchor) else move(anchor,bounds)
        }
        FilterRuntime.masked.value=anchors.count { it.entry.action=="block" }
        // Discovery is separate from tracking; new/recycled cards are still checked regularly.
        val interval=if(contentDirty || start-lastScroll<500) 150L else 1000L
        if(start-lastDiscovery>=interval) scan()
    }
    fun scan() {
        if(!FilterRuntime.enabled.value || !FilterRuntime.consent) { clear();return }
        if((service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { clear();return }
        val started=SystemClock.elapsedRealtime()
        val root=contentRoot() ?: run { clear();return }
        if(contentWindowId!=root.windowId) clear()
        contentWindowId=root.windowId
        lastDiscovery=started
        contentDirty=false
        val pkg=root.packageName?.toString().orEmpty()
        val network=FeedRules.packages[pkg]
        if(network==null || network !in FilterRuntime.selected) { clear();return }
        val snapshot=FilterRuntime.snapshot
        if(snapshot==null || snapshot.expires<=System.currentTimeMillis()) {
            clear();FilterRuntime.status.value="Listes absentes ou expirées : synchronisez";return
        }
        val entries=snapshot.entries
        val window=Rect();root.getBoundsInScreen(window)
        val viewport=Box(window.left,window.top,window.right,window.bottom)
        val nodes=mutableListOf<FeedNode>();val targets=mutableMapOf<Int,AccessibilityNodeInfo>()
        val queue=ArrayDeque<Triple<AccessibilityNodeInfo,Int,Int>>()
        queue.add(Triple(root,-1,0))
        while(queue.isNotEmpty() && nodes.size<1500) {
            val (n,parent,depth)=queue.removeFirst()
            if(!n.isVisibleToUser || n.isPassword || n.isEditable || n.packageName?.toString()!=pkg) continue
            val index=nodes.size;val r=Rect();n.getBoundsInScreen(r)
            nodes+=FeedNode(index,parent,n.viewIdResourceName.orEmpty(),n.text?.take(200)?.toString().orEmpty(),
                n.contentDescription?.take(200)?.toString().orEmpty(),Box(r.left,r.top,r.right,r.bottom),n.isClickable,n.isScrollable)
            targets[index]=n
            if(depth<24) for(i in 0 until n.childCount) n.getChild(i)?.let { queue.add(Triple(it,index,depth+1)) }
        }
        val matches=FeedRules.detect(network,nodes,entries,viewport)
        val remaining=anchors.toMutableList()
        overlayError=false
        for(match in matches) {
            val card=targets[match.cardIndex] ?: continue
            val author=targets[match.authorIndex] ?: continue
            val scroll=match.scrollIndex?.let { targets[it] }
            val footer=match.footerIndex?.let { targets[it] }
            val segments=match.segmentIndices.mapNotNull { targets[it] }
            val bounds=match.bounds.let { if(scroll!=null) it.clip(nodeBox(scroll)) else it } ?: continue
            val old=remaining.firstOrNull { it.card==card && it.entry.key==match.entry.key && it.entry.action==match.entry.action }
            if(old!=null) {
                remaining.remove(old);old.card=card;old.author=author;old.scroll=scroll;old.footer=footer;old.segments=segments
                move(old,bounds)
            } else show(match.copy(bounds=bounds),pkg,scroll,card,author,footer,segments)
        }
        // Keep a previously verified card while its header scrolls out of view.
        // A visible header that no longer matches must invalidate the old anchor.
        remaining.forEach { anchor ->
            val author=anchor.author
            val keep=anchor.card.refresh() && author.refresh() && anchor.card.isVisibleToUser &&
                !author.isVisibleToUser && author.packageName?.toString()==pkg &&
                !author.isPassword && !author.isEditable &&
                FeedRules.authorMatches(FeedNode(0,-1,author.viewIdResourceName.orEmpty(),
                    author.text?.toString().orEmpty(),author.contentDescription?.toString().orEmpty(),nodeBox(author)),anchor.entry)
            if(!keep) remove(anchor)
        }
        shownRed=anchors.count { it.entry.action=="block" }
        val red=shownRed
        FilterRuntime.masked.value=red
        FilterRuntime.scans.value++
        val duration=SystemClock.elapsedRealtime()-started
        FilterRuntime.lastMs.value=duration
        val state=if(overlayError) "Affichage du masque refusé par Android" else if(anchors.isNotEmpty()) "$red masque(s) · ${views.size-red} repère(s) greenlist"
            else "${networks[network] ?: "Test"} : aucun contenu de liste reconnu"
        FilterRuntime.status.value=state
        if(state!=lastState || started-lastLog>15_000) {
            DebugLog.add(service,"scan",JSONObject().put("network",network).put("nodes",nodes.size)
                .put("author_candidates",nodes.count(FeedRules::authorRole)).put("masks",red)
                .put("green",views.size-red).put("duration_ms",duration).put("created",created).put("moved",moved).put("truncated",queue.isNotEmpty())
                .put("view_ids",JSONArray(nodes.map { FeedRules.id(it.resource) }.filter { it.isNotBlank() }.distinct().take(100))))
            lastLog=started;lastState=state
        }
    }
    private fun show(match:FeedMatch,pkg:String,scroll:AccessibilityNodeInfo?,card:AccessibilityNodeInfo,author:AccessibilityNodeInfo,footer:AccessibilityNodeInfo?,segments:List<AccessibilityNodeInfo>) {
        val red=match.entry.action=="block"
        val box=MaskLayout(service).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setPadding(dp(8),dp(4),dp(8),dp(4))
            setBackgroundColor(if(red) Color.argb(204, 0, 0, 0) else Color.TRANSPARENT)
        }
        if(red) {
            // 80%-opaque blank mask; taps are consumed and vertical swipes only scroll.
            box.onSwipe = { forward -> scroll(scroll, pkg, forward) }
            box.isClickable=true
        } else {
            box.gravity=Gravity.BOTTOM or Gravity.LEFT
            box.addView(TextView(service).apply { text="✊";contentDescription="Compte de la greenlist";textSize=26f })
        }
        val r=match.bounds
        val flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(red) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val lp=WindowManager.LayoutParams(r.width,r.height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.LEFT;lp.x=r.left;lp.y=r.top
        if(android.os.Build.VERSION.SDK_INT>=30) lp.setFitInsetsTypes(0)
        runCatching { wm.addView(box,lp);anchors+=Anchor(box,match.entry,card,author,scroll,footer,segments,match.bounds,pkg);created++ }.onFailure {
            overlayError=true
            FilterRuntime.status.value="Affichage du masque refusé par Android"
            DebugLog.add(service,"overlay_error",JSONObject().put("type",it.javaClass.simpleName))
        }
    }
    private fun scroll(node:AccessibilityNodeInfo?,pkg:String,forward:Boolean) {
        val current=contentRoot()
        val allowed=current?.packageName?.toString()==pkg && node?.windowId==current.windowId &&
            node?.refresh()==true && node.isVisibleToUser && node.isScrollable
        val success=allowed && node!!.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        DebugLog.add(service,"scroll",JSONObject().put("success",success))
        if(success) { lastScroll=SystemClock.elapsedRealtime() } else {
            // Never send a gesture through the mask or click the underlying post.
            android.widget.Toast.makeText(service,"Défilez depuis le bord libre.",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun dp(value:Int)=(value*service.resources.displayMetrics.density).toInt()
}
