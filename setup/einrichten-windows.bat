@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "REPO=https://github.com/patrickamrhein/familienzeit-updates/releases/latest/download"
set "ADB=%~dp0platform-tools\adb.exe"
set "PKG=de.familienzeit.kind"

echo.
echo ===== Familienzeit: Einrichtung des Kinder-Handys =====
echo.
if not exist "%ADB%" (
  echo [1/5] Lade die Android-Werkzeuge von Google ...
  curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip || goto fail
  tar -xf platform-tools.zip || goto fail
)
echo [2/5] Lade die Kinder-App ...
curl -L -o kind.apk "%REPO%/kind.apk" || goto fail

echo [3/5] Verbinde jetzt das Handy per USB-Kabel.
echo       Auf dem Handy erscheint "USB-Debugging zulassen?" - bitte "Zulassen" tippen.
"%ADB%" wait-for-device || goto fail

echo [4/5] Installiere die App ...
"%ADB%" install -r kind.apk || goto fail
"%ADB%" shell appops set %PKG% GET_USAGE_STATS allow
"%ADB%" shell dumpsys deviceidle whitelist +%PKG% >nul

echo [5/5] Mache die App zum Geraeteeigentuemer ...
"%ADB%" shell dpm set-device-owner %PKG%/.AdminReceiver || goto owner
"%ADB%" shell am start -n %PKG%/.MainActivity >nul

echo.
echo ===== FERTIG =====
echo Gib jetzt auf dem Handy deines Sohnes den Kopplungscode aus deiner Eltern-App ein.
echo Danach kannst du das Kabel abziehen und seine Konten wieder anmelden.
pause
exit /b 0

:owner
echo.
echo FEHLER: Android hat die Einrichtung abgelehnt.
echo Meist liegt es daran, dass noch ein Konto angemeldet ist oder ein "Privater Bereich"
echo bzw. weiterer Nutzer existiert. Bitte unter Einstellungen - Passwoerter und Konten
echo ALLE Konten entfernen, den Privaten Bereich loeschen und das Skript erneut starten.
pause
exit /b 1

:fail
echo.
echo FEHLER - siehe Meldung oben. Bitte Kabel, Internet und USB-Debugging pruefen.
pause
exit /b 1
