# StatusHub – Status Saver & Media Manager (v1.2)

StatusHub is a modern, privacy-focused Android application designed to help users effortlessly view, manage, and save status media (images and videos) from WhatsApp. Built with "Jetpack Compose" and following modern Android development practices, it offers a fluid, intuitive, and highly customizable user experience.

---

✨ Features

- Media Discovery: Automatically detects viewed status media from local storage using Scoped Storage (SAF).
- Auto-Save Status: (New) Effortlessly save statuses to your gallery automatically as soon as you view them.
- Dark Mode Support: (New) Full support for dark theme, allowing users to toggle between light and dark modes in settings.
- Elegant Gallery: A clean, grid-based layout that categorizes images and videos for easy browsing.
- Fullscreen Preview: High-quality media viewer with swipe navigation and sharing capabilities.
- Integrated Video Player: Seamless video playback powered by **Media3 ExoPlayer**.
- Modern UI: Clean, Material 3 design with edge-to-edge support and smooth animations.
- Privacy First: Operates entirely offline; your media never leaves your device.
- Ad Integrated: Optimized AdMob integration for sustainability without compromising user experience.

---

🚀 Tech Stack

- UI: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Declarative UI)
- Language: [Kotlin](https://kotlinlang.org/)
- Image/Video Loading: [Coil](https://coil-kt.github.io/coil/)
- Video Playback: [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer)
- Storage: Storage Access Framework (SAF) for scoped storage compliance.
- Dependency Management: Gradle Version Catalog (libs.versions.toml)
- Architecture: Clean MVVM (Model-View-ViewModel) pattern.

---

🛠️ Installation & Setup

1. Prerequisites:
   - Android Studio Koala or newer.
   - Android SDK 24 (Android 7.0) or higher.

2. Clone the Repo:
   ```bash
   git clone https://github.com/yourusername/StatusHub.git
   ```

3. Build:
   - Open the project in Android Studio.
   - Sync Gradle files.
   - Configure your own AdMob App ID in `AndroidManifest.xml` and Unit IDs in `AdComponents.kt`.

4. Run:
   - Connect an Android device or start an emulator.
   - Click Run in Android Studio.

---

🔐 Permissions & Privacy

- Storage: Uses Scoped Storage (SAF). The user explicitly grants access to the specific folder where statuses are stored.
- Privacy: StatusHub respects your privacy. No media files are uploaded to any server or shared with third parties.

---

⚠️ Disclaimer

StatusHub is an independent application and is not affiliated with, authorized, maintained, sponsored, or endorsed by WhatsApp Inc. or any of its affiliates. The "WhatsApp" name is copyright to WhatsApp, Inc. StatusHub only facilitates the management of media files already present on the user's local storage.

---

👨‍💻 Author

Developed with ❤️ by "Dhruv".

Feel free to reach out for collaborations or feedback!
