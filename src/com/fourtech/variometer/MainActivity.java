package com.fourtech.variometer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.fourtech.icomet.Channel;
import com.fourtech.icomet.ChannelAllocator;
import com.fourtech.icomet.ICometCallback;
import com.fourtech.icomet.ICometClient;
import com.fourtech.icomet.ICometConf;
import com.fourtech.icomet.IConnCallback;
import com.fourtech.icomet.message.Message;
import com.fourtech.icomet.message.Message.Content;

public class MainActivity extends Activity implements OnClickListener {
	private static final String TAG = "Altimeter";
	private static final boolean DEBUG = true;

	private Button mAddBtn;
	private Button mMinusBtn;
	private Button mMillMinusBtn;
	private AltimeterView mAltimeterView;
	private ICometClient mClient;
	private String mToken;
	private Looper mLooper;
	private Handler mHandler;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mAddBtn = (Button) findViewById(R.id.btn_add);
		mMinusBtn = (Button) findViewById(R.id.btn_minus);
		mMillMinusBtn = (Button) findViewById(R.id.btn_mill_minus);
		mAltimeterView = (AltimeterView) findViewById(R.id.altimeterView);
		mAddBtn.setOnClickListener(this);
		mMinusBtn.setOnClickListener(this);
		mMillMinusBtn.setOnClickListener(this);

		HandlerThread t = new HandlerThread(TAG);
		t.start();
		mLooper = t.getLooper();
		mHandler = new Handler(mLooper);
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				getToken(true);
				if (mToken == null) {
					mHandler.postDelayed(this, 1000);
				}
			}
		}, 100);
	}

	@Override
	protected void onStart() {
		super.onStart();
		mAltimeterView.onStart();
	}

	@Override
	protected void onStop() {
		mAltimeterView.onStop();
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		mAltimeterView.onDestroy();
		mHandler.removeCallbacksAndMessages(null);
		mLooper.quit();
		mLooper = null;
		mHandler = null;
		icometRelease();
		super.onDestroy();
	}

	@Override
	public void onClick(View v) {
		float ratio = mAltimeterView.getRatio();
		if (v == mAddBtn) {
			int n = (int) ratio / 10;
			ratio = (n + 1) * 10.0f;
			if (ratio < 10.0f) ratio = 10.0f;
			mAltimeterView.setRatio(ratio);
		} else if (v == mMinusBtn) {
			int n = (int) ratio / 10;
			ratio = (n - 1) * 10.0f;
			if (ratio < 10.0f) ratio = 10.0f;
			mAltimeterView.setRatio(ratio);
		} else if (v == mMillMinusBtn) {
			mAltimeterView.setRatio(mAltimeterView.getRatio() - 1.0f);
		}
	}

	private void icometRelease() {
		if (mClient != null) {
			mClient.stopComet();
			mClient.stopConnect();
			mClient = null;
		}
	}

	private void getToken(boolean isGet) {
		if (DEBUG) Log.d(TAG, "getToken()");

		// 第一步，创建HttpPost对象
		HttpPost httpPost = new HttpPost("http://auto.fourtech.me/jdy_service/datapush/sign.php");
		// 设置HTTP POST请求参数必须用NameValuePair对象
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("cname", "altimeter_tester"));
		HttpResponse httpResponse = null;
		try {
			StringBuilder sb = new StringBuilder();
			// 设置httpPost请求参数
			httpPost.setEntity(new UrlEncodedFormEntity(params, HTTP.UTF_8));
			httpResponse = new DefaultHttpClient().execute(httpPost);
			HttpEntity entity = httpResponse.getEntity();

			if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				if (entity != null) {
					Log.d(TAG, "OK_result");

					BufferedReader reader = new BufferedReader(
							new InputStreamReader(entity.getContent(), "UTF-8"),
							8192);
					String line = null;
					while ((line = reader.readLine()) != null) {
						sb.append(line + "\n");
					}
					reader.close();

					JSONObject jsonObject = new JSONObject(sb.toString());
					mToken = jsonObject.getString("token");

					Log.d(TAG, "result = " + sb.toString() + "  mToken=" + mToken);
					linkIcomet();
				}

			} else {
				String result = EntityUtils.toString(entity, "UTF-8")
						+ httpResponse.getStatusLine().getStatusCode()
						+ "ERROR";
				Log.d(TAG, "NO_OK_result = " + result);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private void linkIcomet() {
		icometRelease();
		mClient = ICometClient.getInstance();
		ICometConf conf = new ICometConf();
		conf.host = "auto.fourtech.me";
		conf.port = "8100";
		conf.url = "stream";
		conf.iConnCallback = new MyConnCallback();
		conf.iCometCallback = new MyCometCallback();
		conf.channelAllocator = new NoneAuthChannelAllocator("altimeter_tester", mToken);
		mClient.prepare(conf);
		mClient.connect();
	}

	private class MyConnCallback implements IConnCallback {
		@Override
		public void onFail(String msg) {
			if (DEBUG) Log.d(TAG, "connection fail:" + msg);
		}

		@Override
		public void onSuccess() {
			if (DEBUG) Log.d(TAG, "connection ok");
			mClient.comet();
		}

		@Override
		public void onDisconnect() {
			if (DEBUG) Log.d(TAG, "connection has been cut off");
		}

		@Override
		public void onStop() {
			if (DEBUG) Log.d(TAG, "client has been stopped");
		}

		@Override
		public boolean onReconnect(int times) {
			if (DEBUG) Log.d(TAG, "This is the " + times + "st times.");
			return (times >= 3);
		}

		@Override
		public void onReconnectSuccess(int times) {
			if (DEBUG) Log.d(TAG, "onReconnectSuccess at " + times + "st time");
			mClient.comet();
		}
	}

	private class MyCometCallback implements ICometCallback {
		private int mLastSeq = -1;

		@Override
		public void onDataMsgArrived(Content content) {
			if (DEBUG) Log.d(TAG, "data msg arrived: " + content);
		}

		@Override
		public void onMsgArrived(Message msg) {
			if (DEBUG) Log.d(TAG, "msgcontent:" + msg.content);

			String content = msg.content;
			if (!TextUtils.isEmpty(content)) {
				try {
					JSONObject jsonObject = new JSONObject(content);
					String type = jsonObject.getString("type");
					if (Message.Type.TYPE_DATA.equals(type)) {

						int seq = jsonObject.getInt("seq");
						String content2 = jsonObject.getString("content");

						if (seq != mLastSeq && !TextUtils.isEmpty(content2)) {
							if (content2.startsWith("set_altimeter_trigger")) {
								float trigger = Float.valueOf(content2.substring(21));
								Log.i(TAG, "onMsgArrived() trigger=" + trigger);
								mAltimeterView.setTrigger(trigger * 100.0f);
							}
						}

					}
				} catch (Throwable t) {
					Log.w(TAG, "onMsgArrived()", t);
				}
			}
		}

		@Override
		public void onErrorMsgArrived(Message msg) {
			if (DEBUG) Log.d(TAG, "error message arrived with type: " + msg.type);
		}

		@Override
		public void onMsgFormatError() {
			if (DEBUG) Log.d(TAG, "message format error");
		}

	}

	private class NoneAuthChannelAllocator implements ChannelAllocator {
		private String cname;
		private String token;

		public NoneAuthChannelAllocator(String cname, String token) {
			this.cname = cname;
			this.token = token;
		}

		@Override
		public Channel allocate() {
			Channel channel = new Channel();
			channel.cname = cname;
			channel.token = token;
			channel.seq = 1;
			return channel;
		}
	}

}
