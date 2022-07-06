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
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
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

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = "MainActivity";
    private GoogleMap gMap;
    private ScheduledExecutorService pollExecutor;
    private ScheduledExecutorService readPipelineExecutor;
    private ScheduledExecutorService writePipelineExecutor;
    private Handler handler;
    private boolean sdkRegistrationStarted = false;
    private boolean executionInProgress = false;
    private static boolean useBridge = false;
    private Aircraft aircraft = null;
    Marker droneMarker = null;
    private Object mutex = new Object();
    private List<Interconnection.command_type.command_t> executeCommands = new ArrayList<Interconnection.command_type.command_t>();
    private int commandByteLength = 0;
    private static int protocolVersion = 2; // Keep it consistent with Onboard SDK
    private static int channelID = 9745; // Just a random number. Keep it consistent with Onboard SDK

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

    private AtomicReference<State> state = new AtomicReference<State>(State.NO_PERMISSIONS);
    private AtomicReference<Float> laserDistance = new AtomicReference<Float>(0.0F);

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

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                updateUIState();
            }
        };

        updateUIState(); // Init

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        checkAndRequestPermissions();

        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        readPipelineExecutor = Executors.newSingleThreadScheduledExecutor();
        writePipelineExecutor = Executors.newSingleThreadScheduledExecutor();

        Runnable pollRunnable = new Runnable() {
            @Override public void run() { pollJob(); }
        };
        Runnable readPipelineRunnable = new Runnable() {
            @Override
            public void run() { readPipelineJob(); }
        };
        Runnable writePipelineRunnable = new Runnable() {
            @Override
            public void run() { writePipelineJob(); }
        };
        pollExecutor.scheduleAtFixedRate(pollRunnable, 0, 200, TimeUnit.MILLISECONDS);
        readPipelineExecutor.scheduleAtFixedRate(readPipelineRunnable, 0, 200, TimeUnit.MILLISECONDS);
        writePipelineExecutor.scheduleAtFixedRate(writePipelineRunnable, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void actionButtonClicked() {
        synchronized (mutex) {
            // FIXME (implement)
            executeCommands.add(Interconnection.command_type.command_t.PING);
        }

        updateDroneCoordinates(48.858457, 2.2943995, 45.0F); // FIXME (remove)
    }

    private void homeButtonClicked() {
        zoomToDrone();
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

        if (pipeline() == null) {
            if (state.get() == State.CONNECTING) {
                return;
            }
            updateState(State.CONNECTING);

            flightController.getPipelines().connect(channelID, TransmissionControlType.STABLE, error -> {
                if (error == null) {
                    assert(pipeline() != null);
                    updateState(State.NO_GPS);
                }
                else {
                    if (error == PipelineError.NOT_READY) {
                        Log.e(TAG, "Interconnection failed, sleep 5 seconds");
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

    private void reconnect() {
        if (aircraft == null) {
            return;
        }
        Log.i(TAG, "Disconnect pipeline");
        FlightController flightController = aircraft.getFlightController();
        flightController.getPipelines().disconnect(channelID, error -> {
            if (error == PipelineError.CLOSED) {
                // already closed
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
        return flightController.getPipelines().getPipeline(channelID);
    }

    private void readPipelineJob() {
        if (pipeline() == null) {
            return;
        }

        if (commandByteLength == 0) {
            Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
            builder.setVersion(protocolVersion);
            builder.setType(Interconnection.command_type.command_t.PING);
            commandByteLength = builder.build().toByteArray().length;
            assert (commandByteLength > 0);
        }

        byte[] buffer = new byte[commandByteLength];
        int readResult = pipeline().readData(buffer, 0, buffer.length);
        if (readResult == -10008) {
            // Timeout: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L22
            return;
        }
        if (readResult == -10011) {
            // Connection closed:
            // https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L25
            reconnect();
            return;
        }
        if (readResult <= 0) {
            Log.e(TAG, "Read pipeline error: " + readResult);
            return;
        }

        assert(readResult == buffer.length);
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
                default:
                    assert(false);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            Log.e(TAG, "Pipeline parse failed, invalid protocol");
        }
    }

    private void writePipelineJob() {
        if (pipeline() == null) {
            synchronized (mutex) {
                if (executeCommands.size() > 0) {
                    Log.w(TAG, "Number of commands in queue: " + executeCommands.size());
                }
            }
            return;
        }
        if (executionInProgress) {
            return;
        }
        executionInProgress = true;

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

        executionInProgress = false;
    }

    private boolean executeCommand(Interconnection.command_type.command_t command) {
        switch (command) {
            case PING:
                Log.i(TAG, "Execute command PING");
                Interconnection.command_type.Builder builder = Interconnection.command_type.newBuilder();
                builder.setVersion(protocolVersion);
                builder.setType(Interconnection.command_type.command_t.PING);
                byte[] bytesToSend = builder.build().toByteArray();
                int writeResult = pipeline().writeData(bytesToSend, 0, bytesToSend.length);
                if (writeResult == -10008) {
                    // Timeout: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L22
                    Log.e(TAG, "Write failed, timeout");
                    return false;
                }
                if (writeResult == -10011) {
                    // Connection closed: https://github.com/dji-sdk/Onboard-SDK/blob/4.1.0/osdk-core/linker/armv8/inc/mop.h#L25
                    Log.e(TAG, "Write failed, connection closed");
                    reconnect();
                    return false;
                }
                if (writeResult > 0) {
                    assert(writeResult == bytesToSend.length);
                    return true;
                }
                Log.e(TAG, "Write pipeline error: " + writeResult);
                return false;
            default:
                assert(false);
        }
        return true;
    }

    private void updateUIState() {
        MaterialButton droneStatus = findViewById(R.id.droneStatus);
        int buttonColor = getDroneButtonColor();
        droneStatus.setIconTintResource(buttonColor);
        droneStatus.setText(getDroneButtonText());
        droneStatus.setTextColor(getResources().getColor(buttonColor));

        Button laserStatus = findViewById(R.id.laserStatus);
        if (state.get() == State.ONLINE) {
            laserStatus.setText("Laser: " + String.format("%.1f", laserDistance.get()));
        }
        else {
            laserStatus.setText("Laser: N/A");
        }
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
                            if (useBridge) {
                                DJISDKManager.getInstance().enableBridgeModeWithBridgeAppIP("192.168.0.227");
                            }
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
            Log.i(TAG, "Camera: " + camera.getDisplayName());
            camera.setLaserEnabled(true, null);
            camera.getLaserEnabled(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
                @Override
                public void onSuccess(Boolean aBoolean) {
                    assert(aBoolean == true);
                    Log.i(TAG, "Laser is ON");
                    updateState(State.WAIT_LASER);
                    setupMeasurementCallback(camera);
                }

                @Override
                public void onFailure(DJIError djiError) {}
            });
        }
    }

    private void setupMeasurementCallback(Camera camera) {
        for (Lens lens : camera.getLenses()) {
            lens.setLaserMeasureInformationCallback(new LaserMeasureInformation.Callback() {
                @Override
                public void onUpdate(LaserMeasureInformation laserMeasureInformation) {
                    float targetDistance = laserMeasureInformation.getTargetDistance();
                    LaserError laserError = laserMeasureInformation.getLaserError();
                    if (laserError == LaserError.TOO_FAR) {
                        Log.e(TAG, "Laser is too far");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    if (laserError == LaserError.TOO_CLOSE) {
                        Log.e(TAG, "Laser is too close");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    if (laserError == LaserError.NO_SIGNAL) {
                        Log.e(TAG, "Laser no signal");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    if (laserError == LaserError.UNKNOWN) {
                        Log.e(TAG, "Laser state is unknown");
                        updateState(State.LASER_ERROR);
                        return;
                    }
                    assert(laserError == LaserError.NORMAL);
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

    private void updateDroneCoordinates(double latitude, double longitude, float heading) {
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
        droneMarker = gMap.addMarker(markerOptions);
        zoomToDrone();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap; // FIXME (use)
        }
    }
}
