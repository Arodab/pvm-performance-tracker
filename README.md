# PvM Performance Tracker

A RuneLite plugin that tracks your real vs expected combat performance (damage, accuracy, DPS) against any NPC. 

Instead of just telling you how much damage you dealt, this plugin models your exact gear, stats, and prayers on every single tick to calculate what your **expected** damage should have been. This allows you to evaluate your luck, identify missed attacks, and truly measure your performance.

## Features
- **Efficiency Tracker:** Calculates your expected damage versus what it would have been with flawless execution. It automatically accounts for mistakes by detecting:
  - **Missed Gear Switches:** (Optional) Scans your inventory to see if better gear was available for the weapon used.
  - **Missed Prayers:** Identifies attacks made without the correct offensive prayer active.
  - **Unpotted Attacks:** Tracks attacks made when your stat-boosting potions had expired.
- **Raid Support:** Dynamically scales monster defence based on your party size and raid level in the Chambers of Xeric and Tombs of Amascut.
- **Live Overlay:** Displays a real-time comparison of your actual damage vs expected damage.
- **Exportable Data:** Dumps your session fight data to CSV for further analysis.

## Credits
- Special thanks to Kinomoto and zLost for a ton of valuable feedback and ideas for the plugin;
- Portions of the combat math were ported from the LlemonDuck dps-calculator (BSD-2);
- Chambers of Xeric scaling math taken from **GearScape**.
