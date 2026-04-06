# Build Instructions

## Prerequisites
- JDK 17 (`openjdk-17-jdk-headless`)
- Android SDK: platform 34, build-tools 34.0.0

## Setup
```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
```

## Build
```bash
./gradlew assembleDebug       # Debug APK (no minification)
./gradlew assembleRelease     # Release APK (ProGuard shrunk, ~5MB)
```

Output: `app/build/outputs/apk/`

## Release APK Size
- Debug: ~9MB (no minification)
- Release: ~5MB (ProGuard + resource shrinking)
