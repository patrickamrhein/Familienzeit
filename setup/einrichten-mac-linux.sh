#!/bin/bash
# Familienzeit: Einrichtung des Kinder-Handys (macOS / Linux)
cd "$(dirname "$0")" || exit 1
REPO="https://github.com/patrickamrhein/familienzeit-updates/releases/latest/download"
PKG="de.familienzeit.kind"
ADB="./platform-tools/adb"
OS=$( [ "$(uname)" = "Darwin" ] && echo darwin || echo linux )

echo; echo "===== Familienzeit: Einrichtung des Kinder-Handys ====="; echo
if [ ! -x "$ADB" ]; then
  echo "[1/5] Lade die Android-Werkzeuge von Google ..."
  curl -L -o platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip" || exit 1
  unzip -oq platform-tools.zip || exit 1
fi
echo "[2/5] Lade die Kinder-App ..."
curl -L -o kind.apk "$REPO/kind.apk" || exit 1

echo "[3/5] Verbinde jetzt das Handy per USB-Kabel und tippe auf dem Handy 'USB-Debugging zulassen'."
"$ADB" wait-for-device || exit 1

echo "[4/5] Installiere die App ..."
"$ADB" install -r kind.apk || exit 1
"$ADB" shell appops set $PKG GET_USAGE_STATS allow
"$ADB" shell dumpsys deviceidle whitelist +$PKG >/dev/null

echo "[5/5] Mache die App zum Geräteeigentümer ..."
if ! "$ADB" shell dpm set-device-owner $PKG/.AdminReceiver; then
  echo
  echo "FEHLER: Android hat die Einrichtung abgelehnt."
  echo "Meist ist noch ein Konto angemeldet oder es gibt einen 'Privaten Bereich' / weiteren Nutzer."
  echo "Alle Konten entfernen, Privaten Bereich löschen, dann das Skript erneut starten."
  exit 1
fi
"$ADB" shell am start -n $PKG/.MainActivity >/dev/null
echo; echo "===== FERTIG ====="
echo "Auf dem Kinder-Handy erscheint jetzt ein QR-Code. Scanne ihn mit der Eltern-App."
