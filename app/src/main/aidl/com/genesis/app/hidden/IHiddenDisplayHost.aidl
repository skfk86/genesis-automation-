// Privileged side of the hidden display. Runs with shell identity, started by Shizuku.
// Every method returns plain values or a JSON string, so the report can record exactly what happened.
package com.genesis.app.hidden;

interface IHiddenDisplayHost {
    // Called by Shizuku when it removes the service. Fixed transaction code required by Shizuku.
    void destroy() = 16777114;

    // Creates the hidden display. Returns JSON: displayId, flagSet, flags, imePolicy, attempts.
    String createDisplay(int width, int height, int dpi) = 1;

    // Starts an activity (package/class) on the hidden display. With newInstance, a separate task is
    // created there, so an instance already open on the main screen is left where it is.
    // Returns JSON: ok, how, output.
    String launch(int displayId, String component, boolean newInstance) = 2;

    // Injected input on the hidden display only. Returns JSON: ok, error.
    String tap(int displayId, int x, int y) = 3;
    String swipe(int displayId, int x1, int y1, int x2, int y2, long durationMs) = 4;
    String key(int displayId, int keyCode) = 5;

    // Removes the display. Its activities are destroyed, not moved to the main screen.
    void releaseDisplay() = 6;

    // JSON: uid, pid, rssKb, cpuMs, frames, displayId, sdk, lastError.
    String stats() = 7;

    // JPEG of the last frame drawn on the hidden display, scaled to maxWidth. Stays on the phone.
    byte[] thumbnail(int maxWidth) = 8;
}
