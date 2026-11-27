package com.medterms.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 1001;
    private static final int REQ_PERM = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private String patchJs = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        patchJs = readAsset("apk_patch.js");

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                if (HOST.equals(u.getHost())) return false;
                if ("http".equals(sch) || "https".equals(sch)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!patchJs.isEmpty()) v.evaluateJavascript(patchJs, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // ===== هذا هو الجزء الذي كان ناقصاً: فتح نافذة اختيار الملفات =====
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                String[] accept = params.getAcceptTypes();
                boolean wantsAudio = accept != null && accept.length > 0
                        && accept[0] != null && accept[0].startsWith("audio");

                try {
                    Intent intent;
                    if (params.isCaptureEnabled() && wantsAudio) {
                        intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
                    } else {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        List<String> types = new ArrayList<>();
                        if (accept != null) {
                            for (String a : accept) {
                                if (a != null && a.trim().length() > 0 && a.contains("/")) types.add(a.trim());
                            }
                        }
                        if (!types.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                        if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }
                    }
                    startActivityForResult(intent, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "لا يوجد تطبيق لاختيار الملفات", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }

            // ===== المايكروفون والكاميرا للمحادثة المباشرة =====
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    List<String> need = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            need.add(Manifest.permission.RECORD_AUDIO);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                            need.add(Manifest.permission.CAMERA);
                    }
                    if (need.isEmpty()) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermission = request;
                        requestPermissions(need.toArray(new String[0]), REQ_PERM);
                    }
                });
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if (url.startsWith("http")) {
                    try {
                        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                        r.setMimeType(mime);
                        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                                URLUtil.guessFileName(url, cd, mime));
                        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                        Toast.makeText(MainActivity.this, "بدأ التنزيل", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (res == RESULT_OK && data != null) {
                result = WebChromeClient.FileChooserParams.parseResult(res, data);
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM && pendingPermission != null) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingPermission.grant(pendingPermission.getResources());
            else pendingPermission.deny();
            pendingPermission = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** جسر لحفظ الملفات (مثل PDF الناتج من "تحويل إلى PDF") في مجلد التنزيلات */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            new Thread(() -> {
                try {
                    String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    Uri opened = null;
                    String where;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(bytes); }
                        opened = u;
                        where = "التنزيلات";
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        File f = new File(dir, safe);
                        try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                        where = f.getAbsolutePath();
                    }
                    final Uri toOpen = opened;
                    final String w = where;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "تم حفظ " + safe + " في " + w, Toast.LENGTH_LONG).show();
                        if (toOpen != null) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setDataAndType(toOpen, mime);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(i);
                            } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذّر حفظ الملف", Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }
}
