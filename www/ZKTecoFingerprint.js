var exec = require('cordova/exec');

var PLUGIN = 'ZKTecoFingerprint';

var ZKTecoFingerprint = {

    /**
     * Initialize and open the ZKTeco USB fingerprint sensor.
     * Must be called before any other method.
     *
     * @param {Function} success  Called with a status message string.
     * @param {Function} error    Called with an error message string.
     */
    init: function (success, error) {
        exec(success, error, PLUGIN, 'init', []);
    },

    /**
     * Start enrolling a fingerprint for the given user.
     * The user must press the same finger 3 times.
     *
     * The success callback fires multiple times:
     *   - Progress update: { progress: true, step: 1|2|3, total: 3, message: "..." }
     *   - Completion:      { enrolled: true, userId: "...", template: "<base64>" }
     *
     * The returned base64 template should be stored on your server and loaded
     * back via loadTemplates() for future identification.
     *
     * @param {string}   userId   Your application's user ID (string).
     * @param {Function} success  Called with progress/completion objects (see above).
     * @param {Function} error    Called with an error message string.
     */
    startEnroll: function (userId, success, error) {
        exec(success, error, PLUGIN, 'startEnroll', [userId]);
    },

    /**
     * Start continuous identification mode.
     * The success callback fires every time a finger is placed:
     *   - Match:    { identified: true,  userId: "...", score: <int> }
     *   - No match: { identified: false }
     *
     * Call stopCapture() to end identification.
     *
     * @param {Function} success  Called with identification result objects (see above).
     * @param {Function} error    Called with an error message string.
     */
    startIdentify: function (success, error) {
        exec(success, error, PLUGIN, 'startIdentify', []);
    },

    /**
     * Stop the active fingerprint capture session.
     *
     * @param {Function} success  Called on success.
     * @param {Function} error    Called with an error message string.
     */
    stopCapture: function (success, error) {
        exec(success, error, PLUGIN, 'stopCapture', []);
    },

    /**
     * Load an array of fingerprint templates into the sensor's in-memory database.
     * Must be called before startIdentify() so the sensor has templates to match against.
     *
     * @param {Array}    templates  Array of { id: "userId", template: "<base64>" } objects.
     * @param {Function} success    Called with a status message string.
     * @param {Function} error      Called with an error message string.
     *
     * @example
     * ZKTecoFingerprint.loadTemplates([
     *   { id: "42",  template: "AAEC..." },
     *   { id: "101", template: "BgcI..." }
     * ], onSuccess, onError);
     */
    loadTemplates: function (templates, success, error) {
        exec(success, error, PLUGIN, 'loadTemplates', [templates]);
    },

    /**
     * Delete a single user's fingerprint template from the in-memory database.
     *
     * @param {string}   userId   The user ID whose template should be removed.
     * @param {Function} success  Called on success.
     * @param {Function} error    Called with an error message string.
     */
    deleteTemplate: function (userId, success, error) {
        exec(success, error, PLUGIN, 'deleteTemplate', [userId]);
    },

    /**
     * Clear all fingerprint templates from the in-memory database.
     *
     * @param {Function} success  Called on success.
     * @param {Function} error    Called with an error message string.
     */
    clearTemplates: function (success, error) {
        exec(success, error, PLUGIN, 'clearTemplates', []);
    },

    /**
     * Check whether the ZKTeco USB reader is physically connected (VID 6997 / PID 292).
     *
     * @param {Function} success  Called with 1 (connected) or 0 (not connected).
     * @param {Function} error    Called with an error message string.
     */
    isConnected: function (success, error) {
        exec(success, error, PLUGIN, 'isConnected', []);
    }
};

module.exports = ZKTecoFingerprint;
