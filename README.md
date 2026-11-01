# Plip

Android-App, die dich in deinem Rhythmus ans Trinken erinnert. Kotlin, Jetpack Compose, Material 3.

## Bauen und aufs Handy

```
gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Die APK liegt danach in `app/build/outputs/apk/release/`. Sie ist mit dem lokalen Debug-Schlüssel signiert und lässt sich direkt installieren.

## So funktioniert's

- Die Erinnerung kommt als Benachrichtigung mit den Knöpfen „250 ml getrunken“ und „In 10 Min“.
- Der Timer zählt ab deinem letzten Schluck. Ignorierst du eine Erinnerung, kommt nach 10 Minuten die nächste.
- Außerhalb der aktiven Zeit (Standard 08:00 bis 22:00) bleibt Plip still.
- Die Erinnerungen laufen über exakte Alarme (`USE_EXACT_ALARM`) und kommen auch im Ruhemodus pünktlich. Nach einem Neustart werden sie neu gestellt.
