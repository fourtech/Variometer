package com.fourtech.altimeter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;

public class AltimeterView extends SurfaceView implements Callback {
	private static final String TAG = "AltimeterView";

	private Socket mSocket;
	private SurfaceHolder mHolder;
	private boolean isStarted = false;

	private Looper mLooper;
	private Handler mHandler;
	private double[] mPs = new double[100]; // 当前压力平均值
	private double[] mSortedPs = new double[100]; // 当前压力平均值
	private double[] mCs = new double[30]; // 当前压力平均值
	private Paint mPaintMain, mPaintLine, mPaintMax1, mPaintMax2;
	private Paint mPaintCenter, mPaintA, mPaintP, mPaintPLine;
	private double[] mMaxAndMin;

	public AltimeterView(Context context) {
		this(context, null);
	}

	public AltimeterView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AltimeterView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		getHolder().addCallback(this);
		HandlerThread t = new HandlerThread(TAG);
		t.start();
		mLooper = t.getLooper();
		mHandler = new Handler(mLooper);
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

		mPaintMax2 = new Paint();
		mPaintMax2.setColor(0xff560000);
		mPaintMax2.setStrokeWidth(2);

		mPaintA = new Paint();
		mPaintA.setColor(0xff005656);
		mPaintA.setStrokeWidth(3);

		mPaintP = new Paint();
		mPaintP.setColor(0xffffffff);
		mPaintP.setStrokeWidth(2);

		mPaintPLine = new Paint();
		mPaintPLine.setColor(0xff909090);
		mPaintPLine.setStrokeWidth(2);
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		Log.i(TAG, "surfaceChanged() w=" + width + ", h=" + height);
		if (width > 0 && height > 0) {
			mHolder = holder;
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					doRefreshUI(new double[] { 0, 0, 0 });
				}
			});
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
		isStarted = true;
		new Thread() {
			@Override
			public void run() {
				try {
					// 连接服务器 并设置连接超时为5秒
					mSocket = new Socket();
					mSocket.connect(new InetSocketAddress("120.25.123.98", 8086), 5000);
					// 获取输入输出流
					InputStream in = mSocket.getInputStream();
					OutputStream out = mSocket.getOutputStream();
					out.write(new byte[] { 'w', 's', 'c', 'l', 'i', 'e', 'n', 't' });

					int n = 0;
					byte[] buffer = new byte[1024];
					while (isStarted && (n = in.read(buffer)) != -1) {
						final String msg = new String(buffer, 0, n);
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								refreshUIAsync(msg);
							}
						});
					}

					in.close();
					out.close();
					mSocket.close();
				} catch (Throwable tt) {
					Log.w(TAG, "onStart() error", tt);
				}
			}
		}.start();

	}

	public void onStop() {
		isStarted = false;
	}

	private int mN = 0;
	private double mA = 0;
	private void refreshUIAsync(String msg) {
		Log.i(TAG, "refreshUIAsync() msg=" + msg);
		if (msg == null || !msg.startsWith("[")) {
			Log.i(TAG, "refreshUIAsync() nouse msg...");
			return;
		}
		try {
			mHandler.removeCallbacksAndMessages(null);
			JSONArray jsonArray = new JSONArray(msg);
			for (int i = 0, l = jsonArray.length(); i < l; i++) {
				JSONObject jsonObject = jsonArray.getJSONObject(i);
				double pressure = jsonObject.getDouble("p");
				double temperature = jsonObject.getDouble("t");
				pushInArray(mPs, pressure);
				for (int j = 0; j < mPs.length; j++) {
					mSortedPs[j] = mPs[j];
				}
				Arrays.sort(mSortedPs);
				double a = 0;
				for (int j = 10; j < mPs.length - 10; j++) {
					a += mSortedPs[j];
				}
				a /= mPs.length - 20;
				if (mMaxAndMin == null) {
					mMaxAndMin = new double[] { a, a, a, a };
				}
				if (mN < 40) {
					mA = a;
					mN++;
				}

				final double[] newValues = { a, pressure, temperature };
				Log.i(TAG, "refreshUIAsync() newValues=" + Arrays.toString(newValues));
				mHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						doRefreshUI(newValues);
					}
				}, (i+1) * 120);
			}
		} catch (Throwable tt) {
			Log.w(TAG, "refreshUIAsync() error", tt);
		}
	}

	private void doRefreshUI(final double[] newValues) {
		if (mHolder != null) {
			synchronized (mHolder) {
				double pressure = newValues[1];
				pushInArray(mCs, pressure);

				int gap = 30;
				int padding = 60;
				Canvas c = mHolder.lockCanvas();
				Rect r = mHolder.getSurfaceFrame();
				c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

				for (int x = r.left + padding + gap; x < r.right - padding; x += gap) {
					c.drawLine(x, r.top + padding, x, r.bottom - padding, mPaintLine);
				}
				for (int y = r.top + padding + gap; y < r.bottom - padding; y += gap) {
					c.drawLine(r.left + padding, y, r.right - padding, y, mPaintLine);
				}

				int centerY = r.top + (r.bottom - r.top) / 2;
				c.drawLine(r.left + padding, centerY, r.right - padding, centerY, mPaintCenter);

				if (mMaxAndMin != null) {
					if (pressure > mMaxAndMin[0]) {
						mMaxAndMin[0] = pressure;
					} else if (pressure > mMaxAndMin[1]) {
						mMaxAndMin[1] = pressure;
					} else if (pressure < mMaxAndMin[3]) {
						mMaxAndMin[3] = pressure;
					} else if (pressure < mMaxAndMin[2]) {
						mMaxAndMin[2] = pressure;
					}

					if (mMaxAndMin[0] > 0) {
						float y = centerY - (float) (mMaxAndMin[0] - mA) * 1000;
						c.drawLine(r.left + padding, y, r.right - padding, y, mPaintMax1);
					}
					if (mMaxAndMin[1] > 0) {
						float y = centerY - (float) (mMaxAndMin[1] - mA) * 1000;
						c.drawLine(r.left + padding, y, r.right - padding, y, mPaintMax2);
					}
					if (mMaxAndMin[2] > 0) {
						float y = centerY - (float) (mMaxAndMin[2] - mA) * 1000;
						c.drawLine(r.left + padding, y, r.right - padding, y, mPaintMax2);
					}
					if (mMaxAndMin[3] > 0) {
						float y = centerY - (float) (mMaxAndMin[3] - mA) * 1000;
						c.drawLine(r.left + padding, y, r.right - padding, y, mPaintMax1);
					}
				}

				float aY = centerY - (float) (newValues[0] - mA) * 1000;
				c.drawLine(r.left + padding, aY, r.right - padding, aY, mPaintA);

				float pX = 2 * (r.right - r.left) / 3;
				pX = r.left + ((int) pX / gap) * gap;
				for (int j = 0; j < mCs.length; j++) {
					if (mCs[j] > 0) {
						float x = pX - (j * gap);
						if (x > r.left + padding) {
							float newPY = centerY - (float) (mCs[j] - mA) * 1000;
							float prePY = (j < mCs.length - 1) ? (centerY - (float) (mCs[j + 1] - mA) * 1000) : newPY;
							c.drawLine(x, newPY, x - gap, prePY, mPaintPLine);
							c.drawCircle(x, newPY, 3, mPaintP);
						}
					}
				}

				c.drawLine(r.left + padding - 1, r.bottom - padding, r.right - padding, r.bottom - padding, mPaintMain);
				c.drawLine(r.left + padding, r.top + padding, r.left + padding, r.bottom - padding, mPaintMain);

				mHolder.unlockCanvasAndPost(c);
			}
		}
	}

	private void pushInArray(double[] array, double newValue) {
		for (int i = array.length - 1; i > 0; i--) {
			array[i] = array[i - 1] > 0 ? array[i - 1] : newValue;
		}
		array[0] = newValue;
	}

}
