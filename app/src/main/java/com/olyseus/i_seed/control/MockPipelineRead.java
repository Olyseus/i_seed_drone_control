package com.olyseus.i_seed.control;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import interconnection.Interconnection;

public class MockPipelineRead {
    private Object mutex = new Object();
    private List<LatLng> missionPath = new ArrayList<LatLng>();
    private List<byte[]> packets = new ArrayList<byte[]>();
    private int protocolVersion = 0;
    private Interconnection.drone_info.state_t state = Interconnection.drone_info.state_t.READY;
    private int event_id = 0;
    private int currentMissionStep = 0;
    private static int stepsEachSegment = 20;
    private double droneLatitude = 48.8472;
    private double droneLongitude = 2.3866;
    private float droneHeading = 0.0F;
    private static String TAG = "MockPipelineRead";

    // read pipe thread
    // 'mutex' already locked
    private void iterateMission() {
        if (state != Interconnection.drone_info.state_t.EXECUTING) {
            return;
        }
        assert(!missionPath.isEmpty());
        if (missionPath.size() == 1) {
            // immediately reach waypoint
            LatLng p = missionPath.get(0);
            droneLatitude = p.latitude;
            droneLongitude = p.longitude;
            state = Interconnection.drone_info.state_t.READY;
            sendDroneInfo();
            return;
        }

        int nSegments = missionPath.size() - 1;
        int totalSteps = nSegments * stepsEachSegment;
        if (currentMissionStep == totalSteps) {
            LatLng p_last = missionPath.get(nSegments);
            droneLatitude = p_last.latitude;
            droneLongitude = p_last.longitude;
            state = Interconnection.drone_info.state_t.READY;
            sendDroneInfo();
            return;
        }
        assert(currentMissionStep < totalSteps);

        int currentSegment = currentMissionStep / stepsEachSegment;
        int currentStepInSegment = currentMissionStep % stepsEachSegment;
        Log.d(TAG, "Segment #" + currentSegment + ", in segment step #" + currentStepInSegment);
        LatLng start = missionPath.get(currentSegment);
        LatLng stop = missionPath.get(currentSegment + 1);
        double coeffStart = 1.0 * (stepsEachSegment - currentStepInSegment) / stepsEachSegment;
        double coeffStop = 1.0 * currentStepInSegment / stepsEachSegment;

        droneLongitude = coeffStart * start.longitude + coeffStop * stop.longitude;
        droneLatitude = coeffStart * start.latitude + coeffStop * stop.latitude;
        sendDroneInfo();

        currentMissionStep += 1;
    }

    private void sleepMs(int ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // UI thread
    // 'mutex' already locked
    private void sendPacket(byte[] packet) {
        Interconnection.packet_size.Builder builder = Interconnection.packet_size.newBuilder();
        builder.setSize(packet.length);
        Log.d(TAG, "sendPacket: size packet #" + packets.size() + " (len: " + builder.build().toByteArray().length + ")");
        packets.add(builder.build().toByteArray());
        Log.d(TAG, "sendPacket: packet #" + packets.size() + " (len: " + packet.length + ")");
        packets.add(packet);
    }

    // UI thread
    // 'mutex' already locked
    private void sendCommand(Interconnection.command_type.command_t cmd) {
        Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
        builder.setVersion(protocolVersion);
        builder.setType(cmd);
        sendPacket(builder.build().toByteArray());
    }

    // UI thread
    // 'mutex' already locked
    private void sendDroneInfo() {
        Log.d(TAG, "sendDroneInfo: DRONE_INFO");
        sendCommand(Interconnection.command_type.command_t.DRONE_INFO);

        Interconnection.drone_info.Builder builder = Interconnection.drone_info.newBuilder();
        builder.setLatitude(droneLatitude);
        builder.setLongitude(droneLongitude);
        builder.setHeading(droneHeading);
        builder.setState(state);
        builder.setEventId(event_id);

        Log.d(TAG, "sendDroneInfo: drone_info");
        sendPacket(builder.build().toByteArray());
    }

    // UI thread
    // 'mutex' already locked
    private void sendMissionPath(InputPolygon inputPolygon) {
        Log.d(TAG, "sendMissionPath");
        Interconnection.mission_path.Builder builder = Interconnection.mission_path.newBuilder();
        missionPath = inputPolygon.mockMissionPath();
        for (LatLng w : missionPath) {
            Interconnection.coordinate.Builder c_builder = Interconnection.coordinate.newBuilder();
            c_builder.setLongitude(w.longitude);
            c_builder.setLatitude(w.latitude);
            builder.addWaypoints(c_builder.build());
        }
        sendPacket(builder.build().toByteArray());
    }

    public MockPipelineRead(int pVersion) {
        protocolVersion = pVersion;
        sendDroneInfo();
    }

    // read pipe thread
    public int readData(byte[] buffer, int length) {
        boolean doSleep = false;
        byte[] data = null;
        synchronized (mutex) {
            if (packets.isEmpty()) {
                iterateMission();
                sleepMs(1000 / 4);
            }
            if (packets.isEmpty()) {
                doSleep = true;
            } else {
                data = packets.get(0);
                Log.d(TAG, "readData: first packet (len: " + data.length + ")");
                packets.remove(0);
            }
        }
        if (doSleep) {
            Log.d(TAG, "No data to read, waiting...");
            sleepMs(1000);
            // Timeout: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L22
            return -10008;
        }
        assert(data != null);
        assert(data.length == length);
        System.arraycopy(data, 0, buffer, 0, length);
        return length;
    }

    // UI thread
    public void buildMission(InputPolygon inputPolygon, int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "buildMission");
            event_id = new_event_id;

            sleepMs(1000);
            Log.d(TAG, "Send PATH_DATA drone info");
            state = Interconnection.drone_info.state_t.PATH_DATA;
            sendDroneInfo();

            sleepMs(1000);
            Log.d(TAG, "Send mission path");
            sendMissionPath(inputPolygon);

            sleepMs(1000);
            Log.d(TAG, "Send PATH drone info");
            state = Interconnection.drone_info.state_t.PATH;
            sendDroneInfo();
        }
    }

    // write pipe thread
    public void missionPathCancel(int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "missionPathCancel");
            event_id = new_event_id;

            sleepMs(1000);
            Log.d(TAG, "Send READY drone info");
            state = Interconnection.drone_info.state_t.READY;
            sendDroneInfo();
        }
    }

    // write pipe thread
    public void missionStart(int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "missionStart");
            event_id = new_event_id;
            currentMissionStep = 0;

            sleepMs(1000);
            Log.d(TAG, "Send EXECUTING drone info");
            state = Interconnection.drone_info.state_t.EXECUTING;
            sendDroneInfo();
        }
    }

    // write pipe thread
    public void missionPause(int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "missionPause");
            event_id = new_event_id;

            sleepMs(1000);
            Log.d(TAG, "Send PAUSED drone info");
            state = Interconnection.drone_info.state_t.PAUSED;
            sendDroneInfo();
        }
    }

    // write pipe thread
    public void missionContinue(int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "missionContinue");
            event_id = new_event_id;

            sleepMs(1000);
            Log.d(TAG, "Send EXECUTING drone info");
            state = Interconnection.drone_info.state_t.EXECUTING;
            sendDroneInfo();
        }
    }

    // write pipe thread
    public void missionAbort(int new_event_id) {
        synchronized (mutex) {
            Log.d(TAG, "missionAbort");
            event_id = new_event_id;

            sleepMs(1000);
            Log.d(TAG, "Send READY drone info");
            state = Interconnection.drone_info.state_t.READY;
            sendDroneInfo();
        }
    }
}