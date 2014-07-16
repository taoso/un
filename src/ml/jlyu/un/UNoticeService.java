package ml.jlyu.un;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.UUID;

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
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class UNoticeService extends Service implements MqttCallback {

	/**
	 * 重启动服务，包括重新初始化客户端，重新连接服务器，重新订阅主题
	 */
	public final static String ACTION_RESTART = "ml.jlyu.un.action.restart";
	/**
	 * 重新连接服务器，包括重新连接服务器和重新订阅主题
	 */
	public final static String ACTION_RECONNECT = "ml.jlyu.un.action.reconnect";
	/**
	 * 重新订阅主题
	 */
	public final static String ACTION_RESUBSCRIBE = "ml.jlyu.un.action.resubscribe";
	/**
	 * MQTT心跳
	 */
	public static final String ACTION_PING = "ml.jlyu.un.action.ping";

	private final String NOTICE_ERROR = "Error";
	private final String NOTICE_INFO = "Info";

	/**
	 * MQTT消息使用的id不能相同，可以显示多条通知
	 */
	private int noticeId = 100;

	/**
	 * 系统消息公用一个通知id，只显示最新的通知
	 */
	private int appNoticeId = 0;

	private SharedPreferences preferences;

	private BrokerStatusHandler brokerStatusHandler;

	/**
	 * 心跳包发送间隔，最少60秒
	 */
	private int keepAliveInterval = 600;

	private MqttAsyncClient mClient;
	private PingSender pingSender;
	private NetworkConnectionMonitor networkConnectionMonitor;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

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

		preferences = PreferenceManager.getDefaultSharedPreferences(this);

		pingSender = new PingSender();
		networkConnectionMonitor = new NetworkConnectionMonitor();
		brokerStatusHandler = new BrokerStatusHandler();

		registerReceiver(pingSender, new IntentFilter(
				UNoticeService.ACTION_PING));

		IntentFilter brokerStatusIntentFilter = new IntentFilter();
		brokerStatusIntentFilter.addAction(ACTION_RECONNECT);
		brokerStatusIntentFilter.addAction(ACTION_RESTART);
		brokerStatusIntentFilter.addAction(ACTION_RESUBSCRIBE);
		registerReceiver(brokerStatusHandler, brokerStatusIntentFilter);

		defineClient();
	}

	public void handleStart() {
		if (!isOnline()) {
			registerReceiver(networkConnectionMonitor, new IntentFilter(
					ConnectivityManager.CONNECTIVITY_ACTION));
			return;
		}

		if (isConnected()) {
			return;
		}

		connectToBroker();
	}

	private void connectToBroker() {
		MqttConnectOptions mco = new MqttConnectOptions();

		mco.setCleanSession(false);
		mco.setKeepAliveInterval(keepAliveInterval);

		try {
			mClient.connect(mco, null, new IMqttActionListener() {

				@Override
				public void onFailure(IMqttToken token, Throwable throwable) {
					UNoticeService.this.noticeAction(
							UNoticeService.ACTION_RECONNECT, "无法连接服务器（点击重试）");
				}

				@Override
				public void onSuccess(IMqttToken token) {
					notice("CLEAR", "");
					subscribeTopics();
				}
			});
		} catch (Exception e) {
			// TODO 处理连接异常
			// 此处异常为正在连接、已经连接、正在断开连接、已经断开连接四种
			logExt(e);
		}
	}

	private void subscribeTopics() {
		String[] topics = preferences.getString("topic", "/un").split(" ");

		try {
			for (String topic : topics) {
				mClient.subscribe(topic, 1);
			}
			scheduleNextPing();
		} catch (MqttException e) {
			// TODO 可能存在客户端未连接的情况，查明原因
			UNoticeService.this.noticeAction(UNoticeService.ACTION_RESUBSCRIBE,
					"无法订阅主题（点击重试）");
			logExt(e);
		}
	}

	private void logExt(Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		Log.d("UNDEBUG", sw.toString());
	}

	private boolean defineClient() {

		String serverUri = preferences.getString("server_uri",
				"tcp://m2m.eclipse.org:1883");
		String clientId = preferences.getString("client_id", null);
		if (clientId == null) {
			clientId = UUID.randomUUID().toString();
			preferences.edit().putString("client_id", clientId).commit();
		}

		try {
			mClient = new MqttAsyncClient(serverUri, clientId, null);
			mClient.setCallback(this);
			return true;
		} catch (MqttException e) {
			noticeAction(ACTION_RESTART, "初始化客户端失败（点击重试）");
			logExt(e);
		}

		return false;
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		handleStart();

		return Service.START_STICKY;
	}

	public void onDestroy() {
		super.onDestroy();
		try {
			mClient.disconnectForcibly();
			mClient.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			logExt(e);
		}
	}

	private void notice(String title, String content) {
		notice(title, content, null, false);
	}

	private void noticeMessage(String topic, String content) {
		notice(topic, content, null, true);
	}

	private void noticeAction(String action, String description) {
		Intent intent = new Intent(UNoticeService.ACTION_RECONNECT);
		PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		notice(NOTICE_ERROR, description, pIntent, false);
	}

	private void notice(String title, String content, PendingIntent intent,
			boolean isMessage) {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		if (content == "") {
			nm.cancel(appNoticeId);
			return;
		}

		Uri soundUri = RingtoneManager
				.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(
				this).setContentTitle(title)
				.setSmallIcon(R.drawable.ic_launcher).setSound(soundUri)
				.setLights(0xff0000, 1000, 2000).setContentText(content);

		if (intent != null) {
			builder.setContentIntent(intent);
			builder.setAutoCancel(true);
		}

		if (isMessage) {
			nm.notify(this.noticeId++, builder.build());
		} else {
			nm.notify(this.appNoticeId, builder.build());
		}
	}

	@Override
	public void connectionLost(Throwable e) {
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
		wl.acquire();
		try {
			SystemClock.sleep(1000);
			if (isOnline()) {
				UNoticeService.this.connectToBroker();
			} else {
				UNoticeService.this.notice(NOTICE_INFO, "因离线关闭客户端");
				try {
					UNoticeService.this.mClient.disconnectForcibly();
				} catch (MqttException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				registerReceiver(networkConnectionMonitor, new IntentFilter(
						ConnectivityManager.CONNECTIVITY_ACTION));
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
		noticeMessage(topic, new String(message.getPayload()));
		scheduleNextPing();
	}

	private void scheduleNextPing() {
		PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0,
				new Intent(UNoticeService.ACTION_PING),
				PendingIntent.FLAG_UPDATE_CURRENT);
		Calendar wakeUpTime = Calendar.getInstance();
		wakeUpTime.add(Calendar.SECOND, keepAliveInterval - 10);

		AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
		am.set(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(),
				pendingIntent);
	}

	private class PingSender extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (isOnline() && !isConnected()) {
				connectToBroker();
				return;
			}

			scheduleNextPing();
			try {
				mClient.checkPing(this, new IMqttActionListener() {
					@Override
					public void onFailure(IMqttToken token, Throwable e) {
					}

					@Override
					public void onSuccess(IMqttToken token) {
					}
				});
			} catch (MqttException e) {
				noticeAction(ACTION_RECONNECT, "心跳检查失败");
				logExt(e);
			}
		}

	}

	private class NetworkConnectionMonitor extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
			WakeLock wl = pm
					.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
			wl.acquire();
			try {
				if (isOnline()) {
					handleStart();
				}
			} finally {
				wl.release();
			}
		}
	}

	private void restart() {
		try {
			mClient.disconnectForcibly();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		handleStart();
	}

	private class BrokerStatusHandler extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			UNoticeService.this.restart();
		}
	}
}
