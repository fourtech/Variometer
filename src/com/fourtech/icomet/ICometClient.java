package com.fourtech.icomet;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import android.util.Log;

import com.fourtech.icomet.message.Message;
import com.fourtech.icomet.message.MessageInputStream;

public class ICometClient {

	public static class State {
		// status for a client just created
		public static final int STATE_NEW = 0;
		// status for a prepared client
		public static final int STATE_READY = 1;
		// status for a client which has connected to iComet server
		public static final int STATE_CONNCTED = 2;
		// status for a client working with sending or receiving message
		public static final int STATE_COMET = 3;
		// comet was stopped manually
		public static final int STATE_STOP = 4;
		// stop the comet client
		public static final int STATE_STOP_PENDING = 5;
		// disconnect from iComet server, usually by error
		public static final int STATE_DISCONNECT = 6;
	}

	// delay for reconnection, if times of reconnection is larger than 3, treat as 3
	// you can deal with reconnection with onReconnect() method. too much times
	// of reconnection is not recommended
	private static final int[] DELAY = { 30, 120, 600 };

	// host for your iComet server
	// private String host;
	// port of the server for iComet
	// private String port;
	// the URL for connection to iComet server
	private String finalUrl;
	// record the times of reconnection
	private int mReconnTimes = 0;
	// channel object got from your business server
	private Channel mChannel;

	private static ICometClient mClient = new ICometClient();
	private ICometCallback mICometCallback;
	private IConnCallback mIConnCallback;

	private HttpURLConnection mConn;
	private MessageInputStream mInput;
	private OutputStream mOutput;

	private ICometConf mConf;

	// current status
	private int mStatus = State.STATE_NEW;

	private ICometClient() {

	}

	/**
	 * get the single Instance of ICometClient
	 */
	public static ICometClient getInstance() {
		if (mClient == null) {
			mClient = new ICometClient();
		}
		return mClient;
	}

	/**
	 * prepare for connection
	 */
	public void prepare(ICometConf conf) {
		if (conf.channelAllocator == null) {
			conf.channelAllocator = new DefaultChannelAllocator();
		}
		mConf = conf;
		if (mReconnTimes == 0) {
			this.mChannel = conf.channelAllocator.allocate();
		}
		this.finalUrl = buildURL(conf.url);
		this.mICometCallback = conf.iCometCallback;
		this.mIConnCallback = conf.iConnCallback;
		this.mStatus = State.STATE_READY;
	}

	/**
	 * connect to iComet server please call this method in a child thread
	 */
	public void connect() {
		if (this.mStatus != State.STATE_READY) {
			return;
		}
		try {
			System.out.println(this.finalUrl);
			mConn = (HttpURLConnection) new URL(this.finalUrl).openConnection();
			mConn.setRequestMethod("GET");
			mConn.setConnectTimeout(3 * 60 * 1000);
			mConn.setDoInput(true);
			mConn.connect();
			mInput = new MessageInputStream(mConn.getInputStream());
			// mOutput = mConn.getOutputStream();

		} catch (Exception e) {
			if (mConn != null) {
				mConn.disconnect();
			}
			if (mIConnCallback != null) {
				mIConnCallback.onFail(e.getMessage());
			}
			reconnect();
			return;
		}

		this.mStatus = State.STATE_CONNCTED;

		if (mIConnCallback != null) {
			if (mReconnTimes == 0) {
				mIConnCallback.onSuccess();
			} else {
				mIConnCallback.onReconnectSuccess(mReconnTimes);
				mReconnTimes = 0;
			}
		}

	}

	/**
	 * start a new thread to deal with the data transfer
	 */
	public void comet() {
		if (this.mStatus != State.STATE_CONNCTED) {
			return;
		}
		this.mStatus = State.STATE_COMET;
		new SubThread().start();
		// 启动心跳线程
		// new NoopThread().start();
	}

	/**
	 * close the connection to iComet server
	 */
	public void stopComet() {
		mStatus = State.STATE_STOP_PENDING;
	}

	/**
	 * stop connecting to iComet server
	 */
	public void stopConnect() {
		if (mConn != null) {
			mConn.disconnect();
			mConn = null;
		}
	}

	/**
	 * get current status of this client
	 * 
	 * @return status
	 */
	public int currStatus() {
		return mStatus;
	}

	/**
	 * used to reconnect to the server when the connection lose or an error
	 * occur
	 */
	private void reconnect() {
		if (mIConnCallback == null) {
			return;
		}

		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				mReconnTimes++;
				if (!mIConnCallback.onReconnect(mReconnTimes)) {
					if (mStatus != State.STATE_READY) {
						prepare(mConf);
					}
					connect();
				}
			}
		}, DELAY[mReconnTimes > 2 ? 2 : mReconnTimes] * 1000);

	}

	/**
	 * build the URL by method and args
	 * @param method pub sub sign frame
	 * @param args argument
	 * @return URL
	 */
	private String buildURL(String url) {
		StringBuilder sb = new StringBuilder();
		if (!this.mConf.host.startsWith("http://")) {
			sb.append("http://");
		}
		sb.append(this.mConf.host);
		if (!isEmpty(this.mConf.port)) {
			sb.append(":").append(this.mConf.port);
		}
		if (!isEmpty(url)) {
			sb.append("/").append(url);
		}
		if (mConf.expires <= 0 || mConf.expires > 30) {
			mConf.expires = 30;
		}
		if (mChannel == null) {
			return sb.toString();
		}

		sb.append("?");
		sb.append("cname=").append(mChannel.cname);
		sb.append("&").append("seq=").append(mChannel.seq);
		sb.append("&").append("token=").append(mChannel.token);
		sb.append("&").append("expires=").append(mConf.expires);
		sb.append("&").append("ttttt=").append(System.currentTimeMillis());
		return sb.toString();
	}

	/**
	 * thread for retrieving data from iComet server
	 * 
	 * @author keyleduo
	 */
	private class SubThread extends Thread {

		@Override
		public void run() {
			super.run();

			if (mICometCallback == null) {
				throw new IllegalArgumentException("There always should be an ICometCallback to deal with the coming data");
			}

			try {
				while (mStatus == ICometClient.State.STATE_COMET) {
					// block here
					Message msg = mInput.readMessage();
					if (msg != null) {

						mICometCallback.onMsgArrived(msg);

						if (msg.type.equals(Message.Type.TYPE_DATA)
								&& msg.content != null
								&& msg.content.length() > 0) {
							System.out.println(msg);
							Log.d("todd", "msg:" + msg);
							// here comes a data message
							// Message.Content content = gson.fromJson(msg.content, Message.Content.class);
							// mChannel.seq++;
							// mICometCallback.onDataMsgArrived(content);

						} else if (msg.type.equals(Message.Type.TYPE_NOOP)) {

						} else if (msg.type.equals(Message.Type.TYPE_NEXT_SEQ)) {

						} else {
							mICometCallback.onErrorMsgArrived(msg);
						}

					} else {
						// TODO error data

					}
				}
			} catch (Exception e) {
				Log.d("todd", "异常:", e);
				e.printStackTrace();
				mIConnCallback.onDisconnect();
				mStatus = ICometClient.State.STATE_DISCONNECT;
				reconnect();
				return;
			}

			mStatus = ICometClient.State.STATE_STOP;
			if (mIConnCallback != null) {
				mIConnCallback.onStop();
			}
		}
	}

	/**
	 * 心跳线程
	 */
	@SuppressWarnings("unused")
	private class NoopThread extends Thread {
		int seq = 0;

		@Override
		public void run() {
			try {
				while (mStatus == ICometClient.State.STATE_COMET) {
					String msg = "{\"type\":\"noop\",\"cname\":\""
							+ mChannel.cname + "\",\"seq\":" + seq
							+ ",\"content\":\"noop\"}";
					mOutput.write(msg.getBytes());
					seq %= 255;

					Thread.sleep(30000);
				}
			} catch (Exception e) {
				return;
			}
		}
	}

	/**
	 * judge if the source is empty
	 */
	public boolean isEmpty(String source) {
		if (source == null || source.length() < 1) {
			return true;
		}
		return false;
	}

}
