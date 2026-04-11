package com.vandam.zero.camera

enum class CaptureMode {
    PHOTO,
    VIDEO,
}

enum class VideoPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val defaultShutterSpeedNs: Long,
) {
    FHD24(
        label = "24",
        width = 1440,
        height = 1080,
        fps = 24,
        defaultShutterSpeedNs = 20_833_333L,
    ),
    FHD30(
        label = "30",
        width = 1440,
        height = 1080,
        fps = 30,
        defaultShutterSpeedNs = 16_666_666L,
    ),
    ;

    val frameDurationNs: Long
        get() = 1_000_000_000L / fps
}
