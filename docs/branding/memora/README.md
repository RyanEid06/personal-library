# Memora artwork and appearance

The owner supplied these final assets on 16 October 2026. The three PNGs in `originals/` are byte-identical copies from Downloads, retained unchanged for future branding work:

- `launcher-original.png`: Glossy Layered Search Icon.png; text-free full-color launcher for both appearances.
- `wordmark-dark-original.png`: Memora Archive Logo.png; light text for dark mode.
- `wordmark-light-original.png`: Memora Personal Archive Logo.png; dark text for light mode.

Earlier draft logos and the combined showcase are not active resources. Downloads originals are retained. `scripts/prepare_memora_assets.py` uses Pillow to trim export margins and prepare PNG density resources without recoloring or redrawing the artwork. The owner subsequently chose a compact Library header: current item count and date basis appear directly alongside the grid/list and Settings controls, with no Memora logo or name in the row. The original wordmarks remain archived for provenance. Launcher ID, Room database, preference names and backup format are unchanged to preserve existing data. User-facing app name is Memora.

Settings -> Appearance offers Light, Dark and System; System is the initial/default choice. Changes apply immediately and persist across Activity/app recreation. System follows the phone configuration. Header surface/text and system-bar icon contrast follow the resolved mode; launcher artwork is shared across modes.

The existing fixed app palette is now the default instead of wallpaper-derived dynamic colors, so artwork has stable surroundings. PNG transparency avoids a rectangular background mismatch.

| Role | Light RGB / hex | Dark RGB / hex |
| --- | --- | --- |
| Page background | 248, 250, 252 / #F8FAFC | 15, 23, 42 / #0F172A |
| Header/card surface | 255, 255, 255 / #FFFFFF | 30, 41, 59 / #1E293B |
| Main text | 15, 23, 42 / #0F172A | 248, 250, 252 / #F8FAFC |
| Primary | 30, 41, 59 / #1E293B | 245, 158, 11 / #F59E0B |
| Secondary | 217, 119, 6 / #D97706 | 20, 184, 166 / #14B8A6 |

Verification: pure JVM theme regression first reproduced the prior system-only behavior (3 tests, 2 failures), then all 3 passed. Android preference tests cover immediate updates/recreation without changing other settings. API 35 UI instrumentation selects all three modes, checks the visible information/actions row, absence of header branding and actual light/dark surface colors, recreates the Activity, and saves light/dark screenshots. Hosted CI runs the appearance assertions against debug and nondebuggable optimized verification builds. Exact final source results and the permanent signing outcome are recorded in ignored `artifacts/release-v1/FINAL_VERIFICATION.md`. Physical-phone visual/launcher acceptance remains owner work.
