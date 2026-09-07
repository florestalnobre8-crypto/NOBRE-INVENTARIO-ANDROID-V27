package com.nobreflorestal.inventario;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1201;
    private static final int LOCATION_PERMISSION = 1202;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    private LocationManager locationManager;
    private boolean nativeGpsContinuous = false;
    private boolean pendingNativeGps = false;

    private SensorManager sensorManager;
    private Sensor headingSensor;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private boolean useAccelMagHeading = false;
    private boolean haveAccel = false;
    private boolean haveMag = false;
    private final float[] accelValues = new float[3];
    private final float[] magValues = new float[3];
    private boolean headingSensorIsOrientation = false;
    private float lastHeadingDeg = Float.NaN;
    private float lastDispatchedHeading = Float.NaN;
    private long lastHeadingDispatchMs = 0L;
    private boolean headingRequested = false;
    private boolean headingRegistered = false;
    private boolean headingJsPending = false;
    private float pendingHeadingJs = Float.NaN;
    private final float[] rotationMatrix = new float[9];
    private final float[] adjustedMatrix = new float[9];
    private final float[] orientationValues = new float[3];

    private void processHeading(float heading) {
        heading = (heading + 360f) % 360f;
        if (Float.isNaN(lastHeadingDeg)) {
            lastHeadingDeg = heading;
        } else {
            float delta = ((heading - lastHeadingDeg + 540f) % 360f) - 180f;
            float abs = Math.abs(delta);
            // Cursor tipo Avenza: elimina tremor de bussola quando o aparelho esta parado,
            // mas responde rapido quando o usuario realmente gira o celular.
            if (abs < 1.8f) return;
            float alpha = abs > 18f ? 0.72f : (abs > 7f ? 0.52f : 0.34f);
            lastHeadingDeg = (lastHeadingDeg + delta * alpha + 360f) % 360f;
        }
        long now = SystemClock.elapsedRealtime();
        float changed = Float.isNaN(lastDispatchedHeading) ? 999f : Math.abs(((lastHeadingDeg - lastDispatchedHeading + 540f) % 360f) - 180f);
        if (now - lastHeadingDispatchMs < 50L) return;
        if (changed < 0.65f && now - lastHeadingDispatchMs < 220L) return;
        lastHeadingDispatchMs = now;
        lastDispatchedHeading = lastHeadingDeg;
        dispatchNativeHeading(lastHeadingDeg);
    }

    private float headingFromRotationMatrix(float[] matrix) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean remapped;
        switch (rotation) {
            case Surface.ROTATION_90:
                remapped = SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedMatrix);
                break;
            case Surface.ROTATION_180:
                remapped = SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedMatrix);
                break;
            case Surface.ROTATION_270:
                remapped = SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedMatrix);
                break;
            default:
                System.arraycopy(matrix, 0, adjustedMatrix, 0, Math.min(matrix.length, adjustedMatrix.length));
                remapped = true;
                break;
        }
        if (!remapped) return Float.NaN;
        SensorManager.getOrientation(adjustedMatrix, orientationValues);
        return (float) Math.toDegrees(orientationValues[0]);
    }

    private final SensorEventListener headingListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || webView == null) return;
            int type = event.sensor.getType();
            if (useAccelMagHeading && (type == Sensor.TYPE_ACCELEROMETER || type == Sensor.TYPE_MAGNETIC_FIELD)) {
                if (type == Sensor.TYPE_ACCELEROMETER) {
                    System.arraycopy(event.values, 0, accelValues, 0, Math.min(3, event.values.length));
                    haveAccel = true;
                } else {
                    System.arraycopy(event.values, 0, magValues, 0, Math.min(3, event.values.length));
                    haveMag = true;
                }
                if (!haveAccel || !haveMag) return;
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues)) {
                    float h = headingFromRotationMatrix(rotationMatrix);
                    if (!Float.isNaN(h)) processHeading(h);
                }
                return;
            }

            float heading;
            if (headingSensorIsOrientation || type == Sensor.TYPE_ORIENTATION) {
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                heading = event.values[0];
                if (rotation == Surface.ROTATION_90) heading += 90f;
                else if (rotation == Surface.ROTATION_180) heading += 180f;
                else if (rotation == Surface.ROTATION_270) heading += 270f;
            } else {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                heading = headingFromRotationMatrix(rotationMatrix);
                if (Float.isNaN(heading)) return;
            }
            processHeading(heading);
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    private final LocationListener nativeLocationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            dispatchNativeLocation(location);
            if (!nativeGpsContinuous && locationManager != null) {
                try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
            }
        }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (headingSensor == null) headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
            if (headingSensor == null) headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (headingSensor == null) {
                headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
                headingSensorIsOrientation = headingSensor != null;
            }
            if (headingSensor == null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                useAccelMagHeading = accelerometer != null && magnetometer != null;
            }
        }

        webView = new WebView(this);
        setContentView(webView);
        webView.setKeepScreenOn(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new GpsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternalIfNeeded(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternalIfNeeded(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = params.createIntent();
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv"
                });
                try {
                    startActivityForResult(intent, FILE_CHOOSER);
                } catch (ActivityNotFoundException ex) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html?android=1");
    }

    public class GpsBridge {
        @JavascriptInterface public void requestLocationOnce() {
            runOnUiThread(() -> startNativeGps(false));
        }
        @JavascriptInterface public void startLocationUpdates() {
            runOnUiThread(() -> startNativeGps(true));
        }
        @JavascriptInterface public void stopLocationUpdates() {
            runOnUiThread(() -> stopNativeGps());
        }
        @JavascriptInterface public void startHeadingUpdates() {
            runOnUiThread(() -> { headingRequested = true; startHeadingSensor(); });
        }
        @JavascriptInterface public void stopHeadingUpdates() {
            runOnUiThread(() -> { headingRequested = false; stopHeadingSensor(); });
        }
    }

    private void startNativeGps(boolean continuous) {
        nativeGpsContinuous = continuous;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingNativeGps = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        pendingNativeGps = false;
        if (locationManager == null) return;

        try {
            // Evita múltiplos listeners acumulados ao tocar várias vezes em localização/navegação.
            try { locationManager.removeUpdates(nativeLocationListener); } catch (SecurityException ignored) {}
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) dispatchNativeLocation(last);

            boolean requested = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.5f, nativeLocationListener);
                requested = true;
            }
            if (!requested && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0.5f, nativeLocationListener);
            }
        } catch (SecurityException ignored) {}
    }

    private void stopNativeGps() {
        nativeGpsContinuous = false;
        if (locationManager != null) {
            try { locationManager.removeUpdates(nativeLocationListener); } catch (SecurityException ignored) {}
        }
    }

    private void dispatchNativeLocation(Location location) {
        if (location == null || webView == null) return;
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        final float acc = location.hasAccuracy() ? location.getAccuracy() : 0f;
        final float bearing = location.hasBearing() ? location.getBearing() : 0f;
        final float speed = location.hasSpeed() ? location.getSpeed() : 0f;
        final String js = "window.__onNativeLocation&&window.__onNativeLocation(" + lat + "," + lon + "," + acc + "," + bearing + "," + speed + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }


    private void dispatchNativeHeading(float headingDeg) {
        if (webView == null || Float.isNaN(headingDeg)) return;
        pendingHeadingJs = headingDeg;
        if (headingJsPending) return;
        headingJsPending = true;
        webView.post(this::flushNativeHeadingToJs);
    }

    private void flushNativeHeadingToJs() {
        if (webView == null) {
            headingJsPending = false;
            pendingHeadingJs = Float.NaN;
            return;
        }
        final float heading = pendingHeadingJs;
        pendingHeadingJs = Float.NaN;
        final String js = "window.__onNativeHeading&&window.__onNativeHeading(" + heading + ");";
        webView.evaluateJavascript(js, value -> {
            headingJsPending = false;
            if (!Float.isNaN(pendingHeadingJs)) dispatchNativeHeading(pendingHeadingJs);
        });
    }

    private void startHeadingSensor() {
        if (!headingRequested || headingRegistered || sensorManager == null) return;
        lastHeadingDeg = Float.NaN;
        lastDispatchedHeading = Float.NaN;
        lastHeadingDispatchMs = 0L;
        if (headingSensor != null) {
            headingRegistered = sensorManager.registerListener(headingListener, headingSensor, SensorManager.SENSOR_DELAY_GAME);
        } else if (useAccelMagHeading) {
            haveAccel = false;
            haveMag = false;
            boolean a = sensorManager.registerListener(headingListener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            boolean m = sensorManager.registerListener(headingListener, magnetometer, SensorManager.SENSOR_DELAY_GAME);
            headingRegistered = a || m;
        }
    }

    private void stopHeadingSensor() {
        if (sensorManager != null && headingRegistered) sensorManager.unregisterListener(headingListener);
        headingRegistered = false;
        pendingHeadingJs = Float.NaN;
    }

    private boolean openExternalIfNeeded(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (scheme.equals("geo") || scheme.equals("waze") || host.contains("google.com") || host.contains("waze.com")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            } catch (ActivityNotFoundException ignored) {}
        }
        return false;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingGeoCallback != null) {
                pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
            if (granted && pendingNativeGps) startNativeGps(nativeGpsContinuous);
            pendingNativeGps = false;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (headingRequested) startHeadingSensor();
    }

    @Override protected void onPause() {
        stopHeadingSensor();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopNativeGps();
        stopHeadingSensor();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
