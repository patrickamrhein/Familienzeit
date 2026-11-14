# Familienzeit – Einrichtung

Zwei Apps:
- **Familienzeit Eltern** (`eltern.apk`) – auf deinem Handy: Status, Regeln, Standort, Fernbefehle.
- **Familienzeit** (`kind.apk`) – auf dem Pixel deines Sohnes: setzt die Regeln durch, kann nicht deinstalliert werden.

Die fertigen Dateien liegen immer unter
https://github.com/patrickamrhein/familienzeit-updates/releases/latest

## 1. Einmalig: zwei Schlüssel in GitHub hinterlegen

Im privaten Repository `familienzeit` → **Settings → Secrets and variables → Actions → New repository secret**:

1. Name `KEYSTORE_PASSWORD` – Wert: das Passwort, das Claude dir im Chat gegeben hat.
   Ohne dieses Passwort lassen sich später keine Updates mehr bauen – gut aufbewahren!
2. Name `UPDATES_TOKEN` – einen Zugangsschlüssel erstellen unter
   github.com → Profilbild → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token:
   - Repository access: *Only select repositories* → `familienzeit-updates`
   - Permissions → Repository permissions → **Contents: Read and write**
   - Ablaufdatum: so lang wie möglich (danach erneuern)

Danach unter **Actions** den Lauf „Apps bauen und veröffentlichen“ mit *Re-run* bzw. *Run workflow* starten.

## 2. Eltern-App installieren

Auf deinem Handy die Release-Seite öffnen, `eltern.apk` herunterladen und öffnen.
Android fragt einmal, ob der Browser Apps installieren darf → erlauben.
Die App zeigt „Kinder-Handy koppeln“ – das kommt in Schritt 4.

## 3. Kinder-Handy vorbereiten

1. Wichtige Daten sichern (zur Sicherheit).
2. Einstellungen → **Passwörter & Konten** → **alle** Konten entfernen (Google, WhatsApp, …).
   Das Entfernen seines Google-Kontos musst du in Family Link bestätigen.
   Fotos, Chats und Apps bleiben erhalten.
3. Falls vorhanden: **Privaten Bereich** löschen und weitere Nutzer/Gast entfernen.
4. Einstellungen → Über das Telefon → 7× auf **Build-Nummer** tippen → Entwickleroptionen sind an.
5. Einstellungen → System → Entwickleroptionen → **USB-Debugging** einschalten.

## 4. Einrichtung per PC

1. Den Ordner `setup` aus diesem Repository auf den PC laden.
2. Handy per USB-Kabel anschließen.
3. Windows: `einrichten-windows.bat` doppelklicken. Mac/Linux: `einrichten-mac-linux.sh` im Terminal starten.
4. Auf dem Handy „USB-Debugging zulassen“ bestätigen.
5. Am Ende öffnet sich die App auf dem Kinder-Handy und zeigt einen **QR-Code**.
   In der Eltern-App auf **QR-Code scannen** tippen und den Code scannen (oder abtippen).
   Erst danach greifen die Sperren und USB-Debugging wird abgeschaltet.
6. Kabel ab, Konten wieder anmelden. Fertig.

USB-Debugging wird danach automatisch gesperrt (in der Eltern-App unter *Wartung* bei Bedarf freigebbar).

## Was die Kinder-App sperrt

- Deinstallieren der App, Beenden erzwingen, Daten löschen
- Zurücksetzen auf Werkseinstellungen, abgesicherter Modus
- Weitere Nutzer, Privater Bereich, Uhrzeit verstellen, eigene VPNs
- Apps aus unbekannten Quellen, USB-Debugging
- Standort ausschalten

Wird der Nutzungszugriff entzogen, sperrt sich das Handy komplett und du bekommst eine Meldung.

## Notfall

- Eltern-App → Wartung → **Kontrolle komplett aufheben**, oder
- auf dem Kinder-Handy in der App → **Notfall-Freigabe** mit dem Kopplungscode.

## Mehrere Kinder und Eltern

- **Weiteres Kind:** In der Eltern-App oben auf „+ Kind“ tippen und den QR-Code des neuen Kinder-Handys scannen.
- **Zweites Elternteil:** Der Admin tippt unter „Eltern“ auf „Elternteil einladen“. Das andere Elternteil
  installiert die Eltern-App und scannt den angezeigten QR-Code (gilt 24 Stunden, nur einmal).
- **Rollen:** Wer das Kinder-Handy als Erstes koppelt, ist Admin. Admins können Eltern einladen, entfernen,
  zu Admins machen, USB-Debugging freigeben und die Kontrolle aufheben. Elternteile können alles andere.
- **Entfernen:** Wird ein Elternteil entfernt, erzeugt das Kinder-Handy einen neuen Schlüssel.
  Das entfernte Handy kann danach nichts mehr lesen oder senden.
- **Notfall-Code:** Der Admin findet ihn unter „Wartung“. Damit lässt sich direkt am Kinder-Handy ein neues
  Eltern-Handy koppeln (z. B. wenn deins verloren geht) oder die Kontrolle aufheben.
