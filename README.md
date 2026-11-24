# Agenda

Widget per la home (One UI) con eventi di Google Calendar e promemoria di Google Tasks.

- Si ridimensiona: versione stretta a 2 colonne o larga
- Tocca un evento per aprirlo, il cerchio di un promemoria per completarlo, + per un nuovo evento
- Sfondo semitrasparente in tinta con le cartelle di One UI

## Promemoria (Google Tasks)

Servono una volta sola, su Google Cloud Console:
1. Abilitare la Google Tasks API
2. Configurare la schermata di consenso OAuth
3. Creare un ID client OAuth di tipo Android:
   - nome pacchetto: `it.tiziano.agenda`
   - SHA-1: `11:D2:29:CA:51:C9:5C:D6:DC:80:DA:A4:48:60:84:65:6A:96:6C:93`

L'APK viene compilato da GitHub Actions a ogni modifica.
