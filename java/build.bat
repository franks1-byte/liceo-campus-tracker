@echo off
rem Rebuilds LiceoCampusTracker.jar from the source code (needs a JDK, 17 or newer).
if exist out rmdir /s /q out
dir /s /b src\*.java > sources.txt
javac --release 17 -encoding UTF-8 -d out @sources.txt || (del sources.txt & pause & exit /b 1)
del sources.txt
echo Main-Class: liceo.Main> manifest.txt
jar cfm LiceoCampusTracker.jar manifest.txt -C out .
del manifest.txt
echo Built LiceoCampusTracker.jar
