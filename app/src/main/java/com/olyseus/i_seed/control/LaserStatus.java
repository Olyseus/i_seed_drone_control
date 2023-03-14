package com.olyseus.i_seed.control;

import android.util.Log;

import dji.common.camera.LaserError;

public class LaserStatus {
    private static final String TAG = "MainActivity";
    private static String statusNA = "N/A";
    private String laserStatus = statusNA;
    private boolean enabled = false;
    private Object mutex = new Object();
    private boolean hasLatestValue = false;
    private float latestValue = 0.0F;
    private boolean mockDrone = false;

    public LaserStatus(boolean mock) {
        mockDrone = mock;
    }

    public void setEnabled(boolean enable) {
        synchronized (mutex) {
            laserStatus = statusNA;
            enabled = enable;
        }
    }

    public boolean isEnabled() {
        synchronized (mutex) {
            return enabled;
        }
    }

    public String getLaserStatus() {
        synchronized (mutex) {
            return laserStatus;
        }
    }

    public boolean hasValue() {
        if (mockDrone) {
            return true;
        }
        synchronized (mutex) {
            return hasLatestValue;
        }
    }

    public float getValue() {
        if (mockDrone) {
            return 15.0F;
        }
        synchronized (mutex) {
            assert(hasLatestValue);
            hasLatestValue = false;
            return latestValue;
        }
    }

    public void onUpdate(LaserError error, float targetDistance) {
        synchronized (mutex) {
            if (error == LaserError.TOO_FAR) {
                Log.d(TAG, "Laser is too far");
                laserStatus = "too far";
                return;
            }
            if (error == LaserError.TOO_CLOSE) {
                Log.d(TAG, "Laser is too close");
                laserStatus = "too close";
                return;
            }
            if (error == LaserError.NO_SIGNAL) {
                Log.e(TAG, "Laser no signal");
                laserStatus = statusNA;
                return;
            }
            if (error == LaserError.UNKNOWN) {
                Log.e(TAG, "Laser state unknown");
                laserStatus = statusNA;
                return;
            }
            hasLatestValue = true;
            latestValue = targetDistance;
            laserStatus = String.format("%.1f", targetDistance);
        }
    }
}
