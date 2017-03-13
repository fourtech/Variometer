package com.fourtech.icomet.message;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MessageInputStream {
	private InputStream input;
	private BufferedReader reader;

	public MessageInputStream(InputStream input) {
		this.input = input;
		reader = new BufferedReader(new InputStreamReader(this.input));
	}

	private String read() throws Exception {
		return reader.readLine();
	}

	/**
	 * get a message from the input stream, possibly a stream from sub connection
	 * @return a general message, caller should judge the type of this message and do the following work
	 * @throws Exception
	 */
	public Message readMessage() throws Exception {
		Message msg = null;

		String jsonData = this.read();
		if (jsonData != null) {
			msg = new Message();
			msg.type = Message.Type.TYPE_DATA;
			msg.content = jsonData;
			// msg = gson.fromJson(jsonData, Message.class);
		}
		return msg;
	}

}