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
import android.net.Uri;
import android.os.Bundle;
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
        final String js = "window.__onNativeLocation&&window.__onNativeLocation(" + lat + "," + lon + "," + acc + ");";
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
