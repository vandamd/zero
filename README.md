# Zero

A simple camera app for the Light Phone III.

<img src="/example.png" width="300">

## Features
- Capture images without any processing
- RAW or JPGs
- Manual and Auto mode

## Greyscale Toggle

Zero can automatically disable greyscale while the app is open and restore it when you leave.

```bash
adb shell pm grant com.vandam.zero android.permission.WRITE_SECURE_SETTINGS
```

Once granted, the app will automatically:
- Disable grayscale when you open the app
- Restore grayscale when you leave or close the app
