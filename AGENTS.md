# Zero Camera

Zero is a minimal camera app for the Light Phone III. It aims to take photos with as little processing as possible.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose/Material 3, Camera2 API, Gradle 9.2.1 (Kotlin DSL), Min SDK 29, Target SDK 35

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minified)
./gradlew clean assembleDebug    # Clean build
./gradlew build                  # Full build with tests
```

## Lint & Code Style

This project uses **ktlint 1.8.0** for code formatting. Always run ktlint before committing.

```bash
./gradlew ktlintCheck    # Check for code style violations
./gradlew ktlintFormat   # Auto-fix formatting issues
./gradlew lint           # Run Android lint
./gradlew lintDebug
```

**ktlint Configuration** (see `.editorconfig`):
- Composable functions MAY use PascalCase (standard Compose convention)
- Wildcard imports are allowed for Android/Compose packages

## Project Structure

```
app/src/main/java/com/vandam/zero/
├── MainActivity.kt           # Entry point, key event handling
├── CameraViewModel.kt        # UI state management, settings persistence
├── camera/
│   ├── CameraController.kt   # Camera2 API implementation
│   └── GrayscaleConverter.kt # BT.709 grayscale conversion
└── ui/
    ├── CameraScreen.kt       # Main screen composable
    ├── components/
    │   ├── CameraSliders.kt  # Exposure/ISO/Shutter sliders
    │   ├── Crosshair.kt      # Focus indicator
    │   ├── GridOverlay.kt    # Rule of thirds grid
    │   └── ModifierExtensions.kt
    └── theme/
        └── CameraTheme.kt    # Typography, colors, dimensions
```

## Code Style Guidelines

### Naming Conventions

| Type | Convention | Example |
|------|------------|---------|
| Composable functions | PascalCase | `CameraScreen()`, `ExposureSlider()` |
| Regular functions | camelCase | `takePhoto()`, `formatShutterSpeed()` |
| Classes/Objects | PascalCase | `CameraController`, `CameraColors` |
| Constants | SCREAMING_SNAKE_CASE | `OUTPUT_FORMAT_JPEG`, `R_WEIGHT` |
| StateFlow properties | camelCase with underscore prefix for private | `_isCapturing` / `isCapturing` |

### Compose Best Practices

1. **Composable naming**: Use PascalCase for `@Composable` functions that emit UI
2. **State hoisting**: Keep state in ViewModel, pass values and callbacks to composables
3. **Modifier parameter**: Always accept `modifier: Modifier = Modifier` as a parameter
4. **Parameter order**: Required params first, then `modifier`, then optional params, then trailing lambda
5. **Single responsibility**: Each composable should emit 0 or 1 layout node

```kotlin
@Composable
fun MyButton(
    text: String,                    // Required
    onClick: () -> Unit,             // Required callback
    modifier: Modifier = Modifier,   // Optional, starts with modifier
    enabled: Boolean = true,         // Other optional params
) { ... }
```

### State Management

- Use `StateFlow` for observable state in ViewModel
- Collect state with `collectAsState()` in composables
- Group related state into data classes when practical
- Private mutable state with public read-only exposure:

```kotlin
private val _isCapturing = MutableStateFlow(false)
val isCapturing: StateFlow<Boolean> = _isCapturing
```

### Import Organisation

Imports are ordered lexicographically by ktlint. Wildcard imports are allowed for Android/Compose packages.

### Error Handling

- Use nullable types with safe calls (`?.`) for optional operations
- Log errors with Android Log class: `Log.e("Tag", "message")`
- Camera operations should handle `CameraAccessException`
- Never crash on permission denial - show appropriate UI

### Documentation

- Use KDoc (`/** */`) for public APIs and complex functions
- Document non-obvious parameters and return values

## Theme Constants

Use centralized constants from `ui/theme/CameraTheme.kt`:

```kotlin
CameraColors.background      // Color.Black
CameraColors.onSurface       // Color.White
CameraColors.onSurfaceVariant // Color.White.copy(alpha = 0.7f)
CameraDimens.toolbarWidth    // 60.dp
CameraDimens.crosshairSize   // 80.dp
CameraTypography.toolbarText // 32.sp, ExtraBold
CameraTiming.TOAST_DISPLAY_DURATION_MS  // 2000L
```
