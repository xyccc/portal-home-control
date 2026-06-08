package com.yvonna.portalhome.model

/** Which backend a light is controlled through. */
enum class Source { HUE, TUYA }

/** A toggleable light, normalized across Hue and Tuya/Feit. */
data class LightDevice(
    val id: String,
    val name: String,
    val on: Boolean,
    val source: Source,
    /** Current brightness as a percentage (0-100), or null if the light isn't dimmable. */
    val brightness: Int? = null,
) {
    /** True when this light reports a dimming capability and can show a brightness slider. */
    val supportsBrightness: Boolean get() = brightness != null
}

/** A Nest doorbell/camera as reported by the SDM API. */
data class DoorbellDevice(
    /** Full SDM resource name, e.g. enterprises/<p>/devices/<d>. */
    val resourceName: String,
    val displayName: String,
    val type: String,
    val online: Boolean,
)
