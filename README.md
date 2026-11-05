# Talk Board

An offline Android tap-to-speak word board. The starting vocabulary is in
`app/src/main/assets/words.json`; edit it before building if desired. Each item
needs a unique `id`, a single-word `label`, `imagePath` (usually `null`), and
`children`. The app copies this file to private storage on its first launch, so
later edits are made using Edit mode in the app or by importing a backup.

Build an APK with `./gradlew assembleDebug`. Install
`app/build/outputs/apk/debug/app-debug.apk` using Android Studio/USB (`adb
install -r app-debug.apk`) or send that file to the tablet and open it there.

To prevent accidental app switching, open Android **Settings > Security** (or
**Security & privacy**) > **Screen pinning**, enable it, then open Talk Board,
open Recents, tap its icon and select **Pin**. The tablet unlock method is
required to unpin it.