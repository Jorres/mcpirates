"""
build_sheriff_gui_texture.py — emit the sheriff-menu GUI background PNG.

Output:
    src/main/resources/assets/mcpirates/textures/gui/sheriff.png  (256x256)

The active GUI area is 176x168 in the top-left corner of the 256x256 canvas
(extra padding is the standard MC convention so the blit U/V references fit
the full image size). The image bakes in the beveled chest-grey frame, the
3x5 board slot wells (top), the 3x9 player main inventory wells, and the
9-slot hotbar wells. Inactive-slot darkening is rendered live by SheriffScreen,
not baked in here.

Coordinates here MUST match SheriffMenu's addSlot calls — see SheriffMenu.java
for the canonical slot layout.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT = REPO_ROOT / "src" / "main" / "resources" / "assets" / "mcpirates" / "textures" / "gui" / "sheriff.png"

IMG_W, IMG_H = 256, 256
GUI_W, GUI_H = 196, 182

# Vanilla chest-GUI palette.
FRAME_FILL = (198, 198, 198, 255)
FRAME_HI = (255, 255, 255, 255)
FRAME_LO = (85, 85, 85, 255)
WELL_FILL = (139, 139, 139, 255)
WELL_HI = (255, 255, 255, 255)
WELL_LO = (55, 55, 55, 255)


def draw_beveled_frame(d: ImageDraw.ImageDraw) -> None:
    """Fill GUI area and draw a 4-px beveled border around the active GUI rect."""
    d.rectangle((0, 0, GUI_W - 1, GUI_H - 1), fill=FRAME_FILL)
    # Outer 1-px black border
    d.rectangle((0, 0, GUI_W - 1, GUI_H - 1), outline=(0, 0, 0, 255))
    # Inner bevel: light on top/left (rows 1..3), dark on bottom/right
    for offset in (1, 2, 3):
        # top/left highlight
        d.line((offset, offset, GUI_W - 1 - offset, offset), fill=FRAME_HI)
        d.line((offset, offset, offset, GUI_H - 1 - offset), fill=FRAME_HI)
        # bottom/right shadow
        d.line((offset, GUI_H - 1 - offset, GUI_W - 1 - offset, GUI_H - 1 - offset), fill=FRAME_LO)
        d.line((GUI_W - 1 - offset, offset, GUI_W - 1 - offset, GUI_H - 1 - offset), fill=FRAME_LO)


def draw_slot_well(d: ImageDraw.ImageDraw, slot_x: int, slot_y: int) -> None:
    """Draw an 18x18 slot well centered so a 16x16 item lands at (slot_x, slot_y)."""
    x0 = slot_x - 1
    y0 = slot_y - 1
    x1 = slot_x + 16
    y1 = slot_y + 16
    # Well fill: 16x16 inner
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=WELL_FILL)
    # Dark "inset" border: top + left
    d.line((x0, y0, x1 - 1, y0), fill=WELL_LO)
    d.line((x0, y0, x0, y1 - 1), fill=WELL_LO)
    # Highlight border: bottom + right
    d.line((x0 + 1, y1, x1, y1), fill=WELL_HI)
    d.line((x1, y0 + 1, x1, y1), fill=WELL_HI)


def main() -> None:
    img = Image.new("RGBA", (IMG_W, IMG_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_beveled_frame(d)

    # Board: 3 rows of 5 slots starting at x=64, y=18/40/62 (matches SheriffMenu).
    # Left margin x=8..62 holds the row labels ("Maps" / "Seals" / "Rewards").
    for col in range(5):
        for row_y in (18, 40, 62):
            draw_slot_well(d, 64 + col * 18, row_y)

    # Always-available book slot at (160, 18) — top-right, separated from the maps row.
    draw_slot_well(d, 160, 18)

    # Player main inv centered: x=17, y=98/116/134 (20-px gap below reward row).
    for row in range(3):
        for col in range(9):
            draw_slot_well(d, 17 + col * 18, 98 + row * 18)

    # Hotbar at y=158.
    for col in range(9):
        draw_slot_well(d, 17 + col * 18, 158)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, format="PNG", optimize=True)
    print(f"Wrote {OUT.relative_to(REPO_ROOT)}  ({IMG_W}x{IMG_H}, GUI area {GUI_W}x{GUI_H})")


if __name__ == "__main__":
    main()
