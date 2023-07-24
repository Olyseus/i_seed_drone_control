package com.olyseus.i_seed.control;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

import dji.common.model.LocationCoordinate2D;
import interconnection.Interconnection;

public class MissionPath {
    private Object mutex = new Object();
    private boolean redraw = false;
    private List<LatLng> vertices = new ArrayList<LatLng>();
    private Polyline polyline = null;
    private List<Marker> markers = new ArrayList<Marker>();
    private AppCompatActivity activity = null;
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
        polyline.setColor(Color.RED);
        polyline.setZIndex(3);
    }

    // UI thread
    public void userRemove() {
        synchronized (mutex) {
            vertices.clear();
            polyline.setPoints(vertices);
            for (Marker m : markers) {
                m.remove();
            }
            markers.clear();
        }
    }

    // UI thread
    public boolean isEmpty() {
        synchronized (mutex) {
            return vertices.isEmpty();
        }
    }

    // UI thread
    public void draw(LocationCoordinate2D home) {
        synchronized (mutex) {
            if (!redraw) {
                return;
            }
            redraw = false;

            for (Marker m : markers) {
                m.remove();
            }
            markers.clear();

            if (vertices.isEmpty()) {
                polyline.setPoints(vertices);
                assert(polyline.getPoints().isEmpty());
                assert(markers.isEmpty());
                return;
            }

            assert(home != null);
            List<LatLng> tmpPoints = new ArrayList<LatLng>(vertices);
            tmpPoints.add(0, new LatLng(home.getLatitude(), home.getLongitude()));
            polyline.setPoints(tmpPoints);

            Bitmap bitmap = BitmapFactory.decodeResource(activity.getResources(), R.drawable.red_circle);
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 20, 20, false);
            BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(resizedBitmap);

            for (LatLng p : vertices) {
                MarkerOptions marker = new MarkerOptions();
                marker.position(p);
                marker.icon(icon);
                marker.anchor(0.5F, 0.5F);
                Marker m = gMap.addMarker(marker);
                markers.add(m);
            }
        }
    }

    // read pipe thread
    public void load(Interconnection.mission_path path) {
        assert(path.getReserved() == 0x0);
        synchronized (mutex) {
            redraw = true;
            vertices.clear();
            for (Interconnection.coordinate c : path.getWaypointsList()) {
                vertices.add(new LatLng(c.getLatitude(), c.getLongitude()));
            }
        }
    }
}
