@echo off
rem Double-click to start the app (needs Java 17 or newer installed).
java -jar "%~dp0LiceoCampusTracker.jar"
if errorlevel 1 pause
