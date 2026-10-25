<div align="center">

# 🎤 SingCam

**Sing along. Record yourself. One app, one take.**

A lightweight Android camera built for people who love to sing: play any song, hit record, and capture your video and your voice together, without juggling two phones.



![Platform](https://img.shields.io/badge/platform-Android%2010%2B-6A11CB)




![Language](https://img.shields.io/badge/language-Kotlin-FF2E93)




![License](https://img.shields.io/badge/license-MIT-blue)




![Status](https://img.shields.io/badge/status-active-brightgreen)



</div>

---

## 💡 The idea behind SingCam

I like to sing, and I like to share it. But recording a simple singing video was surprisingly awkward:

1. Play the song on one phone.
2. Record the video on a second phone.
3. Hope the volume balance works out.

Most people don't have a second device handy, and the built-in camera app isn't designed for this. Many either stop the music when recording starts or pick up only the room noise.

**SingCam fixes that workflow.** It is a camera app that keeps your music playing while it records, so the song and your voice end up in the same video, from a single phone.

> Built from a real everyday problem, not a tutorial.

---

## ✨ Features

- 🎥 **Record while music plays.** Playback is never interrupted when recording starts.
- 🎙️ **Voice + music in one take.** The microphone captures your voice and the song from the speaker.
- 🎵 **Built-in player.** Pick any audio file stored on your phone and play, pause or adjust it from inside the app.
- 🔊 **Music volume slider.** Keep the song low so your voice stays clear.
- 📱 **Front and back camera.** Switch with one tap.
- ⏱️ **Live REC timer.** You always know how long you've been recording.
- 💾 **Saved to your gallery.** Videos are stored in `Movies/SingCam` as standard MP4 files.
- 🌙 **Clean, modern UI.** Large record button, simple controls, nothing in the way.

---

## 👥 Who is it for?

- Karaoke lovers and bathroom singers who want to share a clip
- Aspiring singers and cover artists posting on social media
- Guitar, piano or ukulele players who want to play over a backing track
- Students, teachers and music-practice groups reviewing their performance
- Anyone who has ever said, *"I wish I could just record this with the song playing"*

---

## 🚀 How to use it

**Option A: Play from another music app (for example Spotify)**

1. Open your music app and start the song.
2. Open SingCam and tap the large record button.
3. Keep the music **speaker volume low**, so the microphone hears your voice clearly.
4. Sing. Tap the button again to stop. Your video is saved automatically.

**Option B: Use the built-in player**

1. Tap 🎵 to choose an audio file from your phone.
2. Tap ▶ to play, adjust the slider, then tap the record button.

> 🎧 **Tip:** Don't wear headphones if you want the song in your video. SingCam records the sound coming through the speaker.

---

## 📦 Install

### Download the APK
1. Go to the **Actions** tab of this repository.
2. Open the latest successful **Build APK** run.
3. Download **SingCam-release-apk** under *Artifacts*, unzip it and install `app-release.apk`.

> You may need to allow "Install unknown apps" for your browser or file manager.

### Build it yourself
```bash
git clone https://github.com/helicasecoded/SingCam.git
cd SingCam
gradle assembleRelease
```
The APK appears in `app/build/outputs/apk/release/`. You need JDK 17 and the Android SDK (or just open the project in Android Studio).

Every push to this repository is built automatically by **GitHub Actions**, so a fresh APK is always one click away.

---

## 🧱 How it works

| Layer | Technology |
|-------|------------|
| Language | Kotlin |
| Camera and video | [CameraX](https://developer.android.com/training/camerax) (`Preview`, `VideoCapture`, `Recorder`) |
| Music playback | Android `MediaPlayer` |
| File access | Storage Access Framework (`OpenDocument`), so no broad storage permission |
| Saving videos | `MediaStore`, so they appear in your gallery right away |
| UI | Native Android views, custom vector shapes |
| CI/CD | GitHub Actions builds a release APK on every push |

The app asks only for what it needs: **camera** and **microphone**. It has no internet permission, no accounts, no ads and no analytics.

### Project structure
```
SingCam/
├── .github/workflows/build.yml     # Automatic APK builds
├── app/src/main/
│   ├── java/com/example/singcam/
│   │   └── MainActivity.kt         # Camera, recording and music logic
│   ├── res/layout/activity_main.xml
│   ├── res/drawable/               # Buttons, panels, launcher icon
│   └── AndroidManifest.xml
├── build.gradle.kts
└── README.md
```

---

## ⚠️ Honest limitations

I'd rather you know these upfront:

- **Audio is captured through the microphone.** The song is not digitally mixed into the video yet, so quality depends on your speaker and room. Headphones will not record the music.
- **Streaming apps can't be recorded internally.** Services such as Spotify protect their audio, so SingCam can't capture it directly. It records the speaker sound instead.
- **Respect copyright.** Only publish videos with music you have the right to share. SingCam is not affiliated with Spotify or any music service; all trademarks belong to their owners.

---

## 🗺️ Roadmap

- [ ] Digital audio mixing for studio-clean sound (local files)
- [ ] Headphone mode: hear the song privately while only your voice and the track are recorded
- [ ] Lyrics display while recording
- [ ] 1080p / 4K and frame-rate options
- [ ] Voice effects such as reverb and echo
- [ ] Timer countdown before recording starts
- [ ] Landscape mode
- [ ] Play Store release

Have an idea? Open an issue. I read every one.

---

## 🤝 Contributing

Contributions are welcome, whether it's code, design, a bug report or a suggestion.

1. Fork the repository
2. Create a branch: `git checkout -b feature/my-idea`
3. Commit your changes: `git commit -m "Add my idea"`
4. Push and open a Pull Request

Good first issues: UI polish, translations, new color themes, and testing on different devices.

---

## 📄 License

Released under the [MIT License](LICENSE). Free to use, modify and share.

---

## 👋 About the author

Hi, I'm **[HELICASE]**, a 20-year-old developer who loves building cool, useful things. SingCam started as a small personal problem and grew into a full app with a modern UI, automated builds and an open-source roadmap.

I enjoy turning everyday annoyances into simple tools, and I'm learning something new with every project: Kotlin, Android, CI/CD and open-source collaboration.

- GitHub: [@helicasecoded](https://github.com/helicasecoded)
- Say hello: [CONTACT ME WHEN YOU NEED AND HAVE ANY IDEA MY INSTAGRAM- 12.011amu ]

⭐ **If SingCam helped you, or you just like the idea, please star the repo.** It genuinely motivates me to keep building.
