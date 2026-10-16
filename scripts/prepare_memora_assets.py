"""Package owner-supplied artwork; preserve exact originals in docs/branding/memora.

Development-only dependency: Pillow. No recoloring, redrawing or AI generation.
"""
from pathlib import Path
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
ORIGINALS = ROOT / "docs/branding/memora/originals"
RES = ROOT / "app/src/main/res"
DRAWABLE = RES / "drawable-nodpi"
DRAWABLE.mkdir(parents=True, exist_ok=True)


def wordmark(source: str, target: str) -> None:
    image = Image.open(ORIGINALS / source).convert("RGBA")
    alpha = image.getchannel("A").point(lambda value: 255 if value > 64 else 0)
    # Ignore isolated low-opacity export speckles; retain a generous artwork gutter.
    rows = [y for y in range(image.height)
            if alpha.crop((0, y, image.width, y + 1)).histogram()[255] > 20]
    columns = [x for x in range(image.width)
               if alpha.crop((x, 0, x + 1, image.height)).histogram()[255] > 20]
    gutter = 32
    image = image.crop((max(0, min(columns) - gutter), max(0, min(rows) - gutter),
                        min(image.width, max(columns) + 1 + gutter),
                        min(image.height, max(rows) + 1 + gutter)))
    image.thumbnail((1000, 300), Image.Resampling.LANCZOS)
    image.save(DRAWABLE / target, optimize=True)


wordmark("wordmark-dark-original.png", "memora_wordmark_dark.png")
wordmark("wordmark-light-original.png", "memora_wordmark_light.png")

launcher = Image.open(ORIGINALS / "launcher-original.png").convert("RGB")
channels = launcher.split()
visible = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
bounds = visible.point(lambda value: 255 if value > 24 else 0).getbbox()
assert bounds is not None
left, top, right, bottom = bounds
size = min(max(right - left, bottom - top) + 16, min(launcher.size))
center_x, center_y = (left + right) // 2, (top + bottom) // 2
left = max(0, min(center_x - size // 2, launcher.width - size))
top = max(0, min(center_y - size // 2, launcher.height - size))
launcher = launcher.crop((left, top, left + size, top + size))
launcher.resize((512, 512), Image.Resampling.LANCZOS).save(DRAWABLE / "memora_launcher.png", optimize=True)
for density, pixels in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = RES / f"mipmap-{density}"
    for name in ["ic_launcher.png", "ic_launcher_round.png"]:
        launcher.resize((pixels, pixels), Image.Resampling.LANCZOS).save(folder / name, optimize=True)
print("Prepared 3 drawable assets and 10 launcher density assets.")
