package morph.platform

expect object RuntimePlatform {
    fun stderrLine(message: String)
    fun exit(code: Int): Nothing
}
