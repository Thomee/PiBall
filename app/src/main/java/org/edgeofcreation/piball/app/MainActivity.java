/*
 * Copyright (c) 2014. Thomee Wright @ Edge Of Creation
 * Thomee@EdgeOfCreation.org
 */

package org.edgeofcreation.piball.app;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;


public class MainActivity extends Activity implements SensorEventListener {

    private Timer mTmr;
    private Date mStart;
    private boolean mStarting;

    private ToneGenerator mToneGen;

    private int mReadInterval, mPreTone;

    private SensorManager mSensMan;
    private Sensor mSensAccel, mSensMag;
    private float[] mCurAcc, mCurMag;
    private float mCurAz, mCurEl;

    private TextView mNextTime, mNextAz, mNextEl;
    private TextView mTopAz, mTopEl;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // force screen orientation to device's "native", so that our display
        // agrees with the internal sensors' idea of the world
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTopAz = (TextView)findViewById(R.id.tvAz);
        mTopEl = (TextView)findViewById(R.id.tvEl);

        mTmr = null;
        mStart = null;
        mStarting = false;

        mSensMan = (SensorManager)getSystemService(SENSOR_SERVICE);
        mSensAccel = mSensMan.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensMag = mSensMan.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        mCurAcc = mCurMag = null;
        mNextTime = mNextAz = mNextEl = null;

        mPreTone = 1000;
        mReadInterval = 5;
    }

    protected void onResume() {
        super.onResume();

        mToneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);

        mSensMan.registerListener(this, mSensAccel, SensorManager.SENSOR_DELAY_NORMAL);
        mSensMan.registerListener(this, mSensMag, SensorManager.SENSOR_DELAY_NORMAL);
    }

    protected void onPause() {
        if (mTmr != null) {
            mTmr.cancel();
            mTmr = null;
        }

        mSensMan.unregisterListener(this);

        mToneGen.release();
        mToneGen = null;

        super.onPause();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                mCurAcc = sensorEvent.values;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mCurMag = sensorEvent.values;
                break;
        }
        if (!recalcAzEl()) { return; }
        mTopAz.setText(Float.toString(mCurAz));
        mTopEl.setText(Float.toString(mCurEl));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    public void btnStart_onClick (View v) {
        Button btn = (Button)v;
        if (mTmr != null) {
            btnStop_onClick(v);
            return;
        }
        btn.setText("Stop");

        mStart = new Date((new Date()).getTime() + mPreTone * 2);
        mStarting = true;
        Date pre = new Date(mStart.getTime() - mPreTone);

        mTmr = new Timer();
        mTmr.scheduleAtFixedRate(
                new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        tmrTick();
                                    }
                                }
                        );
                    }
                },
                0, 100
        );
        mTmr.scheduleAtFixedRate(
                new TimerTask() {
                    @Override
                    public void run() {
                        tmrRead();
                    }
                },
                mStart, mReadInterval * 1000);
        mTmr.scheduleAtFixedRate(
                new TimerTask() {
                    @Override
                    public void run() {
                        tmrPreTone();
                    }
                },
                pre, mReadInterval * 1000);

    }

    private void btnStop_onClick (View v) {
        mTmr.cancel();
        mTmr = null;
        Button btn = (Button)v;
        btn.setText("Start");
        mStart = null;
    }

    private boolean recalcAzEl () {
        if ((mCurAcc == null) || (mCurMag == null)) { return false; }

        float[] rot = new float[9];

        if (!SensorManager.getRotationMatrix(rot, null, mCurAcc, mCurMag)) {
            return false;
        }

        float[] or = SensorManager.getOrientation(rot, new float[3]);
        float azi = (float)(or[0] / Math.PI * 180.0);
        if (azi < 0) { azi += 360; }
        float elev = (float)(or[1] / Math.PI * 180.0);
        elev = -elev; // getOrientation returns 90 for straight down, -90 for straight up
        mCurAz = azi;
        mCurEl = elev;

        return true;
    }

    private void addRow() {
        TableLayout tl = (TableLayout)findViewById(R.id.tblResults);
        TableRow tr = new TableRow(this);
        tl.addView(tr);

        mNextTime = new TextView(this);
        mNextTime.setText("0 sec");
        tr.addView(mNextTime);
        mNextAz = new TextView(this);
        mNextAz.setText("0 az");
        tr.addView(mNextAz);
        mNextEl = new TextView(this);
        mNextEl.setText("0 el");
        tr.addView(mNextEl);
    }

    private void tmrTick () {
        if (mStart == null) { return; }

        if (mNextTime == null) {
            addRow();
        }
        long time = (new Date()).getTime() - mStart.getTime();
        mNextTime.setText(Double.toString(time / 1000.0));

        if (!recalcAzEl()) {
            mNextAz.setText("---");
            mNextEl.setText("---");
            return;
        }
        mNextAz.setText(Float.toString(mCurAz));
        mNextEl.setText(Float.toString(mCurEl));
    }

    private void tmrPreTone() {
        mToneGen.startTone(ToneGenerator.TONE_DTMF_1, mPreTone);
    }

    private void tmrRead() {
        if (mStart == null) { return; }

        mToneGen.startTone(ToneGenerator.TONE_DTMF_D, 100);

        final long time;
        if (mStarting) {
            mStarting = false;
            mStart = new Date();
            time = 0;
        } else {
            time = (new Date()).getTime() - mStart.getTime();
        }

        final boolean valid = recalcAzEl();
        final float az = mCurAz;
        final float el = mCurEl;

        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        tmrReadFinish(time, az, el, valid);
                    }
                }
        );
    }

    private void tmrReadFinish(long time, float az, float el, boolean valid) {
        // This part is run in the UI thread
        mNextTime.setText(Double.toString(time / 1000.0));

        if (valid) {
            mNextAz.setText(Float.toString(mCurAz));
            mNextEl.setText(Float.toString(mCurEl));
        } else {
            mNextAz.setText("---");
            mNextEl.setText("---");
        }

        mNextTime = mNextAz = mNextEl = null;
    }
}

