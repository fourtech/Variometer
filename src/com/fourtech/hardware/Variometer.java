package com.fourtech.hardware;

public class Variometer {
	static {
		System.loadLibrary("altimeter_jni");
	}
	public native void open();
	public native void close();
	public native void getValues(int[] outValues);
	public native int getPressure();
	public native int getTemperature();
}
