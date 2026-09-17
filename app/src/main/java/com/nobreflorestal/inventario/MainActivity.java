package com.nobreflorestal.inventario;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1201;
    private static final int LOCATION_PERMISSION = 1202;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelerometerSensor;
    private Sensor magnetometerSensor;
    private final float[] lastAccel = new float[3];
    private final float[] lastMag = new float[3];
    private boolean hasAccel = false;
    private boolean hasMag = false;
    private long lastHeadingDispatch = 0L;
    private boolean nativeGpsContinuous = false;
    private boolean pendingNativeGps = false;

    private final SensorEventListener headingListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null) return;
            final int type = event.sensor.getType();
            long now = System.currentTimeMillis();
            if (type == Sensor.TYPE_ROTATION_VECTOR) {
                if (now - lastHeadingDispatch < 80L) return;
                lastHeadingDispatch = now;
                float[] rotation = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                SensorManager.getOrientation(rotation, orientation);
                double heading = Math.toDegrees(orientation[0]);
                if (heading < 0) heading += 360.0;
                dispatchNativeHeading(heading);
                return;
            }
            if (type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, lastAccel, 0, Math.min(3, event.values.length));
                hasAccel = true;
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, lastMag, 0, Math.min(3, event.values.length));
                hasMag = true;
            }
            if (!hasAccel || !hasMag || now - lastHeadingDispatch < 100L) return;
            float[] rotation = new float[9];
            float[] orientation = new float[3];
            if (!SensorManager.getRotationMatrix(rotation, null, lastAccel, lastMag)) return;
            SensorManager.getOrientation(rotation, orientation);
            double heading = Math.toDegrees(orientation[0]);
            if (heading < 0) heading += 360.0;
            lastHeadingDispatch = now;
            dispatchNativeHeading(heading);
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
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor != null) {
                sensorManager.registerListener(headingListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            } else {
                accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                if (accelerometerSensor != null) sensorManager.registerListener(headingListener, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
                if (magnetometerSensor != null) sensorManager.registerListener(headingListener, magnetometerSensor, SensorManager.SENSOR_DELAY_GAME);
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);
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

        webView.loadUrl("file:///android_asset/index.html?android=1&build=33");
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
        @JavascriptInterface public void shareBase64File(String fileName, String mimeType, String base64Data) {
            runOnUiThread(() -> shareBase64FileNative(fileName, mimeType, base64Data));
        }
    }


    private void shareBase64FileNative(String fileName, String mimeType, String base64Data) {
        try {
            String safe = fileName == null ? "NOBRE_RASTREIO_SHP.zip" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, safe);
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeType == null || mimeType.isEmpty() ? "application/zip" : mimeType);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Enviar rastreamento SHP"));
        } catch (Exception ex) {
            final String msg = ex.getMessage() == null ? "Falha ao compartilhar o arquivo SHP." : ex.getMessage().replace("'", "");
            if (webView != null) webView.evaluateJavascript("showToast('Erro ao compartilhar SHP: " + msg + "')", null);
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

    private void dispatchNativeHeading(double heading) {
        if (webView == null || Double.isNaN(heading) || Double.isInfinite(heading)) return;
        final String js = "window.__onNativeHeading&&window.__onNativeHeading(" + heading + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
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

    @Override protected void onDestroy() {
        stopNativeGps();
        if (sensorManager != null) sensorManager.unregisterListener(headingListener);
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
