package ml.jlyu.un;

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
import org.eclipse.paho.client.mqttv3.MqttPingSender;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;

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

public class UNoticeService extends Service implements MqttCallback {

	// 重启动服务，包括重新初始化客户端，重新连接服务器，重新订阅主题
	public final static String ACTION_RESTART = "ml.jlyu.un.action.restart";
	// 重新连接服务器，包括重新连接服务器和重新订阅主题
	public final static String ACTION_RECONNECT = "ml.jlyu.un.action.reconnect";
	// 重新订阅主题
	public final static String ACTION_RESUBSCRIBE = "ml.jlyu.un.action.resubscribe";
	// MQTT心跳
	public static final String ACTION_PING = "ml.jlyu.un.action.ping";

	private final String NOTICE_ERROR = "Error";
	private final String NOTICE_INFO = "Info";

	// MQTT消息使用的id不能相同，可以显示多条通知
	private int noticeId = 100;

	// 系统消息公用一个通知id，只显示最新的通知
	private int appNoticeId = 0;

	private SharedPreferences preferences;

	@SuppressWarnings("unused")
	private BrokerStatusHandler brokerStatusHandler;

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
	}

	public synchronized void handleStart() {
		if (mClient == null) {
			defineClient();
		}

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
		String pingInterval = this.preferences.getString("ping_interval", "60");

		mco.setKeepAliveInterval(Integer.parseInt(pingInterval));

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
			e.printStackTrace();
		}
	}

	private void subscribeTopics() {
		String[] topics = preferences.getString("topic", "/un").split(" ");

		try {
			for (String topic : topics) {
				mClient.subscribe(topic, 1);
			}
		} catch (MqttException e) {
			// TODO 可能存在客户端未连接的情况，查明原因
			UNoticeService.this.noticeAction(UNoticeService.ACTION_RESUBSCRIBE,
					"无法订阅主题（点击重试）");
			e.printStackTrace();
		}
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
			mClient = new MqttAsyncClient(serverUri, clientId, null, pingSender);
			mClient.setCallback(this);
			return true;
		} catch (MqttException e) {
			noticeAction(ACTION_RESTART, "初始化客户端失败（点击重试）");
			e.printStackTrace();
		}

		return false;
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		new Thread(new Runnable() {

			@Override
			public void run() {
				handleStart();
			}

		}).start();

		return Service.START_STICKY;
	}

	public void onDestroy() {
		super.onDestroy();
		try {
			mClient.disconnectForcibly();
			mClient.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * 发送普通系统通知。内容为空时可以清空动作通知。
	 *
	 * @param title
	 *            通知标题
	 * @param content
	 *            通知内容
	 */
	private void notice(String title, String content) {
		notice(title, content, null, false);
	}

	/**
	 * 将收到的MQTT消息发送到系统通知栏
	 *
	 * @param topic
	 *            消息主题
	 * @param content
	 *            消息内容
	 */
	private void noticeMessage(String topic, String content) {
		notice(topic, content, null, true);
	}

	/**
	 * 发送“动作”通知。用户点击通知后广播相应动作。
	 *
	 * @param action
	 *            动作标识
	 * @param description
	 *            动作描述，通知内容
	 */
	private void noticeAction(String action, String description) {
		Intent intent = new Intent(UNoticeService.ACTION_RECONNECT);
		PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		notice(NOTICE_ERROR, description, pIntent, false);
	}

	/**
	 * 发送动作通知底层方法
	 *
	 * @param title
	 *            通知标题
	 * @param content
	 *            通知内容，内容为空时清空通知
	 * @param intent
	 *            点击后要广播的动作
	 * @param isMessage
	 *            是否为普通消息，如果是则分开显示；否则，替换之前的通知
	 */
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
	}

	private class PingSender extends BroadcastReceiver implements
			MqttPingSender {

		@Override
		public void onReceive(Context context, Intent intent) {
			clientComms.checkForActivity();
		}

		private ClientComms clientComms;

		@Override
		public void init(ClientComms clientComms) {
			this.clientComms = clientComms;
		}

		@Override
		public void schedule(long delayInMilliseconds) {
			PendingIntent pendingIntent = PendingIntent.getBroadcast(
					UNoticeService.this, 0, new Intent(
							UNoticeService.ACTION_PING),
					PendingIntent.FLAG_UPDATE_CURRENT);

			Calendar wakeUpTime = Calendar.getInstance();
			wakeUpTime.add(Calendar.MILLISECOND,
					(int) (delayInMilliseconds - 5000));

			AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
			am.set(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(),
					pendingIntent);
		}

		@Override
		public void start() {
			clientComms.checkForActivity();
		}

		@Override
		public void stop() {
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

		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					mClient.disconnectForcibly();
				} catch (MqttException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					mClient = null;
				}
				handleStart();
			}

		}).start();
	}

	private class BrokerStatusHandler extends BroadcastReceiver {

		public BrokerStatusHandler() {
			IntentFilter brokerStatusIntentFilter = new IntentFilter();
			brokerStatusIntentFilter.addAction(ACTION_RECONNECT);
			brokerStatusIntentFilter.addAction(ACTION_RESTART);
			brokerStatusIntentFilter.addAction(ACTION_RESUBSCRIBE);

			registerReceiver(this, brokerStatusIntentFilter);
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			UNoticeService.this.restart();
		}

	}
}
