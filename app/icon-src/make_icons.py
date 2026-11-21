"""Builds the Android adaptive launcher icon layers from the flat icon renders.

    python3 icon-src/make_icons.py        (run from app/, needs Pillow)

icon-light.png -> drawable-*/ic_launcher_foreground.png   (default icon)
                  drawable-*/ic_launcher_monochrome.png   (Android 13+ themed icons)
icon-dark.png  -> drawable-night-*/ic_launcher_foreground.png
Background colours live in values[-night]/ic_launcher_colors.xml.
"""
from pathlib import Path
from statistics import median

from PIL import Image

HERE = Path(__file__).parent
RES = HERE.parent / "src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
CANVAS_DP = 108  # adaptive icon layer size
GLYPH_DP = 44    # glyph box; leaves breathing room inside the 66dp safe zone


def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def extract(src: Path):
    """Returns (glyph colour, RGBA glyph cropped to a square), alpha from distance to the background."""
    im = Image.open(src).convert("RGB")
    w, h = im.size
    px = im.load()
    bg = px[20, 20]
    d = [[dist(px[x, y], bg) for x in range(w)] for y in range(h)]
    flat = sorted(v for row in d for v in row)
    solid = flat[int(len(flat) * 0.995)]  # glyph interior distance, ignoring outliers
    fg = tuple(int(median(c)) for c in zip(*(px[x, y] for y in range(0, h, 2) for x in range(0, w, 2)
                                              if d[y][x] > solid * 0.9)))
    lo = solid * 0.08
    xs = [x for y in range(h) for x in range(w) if d[y][x] > solid * 0.3]
    ys = [y for y in range(h) for x in range(w) if d[y][x] > solid * 0.3]
    cx, cy = (min(xs) + max(xs)) // 2, (min(ys) + max(ys)) // 2
    side = max(max(xs) - min(xs), max(ys) - min(ys)) + 8
    alpha = Image.new("L", (w, h))
    alpha.putdata([max(0, min(255, round((v - lo) / (solid - lo) * 255))) for row in d for v in row])
    box = (cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side)
    return fg, alpha.crop(box)


def write(alpha: Image.Image, colour, folder: str, name: str):
    glyph = Image.new("RGBA", alpha.size, colour + (0,))
    glyph.putalpha(alpha)
    for dens, scale in DENSITIES.items():
        canvas, g = round(CANVAS_DP * scale), round(GLYPH_DP * scale)
        out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        out.paste(glyph.resize((g, g), Image.LANCZOS), ((canvas - g) // 2,) * 2)
        path = RES / f"{folder}-{dens}" / f"{name}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        out.save(path, optimize=True)
    print(f"{folder}/{name}: #{'%02x%02x%02x' % colour}")


light_fg, light_alpha = extract(HERE / "icon-light.png")
dark_fg, dark_alpha = extract(HERE / "icon-dark.png")
write(light_alpha, light_fg, "drawable", "ic_launcher_foreground")
write(light_alpha, (255, 255, 255), "drawable", "ic_launcher_monochrome")
write(dark_alpha, dark_fg, "drawable-night", "ic_launcher_foreground")
