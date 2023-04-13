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
import dji.common.flightcontroller.BatteryThresholdBehavior;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
import dji.common.model.LocationCoordinate2D;
import dji.common.remotecontroller.RCMode;
import dji.common.util.CommonCallbacks;
import dji.mop.common.PipelineError;
import dji.mop.common.Pipelines;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.Lens;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
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
    private static boolean mockDrone = false;
    private AtomicReference<Aircraft> aircraft = new AtomicReference<Aircraft>(null);
    Marker droneMarker = null;
    Marker homeMarker = null;
    private Object pinCoordinatesMutex = new Object();
    Marker pinPoint = null;
    private double pinLongitude = 0.0;
    private double pinLatitude = 0.0;
    Polyline tripLine = null;
    private Object executeCommandsMutex = new Object();
    private List<Interconnection.command_type.command_t> executeCommands = new ArrayList<Interconnection.command_type.command_t>();
    private int commandByteLength = 0;
    private int droneCoordinatesByteLength = 0;
    private static int protocolVersion = 9; // Keep it consistent with Onboard SDK
    private static int channelID = 9745; // Just a random number. Keep it consistent with Onboard SDK
    private Object droneCoordinatesAndStateMutex = new Object();
    private double droneLongitude = 0.0;
    private double droneLatitude = 0.0;
    private float droneHeading = 0.0F;
    private Interconnection.drone_coordinates.state_t droneState = Interconnection.drone_coordinates.state_t.WAITING;
    private boolean appOnPause = false;
    private boolean waitForPingReceived = false;
    LaserStatus laserStatus = new LaserStatus(mockDrone);
    PipelineStatus pipelineStatus = new PipelineStatus();

    enum State {
        NO_PERMISSIONS,
        MAP_NOT_READY,
        NOT_REGISTERED,
        NO_PRODUCT,
        WAIT_RC,
        RC_ERROR,
        LOW_BATTERY,
        CONNECTING,
        NO_GPS,
        LASER_OFF,
        ONLINE
    }

    private AtomicReference<State> state = new AtomicReference<State>(State.NO_PERMISSIONS);
    private AtomicReference<LocationCoordinate2D> homeLocation = new AtomicReference<LocationCoordinate2D>(null);
    private AtomicReference<RCMode> latestRcMode = new AtomicReference<RCMode>(null);

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
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
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
        builder2.setState(Interconnection.drone_coordinates.state_t.WAITING);
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

        if (mockDrone) {
            setDroneState(Interconnection.drone_coordinates.state_t.READY);
        }
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

    private void setDroneState(Interconnection.drone_coordinates.state_t newDroneState) {
        droneState = newDroneState;
        handler.sendEmptyMessage(0);
    }

    // UI thread
    private void actionButtonClicked() {
        synchronized (droneCoordinatesAndStateMutex) {
            switch (droneState) {
                case READY:
                    Log.d(TAG, "User action: try start mission");
                    break;
                case WAITING:
                    Log.w(TAG, "Action button clicked on WAITING state");
                    return;
                case PAUSED:
                    Log.d(TAG, "User action: continue mission");
                    setDroneState(Interconnection.drone_coordinates.state_t.WAITING);
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.MISSION_CONTINUE);
                    }
                    return;
                case EXECUTING:
                    Log.d(TAG, "User action: pause mission");
                    setDroneState(Interconnection.drone_coordinates.state_t.WAITING);
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.MISSION_PAUSE);
                    }
                    return;
                default:
                    assert(false);
                    break;
            }
        }

        if (state.get() != State.ONLINE) {
            new MaterialAlertDialogBuilder(this).setMessage("The drone is not online").setPositiveButton("Ok", null).show();
            return;
        }
        if (gMap == null) {
            new MaterialAlertDialogBuilder(this).setMessage("The map is not ready yet").setPositiveButton("Ok", null).show();
            return;
        }
        if (droneMarker == null) {
            new MaterialAlertDialogBuilder(this).setMessage("Please wait for a drone coordinates").setPositiveButton("Ok", null).show();
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
                        Log.d(TAG, "User action: start mission");
                        synchronized (droneCoordinatesAndStateMutex) {
                            setDroneState(Interconnection.drone_coordinates.state_t.WAITING);
                        }
                        synchronized (executeCommandsMutex) {
                            executeCommands.add(Interconnection.command_type.command_t.MISSION_START);
                            if (mockDrone) {
                                executeCommands.add(Interconnection.command_type.command_t.LASER_RANGE);
                            }
                        }
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void homeButtonClicked() {
        if (droneMarker != null) {
            zoomToPosition(droneMarker.getPosition());
        } else if (homeMarker != null) {
            zoomToPosition(homeMarker.getPosition());
        }
    }

    // UI thread
    private void cancelButtonClicked() {
        boolean canBeStopped;
        synchronized (droneCoordinatesAndStateMutex) {
            canBeStopped =
                droneState == Interconnection.drone_coordinates.state_t.PAUSED ||
                droneState == Interconnection.drone_coordinates.state_t.EXECUTING;
        }

        if (canBeStopped) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Are you sure you want to abort this mission?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "User action: abort mission");
                            synchronized (droneCoordinatesAndStateMutex) {
                                setDroneState(Interconnection.drone_coordinates.state_t.WAITING);
                            }
                            synchronized (executeCommandsMutex) {
                                executeCommands.add(Interconnection.command_type.command_t.MISSION_ABORT);
                            }
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
            return;
        }

        if (pinPoint == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to remove the pin?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pinPoint.remove();
                        pinPoint = null;
                        updateTripLine();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    // Call when 'droneMarker' or 'pinPoint' changes
    private void updateTripLine() {
        if (droneMarker == null || pinPoint == null) {
            if (tripLine != null) {
                tripLine.remove();
                tripLine = null;
            }
            return;
        }

        if (tripLine == null) {
            tripLine = gMap.addPolyline(new PolylineOptions().add(droneMarker.getPosition(), pinPoint.getPosition()));
            PatternItem DASH = new Dash(20);
            PatternItem GAP = new Gap(20);
            tripLine.setPattern(Arrays.asList(DASH, GAP));
        } else {
            tripLine.setPoints(Arrays.asList(droneMarker.getPosition(), pinPoint.getPosition()));
        }
    }

    // UI thread
    @Override
    public void onMapClick(LatLng point) {
        if (droneMarker == null) {
            return;
        }
        synchronized (droneCoordinatesAndStateMutex) {
            if (droneState != Interconnection.drone_coordinates.state_t.READY) {
                Log.w(TAG, "Map clicked but drone is not ready");
                return;
            }
        }
        if (pinPoint == null) {
            MarkerOptions markerOptions = new MarkerOptions();
            markerOptions.position(point);
            markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));
            pinPoint = gMap.addMarker(markerOptions);
            synchronized (pinCoordinatesMutex) {
                pinLongitude = pinPoint.getPosition().longitude;
                pinLatitude = pinPoint.getPosition().latitude;
            }
            updateTripLine();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to remove the old pin?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pinPoint.setPosition(point);
                        synchronized (pinCoordinatesMutex) {
                            pinLongitude = pinPoint.getPosition().longitude;
                            pinLatitude = pinPoint.getPosition().latitude;
                        }
                        updateTripLine();
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
        if (state.get() == newState) {
            return;
        }
        Log.d(TAG, "Update state: " + newState);
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

        if (mockDrone) {
            // Paris
            homeLocation.set(new LocationCoordinate2D(48.847344150212244, 2.38680388210137));

            droneLatitude = 48.8473;
            droneLongitude = 2.3868;
            droneHeading = 0.0F;

            updateState(State.ONLINE);
            return;
        }

        if (!sdkRegistrationStarted) {
            sdkRegistrationStarted = true;
            updateState(State.NOT_REGISTERED);
            startSDKRegistration();
            return;
        }

        if (aircraft.get() == null) {
            updateState(State.NO_PRODUCT);
            return;
        }

        FlightController flightController = aircraft.get().getFlightController();

        if (flightController == null) {
            updateState(State.NO_PRODUCT);
            return;
        }

        FlightControllerState flightState = flightController.getState();
        if (flightState.getBatteryThresholdBehavior() != BatteryThresholdBehavior.FLY_NORMALLY) {
            updateState(State.LOW_BATTERY);
            return;
        }

        homeLocation.set(flightState.getHomeLocation());

        if (pipeline() == null) {
            RemoteController remoteController = aircraft.get().getRemoteController();
            if (remoteController == null) {
                updateState(State.WAIT_RC);
                Log.e(TAG, "No remote controller, sleep 5 seconds");
                sleep(5);
                return;
            }

            remoteController.getMode(new CommonCallbacks.CompletionCallbackWith<RCMode>() {
                @Override
                public void onSuccess(RCMode rcMode) {
                    latestRcMode.set(rcMode);
                }

                @Override
                public void onFailure(DJIError djiError) {
                    latestRcMode.set(null);
                }
            });

            if (latestRcMode.get() == null) {
                Log.e(TAG, "Waiting for RCMode");
                updateState(State.WAIT_RC);
                return;
            }

            if (latestRcMode.get() != RCMode.CHANNEL_A) {
                Log.e(TAG, "RC Mode: " + latestRcMode.get());
                updateState(State.RC_ERROR);
                return;
            }

            updateState(State.CONNECTING);
            if (pipelineStatus.isConnectionInProgress()) {
                return;
            }
            Pipelines pipelines = flightController.getPipelines();
            if (pipelines == null) {
              return;
            }
            pipelineStatus.setConnectionInProgress();
            pipelines.connect(channelID, TransmissionControlType.STABLE, error -> {
                if (error == null) {
                    Log.i(TAG, "Pipeline connected");
                    assert(pipeline() != null);
                    pipelineStatus.setConnected(true);
                    waitForPingReceived = true;
                    synchronized (executeCommandsMutex) {
                        if (!executeCommands.contains(Interconnection.command_type.command_t.PING)) {
                            executeCommands.add(Interconnection.command_type.command_t.PING);
                        }
                    }
                    return;
                }
                pipelineStatus.setConnected(false);
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
            });
            return;
        }

        if (waitForPingReceived) {
            updateState(State.CONNECTING);
            return;
        }

        if (pipelineStatus.isDisconnectionInProgress()) {
            updateState(State.CONNECTING);
            return;
        }
        assert(pipelineStatus.isConnected());

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
                break;
        }

        if (laserStatus.isEnabled()) {
            updateState(State.ONLINE);
        } else {
            updateState(State.LASER_OFF);
            enableLaser();
        }
    }

    // read pipe thread
    // write pipe thread
    private void disconnect() {
        if (pipelineStatus.isDisconnectionInProgress()) {
            return;
        }
        if (pipeline() == null) {
            return;
        }
        if (aircraft.get() == null) {
            return;
        }
        FlightController flightController = aircraft.get().getFlightController();
        if (flightController == null) {
          return;
        }
        Pipelines pipelines = flightController.getPipelines();
        if (pipelines == null) {
          return;
        }

        Log.i(TAG, "Disconnect pipeline");
        pipelineStatus.setDisconnectionInProgress();
        pipelines.disconnect(channelID, error -> {
            if (error == null) {
                Log.i(TAG, "Pipeline disconnected");
            } else if (error == PipelineError.CLOSED) {
                Log.i(TAG, "Pipeline disconnected (already closed)");
            } else if (error != null) {
                Log.e(TAG, "Disconnect error: " + error.toString());
            }
            assert(pipeline() == null);
            pipelineStatus.setConnected(false);
        });
    }

    private Pipeline pipeline() {
        if (aircraft.get() == null) {
            return null;
        }
        FlightController flightController = aircraft.get().getFlightController();
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
            if (!pipelineStatus.isConnected()) {
                sleep(5);
                continue;
            }
            Pipeline pipe = pipeline();
            if (pipe == null) {
                return null;
            }
            Log.d(TAG, "readData");
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
        if (appOnPause) {
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
                    waitForPingReceived = false;
                    break;
                case DRONE_COORDINATES:
                    byte[] crdBuffer = readPipeData(droneCoordinatesByteLength);
                    if (crdBuffer == null) {
                        return;
                    }
                    Interconnection.drone_coordinates coordinates = Interconnection.drone_coordinates.parseFrom(crdBuffer);
                    Log.d(TAG, "Drone coordinates and state received");
                    synchronized (droneCoordinatesAndStateMutex) {
                        droneLatitude = coordinates.getLatitude();
                        droneLongitude = coordinates.getLongitude();
                        droneHeading = coordinates.getHeading();
                        droneState = coordinates.getState();
                    }
                    handler.sendEmptyMessage(0);
                    break;
                case LASER_RANGE:
                    Log.i(TAG, "Laser range request received");
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.LASER_RANGE);
                    }
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
        if (appOnPause) {
            return;
        }
        if (pipeline() == null && !mockDrone) {
            synchronized (executeCommandsMutex) {
                if (executeCommands.size() > 0) {
                    Log.w(TAG, "Number of commands in queue: " + executeCommands.size());
                }
            }
            return;
        }

        while (true) {
            Interconnection.command_type.command_t executeCommand = null;
            synchronized (executeCommandsMutex) {
                if (executeCommands.isEmpty()) {
                    break;
                }
                executeCommand = executeCommands.get(0);
            }
            if (executeCommand(executeCommand)) {
                synchronized (executeCommandsMutex) {
                    executeCommands.remove(0);
                }
            }
            else {
                break;
            }
        }
    }

    private boolean writePipeData(byte[] bytesToSend) {
        if (mockDrone) {
            return true;
        }
        while (true) {
            if (!pipelineStatus.isConnected()) {
                sleep(5);
                continue;
            }
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

    private boolean writeCommandToPipe(Interconnection.command_type.command_t command) {
        Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
        builder.setVersion(protocolVersion);
        builder.setType(command);
        byte[] bytesToSend = builder.build().toByteArray();
        assert(bytesToSend.length == commandByteLength);
        return writePipeData(bytesToSend);
    }

    // write pipe thread
    private boolean executeCommand(Interconnection.command_type.command_t command) {
        switch (command) {
            case PING:
                Log.i(TAG, "Execute command PING");
                return writeCommandToPipe(Interconnection.command_type.command_t.PING);
            case MISSION_START: {
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_START)) {
                    return false;
                }
                assert(pinPoint != null);
                Log.i(TAG, "Execute command MISSION_START: lat(" + pinLatitude + ") lon(" + pinLongitude + ")");
                Interconnection.pin_coordinates.Builder builder = Interconnection.pin_coordinates.newBuilder();
                builder.setLatitude(pinLatitude);
                builder.setLongitude(pinLongitude);
                byte[] bytesToSend = builder.build().toByteArray();
                return writePipeData(bytesToSend);
            }
            case MISSION_PAUSE:
                Log.i(TAG, "Execute command MISSION_PAUSE");
                return writeCommandToPipe(Interconnection.command_type.command_t.MISSION_PAUSE);
            case MISSION_CONTINUE:
                Log.i(TAG, "Execute command MISSION_CONTINUE");
                return writeCommandToPipe(Interconnection.command_type.command_t.MISSION_CONTINUE);
            case MISSION_ABORT:
                Log.i(TAG, "Execute command MISSION_ABORT");
                return writeCommandToPipe(Interconnection.command_type.command_t.MISSION_ABORT);
            case LASER_RANGE: {
                if (!laserStatus.hasValue()) {
                    Log.i(TAG, "No laser data to send");
                    return false;
                }
                float laserRange = laserStatus.getValue();
                Log.i(TAG, "Sending laser range: " + laserRange);
                if (!writeCommandToPipe(Interconnection.command_type.command_t.LASER_RANGE)) {
                    return false;
                }
                Interconnection.laser_range.Builder builder = Interconnection.laser_range.newBuilder();
                builder.setRange(laserRange);
                byte[] bytesToSend = builder.build().toByteArray();
                return writePipeData(bytesToSend);
            }
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

        Button laserStatusButton = findViewById(R.id.laserStatus);
        laserStatusButton.setText("Laser: " + (mockDrone ? "(mock)" : laserStatus.getLaserStatus()));

        ImageButton cancelButton = findViewById(R.id.cancelButton);
        Button actionButton = (Button) findViewById(R.id.actionButton);

        synchronized (droneCoordinatesAndStateMutex) {
            switch (droneState) {
                case READY:
                    if (pinPoint == null) {
                        cancelButton.setClickable(false);
                    } else {
                        cancelButton.setClickable(true);
                    }
                    actionButton.setText("Start");
                    actionButton.setClickable(true);
                    break;
                case WAITING:
                    cancelButton.setClickable(false);
                    actionButton.setClickable(false);
                    break;
                case PAUSED:
                    cancelButton.setClickable(true);
                    actionButton.setText("Continue");
                    actionButton.setClickable(true);
                    break;
                case EXECUTING:
                    cancelButton.setClickable(true);
                    actionButton.setText("Pause");
                    actionButton.setClickable(true);
                    break;
                default:
                    assert (false);
                    break;
            }
        }

        updateDroneCoordinates();
        updateHomeMarker();
    }

    private int getDroneButtonColor() {
        switch (state.get()) {
            case NO_PERMISSIONS:
            case MAP_NOT_READY:
            case NOT_REGISTERED:
            case NO_PRODUCT:
            case RC_ERROR:
            case LOW_BATTERY:
                return android.R.color.holo_red_light;
            case WAIT_RC:
            case CONNECTING:
            case NO_GPS:
            case LASER_OFF:
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
            case NO_PRODUCT:
                return "No product";
            case RC_ERROR:
                return "RC error";
            case WAIT_RC:
                return "Waiting RC";
            case LOW_BATTERY:
                return "Low battery";
            case CONNECTING:
                return "Connecting";
            case NO_GPS:
                return "No GPS";
            case LASER_OFF:
                return "Laser is off";
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
                            return;
                        }

                        // Check bundle id and network connection
                        Log.e(TAG, "Registration failed: " + djiError.getDescription());
                        sdkRegistrationStarted = false;
                    }

                    @Override
                    public void onProductDisconnect() {
                        Log.e(TAG, "Product disconnect");
                        aircraft.set(null);
                    }

                    @Override
                    public void onProductConnect(BaseProduct baseProduct) {
                        Log.i(TAG, "On product connect");
                        assert (baseProduct instanceof Aircraft);
                        aircraft.set((Aircraft) baseProduct);
                        assert (aircraft.get() != null);
                        laserStatus.setEnabled(false);
                        pipelineStatus.setConnected(false);
                        waitForPingReceived = false;
                        latestRcMode.set(null);
                    }

                    @Override
                    public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {}

                    @Override
                    public void onProductChanged(BaseProduct baseProduct) {
                        assert (baseProduct instanceof Aircraft);
                        Aircraft newAircraft = (Aircraft) baseProduct;
                        if (aircraft.get() != null) {
                            assert(aircraft.get() == newAircraft);
                        }
                        aircraft.set(newAircraft);
                        assert (aircraft.get() != null);
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
        if (aircraft.get() == null) {
            return;
        }
        List<Camera> cameras = aircraft.get().getCameras();
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
                    // aBoolean can be true/false
                    laserStatus.setEnabled(true);
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
                    laserStatus.onUpdate(error, targetDistance);
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

    private void zoomToPosition(LatLng position) {
        if (gMap == null) {
            return;
        }
        float zoomLevel = 18.0F;
        CameraUpdate cu = CameraUpdateFactory.newLatLngZoom(position, zoomLevel);
        gMap.animateCamera(cu);
    }

    // UI thread
    private void updateDroneCoordinates() {
        if (gMap == null) {
            return;
        }

        if (state.get() != State.ONLINE) {
            return;
        }

        double latitude = 0.0;
        double longitude = 0.0;
        float heading = 0.0F;

        synchronized (droneCoordinatesAndStateMutex) {
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
            updateTripLine();
            return;
        }

        MarkerOptions markerOptions = new MarkerOptions();
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), dji.midware.R.drawable.indoorpointing_canpass);
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 120, 120, false);
        markerOptions.icon(BitmapDescriptorFactory.fromBitmap(resizedBitmap));
        markerOptions.position(pos);
        markerOptions.rotation(heading);
        markerOptions.anchor(0.5F, 0.5F);
        markerOptions.flat(true);
        droneMarker = gMap.addMarker(markerOptions);
        updateTripLine();
        zoomToPosition(droneMarker.getPosition());
    }

    // UI thread
    public void updateHomeMarker() {
        if (gMap == null) {
            return;
        }
        LocationCoordinate2D location = homeLocation.get();
        if (location == null) {
            return;
        }
        if (location.getLongitude() == 0.0 && location.getLatitude() == 0.0) {
            if (homeMarker != null) {
                homeMarker.remove();
                homeMarker = null;
            }
            return;
        }

        LatLng pos = new LatLng(location.getLatitude(), location.getLongitude());

        if (homeMarker == null) {
            MarkerOptions markerOptions = new MarkerOptions();
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.helicopter_landing_icon_24px);
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 40, 40, false);
            markerOptions.icon(BitmapDescriptorFactory.fromBitmap(resizedBitmap));
            markerOptions.position(pos);
            markerOptions.rotation(0.0F);
            markerOptions.anchor(0.5F, 0.5F);
            markerOptions.flat(true);
            homeMarker = gMap.addMarker(markerOptions);
            if (droneMarker == null) {
                zoomToPosition(homeMarker.getPosition());
            }
        } else {
            homeMarker.setPosition(pos);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap;
            gMap.setOnMapClickListener(this);
            gMap.setOnMarkerClickListener(marker -> true);
        }
    }
}
