package com.fourtech.icomet;

public class ICometConf {
	public String host;
	public String port;
	public String url;
	public int expires = 30;

	public ChannelAllocator channelAllocator;
	public ICometCallback iCometCallback;
	public IConnCallback iConnCallback;
}