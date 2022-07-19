package com.olyseus.i_seed.control;

import android.util.Log;

import dji.common.camera.LaserError;

public class LaserStatus {
    private static final String TAG = "MainActivity";
    private static String statusNA = "N/A";
    private String laserStatus = statusNA;
    private boolean enabled = false;
    private Object mutex = new Object();

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
            laserStatus = String.format("%.1f", targetDistance);
        }
    }
}
