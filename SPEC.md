# Zero - A Minimal RAW Camera for Light Phone III

## Project Overview

Zero is a minimalist camera application for the Light Phone III that captures unprocessed RAW images directly from the sensor, inspired by Halide's Process Zero philosophy. The app prioritizes photographer control and authenticity over computational enhancement.

## Philosophy

**Core Principle**: Capture pure sensor data with minimal processing, no AI, no multi-frame merging, no computational photography tricks.

The goal is to produce images that are:
- Authentic and unprocessed
- Grain and imperfections preserved
- User-controlled rather than algorithm-enhanced
- Similar to film photography aesthetics
- Suitable for manual post-processing

Based on [Halide's Process Zero](https://www.lux.camera/process-zero-manual/), which captures "a single photo using raw sensor data" with "very minimal" processing rather than the multi-image merging and AI enhancement of modern smartphone photography.

## Light Phone III Hardware Specifications

From the [official specifications](https://www.thelightphone.com/light-phone-iii-specifications):

- **Rear Sensor**: 50 MP
- **Default Output**: 12 MP (4000x3000px)
- **Shutter Button**: Dedicated two-step button (half-press to focus, full-press to capture)
- **Front Sensor**: 8 MP (not in scope for initial version)

## Technical Implementation

### Camera API Choice

**Options:**

1. **CameraX 1.5** (Recommended)
   - Higher-level API, simpler implementation
   - [DNG/RAW capture introduced November 2025](https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html)
   - Supports simultaneous JPEG + DNG output
   - Better lifecycle management
   - Actively maintained by Google

2. **Camera2 API** (Alternative)
   - Lower-level control
   - More complex implementation
   - Uses `DngCreator` class for RAW files
   - See [Camera2Basic sample](https://github.com/android/camera-samples/blob/main/Camera2Basic/README.md)

**Decision**: Start with CameraX 1.5 for simpler, more maintainable code. Fall back to Camera2 only if specific low-level control is needed.

### RAW Capture Details

**Format**: DNG (Digital Negative)
- Industry-standard RAW format
- Adobe-developed, widely supported
- Contains full sensor data without processing
- Metadata preserved (ISO, shutter speed, white balance, etc.)

**Resolution Strategy**:
- Capture at full 50 MP sensor resolution (if available via RAW)
- Light Phone III defaults to 12 MP output, but we want maximum sensor data
- Need to query `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` to determine available RAW resolutions

**Processing Pipeline**:
1. Capture single frame from sensor
2. Apply minimal demosaicing (Bayer pattern to RGB)
3. No noise reduction
4. No HDR merging
5. No AI enhancement
6. No sharpening beyond sensor capabilities
7. Save as DNG with embedded metadata

**Dual Output Option**:
- Save DNG (RAW) for editing
- Optionally save small JPEG/HEIC preview for quick viewing
- Store together like Halide does

### Features to AVOID

Following Process Zero philosophy, explicitly **do not implement**:
- Night mode / multi-frame exposure stacking
- HDR merging
- Portrait mode / bokeh effects
- AI scene detection
- Automatic noise reduction
- Computational zoom
- Beauty filters
- Any multi-frame processing

### Essential Features

**Minimal Controls** (inspired by Process Zero):
- **Focus** - tap anywhere on viewfinder, or hardware button half-press
- **Capture** - hardware button full-press (primary), or on-screen shutter (backup)

**Settings Menu** (accessed via bottom bar):
- Exposure compensation (±2 EV slider)
- ISO control (auto or manual) - keep under 1600 for best results
- Resolution (12MP / 50MP if available)
- Grid overlay (rule of thirds, on/off)
- Save format (DNG only, or DNG + JPEG preview)

**File Management**:
- Save to Pictures/Zero or DCIM/Zero
- Filename format: `ZERO_YYYYMMDD_HHMMSS.dng`
- Optional sidecar JPEG with same basename
- Exif metadata preservation

**UI Design**:
- **Entire screen is viewfinder** - no overlays, no info display
- Default orientation: portrait (right side up)
- Thin bar at right side (bottom in portrait) with minimal settings access
- Black background, white icons
- Match Light Phone III aesthetic (simple, functional)
- No post-capture editing - just capture and save

## Project Structure

```
zero/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/app/zero/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── CameraViewModel.kt
│   │   │   │   ├── camera/
│   │   │   │   │   ├── CameraController.kt
│   │   │   │   │   └── DngWriter.kt
│   │   │   │   └── ui/
│   │   │   │       ├── CameraScreen.kt
│   │   │   │       └── SettingsScreen.kt
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── SPEC.md
└── README.md
```

## Dependencies

```kotlin
// CameraX
implementation "androidx.camera:camera-core:1.4.0"
implementation "androidx.camera:camera-camera2:1.4.0"
implementation "androidx.camera:camera-lifecycle:1.4.0"
implementation "androidx.camera:camera-view:1.4.0"
implementation "androidx.camera:camera-extensions:1.4.0"

// Jetpack Compose (for UI)
implementation "androidx.compose.ui:ui:1.7.0"
implementation "androidx.compose.material3:material3:1.3.0"
implementation "androidx.compose.ui:ui-tooling-preview:1.7.0"
implementation "androidx.activity:activity-compose:1.9.0"

// Lifecycle
implementation "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0"
implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.0"

// Permissions
implementation "com.google.accompanist:accompanist-permissions:0.36.0"
```

## Permissions Required

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

## Implementation Phases

### Phase 1: Core Capture
- [ ] Set up project structure (Kotlin + CameraX)
- [ ] Implement CameraX initialization
- [ ] Configure RAW/DNG capture
- [ ] Save DNG files to storage
- [ ] Basic viewfinder preview
- [ ] Hardware shutter button integration (KEYCODE_CAMERA, KEYCODE_FOCUS)
- [ ] Request camera permissions

### Phase 2: Essential Controls
- [ ] Tap to focus
- [ ] Exposure compensation (swipe gesture or tap)
- [ ] ISO control (auto/manual toggle)
- [ ] Display current ISO and EV values
- [ ] Grid overlay toggle

### Phase 3: Settings & Polish
- [ ] Minimal settings screen
- [ ] Resolution selection (if 50MP available)
- [ ] DNG only vs DNG+JPEG option
- [ ] Storage location preference
- [ ] Test on Light Phone III hardware
- [ ] Performance optimization
- [ ] Error handling

## Technical Challenges

### Challenge 1: Full Sensor Resolution
- Light Phone III defaults to 12 MP output
- Need to verify if RAW captures can access full 50 MP
- May require Camera2 API for low-level sensor access

### Challenge 2: Hardware Button Integration
- Two-step shutter button (half-press focus, full-press capture)
- Need to map `KEYCODE_CAMERA` and `KEYCODE_FOCUS` events
- Test physical button behavior on actual hardware

### Challenge 3: Minimal Processing
- DNG files contain Bayer pattern data
- Need to ensure preview is accurate without full processing
- Balance between "minimal processing" and usable preview

### Challenge 4: Storage Management
- 50 MP DNG files are large (~50-80 MB each)
- Need efficient storage management
- Consider user warnings for low storage

## Design Mockup (Text Description)

**Main Camera Screen** (Portrait / Right Side Up):
```
┌─────────────────────┐
│                     │
│                     │
│                     │
│                     │
│                     │
│    [VIEWFINDER]     │  <- Full screen viewfinder
│   (entire screen)   │
│                     │
│      ┌─┬─┐          │  <- Optional grid overlay
│      ├─┼─┤          │
│      └─┴─┘          │
│                     │
│                     │
│                     │
│                     │
├─────────────────────┤
│   [⚙]       [•]     │  <- Thin bottom bar: Settings + Shutter
└─────────────────────┘
```

**Interaction**:
- Tap anywhere on viewfinder to focus
- Hardware button (primary): half-press focus, full-press capture
- On-screen shutter button (secondary/backup)
- Settings icon opens settings sheet/screen
- No info overlays on viewfinder
- Exposure/ISO adjustments in settings only

**Settings Screen/Sheet**:
- Exposure compensation (±2 EV slider)
- ISO (Auto / Manual slider)
- Resolution (12MP / 50MP if available)
- Save format (DNG only / DNG + JPEG preview)
- Grid overlay (on/off)
- Storage location
- About / License info

## Future Considerations

**Potential Enhancements** (only if needed):
- White balance presets (daylight, cloudy, tungsten, auto)
- Shutter speed control (for long exposures)
- Timer/self-timer
- Focus peaking visualization
- Black & white mode (sensor-level demosaic)

**Explicitly Out of Scope**:
- Post-capture editing or adjustment
- Filters or effects
- Video recording
- AI/ML features
- Multi-frame processing
- HDR, night mode, portrait mode
- Gallery or image browsing (use system gallery)
- Social sharing features

## References

- [Halide Process Zero Manual](https://www.lux.camera/process-zero-manual/)
- [Process Zero Announcement](https://www.lux.camera/introducing-process-zero-for-iphone/)
- [Light Phone III Specifications](https://www.thelightphone.com/light-phone-iii-specifications)
- [Android CameraX 1.5 Announcement](https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html)
- [Android Camera2 API Documentation](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [DngCreator API Reference](https://developer.android.com/reference/android/hardware/camera2/DngCreator)
- [Android Camera Samples](https://github.com/android/camera-samples/blob/main/Camera2Basic/README.md)

## Name Rationale

**Zero** represents:
- Zero AI processing
- Zero computational photography
- Zero multi-frame merging
- Zero compromises on authenticity
- Process Zero inspiration
- Clean slate for creative control

---

*Last updated: 2025-12-27*
