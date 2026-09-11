// Guardian-grade call capture: these methods run INSIDE the Shizuku
// shell process, which holds CAPTURE_AUDIO_OUTPUT — the only
// non-root way on Android 11+ to record both sides of a call.
package com.neverhide.empire.calls;

interface IShizukuCapture {
    // Begin capturing voice-call audio to the given wav path. Returns 0 on start, -1 on error.
    int start(String wavPath);
    // Stop and finalize the wav. Returns captured seconds, or -1.
    int stop();
    // Quick check the shell service is alive and can record.
    boolean ping();
}
