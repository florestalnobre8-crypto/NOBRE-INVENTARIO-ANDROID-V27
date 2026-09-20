package com.nobreflorestal.inventario;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
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
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Window;
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
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1201;
    private static final int LOCATION_PERMISSION = 1202;
    private static final int PDF_MAP_PICKER = 1203;

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
        try {
            Window w = getWindow();
            w.setNavigationBarColor(Color.rgb(6,46,30));
            w.setStatusBarColor(Color.rgb(6,61,40));
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(true);
            }
        } catch (Exception ignored) {}

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
                        "text/csv",
                        "application/pdf"
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

        webView.loadUrl("file:///android_asset/index.html?android=1&build=47");
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
        @JavascriptInterface public void pickPdfMap() {
            runOnUiThread(() -> openPdfMapPicker());
        }
    }



    private void openPdfMapPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            startActivityForResult(intent, PDF_MAP_PICKER);
        } catch (Exception ex) {
            if (webView != null) webView.evaluateJavascript(
                    "showToast('Não foi possível abrir os arquivos PDF.')", null);
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "MAPA.pdf";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name == null || name.trim().isEmpty() ? "MAPA.pdf" : name;
    }

    private byte[] readUriBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("Arquivo PDF não disponível.");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static class GeoHit {
        double north, south, west, east;
        double[] bbox;
        GeoHit(double n, double s, double w, double e, double[] bb) {
            north=n; south=s; west=w; east=e; bbox=bb;
        }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("north", north);
            o.put("south", south);
            o.put("west", west);
            o.put("east", east);
            return o;
        }
    }

    private List<Double> numbersFrom(String text) {
        List<Double> out = new ArrayList<>();
        Matcher m = Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").matcher(text == null ? "" : text);
        while (m.find()) {
            try { out.add(Double.parseDouble(m.group())); } catch (Exception ignored) {}
        }
        return out;
    }

    private GeoHit geoCandidate(List<Double> nums, boolean swap, double[] bbox) {
        if (nums == null || nums.size() < 8) return null;
        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();
        for (int i=0; i+1<nums.size(); i+=2) {
            double a=nums.get(i), b=nums.get(i+1);
            double lat=swap?b:a, lon=swap?a:b;
            if (Math.abs(lat)>90 || Math.abs(lon)>180) return null;
            lats.add(lat); lons.add(lon);
        }
        if (lats.size()<4) return null;
        double north=-90, south=90, west=180, east=-180;
        for (double v:lats) { north=Math.max(north,v); south=Math.min(south,v); }
        for (double v:lons) { west=Math.min(west,v); east=Math.max(east,v); }
        if (!(north>south && east>west)) return null;
        if ((north-south)>30 || (east-west)>60) return null;
        return new GeoHit(north,south,west,east,bbox);
    }

    private double[] findLastBBox(String context) {
        Matcher bm = Pattern.compile("/BBox\\s*\\[([^\\]]+)\\]", Pattern.DOTALL).matcher(context == null ? "" : context);
        double[] last = null;
        while (bm.find()) {
            List<Double> b = numbersFrom(bm.group(1));
            if (b.size() >= 4) last = new double[]{b.get(0),b.get(1),b.get(2),b.get(3)};
        }
        return last;
    }

    private List<GeoHit> parseGeoText(String text) {
        List<GeoHit> hits = new ArrayList<>();
        if (text == null || !text.contains("/GPTS")) return hits;
        Matcher gm = Pattern.compile("/GPTS\\s*\\[([\\s\\S]*?)\\]").matcher(text);
        while (gm.find() && hits.size() < 12) {
            List<Double> nums = numbersFrom(gm.group(1));
            if (nums.size() < 8) continue;
            int s = Math.max(0, gm.start()-2500);
            int e = Math.min(text.length(), gm.end()+1200);
            double[] bbox = findLastBBox(text.substring(s,e));
            GeoHit a = geoCandidate(nums,false,bbox);
            GeoHit b = geoCandidate(nums,true,bbox);
            if (a != null) hits.add(a);
            if (b != null) hits.add(b);
        }
        return hits;
    }

    private List<GeoHit> extractGeoHits(byte[] bytes) {
        List<GeoHit> hits = new ArrayList<>();
        try {
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            hits.addAll(parseGeoText(raw));
            if (!hits.isEmpty()) return hits;

            int pos=0, checked=0;
            while (checked < 350) {
                int si = raw.indexOf("stream", pos);
                if (si < 0) break;
                int ei = raw.indexOf("endstream", si+6);
                if (ei < 0) break;
                int ds = Math.max(0, si-900);
                String dict = raw.substring(ds, si);
                pos = ei + 9;
                checked++;
                if (!dict.contains("FlateDecode")) continue;

                int dataStart = si + 6;
                if (dataStart < bytes.length && bytes[dataStart] == 13) dataStart++;
                if (dataStart < bytes.length && bytes[dataStart] == 10) dataStart++;
                int dataEnd = ei;
                while (dataEnd > dataStart && (bytes[dataEnd-1] == 10 || bytes[dataEnd-1] == 13)) dataEnd--;

                try (InflaterInputStream zin = new InflaterInputStream(
                        new ByteArrayInputStream(bytes, dataStart, Math.max(0,dataEnd-dataStart)));
                     ByteArrayOutputStream zout = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = zin.read(buf)) > 0 && zout.size() < 8_000_000) zout.write(buf,0,n);
                    String txt = zout.toString(StandardCharsets.ISO_8859_1.name());
                    List<GeoHit> found = parseGeoText(txt);
                    if (!found.isEmpty()) {
                        hits.addAll(found);
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return hits;
    }

    private File renderPdfMapToFile(Uri uri, GeoHit cropHint) throws Exception {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new Exception("Não consegui abrir o PDF.");

        PdfRenderer renderer = new PdfRenderer(pfd);
        if (renderer.getPageCount() < 1) {
            renderer.close(); pfd.close();
            throw new Exception("PDF sem páginas.");
        }

        PdfRenderer.Page page = renderer.openPage(0);
        int pw = Math.max(1, page.getWidth());
        int ph = Math.max(1, page.getHeight());

        double scale = Math.min(4.0, 3000.0 / Math.max(pw, ph));
        scale = Math.max(1.5, scale);
        int w = Math.max(1, (int)Math.round(pw*scale));
        int h = Math.max(1, (int)Math.round(ph*scale));

        long pixels = (long)w*(long)h;
        if (pixels > 8_000_000L) {
            double f = Math.sqrt(8_000_000.0 / pixels);
            w = Math.max(1, (int)Math.round(w*f));
            h = Math.max(1, (int)Math.round(h*f));
            scale = (double)w / pw;
        }

        Bitmap bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        Bitmap output = bitmap;
        if (cropHint != null && cropHint.bbox != null && cropHint.bbox.length >= 4) {
            try {
                double x0=Math.min(cropHint.bbox[0],cropHint.bbox[2]);
                double x1=Math.max(cropHint.bbox[0],cropHint.bbox[2]);
                double y0=Math.min(cropHint.bbox[1],cropHint.bbox[3]);
                double y1=Math.max(cropHint.bbox[1],cropHint.bbox[3]);

                int left=(int)Math.floor(x0*scale);
                int right=(int)Math.ceil(x1*scale);
                int top=(int)Math.floor((ph-y1)*scale);
                int bottom=(int)Math.ceil((ph-y0)*scale);

                left=Math.max(0,Math.min(w-1,left));
                right=Math.max(left+1,Math.min(w,right));
                top=Math.max(0,Math.min(h-1,top));
                bottom=Math.max(top+1,Math.min(h,bottom));

                if (right-left > 200 && bottom-top > 200) {
                    output = Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top);
                }
            } catch (Exception ignored) {}
        }

        File mapsDir = new File(getFilesDir(), "offline_maps");
        if (!mapsDir.exists()) mapsDir.mkdirs();
        File out = new File(mapsDir, "map_pdf_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            output.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        }

        if (output != bitmap) output.recycle();
        bitmap.recycle();
        page.close();
        renderer.close();
        pfd.close();
        return out;
    }

    private void importPdfMapNative(Uri uri) {
        new Thread(() -> {
            try {
                final String name = getDisplayName(uri);
                byte[] raw = readUriBytes(uri);
                List<GeoHit> hits = extractGeoHits(raw);
                GeoHit cropHint = hits.isEmpty() ? null : hits.get(0);
                File imageFile = renderPdfMapToFile(uri, cropHint);

                JSONObject payload = new JSONObject();
                payload.put("name", name);
                payload.put("src", Uri.fromFile(imageFile).toString());
                payload.put("georeferenced", !hits.isEmpty());
                JSONArray arr = new JSONArray();
                for (GeoHit h : hits) arr.put(h.toJson());
                payload.put("boundsCandidates", arr);

                final String arg = JSONObject.quote(payload.toString());
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.onAndroidPdfMapReadyV41(" + arg + ")", null);
                    }
                });
            } catch (Exception ex) {
                final String msg = ex.getMessage() == null ? "Falha ao importar PDF." :
                        ex.getMessage().replace("\\","").replace("'","");
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(
                            "showToast('Não consegui importar o PDF: " + msg + "')", null);
                });
            }
        }).start();
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

        if (requestCode == PDF_MAP_PICKER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                if (webView != null) webView.evaluateJavascript(
                        "showToast('Lendo PDF do mapa...')", null);
                importPdfMapNative(uri);
            }
            return;
        }

        if (requestCode == FILE_CHOOSER && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "if(window.fastRefreshV45){window.fastRefreshV45(false)}", null), 250);
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
