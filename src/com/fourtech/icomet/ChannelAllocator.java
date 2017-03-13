package com.fourtech.icomet;

/**
 * you should implement this interface to connect to your own server for the channel, token, sequence
 */
public interface ChannelAllocator {
	/**
	 * you should never return null for this method
	 * @return Channel channel
	 */
	Channel allocate();
}
