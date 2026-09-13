@echo off
REM Compile and run WinCliAgent on Windows
if not exist "target\classes" mkdir "target\classes"
for /f "delims=" %%i in ('dir /s /b src\main\java\*.java') do (
    javac -d target\classes "%%i"
)
java -cp target\classes agent.Main %*
