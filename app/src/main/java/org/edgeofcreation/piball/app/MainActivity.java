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
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;


public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "PiBall";

    private Timer mTmr;
    private Date mStart;
    private boolean mStarting;

    private ToneGenerator mToneGen;

    private int mReadInterval, mPreTone;
    private float mAveraging;

    private SensorManager mSensMan;
    private Sensor mSensAccel, mSensMag;
    private float[] mCurAcc, mCurMag;
    private float mCurAz, mCurEl, mAvgAz, mAvgEl;

    private TextView mNextTime, mNextAz, mNextEl;
    private TextView mTopAz, mTopEl;

    private ReadingsSet mReadings;


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

        mPreTone = 200;
        mReadInterval = 2;
        mAveraging = (1.0f - 0.1f);
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
            // We're running
            btnStop_onClick(v);
            return;
        }
        if (mReadings != null) {
            // We have readings to calculate
            btnCalc_onClick(v);
            return;
        }
        btn.setText("Stop");

        mReadings = new ReadingsSet();

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
        btn.setText("Calculate");
        mStart = null;
    }

    private final double mAR = 400; // Ascent Rate - 300 ft/min
    private final double mDegPerRad = 180.0 / Math.PI;
    private final double mSecPerHr = 3600;
    private final double mFtPerNM = 6076.12;
    private void btnCalc_onClick (View v) {
        Log.v(TAG, "Calculating from:");
        ReadingsSet.ReadingEntry[] raw = mReadings.getReadings();
        /*
        ReadingsSet fake = new ReadingsSet();
        fake.AddReading(0,0,0);
        fake.AddReading(60f,230.3f,9.5f);
        fake.AddReading(120f,225.4f,10.1f);
        fake.AddReading(180f,223f,11.6f);
        fake.AddReading(240f,223.8f,13.2f);
        fake.AddReading(300f,225.1f,14.6f);
        fake.AddReading(420f,223.5f,17.2f);
        fake.AddReading(540f,221.1f,20f);
        fake.AddReading(660f,218.3f,21.8f);
        fake.AddReading(780f,212.9f,24.9f);
        fake.AddReading(900f,207.7f,27.5f);
        fake.AddReading(1020f,203f,28.8f);
        fake.AddReading(1140f,200.1f,30.2f);
        fake.AddReading(1260f,199.9f,31.6f);
        fake.AddReading(1380f,202.1f,32.8f);
        fake.AddReading(1500f,203.5f,34f);
        fake.AddReading(1620f,205.4f,36.1f);
        fake.AddReading(1740f,206.2f,37.7f);
        fake.AddReading(1860f,204.2f,39.1f);
        fake.AddReading(1980f,199.2f,40.8f);
        fake.AddReading(2100f,191.6f,42f);
        fake.AddReading(2220f,183.7f,43.4f);
        fake.AddReading(2340f,175.4f,45f);
        ReadingsSet.ReadingEntry[] raw = fake.getReadings();
        */
        for (int i = 0; i < raw.length; i++) {
            Log.v(TAG, String.format("Time: %06.2f, Az: %06.2f, El: %06.2f",
                    raw[i].time, raw[i].az, raw[i].el));
        }
        ResultsEntry[] res = new ResultsEntry[raw.length - 1];
        double x0 = 0;
        double y0 = 0;
        double h0 = 0;
        for (int i = 0; i < raw.length - 1; i++) {
            double ht = raw[i+1].time / 60.0f * mAR; // ht in feet
            double hd = ht / Math.tan(raw[i+1].el / mDegPerRad); // horiz dist in feet
            double x = hd * Math.cos(raw[i+1].az / mDegPerRad); // x coord in feet
            double y = hd * Math.sin(raw[i+1].az / mDegPerRad); // y coord in feet

            double dt = raw[i+1].time - raw[i].time;
            double dy = y - y0;
            double dx = x - x0;
            double dd = Math.sqrt(dx * dx + dy * dy); // horiz dist since last point
            double spd = dd / dt; // speed in ft/sec since last point
            spd = spd * mSecPerHr / mFtPerNM; // convert from ft/sec to kts (nm/hr)
            double hdg = Math.atan(dy / dx) * mDegPerRad;
            //hdg += 180; // Convert from "winds to" to "winds from" and from -180..180 to 0..360
            if (hdg < 0) { hdg += 360; }

            Log.v(TAG,String.format(
                    "%f %f %f %f %f %f %f %f %f %f",
                    ht, hd, x, y, dt, dy, dx, dd, spd, hdg));

            res[i] = new ResultsEntry((float)ht, (float)hdg, (float)spd);

            x0 = x;
            y0 = y;
            h0 = ht;
        }
        Log.v(TAG, "Results:");
        for (int i = 0; i < res.length; i++) {
            Log.v(TAG, String.format("%04.0fft: %02.0fkts from %03.0fdeg",
                    res[i].height, res[i].speed, res[i].heading));
        }
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

        final ScrollView sv = (ScrollView)findViewById(R.id.scrollView);
        // if we just call fullScroll here, the table view hasn't had a chance to
        // properly update its size, so the scrollView will stop one line short; if we
        // post it to the scrollView, the tableLayout will do its thing first, then the
        // scrollView will go all the way to the bottom.
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
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
        if ((mAveraging > 0) && (mAveraging < 1)) {
            mAvgAz = (mAveraging * mAvgAz) + ((1 - mAveraging) * mCurAz);
            mAvgEl = (mAveraging * mAvgEl) + ((1 - mAveraging) * mCurEl);
        } else {
            mAvgAz = mCurAz;
            mAvgEl = mCurEl;
        }
        mNextAz.setText(Float.toString(mAvgAz));
        mNextEl.setText(Float.toString(mAvgEl));
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
            mAvgAz = mCurAz;
            mAvgEl = mCurEl;
        } else {
            time = (new Date()).getTime() - mStart.getTime();
        }

        final boolean valid = true;//recalcAzEl();
        final float az = mAvgAz;
        final float el = mAvgEl;
        mReadings.AddReading(time / 1000.0f, az, el);

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
            mNextAz.setText(Float.toString(az));
            mNextEl.setText(Float.toString(el));
        } else {
            mNextAz.setText("---");
            mNextEl.setText("---");
        }

        mNextTime = mNextAz = mNextEl = null;
    }

    public class ResultsEntry {
        public final float height;
        public final float heading;
        public final float speed;

        public ResultsEntry(float height, float heading, float speed) {
            this.height = height;
            this.heading = heading;
            this.speed = speed;
        }
    }
}

