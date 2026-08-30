# PvM Performance Tracker

A RuneLite plugin that tracks your real vs expected combat performance (damage, accuracy, DPS) against any NPC. 

Instead of just telling you how much damage you dealt, this plugin models your exact gear, stats, and prayers on every single tick to calculate what your **expected** damage should have been. This allows you to evaluate your luck, identify missed attacks, and truly measure your performance.

## Features
- **Tick-Perfect Tracking:** Evaluates your loadout at the exact tick an attack is thrown.
- **Raid Support:** Dynamically scales monster defence based on your party size and raid level in the Chambers of Xeric and Tombs of Amascut.
- **Missed Switch Penalty:** Optional GearSearch can tell you if you missed a better weapon switch.
- **Live Overlay:** Displays a real-time comparison of your actual damage vs expected damage.
- **Exportable Data:** Dumps your session fight data to CSV for further analysis.

## Credits
- Special thanks to **GearScape** for the Chambers of Xeric scaling math.
- Portions of the combat math were ported from the LlemonDuck dps-calculator (BSD-2).

