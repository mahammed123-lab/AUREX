@echo off
cd /d C:\Users\maham\AUREX_final
call gradlew.bat :app:assembleDebug --console=plain > build_tts_fix.log 2>&1
echo EXITCODE=%ERRORLEVEL%
