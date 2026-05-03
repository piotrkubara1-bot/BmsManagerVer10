# BmsManager

To jest bardzo prosta instrukcja uruchomienia projektu na Windows.

Ten projekt ma kilka części:

- baza danych MySQL,
- backend API,
- Web GUI w przeglądarce,
- desktop viewer w JavaFX,
- osobną aplikację mobilną Android.

Najważniejsze:

- serwer uruchamiasz jedną komendą: `.\run_server_stack.bat`
- Web GUI działa bez podłączonego BMS
- jeśli nie masz sprzętu, możesz użyć trybu `SIMULATED`
- aplikacja mobilna jest osobnym projektem i uruchamia się osobno

## 1. Co musisz mieć zainstalowane

Na komputerze musisz mieć:

- Java JDK
- XAMPP w folderze `C:\xampp`
- PowerShell
- opcjonalnie Android Studio, jeśli chcesz uruchomić aplikację mobilną

## 2. W jakim folderze uruchamiać komendy

Wszystkie komendy uruchamiaj w folderze projektu.

Przykład:

```powershell
C:\sciezka\do\BmsManager
```

W PowerShell przed plikiem `.bat` zawsze pisz `.\`

Przykład:

```powershell
.\run_server_stack.bat
```

Nie tak:

```powershell
run_server_stack.bat
```

## 3. Pierwsze przygotowanie

### Krok 1. Skopiuj plik konfiguracyjny

Jeśli nie masz jeszcze pliku `.env`, skopiuj go z przykładu:

```powershell
Copy-Item ".env.example" ".env"
```

Jeśli plik `.env` już istnieje, nic nie rób.

Ważne:

- pliku `.env` nie ma na GitHubie celowo,
- ten plik zawiera lokalną konfigurację komputera,
- dlatego w repo jest tylko `.env.example`,
- Ty masz skopiować `.env.example` do `.env` u siebie na swoim komputerze

### Krok 2. Otwórz `.env`

Otwórz plik `.env` w folderze projektu.

Sprawdź, czy masz tam przynajmniej takie wartości:

```env
BMS_API_PORT=8090
WEB_UI_PORT=8088
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=bms
DB_USER=root
DB_PASSWORD=
SERIAL_PORT=COM5
SERIAL_BAUD=115200
BMS_API_INGEST_URL=http://127.0.0.1:8090/api/ingest
```

Jeśli nie masz podłączonego BMS i chcesz tylko testować program, ustaw:

```env
SERIAL_PORT=SIMULATED
```

## 4. Jak uruchomić cały serwer

Najprościej tak:

```powershell
.\run_server_stack.bat
```

Ten skrypt robi po kolei:

1. uruchamia MySQL z XAMPP,
2. czeka aż MySQL będzie gotowy,
3. importuje plik `bms_schema.sql`,
4. uruchamia backend API,
5. uruchamia Web GUI.

Nie musisz osobno uruchamiać bazy, backendu i Web GUI.

## 5. Jak sprawdzić, czy serwer działa

Po uruchomieniu wejdź na:

- [http://127.0.0.1:8090/api/health](http://127.0.0.1:8090/api/health)
- [http://127.0.0.1:8088/dashboard.html](http://127.0.0.1:8088/dashboard.html)

Możesz też sprawdzić to komendą:

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Poprawny wynik powinien wyglądać mniej więcej tak:

```json
{"status":"ok","dbConnected":true}
```

Najważniejsze są dwie rzeczy:

- `"status":"ok"`
- `"dbConnected":true`

## 6. Jak zatrzymać serwer

Zatrzymanie:

```powershell
.\run_server_stack.bat stop
```

albo:

```powershell
.\stop_all.bat
```

### Mocne zatrzymanie, gdy program zachowuje sie dziwnie

Jezeli po zatrzymaniu program dalej dziala, port jest zajety albo Web GUI/desktop viewer dziwnie sie odswieza, zrob pelne czyszczenie procesow Java:

```powershell
.\stop_all.bat
taskkill /F /IM java.exe
taskkill /F /IM javaw.exe
```

Co to robi:

- `.\stop_all.bat` zatrzymuje procesy nasluchujace na portach projektu,
- `taskkill /F /IM java.exe` zamyka pozostale procesy Java w konsoli,
- `taskkill /F /IM javaw.exe` zamyka pozostale procesy Java uruchomione jako okna.

Jezeli zobaczysz komunikat:

```text
ERROR: The process "javaw.exe" not found.
```

to nie jest blad projektu. To znaczy tylko, ze nie bylo procesu `javaw.exe` do zamkniecia.

Po takim czyszczeniu uruchom projekt od nowa:

```powershell
.\run_server_stack.bat
```

## 7. Jak używać Web GUI

Web GUI otwierasz tutaj:

[http://127.0.0.1:8088/dashboard.html](http://127.0.0.1:8088/dashboard.html)

W Web GUI możesz:

- oglądać dane,
- wybrać port COM,
- zapisać port do konfiguracji,
- uruchomić i zatrzymać UART,
- użyć trybu `SIMULATED`,
- wysłać komendy `Reset BMS`, `Clear Events`, `Clear Statistics`

## 8. Co zrobić, jeśli nie masz podłączonego BMS

Jeśli nie masz sprzętu, nadal możesz uruchomić cały projekt.

Zrób tak:

1. uruchom serwer:

```powershell
.\run_server_stack.bat
```

2. otwórz Web GUI:

[http://127.0.0.1:8088/dashboard.html](http://127.0.0.1:8088/dashboard.html)

3. przejdź do zakładki `Cell Settings`
4. wybierz port `SIMULATED`
5. kliknij `Save COM Port`
6. kliknij `Start UART`

Wtedy program nie używa prawdziwego portu COM.
Zamiast tego generuje sztuczne dane testowe.

To jest najlepszy sposób, żeby sprawdzić działanie programu bez urządzenia.

## 9. Co zrobić, jeśli masz prawdziwy BMS

Jeśli masz prawdziwy sprzęt, zrób tak:

1. podłącz BMS do komputera,
2. sprawdź w Menedżerze urządzeń, jaki ma numer portu, na przykład `COM3` albo `COM5`,
3. uruchom serwer:

```powershell
.\run_server_stack.bat
```

4. otwórz Web GUI,
5. przejdź do zakładki `Cell Settings`,
6. wybierz właściwy port COM,
7. kliknij `Save COM Port`,
8. kliknij `Start UART`

## 10. Jak ręcznie uruchomić UART sender

Możesz też uruchomić sender ręcznie z terminala.

Bez podawania portu:

```powershell
.\run_uart_sender.bat
```

Z konkretnym portem:

```powershell
.\run_uart_sender.bat COM3
```

Albo w trybie symulacji:

```powershell
.\run_uart_sender.bat SIMULATED
```

## 11. Desktop viewer

Desktop viewer to osobny program w JavaFX.
Wygląda jak Web GUI, ale działa jako osobne okno na komputerze.

Najpierw uruchom serwer:

```powershell
.\run_server_stack.bat
```

Potem uruchom desktop viewer:

```powershell
.\run_desktop_web_gui.bat
```

Ważne:

- najpierw serwer,
- dopiero potem desktop viewer

## 12. Aplikacja mobilna Android

Aplikacja mobilna jest w osobnym folderze:

`mobile-viewer-android`

To jest osobny projekt do Android Studio.

### Jak uruchomić aplikację mobilną

1. uruchom Android Studio,
2. kliknij `Open`,
3. wskaż folder `mobile-viewer-android`,
4. poczekaj aż Android Studio zrobi sync projektu,
5. uruchom aplikację na telefonie albo emulatorze

## 13. Jak połączyć telefon z komputerem

Telefon i komputer muszą być w tej samej sieci Wi-Fi.

Najpierw na komputerze uruchom serwer:

```powershell
.\run_server_stack.bat
```

Potem sprawdź adres IP komputera:

```powershell
ipconfig
```

Teraz zrób to dokładnie tak:

1. znajdź sekcję `Wireless LAN adapter Wi-Fi`
2. w tej sekcji znajdź linię `IPv4 Address`
3. skopiuj tylko ten adres

Przykład poprawnego adresu:

```text
192.168.31.70
```

Nie wybieraj takich adresów:

- `127.0.0.1`
- `169.254.x.x`
- adresów z kart, które nie są Twoim prawdziwym Wi-Fi

Najczęściej dobry adres to taki, który:

- jest w sekcji `Wi-Fi`,
- ma obok `Default Gateway`,
- wygląda na przykład jak `192.168.1.100` albo `192.168.41.8`

W aplikacji mobilnej wpisujesz wtedy:

```text
http://192.168.31.70:8090
```

Nie wpisuj:

```text
http://127.0.0.1:8090
```

Bo `127.0.0.1` na telefonie oznacza telefon, a nie komputer.

### Szybki test, czy adres IP jest dobry

Po uruchomieniu serwera sprawdź na komputerze:

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Jeśli działa, spróbuj na telefonie w przeglądarce wpisać:

```text
http://TWOJ_ADRES_IP:8090/api/health
```

Przykład:

```text
http://192.168.41.8:8090/api/health
```

Jeśli telefon otwiera ten adres, to aplikacja mobilna też powinna działać.

Jeśli telefon nie otwiera tego adresu, to problem jest zwykle tutaj:

- telefon nie jest w tym samym Wi-Fi,
- backend nie działa,
- firewall Windows blokuje połączenie

## 14. Najczęstsze problemy

### Problem 1. `dbConnected:false`

To zwykle znaczy:

- MySQL nie działa,
- backend uruchomił się za wcześnie,
- dane bazy w `.env` są złe

Najprostsza naprawa:

```powershell
.\stop_all.bat
.\run_server_stack.bat
```

### Problem 2. `Failed to open port COMx`

To zwykle znaczy:

- wybrałeś zły port,
- port jest zajęty przez inny program,
- urządzenie nie jest podłączone

Jeśli nie masz sprzętu, użyj:

```text
SIMULATED
```

### Problem 3. Web GUI działa, ale nie ma danych

To zwykle znaczy:

- UART nie został uruchomiony,
- nie kliknąłeś `Start UART`,
- wybrany port COM jest zły,
- nie uruchomiłeś trybu `SIMULATED`

### Problem 4. PowerShell nie uruchamia `.bat`

Pisz tak:

```powershell
.\run_server_stack.bat
```

Nie tak:

```powershell
run_server_stack.bat
```

## 15. Najważniejsze pliki

- serwer: `run_server_stack.bat`
- backend + Web GUI: `run_full_stack.bat`
- UART sender: `run_uart_sender.bat`
- desktop viewer: `run_desktop_web_gui.bat`
- zatrzymanie: `stop_all.bat`
- konfiguracja: `.env.example`
- aplikacja mobilna: `mobile-viewer-android`
