package ai.lumen.assistant

import android.content.Context
import android.system.Os

/**
 * Embedded Node, one start per process.
 *
 * argv is exactly `node` and the script path. [start] applies every [env] entry
 * to the process environment with [Os.setenv] before Node starts. PhoneMode passes
 * `LUMEN_SETTINGS` as the path to settings.json; the script reads
 * `process.env.LUMEN_SETTINGS`. [ctx] stays on the signature for that caller.
 *
 * Returns false when this process already started Node. [running] is true until
 * Node returns. [exitCode] stays -1 until then.
 */
object NodeRuntime {
    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var exitCode: Int = -1
        private set

    private const val STACK_BYTES = 64L * 1024L * 1024L

    private var started = false
    private var libsLoaded = false

    @Synchronized
    fun start(@Suppress("UNUSED_PARAMETER") ctx: Context, script: String, env: Map<String, String>): Boolean {
        if (started) return false

        for ((key, value) in env) {
            Os.setenv(key, value, true)
        }

        if (!libsLoaded) {
            // libnode.so is linked against the NDK shared C++ runtime.
            System.loadLibrary("c++_shared")
            System.loadLibrary("node")
            System.loadLibrary("node-jni")
            libsLoaded = true
        }

        val args = arrayOf("node", script)
        started = true
        running = true
        Thread(
            null,
            Runnable {
                try {
                    exitCode = startNode(args)
                } finally {
                    running = false
                }
            },
            "node",
            STACK_BYTES,
        ).start()
        return true
    }

    @JvmStatic
    private external fun startNode(args: Array<String>): Int
}
