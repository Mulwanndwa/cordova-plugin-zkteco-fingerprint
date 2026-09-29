package com.zkteco.cordova;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

import com.zkteco.android.biometric.core.device.ParameterHelper;
import com.zkteco.android.biometric.core.device.TransportType;
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintCaptureListener;
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintSensor;
import com.zkteco.android.biometric.module.fingerprintreader.FingprintFactory;
import com.zkteco.android.biometric.module.fingerprintreader.ZKFingerService;
import com.zkteco.android.biometric.module.fingerprintreader.exception.FingerprintException;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ZKTecoFingerprintPlugin extends CordovaPlugin {

    private static final String TAG = "ZKTecoFingerprint";

    // ZKTeco USB fingerprint reader identifiers
    private static final int VID = 6997;
    private static final int PID = 292;

    // Template size produced by ZKTeco SDK
    private static final int TEMPLATE_SIZE = 2048;

    // Enrollment requires 3 consistent scans
    private static final int ENROLL_COUNT = 3;

    private static final String ACTION_USB_PERMISSION = "com.zkteco.cordova.USB_PERMISSION";

    private FingerprintSensor fingerprintSensor;
    private boolean sensorOpen = false;
    private boolean captureRunning = false;

    // "identify" or "enroll"
    private String captureMode = "identify";

    private String enrollUserId = null;
    private int enrollIdx = 0;
    private final byte[][] enrollTemplates = new byte[ENROLL_COUNT][TEMPLATE_SIZE];

    // Active callback kept alive for streaming results
    private CallbackContext activeCallback = null;

    // init() waiting on the USB permission dialog result (null once resolved)
    private CallbackContext pendingInitCallback = null;

    private boolean usbReceiverRegistered = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, granted ? "USB permission granted" : "USB permission denied by user");

            final CallbackContext waiting = pendingInitCallback;
            pendingInitCallback = null;
            if (waiting == null) return;

            cordova.getThreadPool().execute(() -> {
                if (!granted) {
                    waiting.error("USB permission denied for the fingerprint reader");
                    return;
                }
                try {
                    openSensor();
                    waiting.success("Fingerprint sensor initialized");
                } catch (FingerprintException e) {
                    waiting.error("Init failed: " + e.getMessage() + " (code " + e.getErrorCode() + ")");
                } catch (Exception e) {
                    waiting.error("Init failed: " + e.getMessage());
                }
            });
        }
    };

    // -------------------------------------------------------------------------
    // Cordova entry point
    // -------------------------------------------------------------------------

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {

        switch (action) {
            case "init":
                init(callbackContext);
                return true;
            case "startEnroll":
                startEnroll(args.getString(0), callbackContext);
                return true;
            case "startIdentify":
                startIdentify(callbackContext);
                return true;
            case "stopCapture":
                stopCapture(callbackContext);
                return true;
            case "loadTemplates":
                loadTemplates(args.getJSONArray(0), callbackContext);
                return true;
            case "deleteTemplate":
                deleteTemplate(args.getString(0), callbackContext);
                return true;
            case "clearTemplates":
                clearTemplates(callbackContext);
                return true;
            case "isConnected":
                isConnected(callbackContext);
                return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Action implementations
    // -------------------------------------------------------------------------

    private void init(final CallbackContext callbackContext) {
        // Idempotent: openSensor() creates a brand-new native FingerprintSensor
        // every time it runs, with no matching close() of whatever was open
        // before. A caller that re-inits on every scan attempt (e.g. after a
        // prior error) would otherwise leak the old sensor handle while
        // captureRunning/activeCallback state from that leaked session bleeds
        // into the new one — the classic symptom being a "Get Image failed"
        // captureError on the retry because the sensor object actually
        // receiving events is no longer the one startCapture() was called on.
        if (sensorOpen) {
            callbackContext.success("Fingerprint sensor already initialized");
            return;
        }
        cordova.getThreadPool().execute(() -> {
            try {
                registerUsbReceiver();

                UsbManager usbManager = (UsbManager)
                        cordova.getActivity().getSystemService(Context.USB_SERVICE);
                UsbDevice device = findDevice(usbManager);
                if (usbManager == null || device == null) {
                    callbackContext.error("No ZKTeco fingerprint reader detected (VID " + VID + " / PID " + PID + ")");
                    return;
                }

                if (!usbManager.hasPermission(device)) {
                    // Hold the callback open until the permission dialog result comes back
                    // through usbReceiver — opening the sensor before that would race the
                    // native SDK against a permission it doesn't have yet.
                    pendingInitCallback = callbackContext;
                    requestUsbPermission(usbManager, device);
                    return;
                }

                openSensor();
                callbackContext.success("Fingerprint sensor initialized");
            } catch (FingerprintException e) {
                Log.e(TAG, "init failed: " + e.getMessage());
                callbackContext.error("Init failed: " + e.getMessage()
                        + " (code " + e.getErrorCode() + ")");
            } catch (Exception e) {
                Log.e(TAG, "init exception: " + e.getMessage());
                callbackContext.error("Init failed: " + e.getMessage());
            }
        });
    }

    private void startEnroll(final String userId, final CallbackContext callbackContext) {
        if (!sensorOpen) {
            callbackContext.error("Sensor not initialized — call init() first");
            return;
        }
        captureMode = "enroll";
        enrollUserId = userId;
        enrollIdx = 0;
        activeCallback = callbackContext;

        ensureCaptureRunning();

        // Notify the caller that enrollment has started
        sendKeepAlive(buildProgress(0, ENROLL_COUNT,
                "Place the same finger " + ENROLL_COUNT + " times"));
    }

    private void startIdentify(final CallbackContext callbackContext) {
        if (!sensorOpen) {
            callbackContext.error("Sensor not initialized — call init() first");
            return;
        }
        captureMode = "identify";
        activeCallback = callbackContext;

        ensureCaptureRunning();
    }

    private void stopCapture(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                stopCapturingInternal();
                callbackContext.success("Capture stopped");
            } catch (FingerprintException e) {
                callbackContext.error("Stop capture failed: " + e.getMessage());
            }
        });
    }

    private void loadTemplates(final JSONArray templates, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                int loaded = 0;
                for (int i = 0; i < templates.length(); i++) {
                    JSONObject tpl = templates.getJSONObject(i);
                    String userId = tpl.getString("id");
                    String base64Template = tpl.optString("template", "");
                    if (!base64Template.isEmpty() && !base64Template.equals("null")) {
                        byte[] raw = Base64.decode(base64Template, Base64.DEFAULT);
                        ZKFingerService.save(raw, templateKey(userId));
                        loaded++;
                    }
                }
                callbackContext.success("Loaded " + loaded + " templates");
            } catch (JSONException e) {
                callbackContext.error("loadTemplates JSON error: " + e.getMessage());
            }
        });
    }

    private void deleteTemplate(final String userId, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            ZKFingerService.del(templateKey(userId));
            callbackContext.success("Template deleted for user " + userId);
        });
    }

    private void clearTemplates(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            ZKFingerService.clear();
            callbackContext.success("All templates cleared");
        });
    }

    private void isConnected(final CallbackContext callbackContext) {
        UsbManager usbManager = (UsbManager)
                cordova.getActivity().getSystemService(Context.USB_SERVICE);
        callbackContext.success(findDevice(usbManager) != null ? 1 : 0);
    }

    // -------------------------------------------------------------------------
    // Sensor lifecycle
    // -------------------------------------------------------------------------

    private void openSensor() throws FingerprintException {
        Map<String, Object> params = new HashMap<>();
        params.put(ParameterHelper.PARAM_KEY_VID, VID);
        params.put(ParameterHelper.PARAM_KEY_PID, PID);

        fingerprintSensor = FingprintFactory.createFingerprintSensor(
                cordova.getActivity(), TransportType.USB, params);
        fingerprintSensor.open(0);
        sensorOpen = true;
        Log.i(TAG, "Fingerprint sensor opened");
    }

    private void ensureCaptureRunning() {
        if (captureRunning) return;
        try {
            fingerprintSensor.setFingerprintCaptureListener(0, captureListener);
            fingerprintSensor.startCapture(0);
            captureRunning = true;
            Log.i(TAG, "Capture started in mode: " + captureMode);
        } catch (FingerprintException e) {
            Log.e(TAG, "startCapture failed: " + e.getMessage());
            if (activeCallback != null) {
                activeCallback.error("Start capture failed: " + e.getMessage());
            }
        }
    }

    private void stopCapturingInternal() throws FingerprintException {
        if (captureRunning && fingerprintSensor != null) {
            fingerprintSensor.stopCapture(0);
            captureRunning = false;
            Log.i(TAG, "Capture stopped");
        }
    }

    // -------------------------------------------------------------------------
    // Fingerprint capture listener
    // -------------------------------------------------------------------------

    private final FingerprintCaptureListener captureListener = new FingerprintCaptureListener() {

        @Override
        public void captureOK(byte[] fpImage) {
            // Raw image available as soon as a finger touches the sensor — pushed to
            // JS immediately (ahead of extractOK's template match) so the UI can show
            // a live preview while the user's finger is still on the reader.
            if (activeCallback == null || fingerprintSensor == null) return;
            try {
                int width = fingerprintSensor.getImageWidth();
                int height = fingerprintSensor.getImageHeight();
                String base64 = imageToBase64(fpImage, width, height);
                if (base64 == null) return;

                JSONObject payload = new JSONObject();
                payload.put("image", "data:image/png;base64," + base64);
                payload.put("width", width);
                payload.put("height", height);
                sendKeepAlive(payload);
            } catch (Exception e) {
                Log.e(TAG, "captureOK image encode failed: " + e.getMessage());
            }
        }

        @Override
        public void captureError(FingerprintException e) {
            // The SDK raises this for transient misses too (e.g. "Get Image failed" when no
            // finger is on the sensor or the press was partial). Capture keeps polling, so
            // don't tear down the callback — the next extractOK will complete it.
            Log.w(TAG, "captureError (non-fatal): " + e.getMessage());
        }

        @Override
        public void extractError(int errorCode) {
            Log.e(TAG, "extractError: " + errorCode);
            if (activeCallback != null) {
                PluginResult pr = new PluginResult(PluginResult.Status.ERROR,
                        "Extract error (code " + errorCode + ")");
                pr.setKeepCallback(true);
                activeCallback.sendPluginResult(pr);
            }
        }

        @Override
        public void extractOK(byte[] fpTemplate) {
            if (activeCallback == null) return;

            if ("identify".equals(captureMode)) {
                handleIdentify(fpTemplate);
            } else if ("enroll".equals(captureMode)) {
                handleEnroll(fpTemplate);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Identify
    // -------------------------------------------------------------------------

    private void handleIdentify(byte[] fpTemplate) {
        byte[] bufids = new byte[256];
        int ret = ZKFingerService.identify(fpTemplate, bufids, 55, 1);

        try {
            JSONObject result = new JSONObject();
            if (ret > 0) {
                String[] parts = new String(bufids).split("\t");
                String userId = parseUserIdFromMatch(parts[0]);
                int score = 0;
                if (parts.length > 1) {
                    try { score = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
                }
                result.put("identified", true);
                result.put("userId", userId);
                result.put("score", score);
            } else {
                result.put("identified", false);
            }
            sendKeepAlive(result);
        } catch (JSONException e) {
            Log.e(TAG, "handleIdentify JSON error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Enroll
    // -------------------------------------------------------------------------

    private void handleEnroll(byte[] fpTemplate) {
        // Reject if this finger is already enrolled under a different user
        byte[] bufids = new byte[256];
        int dupCheck = ZKFingerService.identify(fpTemplate, bufids, 55, 1);
        if (dupCheck > 0) {
            String[] parts = new String(bufids).split("\t");
            String existingUserId = parseUserIdFromMatch(parts[0]);
            try {
                JSONObject err = new JSONObject();
                err.put("error", "ALREADY_ENROLLED");
                err.put("existingUserId", existingUserId);
                PluginResult pr = new PluginResult(PluginResult.Status.ERROR, err);
                pr.setKeepCallback(true);
                activeCallback.sendPluginResult(pr);
            } catch (JSONException e) {
                activeCallback.error("Finger already enrolled by user: " + existingUserId);
            }
            return;
        }

        // Ensure same finger each time
        if (enrollIdx > 0 &&
                ZKFingerService.verify(enrollTemplates[enrollIdx - 1], fpTemplate) <= 0) {
            sendKeepAlive(buildProgress(enrollIdx, ENROLL_COUNT,
                    "Different finger detected — please use the same finger"));
            return;
        }

        // Store this scan
        System.arraycopy(fpTemplate, 0, enrollTemplates[enrollIdx], 0, TEMPLATE_SIZE);
        enrollIdx++;

        if (enrollIdx < ENROLL_COUNT) {
            int remaining = ENROLL_COUNT - enrollIdx;
            sendKeepAlive(buildProgress(enrollIdx, ENROLL_COUNT,
                    "Good — " + remaining + " scan" + (remaining == 1 ? "" : "s") + " remaining"));
            return;
        }

        // All 3 scans collected — merge into final template
        byte[] finalTemplate = new byte[TEMPLATE_SIZE];
        int mergeRet = ZKFingerService.merge(
                enrollTemplates[0], enrollTemplates[1], enrollTemplates[2], finalTemplate);

        if (mergeRet <= 0) {
            enrollIdx = 0;
            PluginResult pr = new PluginResult(PluginResult.Status.ERROR,
                    "Template merge failed — please try again");
            pr.setKeepCallback(true);
            activeCallback.sendPluginResult(pr);
            return;
        }

        // Save in-memory and return template to JavaScript
        ZKFingerService.save(finalTemplate, templateKey(enrollUserId));
        String base64 = Base64.encodeToString(finalTemplate, Base64.DEFAULT);

        try {
            JSONObject result = new JSONObject();
            result.put("enrolled", true);
            result.put("userId", enrollUserId);
            result.put("template", base64);

            // Last result — do not keep callback
            PluginResult pr = new PluginResult(PluginResult.Status.OK, result);
            pr.setKeepCallback(false);
            activeCallback.sendPluginResult(pr);
            activeCallback = null;
        } catch (JSONException e) {
            activeCallback.error("Enrollment complete but JSON error: " + e.getMessage());
            activeCallback = null;
        }

        // Reset enroll state
        captureMode = "identify";
        enrollIdx = 0;
        enrollUserId = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendKeepAlive(JSONObject payload) {
        if (activeCallback == null) return;
        PluginResult pr = new PluginResult(PluginResult.Status.OK, payload);
        pr.setKeepCallback(true);
        activeCallback.sendPluginResult(pr);
    }

    private JSONObject buildProgress(int step, int total, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("progress", true);
            obj.put("step", step);
            obj.put("total", total);
            obj.put("message", message);
        } catch (JSONException ignored) {}
        return obj;
    }

    /**
     * Converts the sensor's raw 8-bit grayscale frame (one byte per pixel) into a
     * base64-encoded PNG suitable for an <img> src on the JS side.
     */
    private String imageToBase64(byte[] grayscale, int width, int height) {
        if (grayscale == null || width <= 0 || height <= 0 || grayscale.length < width * height) {
            return null;
        }
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int gray = grayscale[i] & 0xFF;
            pixels[i] = Color.argb(255, gray, gray, gray);
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        bitmap.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    /** Templates are stored in ZKFingerService with a "test" prefix, matching the original app. */
    private String templateKey(String userId) {
        return "test" + userId;
    }

    /**
     * Mirrors the reference app's parseUserIdFromMatch: strips the "test" storage prefix,
     * then strips any "_<suffix>" (e.g. a per-finger template index) a caller may have
     * appended to the id passed into loadTemplates()/startEnroll().
     */
    private String parseUserIdFromMatch(String rawMatchField) {
        String rawId = rawMatchField.trim().substring(4);
        int suffixAt = rawId.indexOf('_');
        return suffixAt >= 0 ? rawId.substring(0, suffixAt) : rawId;
    }

    private void registerUsbReceiver() {
        if (!usbReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            cordova.getActivity().getApplicationContext()
                    .registerReceiver(usbReceiver, filter);
            usbReceiverRegistered = true;
        }
    }

    /** First attached USB device matching the ZKTeco reader's VID/PID, or null if none. */
    private UsbDevice findDevice(UsbManager usbManager) {
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == VID && device.getProductId() == PID) {
                return device;
            }
        }
        return null;
    }

    private void requestUsbPermission(UsbManager usbManager, UsbDevice device) {
        Context context = cordova.getActivity().getApplicationContext();
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, pendingIntent);
        Log.i(TAG, "USB permission requested");
    }

    // -------------------------------------------------------------------------
    // Lifecycle cleanup
    // -------------------------------------------------------------------------

    @Override
    public void onDestroy() {
        try {
            if (captureRunning && fingerprintSensor != null) {
                fingerprintSensor.stopCapture(0);
                captureRunning = false;
            }
            if (sensorOpen && fingerprintSensor != null) {
                fingerprintSensor.close(0);
                sensorOpen = false;
            }
        } catch (FingerprintException e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }

        if (usbReceiverRegistered) {
            try {
                cordova.getActivity().getApplicationContext()
                        .unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException ignored) {}
            usbReceiverRegistered = false;
        }

        super.onDestroy();
    }
}
