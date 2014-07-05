package ml.jlyu.un;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class UNoticeService extends Service implements MqttCallback {
	private int noticeId = 0;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private MqttAsyncClient mClient;

	private boolean isConnected() {
		return mClient != null && mClient.isConnected();
	}

	private boolean isOnline() {
		ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

		return cm.getActiveNetworkInfo() != null
				&& cm.getActiveNetworkInfo().isAvailable()
				&& cm.getActiveNetworkInfo().isConnected();
	}

	public void onCreate() {
		super.onCreate();

		if (pingSender == null) {
			pingSender = new PingSender();
		}

		if (this.networkConnectionMonitor == null) {
			this.networkConnectionMonitor = new NetworkConnectionMonitor();
		}

		registerReceiver(this.networkConnectionMonitor, new IntentFilter(
				ConnectivityManager.CONNECTIVITY_ACTION));

		registerReceiver(pingSender, new IntentFilter(
				UNoticeService.MQTT_PING_ACTION));

		defineClient();
	}

	public void handleStart() {
		
		if (isConnected()) {
			return;
		}

		if (!isOnline()) {
			return;
		}

		connectToBroker();
	}
	
	private int keepAliveInterval = 600;

	private void connectToBroker() {

		MqttConnectOptions mco = new MqttConnectOptions();
		mco.setCleanSession(true);
		mco.setKeepAliveInterval(keepAliveInterval);

		try {
			mClient.connect(mco, "", new IMqttActionListener() {

				@Override
				public void onFailure(IMqttToken token, Throwable e) {
					notice("ERROR", "connect failed");
					logExt(e);
				}

				@Override
				public void onSuccess(IMqttToken token) {
					try {
						mClient.subscribe("ml/jlyu", 2);
					} catch (MqttException e) {
						notice("ERROR", "subscript failed");
						logExt(e);
					}
				}
			});
		} catch (Exception e) {
			logExt(e);
		}
	}

	private void logExt(Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		Log.e("HEHE", sw.toString());
	}

	private void defineClient() {
		String serverUri = "tcp://m2m.eclipse.org";
		String clientId = "ml.jlyu.mqtt";

		try {
			mClient = new MqttAsyncClient(serverUri, clientId, null);
			mClient.setCallback(this);
		} catch (MqttException e) {
			logExt(e);
			mClient = null;
		}
	}

	private PingSender pingSender;

	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		handleStart();

		return Service.START_STICKY;
	}

	public void onDestroy() {
		super.onDestroy();
		try {
			mClient.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			logExt(e);
		}
	}

	private void notice(String title, String content) {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		Uri soundUri = RingtoneManager
				.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
				this).setContentTitle(title)
				.setSmallIcon(R.drawable.ic_launcher).setSound(soundUri)
				.setLights(0xff0000, 1000, 1000).setAutoCancel(false)
				.setContentText(content);

		nm.notify(this.noticeId++, mBuilder.build());
	}

	@Override
	public void connectionLost(Throwable e) {
		logExt(e);
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
		wl.acquire();
		try {
			if (isOnline()) {
				connectToBroker();
			}
		} finally {
			wl.release();
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {
		// TODO Auto-generated method stub
	}

	@Override
	public void messageArrived(String topic, MqttMessage message)
			throws Exception {
		notice(topic, new String(message.getPayload()));
	}

	public static final String MQTT_PING_ACTION = "ml.jlyu.mqtt.action.ping";

	private void scheduleNextPing() {
		PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0,
				new Intent(UNoticeService.MQTT_PING_ACTION),
				PendingIntent.FLAG_UPDATE_CURRENT);
		Calendar wakeUpTime = Calendar.getInstance();
		wakeUpTime.add(Calendar.SECOND, this.keepAliveInterval - 10);

		AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
		am.set(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(),
				pendingIntent);
	}

	private class PingSender extends BroadcastReceiver {
		private class PingListener implements IMqttActionListener {

			@Override
			public void onFailure(IMqttToken token, Throwable e) {
				scheduleNextPing();
			}

			@Override
			public void onSuccess(IMqttToken token) {
				scheduleNextPing();
			}
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			notice("PingSender", "Ping");
			try {
				mClient.checkPing(this, new PingListener());
			} catch (MqttException e) {
				notice("Error", "check ping failed");
			}
		}

	}

	private NetworkConnectionMonitor networkConnectionMonitor;

	private class NetworkConnectionMonitor extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
			WakeLock wl = pm
					.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
			wl.acquire();
			try {
				if (isOnline()) {
					connectToBroker();
				}
			} finally {
				wl.release();
			}
		}
	}
}
