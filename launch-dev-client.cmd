@echo off
title RuneLite Dev Client - PvM Performance Tracker
set "JAVA_HOME=C:\Users\T-GAMER\.jdks\jdk-11.0.31+11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
echo ============================================================
echo  RuneLite dev client - PvM Performance Tracker
echo ============================================================
echo.
echo Log in with a Jagex account per:
echo   https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
echo.
echo The overlay shows, for the current fight:
echo   Max hit / Spec max  - expected, for the loadout worn right now
echo   Avg hit             - measured damage per attack vs expected
echo   Hits, Accuracy      - measured vs expected
echo   Ticks lost          - ticks off cooldown with no attack, and
echo                         how many of those went on eating
echo.
echo WHAT TO CHECK THIS RUN - tick loss is the newest and least
echo verified part, rewritten three times and never run in game:
echo.
echo   1. Stand and attack one NPC without stopping. Ticks lost
echo      should stay at or near zero for melee, ranged and magic.
echo   2. Deliberately skip an attack. A scythe swung on tick 1 can
echo      go again on tick 6, so waiting until tick 7 is 1 lost.
echo   3. Eat mid fight. The lost ticks should appear on the
echo      "to eating" line, not lumped into the total.
echo   4. Combo eat on the same tick you attack. This broke the two
echo      earlier attempts - the attack must still be counted.
echo   5. Chinchompas, if you have some. Their projectile may not
echo      name a target, which would show as huge false tick loss.
echo   6. Config "Overlay: whole trip" - totals should survive kills
echo      and reset only from the New trip button in the side panel.
echo.
echo Also worth a look: Tumeken's shadow max hit OUTSIDE Tombs of
echo Amascut after a raid - it should read 3x, not 4x.
echo.
echo Close the RuneLite window to return here.
echo.
call gradlew.bat run
echo.
echo Client closed with exit code %errorlevel%.
pause
