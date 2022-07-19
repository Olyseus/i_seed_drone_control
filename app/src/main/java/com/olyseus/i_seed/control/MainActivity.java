package com.olyseus.i_seed.control;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;

import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PatternItem;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dji.common.camera.LaserError;
import dji.common.camera.LaserMeasureInformation;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
import dji.common.util.CommonCallbacks;
import dji.mop.common.PipelineError;
import dji.mop.common.Pipelines;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.Lens;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.mop.common.Pipeline;
import dji.mop.common.TransmissionControlType;

import interconnection.Interconnection;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, GoogleMap.OnMapClickListener {
    private static final String TAG = "MainActivity";
    private GoogleMap gMap;
    private ScheduledExecutorService pollExecutor;
    private ScheduledExecutorService readPipelineExecutor;
    private ScheduledExecutorService writePipelineExecutor;
    private Handler handler;
    private boolean sdkRegistrationStarted = false;
    private static boolean useBridge = false;
    private Aircraft aircraft = null;
    Marker droneMarker = null;
    private Object pinCoordinatesMutex = new Object();
    Marker pinPoint = null;
    private double pinLongitude = 0.0;
    private double pinLatitude = 0.0;
    Polyline tripLine = null;
    private Object mutex = new Object();
    private List<Interconnection.command_type.command_t> executeCommands = new ArrayList<Interconnection.command_type.command_t>();
    private int commandByteLength = 0;
    private int droneCoordinatesByteLength = 0;
    private static int protocolVersion = 6; // Keep it consistent with Onboard SDK
    private static int channelID = 9745; // Just a random number. Keep it consistent with Onboard SDK
    private Object droneCoordinatesMutex = new Object();
    private double droneLongitude = 0.0;
    private double droneLatitude = 0.0;
    private float droneHeading = 0.0F;
    private boolean appOnPause = false;

    enum State {
        NO_PERMISSIONS,
        MAP_NOT_READY,
        NOT_REGISTERED,
        REGISTRATION_FAILED,
        NO_PRODUCT,
        CONNECTING,
        INTERCONNECTION_ERROR,
        NO_GPS,
        LASER_OFF,
        WAIT_LASER,
        LASER_ERROR,
        ONLINE
    }

    enum Mission {
        STOPPED,
        IN_PROGRESS,
        PAUSED
    }

    private AtomicReference<State> state = new AtomicReference<State>(State.NO_PERMISSIONS);
    private AtomicReference<LaserError> laserError = new AtomicReference<LaserError>(LaserError.NO_SIGNAL);
    private AtomicReference<Float> laserDistance = new AtomicReference<Float>(0.0F);
    private AtomicReference<Mission> mission_status = new AtomicReference<Mission>(Mission.STOPPED);

    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
    };
    private List<String> missingPermission = new ArrayList<>();
    private static final int REQUEST_PERMISSION_CODE = 4934; // Just a random number

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appOnPause = false;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button actionButton = (Button) findViewById(R.id.actionButton);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { actionButtonClicked(); }
        });
        ImageButton homeButton = (ImageButton) findViewById(R.id.homeButton);
        homeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { homeButtonClicked(); }
        });
        ImageButton cancelButton = findViewById(R.id.cancelButton);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { cancelButtonClicked(); }
        });

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                updateUIState();
            }
        };

        Interconnection.command_type.Builder builder1 = Interconnection.command_type.newBuilder();
        builder1.setVersion(protocolVersion);
        builder1.setType(Interconnection.command_type.command_t.PING);
        commandByteLength = builder1.build().toByteArray().length;
        assert (commandByteLength > 0);

        Interconnection.drone_coordinates.Builder builder2 = Interconnection.drone_coordinates.newBuilder();
        builder2.setLatitude(0.0);
        builder2.setLongitude(0.0);
        builder2.setHeading(0.0F);
        droneCoordinatesByteLength = builder2.build().toByteArray().length;
        assert (droneCoordinatesByteLength > 0);

        updateUIState(); // Init

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        checkAndRequestPermissions();

        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        readPipelineExecutor = Executors.newSingleThreadScheduledExecutor();
        writePipelineExecutor = Executors.newSingleThreadScheduledExecutor();

        Runnable pollRunnable = new Runnable() {
            @Override public void run() {
              try {
                pollJob();
              }
              catch (Throwable e) {
                e.printStackTrace();
                Log.e(TAG, "pollJob exception: " + e);
              }
            }
        };
        Runnable readPipelineRunnable = new Runnable() {
            @Override
            public void run() {
              try {
                readPipelineJob();
              }
              catch (Throwable e) {
                e.printStackTrace();
                Log.e(TAG, "readPipelineJob exception: " + e);
              }
            }
        };
        Runnable writePipelineRunnable = new Runnable() {
            @Override
            public void run() {
              try {
                writePipelineJob();
              }
              catch (Throwable e) {
                e.printStackTrace();
                Log.e(TAG, "writePipelineJob exception: " + e);
              }
            }
        };
        pollExecutor.scheduleAtFixedRate(pollRunnable, 0, 200, TimeUnit.MILLISECONDS);
        readPipelineExecutor.scheduleAtFixedRate(readPipelineRunnable, 0, 200, TimeUnit.MILLISECONDS);
        writePipelineExecutor.scheduleAtFixedRate(writePipelineRunnable, 0, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onPause() {
        appOnPause = true;
        Log.d(TAG, "onPause");
        super.onPause();

        disconnect();
    }

    @Override
    protected void onResume() {
        appOnPause = false;
        Log.d(TAG, "onResume");
        super.onResume();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();

        disconnect();
    }

    // UI thread
    // read pipe thread
    private void setMissionStatus(Mission newMissionStatus, boolean fromDrone) {
        switch (newMissionStatus) {
            case STOPPED:
                assert(mission_status.get() != Mission.STOPPED);
                if (fromDrone) {
                    // Mission finished
                } else {
                    // Aborting mission
                    synchronized (mutex) {
                        executeCommands.add(Interconnection.command_type.command_t.MISSION_ABORT);
                    }
                }
                break;
            case IN_PROGRESS:
                assert(mission_status.get() != Mission.IN_PROGRESS);
                assert(!fromDrone);
                synchronized (mutex) {
                    executeCommands.add(Interconnection.command_type.command_t.MISSION_START);
                }
                break;
            case PAUSED:
                assert(mission_status.get() == Mission.IN_PROGRESS);
                assert(!fromDrone);
                synchronized (mutex) {
                    executeCommands.add(Interconnection.command_type.command_t.MISSION_PAUSE);
                }
                break;
        }
        mission_status.set(newMissionStatus);
        handler.sendEmptyMessage(0);
    }

    // UI thread
    private void actionButtonClicked() {
        if (mission_status.get() == Mission.PAUSED) {
            setMissionStatus(Mission.IN_PROGRESS, false);
            return;
        }
        if (mission_status.get() == Mission.IN_PROGRESS) {
            setMissionStatus(Mission.PAUSED, false);
            return;
        }
        if (state.get() != State.ONLINE) {
            new MaterialAlertDialogBuilder(this).setMessage("The drone is not online").setPositiveButton("Ok", null).show();
            return;
        }
        if (gMap == null) {
            new MaterialAlertDialogBuilder(this).setMessage("The map is not ready yet").setPositiveButton("Ok", null).show();
            return;
        }
        if (pinPoint == null) {
            new MaterialAlertDialogBuilder(this).setMessage("Please drop a pin to start").setPositiveButton("Ok", null).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to start this mission?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setMissionStatus(Mission.IN_PROGRESS, false);
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void homeButtonClicked() {
        zoomToDrone();
    }

    // UI thread
    private void cancelButtonClicked() {
        if (mission_status.get() != Mission.STOPPED) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Are you sure you want to abort this mission?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            setMissionStatus(Mission.STOPPED, false);
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
            return;
        }

        assert(pinPoint != null);
        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to remove the pin?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pinPoint.remove();
                        tripLine.remove();
                        pinPoint = null;
                        tripLine = null;
                        updateUIState();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    // UI thread
    @Override
    public void onMapClick(LatLng point) {
        if (droneMarker == null) {
            return;
        }
        if (mission_status.get() != Mission.STOPPED) {
            return;
        }
        if (pinPoint == null) {
            MarkerOptions markerOptions = new MarkerOptions();
            markerOptions.position(point);
            markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));
            pinPoint = gMap.addMarker(markerOptions);
            tripLine = gMap.addPolyline(new PolylineOptions().add(droneMarker.getPosition(), pinPoint.getPosition()));
            synchronized (pinCoordinatesMutex) {
                pinLongitude = pinPoint.getPosition().longitude;
                pinLatitude = pinPoint.getPosition().latitude;
            }
            PatternItem DASH = new Dash(20);
            PatternItem GAP = new Gap(20);
            tripLine.setPattern(Arrays.asList(DASH, GAP));
            updateUIState();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to remove the old pin?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pinPoint.setPosition(point);
                        tripLine.setPoints(Arrays.asList(droneMarker.getPosition(), pinPoint.getPosition()));
                        synchronized (pinCoordinatesMutex) {
                            pinLongitude = pinPoint.getPosition().longitude;
                            pinLatitude = pinPoint.getPosition().latitude;
                        }
                        updateUIState();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void updateState(State newState) {
        state.set(newState);
        handler.sendEmptyMessage(0);
    }

    private void pollJob() {
        if (appOnPause) {
            return;
        }

        if (!missingPermission.isEmpty()) {
            updateState(State.NO_PERMISSIONS);
            return;
        }

        if (gMap == null) {
            updateState(State.MAP_NOT_READY);
            return;
        }

        if (!sdkRegistrationStarted) {
            sdkRegistrationStarted = true;
            updateState(State.NOT_REGISTERED);
            startSDKRegistration();
            return;
        }

        if (aircraft == null) {
            return;
        }

        FlightController flightController = aircraft.getFlightController();

        if (flightController == null) {
          return;
        }

        if (pipeline() == null) {
            if (state.get() == State.CONNECTING) {
                return;
            }
            updateState(State.CONNECTING);

            Pipelines pipelines = flightController.getPipelines();
            if (pipelines == null) {
              return;
            }
            pipelines.connect(channelID, TransmissionControlType.STABLE, error -> {
                if (error == null) {
                    assert(pipeline() != null);
                    updateState(State.NO_GPS);
                }
                else {
                    if (error == PipelineError.NOT_READY) {
                        Log.e(TAG, "Interconnection failed, sleep 5 second");
                        sleep(5);
                    } else if (error == PipelineError.CONNECTION_REFUSED) {
                        Log.e(TAG, "Onboard not started, sleep 5 seconds");
                        showToast("No onboard connection");
                        sleep(5);
                    } else if (error == PipelineError.INVALID_PARAMETERS) {
                        // Can be received when Onboard SDK stopped and started again
                    } else if (error == PipelineError.TIMEOUT) {
                        Log.e(TAG, "Interconnection failed by timeout, sleep 5 seconds");
                        sleep(5);
                    } else {
                        Log.e(TAG, "Interconnection error: " + error.toString());
                    }
                    // Can return non-null even if on error code. Happens on Onboard SDK restart (auto-reconnect?)
                    updateState(pipeline() == null ? State.INTERCONNECTION_ERROR : State.NO_GPS);
                }
            });
            return;
        }

        FlightControllerState flightState = flightController.getState();
        GPSSignalLevel gpsSignalLevel = flightState.getGPSSignalLevel();
        switch (gpsSignalLevel) {
            case NONE:
            case LEVEL_0:
            case LEVEL_1:
            default:
                updateState(State.NO_GPS);
                return;
            case LEVEL_2:
            case LEVEL_3:
            case LEVEL_4:
            case LEVEL_5:
                if (state.get() == State.NO_GPS) {
                    updateState(State.LASER_OFF);
                }
                break;
        }

        if (state.get() == State.LASER_OFF) {
            enableLaser();
            return;
        }
    }

    // read pipe thread
    // write pipe thread
    private void disconnect() {
        if (aircraft == null) {
            return;
        }
        FlightController flightController = aircraft.getFlightController();
        if (flightController == null) {
          return;
        }
        Pipelines pipelines = flightController.getPipelines();
        if (pipelines == null) {
          return;
        }
        Log.i(TAG, "Disconnect pipeline");
        pipelines.disconnect(channelID, error -> {
            if (error == null) {
                Log.i(TAG, "Pipeline disconnected");
            } else if (error == PipelineError.CLOSED) {
                Log.i(TAG, "Pipeline disconnected (already closed)");
            } else if (error != null) {
                Log.e(TAG, "Disconnect error: " + error.toString());
            }
        });
    }

    private Pipeline pipeline() {
        if (aircraft == null) {
            return null;
        }

        FlightController flightController = aircraft.getFlightController();
        if (flightController == null) {
          return null;
        }
        Pipelines pipelines = flightController.getPipelines();
        if (pipelines == null) {
            return null;
        }
        return pipelines.getPipeline(channelID);
    }

    // read pipe thread
    private byte[] readPipeData(int length) {
        byte[] buffer = new byte[length];
        while (true) {
            Pipeline pipe = pipeline();
            if (pipe == null) {
                return null;
            }
            int readResult = pipe.readData(buffer, 0, buffer.length);
            if (readResult == -10008) {
                // Timeout: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L22
                continue;
            }
            if (readResult == -10011) {
                // Connection closed:
                // https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L25
                disconnect();
                return null;
            }
            if (readResult <= 0) {
                Log.e(TAG, "Read pipeline error: " + readResult);
                return null;
            }
            assert(readResult == buffer.length);
            break;
        }

        return buffer;
    }

    // read pipe thread
    private void readPipelineJob() {
        if (pipeline() == null) {
            return;
        }

        byte[] buffer = readPipeData(commandByteLength);
        if (buffer == null) {
            return;
        }

        try {
            Interconnection.command_type command = Interconnection.command_type.parseFrom(buffer);
            if (command.getVersion() != protocolVersion) {
                if (command.getVersion() > protocolVersion) {
                    showToast("Mismatch: upgrade Android");
                }
                else {
                    showToast("Mismatch: upgrade Onboard");
                }
                return;
            }
            switch (command.getType()) {
                case PING:
                    Log.i(TAG, "PING received");
                    break;
                case DRONE_COORDINATES:
                    byte[] crdBuffer = readPipeData(droneCoordinatesByteLength);
                    if (crdBuffer == null) {
                        return;
                    }
                    Interconnection.drone_coordinates coordinates = Interconnection.drone_coordinates.parseFrom(crdBuffer);
                    synchronized (droneCoordinatesMutex) {
                        droneLatitude = coordinates.getLatitude();
                        droneLongitude = coordinates.getLongitude();
                        droneHeading = coordinates.getHeading();
                    }
                    break;
                case MISSION_FINISHED:
                    Log.i(TAG, "Mission finished");
                    setMissionStatus(Mission.STOPPED, true);
                    break;
                default:
                    assert(false);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            Log.e(TAG, "Pipeline parse failed, invalid protocol");
        }
    }

    // write pipe thread
    private void writePipelineJob() {
        if (pipeline() == null) {
            synchronized (mutex) {
                if (executeCommands.size() > 0) {
                    Log.w(TAG, "Number of commands in queue: " + executeCommands.size());
                }
            }
            return;
        }

        while (true) {
            Interconnection.command_type.command_t executeCommand = null;
            synchronized (mutex) {
                if (executeCommands.isEmpty()) {
                    break;
                }
                executeCommand = executeCommands.get(0);
            }
            if (executeCommand(executeCommand)) {
                synchronized (mutex) {
                    executeCommands.remove(0);
                }
            }
            else {
                break;
            }
        }
    }

    private boolean sendBytesToPipe(byte[] bytesToSend) {
        while (true) {
            Pipeline pipe = pipeline();
            if (pipe == null) {
                return false;
            }
            int writeResult = pipe.writeData(bytesToSend, 0, bytesToSend.length);
            if (writeResult == -10008) {
                // Timeout: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L22
                Log.e(TAG, "Write failed, timeout");
                continue;
            }
            if (writeResult == -10011) {
                // Connection closed: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L25
                Log.e(TAG, "Write failed, connection closed");
                disconnect();
                return false;
            }
            if (writeResult > 0) {
                assert (writeResult == bytesToSend.length);
                return true;
            }
            Log.e(TAG, "Write failed: " + writeResult);
            return false;
        }
    }

    private boolean sendCommandToPipe(Interconnection.command_type.command_t command) {
        Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
        builder.setVersion(protocolVersion);
        builder.setType(command);
        byte[] bytesToSend = builder.build().toByteArray();
        assert(bytesToSend.length == commandByteLength);
        return sendBytesToPipe(bytesToSend);
    }

    // write pipe thread
    private boolean executeCommand(Interconnection.command_type.command_t command) {
        switch (command) {
            case PING:
                Log.i(TAG, "Execute command PING");
                return sendCommandToPipe(Interconnection.command_type.command_t.PING);
            case MISSION_START:
                if (!sendCommandToPipe(Interconnection.command_type.command_t.MISSION_START)) {
                    return false;
                }
                assert(pinPoint != null);
                Log.i(TAG, "Execute command MISSION_START: lat(" + pinLatitude + ") lon(" + pinLongitude + ")");
                Interconnection.pin_coordinates.Builder builder = Interconnection.pin_coordinates.newBuilder();
                builder.setLatitude(pinLatitude);
                builder.setLongitude(pinLongitude);
                byte[] bytesToSend = builder.build().toByteArray();
                return sendBytesToPipe(bytesToSend);
            case MISSION_PAUSE:
                Log.i(TAG, "Execute command MISSION_PAUSE");
                return sendCommandToPipe(Interconnection.command_type.command_t.MISSION_PAUSE);
            case MISSION_ABORT:
                Log.i(TAG, "Execute command MISSION_ABORT");
                return sendCommandToPipe(Interconnection.command_type.command_t.MISSION_ABORT);
            default:
                assert(false);
        }
        return true;
    }

    // UI thread
    private void updateUIState() {
        MaterialButton droneStatus = findViewById(R.id.droneStatus);
        int buttonColor = getDroneButtonColor();
        droneStatus.setIconTintResource(buttonColor);
        droneStatus.setText(getDroneButtonText());
        droneStatus.setTextColor(getResources().getColor(buttonColor));

        Button laserStatus = findViewById(R.id.laserStatus);
        if (state.get() == State.ONLINE) {
            String msg = "";
            switch (laserError.get()) {
                case TOO_CLOSE:
                    msg = "too close";
                    break;
                case TOO_FAR:
                    msg = "too far";
                    break;
                case NORMAL:
                    msg = String.format("%.1f", laserDistance.get());
                    break;
                default:
                    assert(false);
            }
            laserStatus.setText("Laser: " + msg);
        }
        else {
            laserStatus.setText("Laser: N/A");
        }

        ImageButton cancelButton = findViewById(R.id.cancelButton);
        if ((mission_status.get() != Mission.STOPPED) || (pinPoint != null)) {
            cancelButton.setVisibility(View.VISIBLE);
        } else {
            cancelButton.setVisibility(View.GONE);
        }

        Button actionButton = (Button) findViewById(R.id.actionButton);
        switch (mission_status.get()) {
            case STOPPED:
                actionButton.setText("Start");
                break;
            case PAUSED:
                actionButton.setText("Continue");
                break;
            case IN_PROGRESS:
                actionButton.setText("Pause");
                break;
            default:
                assert(false);
        }

        updateDroneCoordinates();
    }

    private int getDroneButtonColor() {
        switch (state.get()) {
            case NO_PERMISSIONS:
            case MAP_NOT_READY:
            case NOT_REGISTERED:
            case REGISTRATION_FAILED:
            case NO_PRODUCT:
                return android.R.color.holo_red_light;
            case CONNECTING:
            case INTERCONNECTION_ERROR:
            case NO_GPS:
            case LASER_OFF:
            case WAIT_LASER:
            case LASER_ERROR:
                return android.R.color.holo_orange_light;
            case ONLINE:
                return android.R.color.holo_green_dark;
            default:
                assert(false); // Unreachable
                return android.R.color.holo_red_light;
        }
    }

    private String getDroneButtonText() {
        switch (state.get()) {
            case NO_PERMISSIONS:
                return "No permissions";
            case MAP_NOT_READY:
                return "Map not ready";
            case NOT_REGISTERED:
                return "Not registered";
            case REGISTRATION_FAILED:
                return "Registration failed";
            case NO_PRODUCT:
                return "No product";
            case CONNECTING:
                return "Connecting";
            case INTERCONNECTION_ERROR:
                return "Interconnection error";
            case NO_GPS:
                return "No GPS";
            case LASER_OFF:
                return "Laser is off";
            case WAIT_LASER:
                return "Waiting laser";
            case LASER_ERROR:
                return "Laser error";
            case ONLINE:
                return "Online";
            default:
                assert(false); // Unreachable
                return "";
        }
    }

    /**
     * Checks if there is any missing permissions, and
     * requests runtime permission if needed.
     */
    private void checkAndRequestPermissions() {
        // Check for permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String eachPermission : REQUIRED_PERMISSION_LIST) {
                if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                    missingPermission.add(eachPermission);
                }
            }
        }

        if (missingPermission.isEmpty()) {
            return;
        }

        // Request for missing permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }
    }

    /**
     * Result of runtime permission request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Check for granted permission and remove from missing list
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = grantResults.length - 1; i >= 0; i--) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermission.remove(permissions[i]);
                }
            }
        }
        // If there is enough permission, we will start the registration
        if (!missingPermission.isEmpty()) {
            showToast("Permissions are missing");
        }
    }

    private void startSDKRegistration() {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                DJISDKManager.getInstance().registerApp(MainActivity.this.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
                    @Override
                    public void onRegister(DJIError djiError) {
                        if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                            Log.d(TAG, "Registration success");
                            if (useBridge) {
                                Log.d(TAG, "Using bridge");
                                DJISDKManager.getInstance().enableBridgeModeWithBridgeAppIP("192.168.0.227");
                            }
                            Log.d(TAG, "Start connection to product");
                            DJISDKManager.getInstance().startConnectionToProduct();
                            updateState(State.NO_PRODUCT);
                            return;
                        }

                        // Check bundle id and network connection
                        Log.e(TAG, "Registration failed: " + djiError.getDescription());
                        sdkRegistrationStarted = false;
                        updateState(State.REGISTRATION_FAILED);
                    }

                    @Override
                    public void onProductDisconnect() {
                        Log.e(TAG, "Product disconnect");
                        aircraft = null;
                        updateState(State.NO_PRODUCT);
                    }

                    @Override
                    public void onProductConnect(BaseProduct baseProduct) {
                        Log.i(TAG, "On product connect");
                        assert (baseProduct instanceof Aircraft);
                        aircraft = (Aircraft) baseProduct;
                        assert (aircraft != null);
                        updateState(State.LASER_OFF);
                    }

                    @Override
                    public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {}

                    @Override
                    public void onProductChanged(BaseProduct baseProduct) {
                        assert (baseProduct instanceof Aircraft);
                        Aircraft newAircraft = (Aircraft) baseProduct;
                        if (aircraft != null) {
                            assert(aircraft == newAircraft);
                        }
                        aircraft = newAircraft;
                        assert (aircraft != null);
                        updateState(State.LASER_OFF);
                    }

                    @Override
                    public void onDatabaseDownloadProgress(long l, long l1) {}

                    @Override
                    public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent oldComponent,
                                                  BaseComponent newComponent) {}
                });
            }
        });
    }

    private void enableLaser() {
        List<Camera> cameras = aircraft.getCameras();
        if (cameras.isEmpty()) {
            return;
        }
        for (Camera camera : cameras) {
            String displayName = camera.getDisplayName();
            if (displayName == null) {
                continue;
            }
            Log.i(TAG, "Camera: " + displayName);
            assert(displayName.equals("Zenmuse H20"));
            camera.setLaserEnabled(true, null);
            camera.getLaserEnabled(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
                @Override
                public void onSuccess(Boolean aBoolean) {
                    Log.i(TAG, "Laser is ON: " + aBoolean);
                    assert(aBoolean == true);
                    updateState(State.WAIT_LASER);
                    setupMeasurementCallback(camera);
                }

                @Override
                public void onFailure(DJIError djiError) {
                    Log.e(TAG, "Laser failure");
                }
            });
        }
    }

    private void setupMeasurementCallback(Camera camera) {
        for (Lens lens : camera.getLenses()) {
            lens.setLaserMeasureInformationCallback(new LaserMeasureInformation.Callback() {
                @Override
                public void onUpdate(LaserMeasureInformation laserMeasureInformation) {
                    float targetDistance = laserMeasureInformation.getTargetDistance();
                    LaserError error = laserMeasureInformation.getLaserError();
                    if (error == LaserError.TOO_FAR) {
                        Log.d(TAG, "Laser is too far");
                        laserError.set(LaserError.TOO_FAR);
                        updateState(State.ONLINE);
                        return;
                    }
                    if (error == LaserError.TOO_CLOSE) {
                        Log.d(TAG, "Laser is too close");
                        laserError.set(LaserError.TOO_CLOSE);
                        updateState(State.ONLINE);
                        return;
                    }
                    if (error == LaserError.NO_SIGNAL) {
                        Log.e(TAG, "Laser no signal");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    if (error == LaserError.UNKNOWN) {
                        Log.e(TAG, "Laser state is unknown");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    assert(error == LaserError.NORMAL);
                    laserError.set(LaserError.NORMAL);
                    updateState(State.ONLINE);
                    laserDistance.set(targetDistance);
                    Log.d(TAG, "Laser info: " + laserMeasureInformation);
                }
            });
        }
    }

    private void showToast(final String toastMsg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void zoomToDrone() {
        if (gMap == null) {
            return;
        }
        if (droneMarker == null) {
            return;
        }
        float zoomLevel = 18.0F;
        CameraUpdate cu = CameraUpdateFactory.newLatLngZoom(droneMarker.getPosition(), zoomLevel);
        gMap.animateCamera(cu);
    }

    // UI thread
    private void updateDroneCoordinates() {
        if (gMap == null) {
            return;
        }

        double latitude = 0.0;
        double longitude = 0.0;
        float heading = 0.0F;

        synchronized (droneCoordinatesMutex) {
            latitude = droneLatitude;
            longitude = droneLongitude;
            heading = droneHeading;
        }

        if (latitude == 0.0 && longitude == 0.0 && heading == 0.0) {
            return;
        }

        LatLng pos = new LatLng(latitude, longitude);

        if (droneMarker != null) {
            droneMarker.setPosition(pos);
            droneMarker.setRotation(heading);
            if (pinPoint != null) {
                tripLine.setPoints(Arrays.asList(droneMarker.getPosition(), pinPoint.getPosition()));
            }
            return;
        }

        MarkerOptions markerOptions = new MarkerOptions();
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), dji.midware.R.drawable.indoorpointing_canpass);
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 120, 120, false);
        markerOptions.icon(BitmapDescriptorFactory.fromBitmap(resizedBitmap));
        markerOptions.position(pos);
        markerOptions.rotation(heading);
        markerOptions.anchor(0.5F, 0.5F);
        droneMarker = gMap.addMarker(markerOptions);
        if (pinPoint != null) {
            tripLine.setPoints(Arrays.asList(droneMarker.getPosition(), pinPoint.getPosition()));
        }
        zoomToDrone();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap;
            gMap.setOnMapClickListener(this);
        }
    }
}
