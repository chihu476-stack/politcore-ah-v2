@echo off
chcp 65001 >nul
set MODS=%APPDATA%\PrismLauncher\instances\Fabulously Optimized(1)\minecraft\mods
echo Закройте Minecraft перед обновлением!
del "%MODS%\politcore-ah*.jar" 2>nul
curl -L -f -o "%MODS%\politcore-ah.jar" https://github.com/chihu476-stack/politcore-ah-v2/releases/download/latest/politcore-ah.jar
if errorlevel 1 (echo ОШИБКА: не удалось скачать) else (echo Готово! Мод обновлён.)
pause
