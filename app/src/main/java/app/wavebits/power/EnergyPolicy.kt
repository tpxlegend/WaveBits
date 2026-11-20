package app.wavebits.power

/** Playback transport and listening events are independent of this visual policy. */
data class EnergyPolicy(
    val saverEnabled: Boolean = false,
    val oledBlack: Boolean = false,
    val allowCanvas: Boolean = true,
    val videoMaxHeight: Int = 1080,
    val allowBackgroundLookups: Boolean = true,
    val disableHeavyAnimations: Boolean = false,
    val progressFps: Int = 60,
    /** 0 = no preference: the LTPO screen already drops its rate when nothing moves. */
    val displayRefreshCap: Float = 0f,
) {
    companion object {
        /**
         * App Battery Saver takes effect when enabled and the device is not charging.
         * While plugged in, background lookups, video quality, and animations restore automatically.
         */
        fun resolve(
            manualSaver: Boolean,
            blackPreference: Boolean,
            canvasEnabled: Boolean = true,
            isCharging: Boolean = false,
        ): EnergyPolicy {
            val saver = manualSaver && !isCharging
            return EnergyPolicy(
                saverEnabled = saver,
                oledBlack = blackPreference,
                allowCanvas = !saver && canvasEnabled,
                videoMaxHeight = if (saver) 480 else 1080,
                allowBackgroundLookups = !saver,
                disableHeavyAnimations = saver,
                progressFps = if (saver) 20 else 60,
                displayRefreshCap = if (saver) 60f else 0f,
            )
        }
    }
}

data class EnergyState(
    val manualSaver: Boolean = false,
    /** AMOLED appearance stays user-controlled while saver limits visual work. */
    val blackPreference: Boolean = false,
    /** The user's Canvas switch (Settings), independent of the saver. */
    val canvasEnabled: Boolean = true,
    /** Whether external power / USB / wireless charging is currently connected. */
    val isCharging: Boolean = false,
) {
    val policy: EnergyPolicy get() = EnergyPolicy.resolve(manualSaver, blackPreference, canvasEnabled, isCharging)
}
