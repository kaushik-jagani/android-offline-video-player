# Android Offline Video Player

A feature-rich, fully offline video player for Android built with **Kotlin**, **Media3 ExoPlayer**, and **Room Database**. Designed with a modern dark UI inspired by MX Player.

## Features

### Core Playback
- **Offline-first** — plays local video files with no internet required
- **Auto-resume** — every video remembers where you left off; fully watched videos restart from the beginning
- **Playlist support** — next/previous navigation across folder videos
- **Variable speed** — 0.25x to 2.0x via Material Slider bottom sheet
- **Loop mode** — single-video repeat toggle

### Gesture Controls
- **Horizontal swipe** — seek forward/backward with frame thumbnail preview
- **Left vertical swipe** — brightness adjustment with on-screen indicator
- **Right vertical swipe** — volume adjustment with on-screen indicator
- **Double-tap** — play/pause
- **Pinch-to-zoom** — fit / fill / crop aspect ratio modes

### Player UI
- Horizontal scrollable toolbar with quick-access icons:
  A-B Repeat · Equalizer · Speed · Screenshot · Rotation · Night Mode · Loop · Mute · Sleep Timer
- Seek preview overlay with timestamp, delta, and video frame thumbnail
- Screen lock to prevent accidental touches
- Auto-rotating orientation based on video dimensions
- Remaining time display

### Library Management
- Automatic MediaStore scanning of all device videos
- Folder-based browsing with video count per folder
- Video metadata display (title, duration, file size)
- Resume positions preserved across app rescans

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 1.9.22 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Video Engine | AndroidX Media3 ExoPlayer 1.2.1 |
| Database | Room 2.6.1 |
| Image Loading | Glide 4.16.0 |
| UI | Material Design 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Build | Gradle 8.5 + AGP 8.2.2 + KSP |

## Project Structure

```
app/src/main/java/com/example/videoplayer/
├── data/
│   ├── database/       # Room Database, DAO, migrations
│   ├── model/          # VideoItem, VideoFolder entities
│   └── repository/     # VideoRepository (MediaStore + Room)
├── player/
│   └── ExoPlayerManager.kt    # ExoPlayer wrapper
├── ui/
│   ├── main/           # MainActivity + MainViewModel (folder list)
│   ├── video_list/     # VideoListActivity + adapter (videos in folder)
│   └── player/         # PlayerActivity + PlayerViewModel + gestures
├── utils/
│   ├── Constants.kt
│   └── TimeFormatter.kt
└── VideoPlayerApplication.kt
```

## Building

1. Clone the repository
2. Open in Android Studio (Hedgehog or newer)
3. Sync Gradle and build
4. Run on a device or emulator with local video files

```bash
git clone https://github.com/kaushik-jagani/android-offline-video-player.git
cd android-offline-video-player
./gradlew assembleDebug
```

## Permissions

- `READ_MEDIA_VIDEO` (API 33+) / `READ_EXTERNAL_STORAGE` (API < 33) — to scan device videos

## License

This project is open source and available under the [MIT License](LICENSE).
