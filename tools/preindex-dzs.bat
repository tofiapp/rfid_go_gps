@echo off
setlocal
set "JAR=%~dp0preindex-dzs.jar"
set "DB=%~1"

if "%DB%"=="" (
  echo Pouziti: preindex-dzs.bat "C:\cesta\k\DZS_PASPORT_TPI.sqlite"
  echo.
  echo Potrebujete Java 17+ ^(JRE staci^). Stahnete z https://adoptium.net/
  exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
  echo Java neni nainstalovana. Stahnete JRE 17+ z https://adoptium.net/
  exit /b 1
)

if not exist "%JAR%" (
  echo Soubor preindex-dzs.jar neni vedle tohoto skriptu: %JAR%
  echo Stahnete ho z GitHub Releases ^(rfid_go_gps^) nebo sestavte: gradlew :preindex:shadowJar
  exit /b 1
)

java -jar "%JAR%" "%DB%" --stats --verify
exit /b %ERRORLEVEL%
