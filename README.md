# Multi Profile Browser (Kotlin)

## Build
1. Android Studio (Koala / Ladybug ya newer) me **File > Open** > is folder ko select karo.
2. Gradle sync hone do (internet chahiye, Gradle 8.9 + JDK 17 auto-use hota hai).
3. Phone USB debugging se connect karo > **Run**. APK chahiye to **Build > Build APK(s)**.

## Features
- Launch pe profile list + "Create profile" (name + optional countdown).
- Countdown: time pick karo (e.g. abhi 12:00, tumne 06:00 dala -> 6h left). Profile name ke side me live dikhta hai, 0 hone par "Ready ✓". App band hone par bhi chalta rehta hai (absolute time save hota hai).
- Har profile ka alag WebView Profile: cookies, localStorage, cache alag. Kabhi share nahi hote.
- Download: Downloads/<ProfileName>/ me save, "Share" button se file share. Upload bhi chalta hai.
- Profiles, logins aur last page app band karke kholne par bhi saved rehte hain.
- Edge-to-edge UI: status bar, navigation bar, notch/punch-hole aur keyboard ke hisaab se padding.

## Note
Separate profiles ke liye Android System WebView / Chrome ka recent version chahiye (2023+).
