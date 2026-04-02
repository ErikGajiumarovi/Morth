package morph.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.system.exitProcess
import platform.posix.fprintf
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
actual object RuntimePlatform {
    actual fun stderrLine(message: String) {
        fprintf(stderr, "%s\n", message)
    }

    actual fun exit(code: Int): Nothing = exitProcess(code)
}
