package com.fourtech.icomet;

import com.fourtech.icomet.message.Message;

public interface ICometCallback {
	// a message with data arrived
	void onDataMsgArrived(Message.Content content);

	// a message arrived, maybe not with data
	void onMsgArrived(Message msg);

	// a error message arrived
	void onErrorMsgArrived(Message msg);

	// message format error, can not parse json
	void onMsgFormatError();
}