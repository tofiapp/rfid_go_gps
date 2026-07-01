@echo off
setlocal
set JAR=%~dp0..\preindex\build\libs\preindex-dzs-1.0.jar
if not exist "%JAR%" (
  echo Nejprve sestavte JAR: gradlew :preindex:jar
  exit /b 1
)
java -jar "%JAR%" %*
