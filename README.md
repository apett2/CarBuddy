# CarBuddy

CarBuddy is an Android app featuring an emoji-headed stick figure that reacts to music, speed, turns, and bumps while driving. Customize the emojis and background, and identify songs with ACRCloud integration.

Disclaimer: this app may cause distraction while driving. Use cautiously at your own risk and always abide by all traffic laws.

## Features
- Animated stick figure with sound-reactive "fingers" and "legs"
- Speed-based emoji changes
- Song identification via ACRCloud (user-provided API keys)
- Customizable background and emojis
- Built with Jetpack Compose, Google Oboe, and KissFFT

## Requirements
- Android Studio
- Min SDK: 23
- ACRCloud API keys (sign up at [ACRCloud](https://www.acrcloud.com/)) Optional, if SongID feature is desired.

## Setup
1. Clone the repo: `git clone https://github.com/yourusername/CarBuddy.git`
2. Open in Android Studio
3. Enter your ACRCloud API key and secret when prompted
4. Build and run on an Android device

OR 

1. Download the APK from the releases page and side load on your Android phone:
2. Allow Installation
Go to Settings > Apps > Three dots in the top right corner > Special access > Install unknown apps. Find your file manager or browser (e.g., My Files, Chrome), and toggle on Allow from this source.
4. Install the App
Open your file manager or downloads app, tap CarBuddy.apk, and select Install. If prompted about unknown apps, tap OK or Install anyway. Scan app for safety if prompted. 
5. Secure Your Device
After installation, go back to Settings > Apps > Three dots in the top right corner > Special access > Install unknown apps and toggle off the permission you enabled.

## License
CarBuddy incorporates several open-source libraries and resources: Google Oboe, licensed under the Apache License 2.0 by The Android Open Source Project (Copyright 2015), allows use and distribution under the terms at http://www.apache.org/licenses/LICENSE-2.0, provided "AS IS" without warranties unless required by law. KissFFT, under the BSD 3-Clause License by Mark Borgerding (Copyright 2003-2010), permits redistribution and modification if the copyright notice, conditions, and disclaimer are retained, offered "AS IS" with no warranties and no liability for damages. Coil, also under the Apache License 2.0 by Coil Contributors (Copyright 2020), follows the same terms as Oboe, available at http://www.apache.org/licenses/LICENSE-2.0, distributed without warranties unless legally mandated. The app icon is designed by Freepik from Flaticon, and the project was built with assistance from Grok 3, created by xAI, acknowledged here as a courtesy.
