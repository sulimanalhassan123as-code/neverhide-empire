// Cyber Cleaner freezer — runs INSIDE the Shizuku shell process, where
// Runtime.exec("pm suspend ...") executes with shell (ADB) privileges.
// This is the same UserService pattern proven by the v2.4.0 call capture.
package com.neverhide.empire.cleaner;

interface IShizukuShell {
    // Run a shell command as the Shizuku shell user.
    // Returns "exit:<code>\n<stdout+stderr>" — caller parses the exit code.
    String exec(String cmd);
}
