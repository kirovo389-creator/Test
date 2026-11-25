# VR Cinema

Android cinema player with an aspect-correct curved theatre screen and a phone-camera background for a simple Cardboard-style VR viewer.

## Features

- Pick a local video using Android's system file picker.
- Receive a video shared from Telegram or another app using Android Share.
- Play direct HTTP(S) media streams supported by Media3: MP4, WebM, HLS (.m3u8) and DASH (.mpd) (depending on the server and codecs).
- Landscape theatre preview with a curved-edge screen.
- Adjust screen size and apparent distance.
- Side-by-side left/right eye view for a phone VR headset.
- Camera passthrough background, double-tap repositioning and rotation-vector head tracking.
- Basic play/pause control while in VR.

## Link limitations

- A YouTube watch URL is a webpage, not a direct media stream. The app opens it in the official YouTube app instead of extracting or bypassing YouTube's playback restrictions.
- Telegram private posts and channels require the user's own Telegram access. To play a Telegram video in VR Cinema, open the video in Telegram and use **Share → VR Cinema** (or save it and select it from the file picker).
- Direct video URLs must be reachable by the phone and should not require an unsupported web login, DRM session, or custom authorization flow.

## Build

The GitHub Actions workflow builds the release APK on pushes to the `vr-cinema` branch and uploads it as the `vr-cinema-release-apk` workflow artifact.

Package: `com.devtudio.vrcinema`

The APK is debug-key signed for convenient sideload testing. It is not a Play Store production signing key.
