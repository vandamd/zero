<img src="example.png" alt="Zero Screenshots">

<p>A simple camera app for the Light Phone III.</p>

![GitHub Release](https://img.shields.io/github/v/release/vandamd/zero)

## Installation
The lastest .apk file is available in [releases](https://github.com/vandamd/zero/releases/latest).

I recommend using [Obtainium](https://github.com/ImranR98/Obtainium) and adding the repository's URL to receive updates.

## Features
- Uses the Light Phone III's camera button
- Capture images without any processing
- RAW or JPGs
- Manual and Auto mode

## Key Mapper
If you use Key Mapper, I recommend setting a trigger to open Zero when the camera button is pressed. Don't forget to add a constraint so it only works when Zero is not in the foreground!

## Greyscale Toggle

Zero can automatically disable greyscale while the app is open and restore it when you leave. 

This requires granting the app special permission via ADB:

```bash
adb shell pm grant com.vandam.zero android.permission.WRITE_SECURE_SETTINGS
```

## Support
Zero is developed and maintained in my free time.

If you find it useful, please [consider sponsoring](https://github.com/sponsors/vandamd)! :)
