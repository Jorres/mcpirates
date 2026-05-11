"""
build_placeholder_textures.py — emit hand-drawn placeholder PNGs for mcpirates assets.

Outputs:
    src/main/resources/assets/mcpirates/textures/block/bounty_board.png   (16x16)
    src/main/resources/assets/mcpirates/textures/item/captain_seal.png    (16x16)
    src/main/resources/assets/mcpirates/textures/item/furled_bounty.png   (16x16)
    src/main/resources/assets/mcpirates/textures/entity/villager/profession/sheriff.png  (64x64)

These are intentionally simple and visually distinct from any vanilla asset so we can
identify the placeholder at a glance and replace later. Re-run any time the design
parameters change.

The villager-profession texture is a 64×64 overlay rendered on top of the vanilla
villager body — same UV mapping as the cartographer profession texture. We tint a
"sheriff hat" (a red band on the head crown) and a "badge" patch on the torso so the
sheriff is identifiable across all villager body types.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "src" / "main" / "resources" / "assets" / "mcpirates" / "textures"


def make_bounty_board() -> Image.Image:
    """16x16 wooden plaque with a parchment square and a red X."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Plank base: dark oak-ish
    d.rectangle((0, 0, 15, 15), fill=(86, 58, 38, 255))
    # Plank vertical grain
    for x in (3, 7, 11):
        d.line((x, 0, x, 15), fill=(64, 42, 26, 255))
    # Parchment square (centered, 10x10)
    d.rectangle((3, 3, 12, 12), fill=(232, 211, 165, 255))
    d.rectangle((3, 3, 12, 12), outline=(168, 138, 90, 255))
    # Red X — the "wanted poster" mark
    d.line((5, 5, 10, 10), fill=(176, 28, 28, 255), width=1)
    d.line((10, 5, 5, 10), fill=(176, 28, 28, 255), width=1)
    # Small dark accents in corners to give the parchment "tacks"
    d.point((3, 3), fill=(48, 32, 16, 255))
    d.point((12, 3), fill=(48, 32, 16, 255))
    d.point((3, 12), fill=(48, 32, 16, 255))
    d.point((12, 12), fill=(48, 32, 16, 255))
    return img


def make_captain_seal() -> Image.Image:
    """16x16 red wax seal with a gold star at center."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Wax disc (rough circle in pixels)
    # Inner solid: rows 2..13 / cols 2..13 with corner trimming
    solid_red = (170, 26, 36, 255)
    dark_red = (122, 14, 22, 255)
    gold = (240, 196, 64, 255)
    dark_gold = (172, 132, 28, 255)
    pixels = []
    # Crude rounded square: skip the 4 corner pixels
    for y in range(2, 14):
        for x in range(2, 14):
            if (x, y) in {(2, 2), (13, 2), (2, 13), (13, 13)}:
                continue
            pixels.append((x, y))
    for x, y in pixels:
        d.point((x, y), fill=solid_red)
    # Darker rim — edge pixels of the disc
    for x, y in pixels:
        if x in (2, 13) or y in (2, 13):
            d.point((x, y), fill=dark_red)
        # extra corner softening
        if (x, y) in {(3, 2), (12, 2), (3, 13), (12, 13), (2, 3), (13, 3), (2, 12), (13, 12)}:
            d.point((x, y), fill=dark_red)
    # 5-pointed star at center (rendered as a simple 5x5 cross + diagonals)
    star_pts_gold = [
        (7, 5), (8, 5),                              # top
        (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7),  # mid bar
        (6, 8), (7, 8), (8, 8), (9, 8),
        (6, 9), (7, 9), (8, 9), (9, 9),
        (5, 10), (6, 10), (9, 10), (10, 10),         # legs
    ]
    for p in star_pts_gold:
        d.point(p, fill=gold)
    # Highlight on top-left of star
    d.point((7, 6), fill=dark_gold)
    d.point((8, 6), fill=dark_gold)
    return img


def make_furled_bounty() -> Image.Image:
    """16x16 rolled-up parchment scroll with a red wax seal at the centre."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    parchment = (232, 211, 165, 255)
    parchment_shade = (190, 168, 120, 255)
    parchment_dark = (148, 124, 80, 255)
    wax_red = (170, 26, 36, 255)
    wax_dark = (122, 14, 22, 255)

    # Scroll body: horizontal cylinder centred at y=8, spanning x=1..14.
    # Top edge (lit) at y=5..6, mid at y=7..10, bottom shade at y=11..12.
    for x in range(1, 15):
        d.point((x, 5), fill=parchment_shade)
        for y in range(6, 11):
            d.point((x, y), fill=parchment)
        d.point((x, 11), fill=parchment_shade)
        d.point((x, 12), fill=parchment_dark)

    # End caps: small darker rolls at x=0 and x=15 hinting at the curl.
    for y in range(5, 13):
        d.point((0, y), fill=parchment_dark)
        d.point((15, y), fill=parchment_dark)
    # Curl detail at the ends
    d.point((1, 5), fill=parchment_dark)
    d.point((1, 12), fill=parchment_dark)
    d.point((14, 5), fill=parchment_dark)
    d.point((14, 12), fill=parchment_dark)

    # Red wax seal in the middle to suggest "still sealed / unread".
    seal_pixels = [
        (7, 7), (8, 7),
        (6, 8), (7, 8), (8, 8), (9, 8),
        (6, 9), (7, 9), (8, 9), (9, 9),
        (7, 10), (8, 10),
    ]
    for p in seal_pixels:
        d.point(p, fill=wax_red)
    # Wax highlight to make it pop on the parchment
    d.point((7, 8), fill=wax_dark)
    d.point((8, 9), fill=wax_dark)

    return img


def make_sheriff_profession() -> Image.Image:
    """
    64x64 villager profession overlay.

    The vanilla villager profession texture is overlaid on top of the base biome-typed
    villager body in {@code minecraft:entity/villager/villager}. We follow the same UV
    layout as the cartographer texture:
        * head crown brim    → UV (0..32, 0..7) at original face Z
        * torso front        → UV (16..40, 20..32)
    Most of the canvas is transparent so the base body bleeds through. We paint:
        * a red 4-pixel-tall band around the head crown → "sheriff hat"
        * a small gold star on the torso              → "badge"

    These coordinates are approximate; the goal is "visually distinct sheriff", not
    "pixel-perfect alignment". The textures can be refined once we ship.
    """
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    sheriff_red = (148, 26, 32, 255)
    band_dark = (96, 12, 18, 255)
    badge_gold = (240, 196, 64, 255)
    badge_dark = (172, 132, 28, 255)

    # Head-band: vanilla villager head crown texture spans U=0..32, V=0..7 (the four
    # 8-wide faces stacked horizontally in the head's first row). Painting a band 1
    # pixel tall along V=4..5 over U=0..32 gives a horizontal stripe around the head.
    for u in range(0, 32):
        d.point((u, 4), fill=sheriff_red)
        d.point((u, 5), fill=sheriff_red)
        d.point((u, 6), fill=band_dark)

    # Torso badge: villager torso texture spans U=16..40, V=20..32 (front face is
    # roughly U=20..28, V=20..32). A small star on the chest.
    star_origin_u = 23
    star_origin_v = 25
    star_offsets = [
        (1, 0), (2, 0),
        (0, 2), (1, 2), (2, 2), (3, 2), (4, 2),
        (1, 3), (2, 3), (3, 3),
        (1, 4), (2, 4), (3, 4),
        (0, 5), (1, 5), (3, 5), (4, 5),
    ]
    for du, dv in star_offsets:
        d.point((star_origin_u + du, star_origin_v + dv), fill=badge_gold)
    # Star center highlight
    d.point((star_origin_u + 2, star_origin_v + 3), fill=badge_dark)

    return img


def main() -> None:
    targets = [
        (ASSETS / "block" / "bounty_board.png", make_bounty_board),
        (ASSETS / "item" / "captain_seal.png", make_captain_seal),
        (ASSETS / "item" / "furled_bounty.png", make_furled_bounty),
        (ASSETS / "entity" / "villager" / "profession" / "sheriff.png", make_sheriff_profession),
    ]
    for path, fn in targets:
        path.parent.mkdir(parents=True, exist_ok=True)
        img = fn()
        img.save(path, format="PNG", optimize=True)
        print(f"Wrote {path.relative_to(REPO_ROOT)}  ({img.width}x{img.height})")


if __name__ == "__main__":
    main()
