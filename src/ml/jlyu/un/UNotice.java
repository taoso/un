package ml.jlyu.un;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class UNotice extends Service {
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
		Log.d("a", "start");
		super.onStartCommand(intent, flags, startId);
		
		return Service.START_STICKY;
	}
	
	public void onDestroy() {
		super.onDestroy();
	}
}
