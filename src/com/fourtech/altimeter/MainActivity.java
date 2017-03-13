package com.fourtech.altimeter;

import android.app.Activity;
import android.os.Bundle;

import com.fourtech.variometer.R;

public class MainActivity extends Activity {

	private AltimeterView mAltimeterView;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mAltimeterView = (AltimeterView) findViewById(R.id.altimeterView);
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
}
