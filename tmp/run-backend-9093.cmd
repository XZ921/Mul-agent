@echo off
setlocal

set "ROOT=E:\java_study\Mul-agnet"
set "BACKEND=%ROOT%\backend"
set "MVN=E:\java\maven\maven\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd"
set "STAMP=%~1"

if "%STAMP%"=="" (
    set "STAMP=manual"
)

cd /d "%BACKEND%"
call "%MVN%" spring-boot:run 1>>"%BACKEND%\logs\live-restart-9093-%STAMP%.out.log" 2>>"%BACKEND%\logs\live-restart-9093-%STAMP%.err.log"
