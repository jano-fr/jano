package fr.listes.beta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/** Keeps a user-enabled filtering session visible while the accessibility listener waits. */
class FilterSessionService:Service() {
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        FilterRuntime.initialize(this)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jano_filter","Filtre JANÔ",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause=PendingIntent.getService(this,1,Intent(this,FilterSessionService::class.java).setAction("pause"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text=if(LocalBridge.connected.value && LocalBridge.service!=null) "Filtre actif · en attente d’un réseau sélectionné" else "Service d’accessibilité interrompu : ouvrez JANÔ"
        val notification=Notification.Builder(this,"jano_filter").setSmallIcon(R.drawable.ic_app)
            .setContentTitle("JANÔ").setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"Mettre en pause",pause).build()).build()
        try {
            if(Build.VERSION.SDK_INT>=34) startForeground(55,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(55,notification)
        } catch(e:Exception) {
            DebugLog.add(this,"foreground_error",JSONObject().put("type",e.javaClass.simpleName))
            stopSelf();return START_NOT_STICKY
        }
        if(intent?.action=="pause") FilterRuntime.setEnabled(this,false)
        if(!FilterRuntime.enabled.value || !FilterRuntime.consent) {
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        return START_STICKY
    }
    companion object {
        fun update(context:Context) {
            val intent=Intent(context,FilterSessionService::class.java)
            if(!FilterRuntime.enabled.value || !FilterRuntime.consent) {context.stopService(intent);return}
            runCatching {context.startForegroundService(intent)}.onFailure {
                DebugLog.add(context,"foreground_start_refused",JSONObject().put("type",it.javaClass.simpleName))
            }
        }
    }
}
