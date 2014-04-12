/*
 * Copyright (c) 2014. Thomee Wright @ Edge Of Creation
 * Thomee@EdgeOfCreation.org
 */

package org.edgeofcreation.piball.app;

import java.util.ArrayList;

/**
 * Created by elessar on 4/9/14.
 */
public class ReadingsSet {
    public class ReadingEntry {
        public final float time;
        public final float az;
        public final float el;

        public ReadingEntry(float time, float az, float el) {
            this.time = time;
            this.az = az;
            this.el = el;
        }
    }

    private ArrayList<ReadingEntry> mReadings;

    public ReadingsSet() {
        this.mReadings = new ArrayList<ReadingEntry>();
    }

    public void AddReading (float time, float az, float el) {
        this.mReadings.add(new ReadingEntry(time, az, el));
    }

    public ReadingEntry[] getReadings() {
        return this.mReadings.toArray(new ReadingEntry[mReadings.size()]);
    }
}
