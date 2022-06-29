package com.olyseus.i_seed.control;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

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
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.Lens;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = "MainActivity";
    private GoogleMap gMap;
    private ScheduledExecutorService executorService;
    private Handler handler;
    private boolean sdkRegistrationStarted = false;
    private static boolean useBridge = false;
    private Aircraft aircraft = null;

    enum State {
        NO_PERMISSIONS,
        MAP_NOT_READY,
        NOT_REGISTERED,
        REGISTRATION_FAILED,
        NO_PRODUCT,
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

        executorService = Executors.newSingleThreadScheduledExecutor();
        Runnable pollRunnable = new Runnable() {
            @Override public void run() { pollJob(); }
        };
        executorService.scheduleAtFixedRate(pollRunnable, 0, 1, TimeUnit.SECONDS);
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

        if (aircraft != null) {
            FlightController flightController = aircraft.getFlightController();
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
        }

        if (state.get() == State.LASER_OFF) {
            enableLaser();
            return;
        }
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
                        assert(false);
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
                    Log.i(TAG, "Laser info: " + laserMeasureInformation);
                }
            });
        }
    }

    private void showToast(final String toastMsg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap; // FIXME (use)
        }
    }
}