package ml.jlyu.un;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class UNotice extends Service {
	private int noticeId = 0;
	
	public UNotice() {
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	public void onCreate() {
		super.onCreate();
	}
	
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);
		
    	NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    	NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
        .setContentTitle("Notification Title By Service")
        .setSmallIcon(R.drawable.ic_launcher)
        .setSound(soundUri)
        .setLights(0xff0000, 1000, 1000)
        .setAutoCancel(false)
        .setContentText("I'm started!");
    	
        nm.notify(this.noticeId++, mBuilder.build());
		
		return Service.START_STICKY;
	}
	
	public void onDestroy() {
		super.onDestroy();
	}
}
