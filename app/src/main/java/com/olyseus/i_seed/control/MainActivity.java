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
import android.graphics.Color;
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

import com.google.android.gms.maps.model.BitmapDescriptor;
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
    private Marker droneMarker = null;
    private Marker homeMarker = null;
    private MaterialButton droneStatus = null;
    private ImageButton cancelButton = null;
    private Button laserStatusButton = null;
    private Button actionButton = null;

    InputPolygon inputPolygon = new InputPolygon();
    MissionPath missionPath = new MissionPath();
    MockPipelineRead mockPipelineRead = null;
    private Object executeCommandsMutex = new Object();
    private List<Interconnection.command_type.command_t> executeCommands = new ArrayList<Interconnection.command_type.command_t>();
    private int packetSize = 0;
    private static int protocolVersion = 17; // Keep it consistent with Onboard SDK
    private static int channelID = 9745; // Just a random number. Keep it consistent with Onboard SDK
    private Object droneCoordinatesAndStateMutex = new Object();
    private double droneLongitude = 0.0;
    private double droneLatitude = 0.0;
    private float droneHeading = 0.0F;
    private Interconnection.drone_info.state_t droneState = Interconnection.drone_info.state_t.WAITING;
    private boolean appOnPause = false;
    private boolean waitForPongReceived = false;
    LaserStatus laserStatus = new LaserStatus(mockDrone);
    PipelineStatus pipelineStatus = new PipelineStatus();
    static private int invalid_event_id = -1;
    private int event_id = invalid_event_id;

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

    public static final int PATTERN_DASH_LENGTH_PX = 10;
    public static final int PATTERN_GAP_LENGTH_PX = 10;
    public static final PatternItem DASH = new Dash(PATTERN_DASH_LENGTH_PX);
    public static final PatternItem GAP = new Gap(PATTERN_GAP_LENGTH_PX);
    public static final List<PatternItem> POLYGON_PATTERN = Arrays.asList(GAP, DASH);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appOnPause = false;
        mockPipelineRead = new MockPipelineRead(protocolVersion);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        actionButton = (Button) findViewById(R.id.actionButton);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { actionButtonClicked(); }
        });
        ImageButton homeButton = (ImageButton) findViewById(R.id.homeButton);
        homeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { homeButtonClicked(); }
        });
        cancelButton = findViewById(R.id.cancelButton);
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

        Interconnection.packet_size.Builder builder = Interconnection.packet_size.newBuilder();
        builder.setSize(0);
        packetSize = builder.build().toByteArray().length;
        assert (packetSize > 0);

        droneStatus = findViewById(R.id.droneStatus);
        laserStatusButton = findViewById(R.id.laserStatus);

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
                System.exit(-1);
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
                System.exit(-1);
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
                System.exit(-1);
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
    private void setDroneState(Interconnection.drone_info.state_t newDroneState) {
        droneState = newDroneState;
        updateUIState();
    }

    // UI thread
    private void actionButtonClicked() {
        synchronized (droneCoordinatesAndStateMutex) {
            switch (droneState) {
                case READY:
                    if (inputPolygon.size() > 2) {
                        Log.d(TAG, "User action: build mission");
                        setDroneState(Interconnection.drone_info.state_t.WAITING);
                        synchronized (executeCommandsMutex) {
                            executeCommands.add(Interconnection.command_type.command_t.BUILD_MISSION);
                        }
                        return;
                    }
                    Log.d(TAG, "User action: no polygon");
                    break;
                case WAITING:
                case PATH_DATA:
                    Log.w(TAG, "Action button clicked on WAITING state");
                    return;
                case PAUSED:
                    Log.d(TAG, "User action: continue mission");
                    setDroneState(Interconnection.drone_info.state_t.WAITING);
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.MISSION_CONTINUE);
                    }
                    return;
                case EXECUTING:
                    Log.d(TAG, "User action: pause mission");
                    setDroneState(Interconnection.drone_info.state_t.WAITING);
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.MISSION_PAUSE);
                    }
                    return;
                case PATH:
                    Log.d(TAG, "User action: mission start");
                    assert(inputPolygon.size() > 2);
                    assert(!missionPath.isEmpty());
                    break;
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
        if (inputPolygon.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setMessage("Please provide input mission polygon to start").setPositiveButton("Ok", null).show();
            return;
        }
        if (inputPolygon.size() <= 2) {
            new MaterialAlertDialogBuilder(this).setMessage("Please provide more input polygon vertices").setPositiveButton("Ok", null).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setMessage("Are you sure you want to start this mission?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG, "User action: start mission");
                        synchronized (droneCoordinatesAndStateMutex) {
                            setDroneState(Interconnection.drone_info.state_t.WAITING);
                        }
                        synchronized (executeCommandsMutex) {
                            executeCommands.add(Interconnection.command_type.command_t.MISSION_START);
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
        boolean isWaiting = false;
        boolean removePath = false;
        boolean removeInputPolygon = false;
        synchronized (droneCoordinatesAndStateMutex) {
            canBeStopped =
                droneState == Interconnection.drone_info.state_t.PAUSED ||
                droneState == Interconnection.drone_info.state_t.EXECUTING;
            isWaiting =
                droneState == Interconnection.drone_info.state_t.WAITING ||
                droneState == Interconnection.drone_info.state_t.PATH_DATA;
            removePath = (droneState == Interconnection.drone_info.state_t.PATH);
            removeInputPolygon = (droneState == Interconnection.drone_info.state_t.READY);
        }

        if (canBeStopped) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Are you sure you want to abort this mission?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "User action: abort mission");
                            synchronized (droneCoordinatesAndStateMutex) {
                                setDroneState(Interconnection.drone_info.state_t.WAITING);
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

        if (removePath) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Are you sure you want to clear the mission path?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "User action: clear mission path");
                            synchronized (droneCoordinatesAndStateMutex) {
                                setDroneState(Interconnection.drone_info.state_t.WAITING);
                            }
                            synchronized (executeCommandsMutex) {
                                executeCommands.add(Interconnection.command_type.command_t.MISSION_PATH_CANCEL);
                            }
                            missionPath.userRemove();
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
            return;
        }

        if (removeInputPolygon) {
            inputPolygon.userRemove();
            missionPath.userRemove();
            handler.sendEmptyMessage(0);
            return;
        }

        assert(isWaiting);
        new MaterialAlertDialogBuilder(this).setMessage("Waiting for a drone state").setPositiveButton("Ok", null).show();
    }

    // UI thread
    @Override
    public void onMapClick(LatLng point) {
        if (droneMarker == null) {
            return;
        }
        synchronized (droneCoordinatesAndStateMutex) {
            if (droneState != Interconnection.drone_info.state_t.READY) {
                Log.w(TAG, "Map clicked but drone is not ready");
                return;
            }
        }

        inputPolygon.add(point);
        handler.sendEmptyMessage(0);
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
                    waitForPongReceived = true;
                    event_id = invalid_event_id;
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

        if (waitForPongReceived) {
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
            int readResult = -1;
            if (mockDrone) {
                Log.d(TAG, "read mock data");
                readResult = mockPipelineRead.readData(buffer, buffer.length);
            }
            else {
                if (!pipelineStatus.isConnected()) {
                    sleep(5);
                    continue;
                }
                Pipeline pipe = pipeline();
                if (pipe == null) {
                    return null;
                }
                Log.d(TAG, "readData");
                readResult = pipe.readData(buffer, 0, buffer.length);
            }
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

    private int readSizeFromPipe() throws InvalidProtocolBufferException {
        byte[] buffer = readPipeData(packetSize);
        if (buffer == null) {
            return 0;
        }
        Interconnection.packet_size p_size = Interconnection.packet_size.parseFrom(buffer);
        return p_size.getSize();
    }

    // read pipe thread
    private void readMissionPathFromPipe() throws InvalidProtocolBufferException {
        int buffer_size = readSizeFromPipe();
        assert(buffer_size > 0);

        byte[] buffer = readPipeData(buffer_size);
        assert(buffer != null);

        Interconnection.mission_path m_path = Interconnection.mission_path.parseFrom(buffer);
        assert(m_path.getReserved() == 0x0);
        missionPath.load(m_path);
        if (missionPath.isEmpty()) {
            Log.w(TAG, "Loaded mission path is empty");
        }
    }

    // read pipe thread
    private void readPipelineJob() {
        if (appOnPause) {
            return;
        }

        try {
            Log.d(TAG, "readPipelineJob: readSizeFromPipe");
            int buffer_size = readSizeFromPipe();
            if (buffer_size == 0) {
                return;
            }
            Log.d(TAG, "readPipelineJob: readPipeData");
            byte[] buffer = readPipeData(buffer_size);
            if (buffer == null) {
                return;
            }

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
                case PONG:
                    Log.i(TAG, "PONG received");
                    waitForPongReceived = false;
                    break;
                case DRONE_INFO:
                    buffer_size = readSizeFromPipe();
                    if (buffer_size == 0) {
                        return;
                    }
                    byte[] crdBuffer = readPipeData(buffer_size);
                    if (crdBuffer == null) {
                        return;
                    }
                    Interconnection.drone_info d_info = Interconnection.drone_info.parseFrom(crdBuffer);
                    Log.d(TAG, "Drone coordinates and state received");
                    if (event_id == invalid_event_id) {
                        event_id = d_info.getEventId();
                        assert(event_id != invalid_event_id);
                        assert(event_id >= 0);
                    }
                    boolean readMissionPath = false;
                    boolean checkPath = false;
                    if (event_id > d_info.getEventId()) {
                        Log.d(TAG, "Ignore stale drone state");
                        assert(event_id == d_info.getEventId() + 1);
                    } else {
                        assert(event_id == d_info.getEventId());
                        synchronized (droneCoordinatesAndStateMutex) {
                            droneLatitude = d_info.getLatitude();
                            droneLongitude = d_info.getLongitude();
                            droneHeading = d_info.getHeading();
                            droneState = d_info.getState();
                            if (droneState == Interconnection.drone_info.state_t.PATH_DATA) {
                                readMissionPath = true;
                            }
                            if (droneState == Interconnection.drone_info.state_t.PATH) {
                                checkPath = true;
                            }
                        }
                    }
                    if (readMissionPath) {
                        readMissionPathFromPipe();
                    }
                    if (checkPath && missionPath.isEmpty()) {
                        Log.i(TAG, "State is PATH but mission path empty - emit cancel");
                        droneState = Interconnection.drone_info.state_t.WAITING;
                        handler.sendEmptyMessage(0);
                        synchronized (executeCommandsMutex) {
                            executeCommands.add(Interconnection.command_type.command_t.MISSION_PATH_CANCEL);
                        }
                    }
                    handler.sendEmptyMessage(0);
                    break;
                case LASER_RANGE_REQUEST:
                    Log.i(TAG, "Laser range request received");
                    synchronized (executeCommandsMutex) {
                        executeCommands.add(Interconnection.command_type.command_t.LASER_RANGE_RESPONSE);
                    }
                    break;
                default:
                    assert(false);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            Log.e(TAG, "Pipeline parse failed, invalid protocol");
            System.exit(-1);
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

    private boolean writeEventIdToPipe() {
        assert(event_id != invalid_event_id);
        event_id++;
        Interconnection.event_id_message.Builder builder = Interconnection.event_id_message.newBuilder();
        builder.setEventId(event_id);
        byte[] bytesToSend = builder.build().toByteArray();
        if (!writeSizeToPipe(bytesToSend.length)) {
            return false;
        }
        return writePipeData(bytesToSend);
    }

    private boolean writeCommandToPipe(Interconnection.command_type.command_t command) {
        Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
        builder.setVersion(protocolVersion);
        builder.setType(command);
        byte[] bytesToSend = builder.build().toByteArray();
        if (!writeSizeToPipe(bytesToSend.length)) {
            return false;
        }
        return writePipeData(bytesToSend);
    }

    private boolean writeSizeToPipe(int size) {
        Interconnection.packet_size.Builder builder = Interconnection.packet_size.newBuilder();
        builder.setSize(size);
        byte[] bytesToSend = builder.build().toByteArray();
        assert(bytesToSend.length == packetSize);
        return writePipeData(bytesToSend);
    }

    // write pipe thread
    private boolean executeCommand(Interconnection.command_type.command_t command) {
        switch (command) {
            case PING:
                Log.i(TAG, "Execute command PING");
                return writeCommandToPipe(Interconnection.command_type.command_t.PING);
            case BUILD_MISSION: {
                if (!writeCommandToPipe(Interconnection.command_type.command_t.BUILD_MISSION)) {
                    return false;
                }
                Interconnection.coordinate.Builder home_builder = Interconnection.coordinate.newBuilder();
                home_builder.setLatitude(homeLocation.get().getLatitude());
                home_builder.setLongitude(homeLocation.get().getLongitude());

                Interconnection.input_polygon.Builder builder = Interconnection.input_polygon.newBuilder();
                assert (event_id != invalid_event_id);
                event_id++;
                builder.setEventId(event_id);
                builder.setHome(home_builder.build());
                inputPolygon.buildVertices(builder);
                assert(builder.getVerticesCount() > 2);
                byte[] bytesToSend = builder.build().toByteArray();
                if (!writeSizeToPipe(bytesToSend.length)) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.buildMission(inputPolygon, event_id);
                }
                return writePipeData(bytesToSend);
            }
            case MISSION_PATH_CANCEL:
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_PATH_CANCEL)) {
                    return false;
                }
                if (!writeEventIdToPipe()) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.missionPathCancel(event_id);
                }
                return true;
            case MISSION_START: {
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_START)) {
                    return false;
                }
                if (!writeEventIdToPipe()) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.missionStart(event_id);
                }
                return true;
            }
            case MISSION_PAUSE:
                Log.i(TAG, "Execute command MISSION_PAUSE");
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_PAUSE)) {
                    return false;
                }
                if (!writeEventIdToPipe()) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.missionPause(event_id);
                }
                return true;
            case MISSION_CONTINUE:
                Log.i(TAG, "Execute command MISSION_CONTINUE");
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_CONTINUE)) {
                    return false;
                }
                if (!writeEventIdToPipe()) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.missionContinue(event_id);
                }
                return true;
            case MISSION_ABORT:
                Log.i(TAG, "Execute command MISSION_ABORT");
                if (!writeCommandToPipe(Interconnection.command_type.command_t.MISSION_ABORT)) {
                    return false;
                }
                if (!writeEventIdToPipe()) {
                    return false;
                }
                if (mockDrone) {
                    mockPipelineRead.missionAbort(event_id);
                }
                return true;
            case LASER_RANGE_RESPONSE: {
                if (!laserStatus.hasValue()) {
                    Log.i(TAG, "No laser data to send");
                    return false;
                }
                float laserRange = laserStatus.getValue();
                Log.i(TAG, "Sending laser range: " + laserRange);
                if (!writeCommandToPipe(Interconnection.command_type.command_t.LASER_RANGE_RESPONSE)) {
                    return false;
                }
                Interconnection.laser_range.Builder builder = Interconnection.laser_range.newBuilder();
                builder.setRange(laserRange);
                byte[] bytesToSend = builder.build().toByteArray();
                if (!writeSizeToPipe(bytesToSend.length)) {
                    return false;
                }
                return writePipeData(bytesToSend);
            }
            default:
                assert(false);
        }
        return true;
    }

    // UI thread
    private void updateUIState() {
        int buttonColor = getDroneButtonColor();
        droneStatus.setIconTintResource(buttonColor);
        droneStatus.setText(getDroneButtonText());
        droneStatus.setTextColor(getResources().getColor(buttonColor));

        laserStatusButton.setText("Laser: " + laserStatus.getLaserStatus());

        synchronized (droneCoordinatesAndStateMutex) {
            switch (droneState) {
                case READY:
                    if (inputPolygon.isEmpty()) {
                        cancelButton.setVisibility(View.INVISIBLE);
                    } else {
                        cancelButton.setVisibility(View.VISIBLE);
                    }
                    actionButton.setText("Build");
                    actionButton.setEnabled(true);
                    break;
                case PATH_DATA:
                case WAITING:
                    Log.d(TAG, "updateUIState: WAITING");
                    cancelButton.setVisibility(View.INVISIBLE);
                    actionButton.setText("Waiting...");
                    actionButton.setEnabled(false);
                    break;
                case PAUSED:
                    Log.d(TAG, "updateUIState: PAUSED");
                    cancelButton.setVisibility(View.VISIBLE);
                    actionButton.setText("Continue");
                    actionButton.setEnabled(true);
                    break;
                case EXECUTING:
                    cancelButton.setVisibility(View.VISIBLE);
                    actionButton.setText("Pause");
                    actionButton.setEnabled(true);
                    break;
                case PATH:
                    cancelButton.setVisibility(View.VISIBLE);
                    actionButton.setText("Start");
                    actionButton.setEnabled(true);
                    break;
                default:
                    assert (false);
                    break;
            }
        }

        updateDroneCoordinates();
        updateHomeMarker();
        missionPath.draw(homeLocation.get());
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
                        waitForPongReceived = false;
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
        Log.d(TAG, "updateDroneCoordinates");

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

    // UI thread
    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap;
            inputPolygon.onMapReady(this, gMap);
            missionPath.onMapReady(this, gMap);
            gMap.setOnMapClickListener(this);
            gMap.setOnMarkerClickListener(marker -> true);
        }
    }
}
