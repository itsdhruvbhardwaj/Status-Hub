# StatusHub – Status Saver & Media Manager


StatusHub is a modern, privacy-focused Android application designed to help users effortlessly view, manage, and save status media (images and videos) from messaging platforms like WhatsApp. Built with Jetpack Compos and following modern Android development practices, it offers a fluid and intuitive user experience.



✨ Features

- Media Discovery: Automatically detects viewed status media from local storage using Storage Access Framework (SAF).
- Elegant Gallery: A grid-based layout that separates images and videos for easy browsing.
- Fullscreen Preview: High-quality media viewer with swipe-to-navigate functionality.
- Integrated Video Player: Seamless video playback powered by **Media3 ExoPlayer**.
- Modern UI: Clean, glassmorphism-inspired design with support for edge-to-edge display.
- Privacy First: Operates entirely offline with no data collection or unnecessary network usage.
- Ad Integration: Non-intrusive AdMob integration for sustainability.


🚀 Tech Stack

- UI: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Declarative UI)
- Language: [Kotlin](https://kotlinlang.org/)
- Image/Video Loading: [Coil](https://coil-kt.github.io/coil/)
- Video Playback: [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer)
- Storage: Storage Access Framework (SAF) for scoped storage compliance.
- Network: Retrofit & OkHttp (for potential future API integrations).
- Architecture: MVVM (Model-View-ViewModel) pattern with a clean package structure (`domain`, `data`, `ui`).



🛠️ Installation & Setup

1. Prerequisites:
   - Android Studio Iguana or newer.
   - Android SDK 24 (Android 7.0) or higher.

2. Clone the Repo:
   ```bash
   git clone https://github.com/yourusername/StatusHub.git
   ```

3. Build:
   - Open the project in Android Studio.
   - Sync Gradle files.
   - Set up your `admob_app_id` in `AndroidManifest.xml` (if applicable).

4. Run:
   - Connect an Android device or start an emulator.
   - Click **Run** in Android Studio.

🔐 Permissions & Privacy

- Storage: Uses Scoped Storage (SAF). The user explicitly grants access to the specific folder where statuses are stored.
- Privacy: No media files are uploaded to any server. All processing happens locally on your device.

⚠️ Disclaimer

StatusHub is an independent application and is not affiliated with, authorized, maintained, sponsored, or endorsed by WhatsApp Inc. or any of its affiliates. The "WhatsApp" name is copyright to WhatsApp, Inc. StatusHub only facilitates the management of media files already present on the user's local storage.

👨‍💻 Author

Developed with ❤️ by Dhruv.

Feel free to reach out for collaborations or feedback!
