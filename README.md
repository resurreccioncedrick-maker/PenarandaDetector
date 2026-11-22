# Peñaranda Branch Attendance Detector

Android app: opens the camera, finds people (front, side or back), draws a box on each HEAD,
counts each person once, and "Show count" displays the total.

## Get the APK (no Android Studio needed)
1. Create a GitHub repo and upload this whole folder (keep the .github folder).
2. Open the repo > Actions > "Build APK" > wait ~5 min.
3. Download the `HeadCounter-apk` artifact, unzip, send app-debug.apk to your phone and install it.

## Or with Android Studio
Open this folder (needs internet the first time, it downloads the detection model) > Build > Build APK(s).

## Using it
Allow the camera > Start scan > sweep the camera over the people > Show count.
Boxes: cyan = seen (not scanning), yellow = confirming, green + #n = counted.
