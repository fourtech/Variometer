package com.fourtech.variometer;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;

import com.fourtech.hardware.Variometer;

public class AltimeterView extends SurfaceView implements Callback {
	private static final String TAG = "AltimeterView";
	private static final boolean DEBUG = false;

	private SurfaceHolder mHolder;

	private Looper mLooper;
	private Handler mHandler;
	private Looper mSendLooper;
	private Handler mSendHandler;

	private static final int MSG_SEND_TEXT = 0;
	private static final int MSG_SEND_OPENED = 1;
	private static final int MSG_SEND_CLOSED = 2;

	private long mSendOpenedTime = 0;
	private long mSendClosedTime = 0;

	private static final int P_SIZE = 50;
	private static final int C_SIZE = 50;

	private int mN = 0; // 计算第N次重新开始
	private double mCenterA = 0; // 参考压力平均值
	private float mRatio = 30.0f; // 缩放比例
	private float mTrigger = 12.0f; // 触发开门警告的阀值
	private double[] mPs/* = new double[P_SIZE]*/;    // 压力值
	private double[] mSortedPs = new double[P_SIZE];  // 排序压力值
	private double[] mCs/* = new double[C_SIZE]*/;    // 保留C_SIZE个点的轨迹
	private double[] mSortedCs = new double[C_SIZE];  // 排序C_SIZE个点的轨迹
	private Paint mPaintMain, mPaintLine, mPaintMax1, mPaintMax2;
	private Paint mPaintCenter, mPaintA, mPaintP, mPaintPLine;

	private Variometer mVariometer;
	private int[] mOutValues = { 0, 0 };

	public AltimeterView(Context context) {
		this(context, null);
	}

	public AltimeterView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AltimeterView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		getHolder().addCallback(this);
		mVariometer = new Variometer();
		mVariometer.open();

		HandlerThread t = new HandlerThread(TAG);
		t.start();
		mLooper = t.getLooper();
		mHandler = new Handler(mLooper);

		t = new HandlerThread(TAG + "-send");
		t.start();
		mSendLooper = t.getLooper();
		mSendHandler = new Handler(mSendLooper) {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_SEND_TEXT:
				case MSG_SEND_OPENED:
				case MSG_SEND_CLOSED: {
					long now = System.currentTimeMillis();
					if (msg.what == MSG_SEND_OPENED) {
						mSendClosedTime = 0;
						if (now - mSendOpenedTime < 1000) {
							break;
						}
						mSendOpenedTime = now;
					} else if (msg.what == MSG_SEND_CLOSED) {
						mSendOpenedTime = 0;
						if (now - mSendClosedTime < 1000) {
							break;
						}
						mSendClosedTime = now;
					}
					sendTextMsgToWeXin((String) msg.obj);
					break;
				}
				default:
					break;
				}
			}
		};

		mPaintMain = new Paint();
		mPaintMain.setColor(0xff565656);
		mPaintMain.setStrokeWidth(5);
		mPaintLine = new Paint();
		mPaintLine.setColor(0xff232323);
		mPaintLine.setStrokeWidth(2);

		mPaintCenter = new Paint();
		mPaintCenter.setColor(0xff560056);
		mPaintCenter.setStrokeWidth(3);

		mPaintMax1 = new Paint();
		mPaintMax1.setColor(0xff990000);
		mPaintMax1.setStrokeWidth(2);
		mPaintMax1.setTextSize(26);
		mPaintMax1.setTextAlign(Align.RIGHT);

		mPaintMax2 = new Paint();
		mPaintMax2.setColor(0xff560000);
		mPaintMax2.setStrokeWidth(2);

		mPaintA = new Paint();
		mPaintA.setColor(0xff005656);
		mPaintA.setStrokeWidth(3);
		mPaintA.setTextSize(26);
		mPaintA.setTextAlign(Align.RIGHT);

		mPaintP = new Paint();
		mPaintP.setColor(0xffffffff);
		mPaintP.setStrokeWidth(2);

		mPaintPLine = new Paint();
		mPaintPLine.setColor(0xff909090);
		mPaintPLine.setStrokeWidth(2);
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.i(TAG, "surfaceChanged() w=" + width + ", h=" + height);
		if (width > 0 && height > 0) {
			mHolder = holder;
		}
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.i(TAG, "surfaceCreated()");
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(TAG, "surfaceDestroyed()");
		mHolder = null;
	}

	public void onStart() {
		onStop();
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				refreshUIAsync();
				postDelayed(this, 20);
			}
		});
	}

	public void onStop() {
		mHandler.removeCallbacksAndMessages(null);
	}

	protected void onDestroy() {
		mVariometer.close();
	}

	public float getRatio() {
		return mRatio;
	}

	public void setRatio(float ratio) {
		mRatio = ratio;
		if (mRatio < 1.0f) mRatio = 1.0f;
		Log.i(TAG, "setRatio() ratio=" + ratio);
	}

	public float getTrigger() {
		return mTrigger;
	}

	public void setTrigger(final float trigger) {
		mTrigger = trigger;
		String text = "Set Trigger to " + (trigger / 100.0f);
		mSendHandler.obtainMessage(MSG_SEND_TEXT, text).sendToTarget();
	}

	// 更新UI，需要异步调用
	private void refreshUIAsync() {
		if (DEBUG) Log.i(TAG, "refreshUIAsync() +++");
		mVariometer.getValues(mOutValues); // 读取气压和温度值
		if (DEBUG) Log.i(TAG, "refreshUIAsync() outValues=" + Arrays.toString(mOutValues));
		try {
			double pressure = mOutValues[0];
			double temperature = mOutValues[1];
			if (mPs == null) {
				mPs = new double[P_SIZE];
				for (int i = 0; i < mPs.length; i++) {
					mPs[i] = pressure;
				}
			}

			// 把新加入的气压值插入队列
			pushInArray(mPs, pressure);

			// 排序气压值，以方便过滤掉最大值和最小值求平均数
			for (int j = 0; j < mPs.length; j++) {
				mSortedPs[j] = mPs[j];
			}
			Arrays.sort(mSortedPs);

			// 求平均值
			double a = 0;
			// 过滤掉10个最大值和10个最小值
			for (int j = 10; j < mPs.length - 10; j++) {
				a += mSortedPs[j];
			}
			a /= mPs.length - 20;

			// 如果参考气压值离中点太远了则更新参考气压值
			if (Math.abs(a - mCenterA) * mRatio > 30) {
				mCenterA = a;
			}

			if (DEBUG) Log.i(TAG, "refreshUIAsync() a=" + a + ", p=" + pressure + ", t=" + temperature);

			// 如果压力超过阀值则触发开门警告
			double delta = pressure - a;
			if (mN >= P_SIZE && Math.abs(delta) >= mTrigger) {
				if (delta > 0) { // 气压升高为车门关闭
					String text = String.format(Locale.ENGLISH, "Door closed ( %.2f mBar )", delta / 100.0f);
					mSendHandler.removeMessages(MSG_SEND_CLOSED);
					Message msg = mSendHandler.obtainMessage(MSG_SEND_CLOSED, text);
					mSendHandler.sendMessageDelayed(msg, 200);
				} else { // 气压减低为车门打开
					String text = String.format(Locale.ENGLISH, "Door opened ( %.2f mBar )", delta / 100.0f);
					mSendHandler.removeMessages(MSG_SEND_OPENED);
					Message msg = mSendHandler.obtainMessage(MSG_SEND_OPENED, text);
					mSendHandler.sendMessageDelayed(msg, 200);
				}
			} else {
				mN++;
			}

			doRefreshUI(a, pressure, temperature);
		} catch (Throwable tt) {
			Log.w(TAG, "refreshUIAsync() error", tt);
		}
	}

	// 更新UI
	private void doRefreshUI(double average, double pressure, double temperature) {
		if (mHolder != null) {
			synchronized (mHolder) {
				if (mCs == null) {
					mCs = new double[C_SIZE];
					for (int i = 0; i < mCs.length; i++) {
						mCs[i] = mCenterA;
					}
				}

				// 把新加入的气压值插入队列
				pushInArray(mCs, pressure);

				// 排序气压值，以方便下面找到最大值和最小值
				for (int j = 0; j < mCs.length; j++) {
					mSortedCs[j] = mCs[j];
				}
				Arrays.sort(mSortedCs);

				// 开始绘制
				int gap = 30;
				int paddingL = 120;
				int paddingT = 40;
				int paddingR = 40;
				int paddingB = 24;

				Canvas c = mHolder.lockCanvas();
				Rect r = mHolder.getSurfaceFrame();
				int centerY = r.top + (r.bottom - r.top) / 2;

				// 清理画布
				c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

				// 绘制网格
				for (int x = r.left + paddingL + gap; x < r.right - paddingR; x += gap) {
					c.drawLine(x, r.top + paddingT, x, r.bottom - paddingB, mPaintLine);
				}
				for (int y = r.top + paddingT + gap; y < r.bottom - paddingB; y += gap) {
					c.drawLine(r.left + paddingL, y, r.right - paddingR, y, mPaintLine);
				}

				// 绘制最大值和最小值的线和文字
				double maxmin = mSortedCs[mSortedCs.length - 2];
				if (maxmin > 0) {
					double delta = maxmin - mCenterA;
					float y = centerY - (float) (delta) * mRatio;
					c.drawLine(r.left + paddingL, y, r.right - paddingR, y, mPaintMax2);
				}
				maxmin = mSortedCs[mSortedCs.length - 1];
				if (maxmin > 0) {
					double delta = maxmin - mCenterA;
					float y = centerY - (float) (delta) * mRatio;
					c.drawLine(r.left + paddingL, y, r.right - paddingR, y, mPaintMax1);
					// 绘气压值和差异值
					delta = maxmin - average;
					if (y < paddingT + 48) y = paddingT + 48;
					String text = String.format(Locale.ENGLISH, "%.2f", maxmin / 100.0f);
					c.drawText(text, paddingL - 4, y, mPaintMax1);
					text = String.format(Locale.ENGLISH, ((maxmin > mCenterA) ? "(+%.2f)" : "(%.2f)"), (delta) / 100.0f);
					c.drawText(text, paddingL - 4, y - 24, mPaintMax1);
				}
				maxmin = mSortedCs[1];
				if (maxmin > 0) {
					double delta = maxmin - mCenterA;
					float y = centerY - (float) (delta) * mRatio;
					c.drawLine(r.left + paddingL, y, r.right - paddingR, y, mPaintMax2);
				}
				maxmin = mSortedCs[0];
				if (maxmin > 0) {
					double delta = maxmin - mCenterA;
					float y = centerY - (float) (delta) * mRatio;
					c.drawLine(r.left + paddingL, y, r.right - paddingR, y, mPaintMax1);
					// 绘气压值和差异值
					delta = maxmin - average;
					if (y > r.bottom - paddingB - 48) y = r.bottom - paddingB - 48;
					String text = String.format(Locale.ENGLISH, "%.2f", maxmin / 100.0f);
					c.drawText(text, paddingL - 4, y + 24, mPaintMax1);
					text = String.format(Locale.ENGLISH, ((maxmin > mCenterA) ? "(+%.2f)" : "(%.2f)"), (delta) / 100.0f);
					c.drawText(text, paddingL - 4, y + 48, mPaintMax1);
				}

				// 绘制平均气压值和文字
				String text = String.format(Locale.ENGLISH, "%.2f", average / 100.0f);
				float aY = centerY - (float) (average - mCenterA) * mRatio;
				c.drawLine(r.left + paddingL, aY, r.right - paddingR, aY, mPaintA);
				c.drawText(text, paddingL - 4, aY + 12, mPaintA);

				// 绘制气压值按时间变化轨迹点和折线
				float pX = 4 * (r.right - r.left) / 5;
				pX = r.left + paddingL + ((int) pX / gap) * gap;
				for (int j = 0; j < mCs.length; j++) {
					if (mCs[j] > 0) {
						float x = pX - (j * gap);
						if (x > r.left + paddingL) {
							float newPY = centerY - (float) (mCs[j] - mCenterA) * mRatio;
							float prePY = (j < mCs.length - 1) ? (centerY - (float) (mCs[j + 1] - mCenterA) * mRatio) : newPY;
							c.drawLine(x, newPY, x - gap, prePY, mPaintPLine);
							c.drawCircle(x, newPY, 3, mPaintP);
						}
					}
				}

				// 绘制温度和触发参数文字
				text = String.format(Locale.ENGLISH, "TEMP: %.2f°C   Trig: %.2fmBar", temperature / 100.0f, mTrigger / 100.0f);
				c.drawText(text, r.right - paddingR - 260, paddingT + 30, mPaintA);

				// 绘制主坐标和纵坐标中点
				c.drawLine(r.left + paddingL, centerY, r.left + paddingL + 8, centerY, mPaintMain);
				c.drawLine(r.left + paddingL - 1, r.bottom - paddingB, r.right - paddingR, r.bottom - paddingB, mPaintMain);
				c.drawLine(r.left + paddingL, r.top + paddingT, r.left + paddingL, r.bottom - paddingB, mPaintMain);

				mHolder.unlockCanvasAndPost(c);
			}
		}
	}

	// 发送文字给微信服务号
	private boolean sendTextMsgToWeXin(String msg) {
		HttpPost httpPost = new HttpPost("http://auto.fourtech.me/jdy_service/altimeter/pushTextMsgToUserWeixin.php");
		HttpClient httpClient = new DefaultHttpClient();
		HttpParams httpParams = httpClient.getParams();
		httpParams.setParameter(CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_1);
		httpParams.setParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, true);
		httpParams.setParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8192);
		httpParams.setParameter(CoreProtocolPNames.HTTP_CONTENT_CHARSET, Charset.forName("UTF-8"));
		httpParams.setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 10000);
		httpParams.setParameter(CoreConnectionPNames.SO_TIMEOUT, 10000);
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("text_msg", msg));
		try {
			httpPost.setEntity(new UrlEncodedFormEntity(params, HTTP.UTF_8));
			HttpResponse httpResponse = httpClient.execute(httpPost);
			if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				return true;
			}
		} catch (Throwable t) {
			Log.w(TAG, "sendTextMsgToWeXin() failed", t);
		}
		return false;
	}

	// 把newValue插入数组最前面并依次后移
	private void pushInArray(double[] array, double newValue) {
		for (int i = array.length - 1; i > 0; i--) {
			array[i] = array[i - 1] > 0 ? array[i - 1] : newValue;
		}
		array[0] = newValue;
	}

}
