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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = "MainActivity";
    private GoogleMap gMap;
    private ScheduledExecutorService executorService;
    private Handler handler;

    enum State {
        NO_PERMISSIONS,
        MAP_NOT_READY,
        NO_PRODUCT,
        ONLINE
    }

    private AtomicReference<State> state = new AtomicReference<State>(State.NO_PERMISSIONS);
    private AtomicReference<Double> laserDistance = new AtomicReference<Double>(0.0);

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

    private void pollJob() {
        if (!missingPermission.isEmpty()) {
            state.set(State.NO_PERMISSIONS);
            handler.sendEmptyMessage(0);
            return;
        }

        if (gMap == null) {
            state.set(State.MAP_NOT_READY);
            handler.sendEmptyMessage(0);
            return;
        }

        // FIXME
        state.set(State.ONLINE);
        handler.sendEmptyMessage(0);
    }

    private void updateUIState() {
        MaterialButton droneStatus = findViewById(R.id.droneStatus);
        int buttonColor = getDroneButtonColor();
        droneStatus.setIconTintResource(buttonColor);
        droneStatus.setText(getDroneButtonText());
        droneStatus.setTextColor(getResources().getColor(buttonColor));

        Button laserStatus = findViewById(R.id.laserStatus);
        if (state.get() == State.ONLINE) {
            laserStatus.setText("Laser: " + laserDistance.get());
        }
        else {
            laserStatus.setText("Laser: N/A");
        }
    }

    private int getDroneButtonColor() {
        switch (state.get()) {
            case NO_PERMISSIONS:
            case MAP_NOT_READY:
            case NO_PRODUCT:
                return android.R.color.holo_red_light;
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
            case NO_PRODUCT:
                return "No product";
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