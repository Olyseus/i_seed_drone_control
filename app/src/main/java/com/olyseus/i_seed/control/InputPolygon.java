package com.olyseus.i_seed.control;

import static com.olyseus.i_seed.control.MainActivity.POLYGON_PATTERN;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import interconnection.Interconnection;

public class InputPolygon {
    private Object mutex = new Object();
    private List<LatLng> vertices = new ArrayList<LatLng>();
    private Polyline polyline = null;
    private Polyline endLine = null;
    private List<Marker> markers = new ArrayList<Marker>();
    private AppCompatActivity activity = null;
    static private String TAG = "InputPolygon";
    private GoogleMap gMap = null;

    // UI thread
    public void onMapReady(AppCompatActivity appActivity, GoogleMap googleMap) {
        assert(activity == null);
        assert(appActivity != null);
        activity = appActivity;

        assert(gMap == null);
        assert(googleMap != null);
        gMap = googleMap;

        polyline = gMap.addPolyline(new PolylineOptions().clickable(false));
        polyline.setWidth(5.0F);

        endLine = gMap.addPolyline(new PolylineOptions().clickable(false));
        endLine.setWidth(5.0F);
        endLine.setPattern(POLYGON_PATTERN);
    }

    // UI thread
    public void add(LatLng point) {
        synchronized (mutex) {
            vertices.add(point);
            List<LatLng> tmpPoints = new ArrayList<LatLng>(vertices);
            if (tmpPoints.size() > 2) {
                assert(endLine != null);
                endLine.setPoints(Arrays.asList(vertices.get(0), vertices.get(vertices.size() - 1)));
            }
            polyline.setPoints(tmpPoints);

            Bitmap bitmap = BitmapFactory.decodeResource(activity.getResources(), R.drawable.blank_check_circle);
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 20, 20, false);
            BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(resizedBitmap);

            MarkerOptions marker = new MarkerOptions();
            marker.position(point);
            marker.icon(icon);
            marker.anchor(0.5F, 0.5F);
            Marker m = gMap.addMarker(marker);
            markers.add(m);
        }
    }

    // UI thread
    public int size() {
        synchronized (mutex) {
            return vertices.size();
        }
    }

    // UI thread
    public boolean isEmpty() {
        synchronized (mutex) {
            return vertices.isEmpty();
        }
    }

    // UI thread
    public void userRemove() {
        synchronized (mutex) {
            if (vertices.isEmpty()) {
                assert(polyline.getPoints().isEmpty());
                assert(endLine.getPoints().isEmpty());
                assert(markers.isEmpty());
                return;
            }
        }

        assert(activity != null);
        new MaterialAlertDialogBuilder(activity)
                .setMessage("Are you sure you want to clear the mission polygon?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG, "User action: clear mission polygon");
                        synchronized (mutex) {
                            assert(!vertices.isEmpty());
                            vertices.clear();
                            polyline.setPoints(vertices);
                            endLine.setPoints(vertices);
                            for (Marker m : markers) {
                                m.remove();
                            }
                            markers.clear();
                        }
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    // write pipe thread
    public void buildVertices(Interconnection.input_polygon.Builder input_polygon_builder) {
        synchronized (mutex) {
            assert(vertices.size() > 2);
            for (LatLng c : vertices) {
                Interconnection.coordinate.Builder c_builder = Interconnection.coordinate.newBuilder();
                c_builder.setLatitude(c.latitude);
                c_builder.setLongitude(c.longitude);
                input_polygon_builder.addVertices(c_builder.build());
            }
        }
    }

    // UI thread
    public List<LatLng> mockMissionPath() {
        synchronized (mutex) {
            assert(vertices.size() > 2);
            double lat = 0.0;
            double lon = 0.0;
            for (LatLng v : vertices) {
                lat = lat + v.latitude;
                lon = lon + v.longitude;
            }
            lat = lat / vertices.size();
            lon = lon / vertices.size();

            List<LatLng> result = new ArrayList<LatLng>();
            for (LatLng v : vertices) {
                result.add(new LatLng((v.latitude + lat) / 2.0, (v.longitude + lon) / 2.0));
            }
            return result;
        }
    }
}