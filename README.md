# cordova-plugin-zkteco-fingerprint

Cordova plugin for ZKTeco USB fingerprint readers using the ZKTeco SILK ID SDK. Supports enrolment and 1-to-N identification on Android via USB OTG.

**Platform support:** Android only (cordova-android ≥ 10.0.0)

---

## Supported hardware

| Device | VID | PID |
|--------|-----|-----|
| ZKTeco USB fingerprint reader (SLK20R / compatible) | 6997 (0x1B55) | 292 (0x0124) |

The device filter is declared in `res/xml/zkteco_device_filter.xml` and registered in the Android manifest so the app receives `USB_DEVICE_ATTACHED` intents automatically.

---

## Installation

```bash
cordova plugin add cordova-plugin-zkteco-fingerprint
```

Or from a local path:

```bash
cordova plugin add /path/to/cordova-plugin-zkteco-fingerprint
```

### Android requirements

- `cordova-android` 10.0.0 or later
- A device with USB OTG host support
- The `android.hardware.usb.host` feature is declared as required in the manifest — devices without USB host capability will not be offered this app in the Play Store.

---

## Permissions

The plugin automatically adds the following to `AndroidManifest.xml`:

| Permission / Feature | Purpose |
|----------------------|---------|
| `android.permission.INTERNET` | Network access for your app's server calls |
| `android.hardware.usb.host` | USB host mode (required) |
| `USB_DEVICE_ATTACHED` intent filter | Launches the app when the reader is plugged in |

A runtime USB-permission dialog is shown to the user the first time the reader is detected. Subsequent launches skip this if permission was already granted.

---

## API reference

The global object `ZKTecoFingerprint` is available after `deviceready`.

All methods follow the Node-style `(success, error)` callback pattern. The `error` callback always receives a plain string describing the failure.

---

### `init(success, error)`

Initialises the USB fingerprint sensor. **Must be called before any other method.**

Registers the USB broadcast receiver, requests USB permission if not already granted, and opens the sensor.

```js
ZKTecoFingerprint.init(
    function (msg) { console.log(msg); },   // "Fingerprint sensor initialized"
    function (err) { console.error(err); }
);
```

---

### `isConnected(success, error)`

Checks whether the ZKTeco reader is physically connected.

```js
ZKTecoFingerprint.isConnected(
    function (status) {
        if (status === 1) {
            console.log('Reader connected');
        } else {
            console.log('Reader not found');
        }
    },
    function (err) { console.error(err); }
);
```

| `success` value | Meaning |
|-----------------|---------|
| `1` | Reader is present (VID 6997 / PID 292 found) |
| `0` | Reader is not connected |

---

### `loadTemplates(templates, success, error)`

Loads previously enrolled fingerprint templates into the sensor's in-memory database. **Call this before `startIdentify()`** so the SDK has templates to match against.

Templates are normally fetched from your server and were originally produced by `startEnroll()`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `templates` | `Array` | Array of `{ id: string, template: string }` objects |
| `id` | `string` | Your application's user identifier |
| `template` | `string` | Base64-encoded template string returned by `startEnroll()` |

```js
ZKTecoFingerprint.loadTemplates(
    [
        { id: '42',  template: 'AAEC...' },
        { id: '101', template: 'BgcI...' }
    ],
    function (msg) { console.log(msg); },   // "Loaded 2 templates"
    function (err) { console.error(err); }
);
```

Entries with a missing or `null` template are silently skipped.

---

### `startEnroll(userId, success, error)`

Starts an enrolment session for the given user. The user must press the **same finger 3 times**.

The `success` callback fires **multiple times** (keep-alive):

**Progress update** (fires after each of the first two successful scans, and once at the start):

```json
{
    "progress": true,
    "step": 0,
    "total": 3,
    "message": "Place the same finger 3 times"
}
```

`step` increments from `0` to `2` as scans are accepted.

**Completion** (fires once after the third successful scan):

```json
{
    "enrolled": true,
    "userId": "42",
    "template": "<base64-encoded template string>"
}
```

The `template` value should be persisted on your server and passed back to `loadTemplates()` in future sessions.

**Duplicate-finger error** (fires if the scanned finger is already enrolled under a different user):

The `error` callback receives a JSON object:

```json
{
    "error": "ALREADY_ENROLLED",
    "existingUserId": "99"
}
```

```js
ZKTecoFingerprint.startEnroll(
    '42',
    function (result) {
        if (result.progress) {
            updateUI('Step ' + result.step + '/' + result.total + ': ' + result.message);
        } else if (result.enrolled) {
            saveToServer(result.userId, result.template);
        }
    },
    function (err) {
        var parsed = (typeof err === 'string') ? err : JSON.stringify(err);
        console.error('Enrol error:', parsed);
    }
);
```

The enrolment session ends automatically after successful completion. You do not need to call `stopCapture()` afterward (though it is safe to do so).

---

### `startIdentify(success, error)`

Starts continuous identification mode. The `success` callback fires **every time a finger is placed** on the reader.

**Match:**

```json
{
    "identified": true,
    "userId": "42",
    "score": 78
}
```

**No match:**

```json
{
    "identified": false
}
```

Call `stopCapture()` to end the session.

```js
ZKTecoFingerprint.startIdentify(
    function (result) {
        if (result.identified) {
            console.log('User ' + result.userId + ' identified (score: ' + result.score + ')');
        } else {
            console.log('Finger not recognised');
        }
    },
    function (err) { console.error('Identify error:', err); }
);
```

> The identification threshold is fixed at 55. Scores above this value indicate a match; the `score` field reflects the similarity returned by the SDK.

---

### `stopCapture(success, error)`

Stops the active capture session (enrolment or identification).

```js
ZKTecoFingerprint.stopCapture(
    function (msg) { console.log(msg); },   // "Capture stopped"
    function (err) { console.error(err); }
);
```

Safe to call even if no capture is in progress.

---

### `deleteTemplate(userId, success, error)`

Removes a single user's template from the in-memory database.

```js
ZKTecoFingerprint.deleteTemplate(
    '42',
    function (msg) { console.log(msg); },
    function (err) { console.error(err); }
);
```

This does **not** affect your server-side storage — only the in-memory copy loaded by `loadTemplates()`.

---

### `clearTemplates(success, error)`

Removes all templates from the in-memory database.

```js
ZKTecoFingerprint.clearTemplates(
    function (msg) { console.log(msg); },
    function (err) { console.error(err); }
);
```

---

## Typical workflow

```
deviceready
    └─ isConnected()          ← confirm reader is plugged in
         └─ init()            ← open the sensor
              ├─ loadTemplates()   ← load enrolled templates from your server
              │
              ├─ startIdentify()  ← continuous identification loop
              │       └─ (on match) look up user, grant access
              │       └─ stopCapture() when done
              │
              └─ startEnroll()    ← one-off enrolment for a new user
                      └─ (on enrolled) save template.template to your server
```

### Full example

```js
document.addEventListener('deviceready', function () {

    ZKTecoFingerprint.isConnected(function (connected) {
        if (!connected) {
            alert('Please plug in the fingerprint reader.');
            return;
        }

        ZKTecoFingerprint.init(function () {

            // Load templates from your API
            fetchTemplatesFromServer().then(function (templates) {
                ZKTecoFingerprint.loadTemplates(templates, function () {

                    ZKTecoFingerprint.startIdentify(function (result) {
                        if (result.identified) {
                            grantAccess(result.userId);
                        }
                    }, console.error);

                }, console.error);
            });

        }, console.error);
    }, console.error);

});
```

---

## Enrolling a new user

```js
ZKTecoFingerprint.init(function () {

    ZKTecoFingerprint.startEnroll('user-123', function (result) {

        if (result.progress) {
            showMessage(result.message);   // guide the user
            return;
        }

        if (result.enrolled) {
            // Persist the template on your server
            api.post('/fingerprints', {
                userId: result.userId,
                template: result.template
            });
        }

    }, function (err) {
        // err may be a JSON string for ALREADY_ENROLLED
        try {
            var obj = JSON.parse(err);
            if (obj.error === 'ALREADY_ENROLLED') {
                alert('This finger is already registered to user ' + obj.existingUserId);
            }
        } catch (_) {
            console.error(err);
        }
    });

}, console.error);
```

---

## Error handling

| Scenario | Callback | Value |
|----------|----------|-------|
| Sensor not found / failed to open | `error` | String message with SDK error code |
| `startEnroll` / `startIdentify` called before `init` | `error` | `"Sensor not initialized — call init() first"` |
| Duplicate finger detected during enrolment | `error` | JSON string `{ "error": "ALREADY_ENROLLED", "existingUserId": "..." }` |
| Different finger used between enrolment scans | `success` | Progress object with a warning message — scan is not counted |
| Template merge failed after 3 scans | `error` (keep-alive) | `"Template merge failed — please try again"` |
| SDK extract error | `error` (keep-alive) | `"Extract error (code N)"` |

---

## Notes and limitations

- **Android only.** There is no iOS support.
- **In-memory templates.** `loadTemplates()` stores templates in the ZKFingerService in-memory database. Templates are lost when the activity is destroyed. Re-load them from your server on each app start.
- **Template size.** Each template is 2048 bytes (4 KB base64-encoded).
- **Enrolment scan count.** Fixed at 3 scans. The plugin verifies that each successive scan matches the previous one before accepting it.
- **Identification threshold.** Fixed at 55. Adjust the source if your environment requires a different sensitivity.
- **Single active callback.** Only one `startEnroll` or `startIdentify` session can be active at a time. Starting a new session while one is active will replace the previous callback without stopping the capture hardware.
- **USB permission.** The OS shows a one-time permission dialog per device. If the user denies it, `init()` will succeed but `startIdentify` / `startEnroll` will fail with a capture error. Handle this by calling `isConnected()` and surfacing a message to the user.

---

## Project structure

```
cordova-plugin-zkteco-fingerprint/
├── plugin.xml                          Plugin manifest
├── package.json
├── www/
│   └── ZKTecoFingerprint.js            JavaScript API
└── src/android/
    ├── ZKTecoFingerprintPlugin.java    Cordova plugin implementation
    ├── zkteco-fingerprint.gradle       Gradle config (jniLibs + JARs)
    ├── libs/
    │   ├── zkandroidfpreader.jar       ZKTeco fingerprint reader SDK
    │   ├── zkandroidcore.jar           ZKTeco core SDK
    │   ├── musicg-1.4.2.0.jar          Audio processing dependency
    │   ├── armeabi-v7a/
    │   │   ├── libzksilkid.so
    │   │   └── libzkfinger10.so
    │   └── arm64-v8a/
    │       ├── libzksilkid.so
    │       └── libzkfinger10.so
    └── res/xml/
        └── zkteco_device_filter.xml    USB host device filter (VID/PID)
```

---

## License

MIT — see [LICENSE](LICENSE).

Author: [ordev.io](https://ordev.io)
