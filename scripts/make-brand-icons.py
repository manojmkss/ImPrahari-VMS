#!/usr/bin/env python3
"""Make every ImPrahari icon from the logo (docs/brand/imprahari-logo.png).

    python3 scripts/make-brand-icons.py [logo.png]

Writes the web app's icons, the Android app's launcher/themed/sign-in images,
the Home Assistant brand icons and the GitHub social preview. Needs Pillow.
The emblem's box is found by itself: the bright area on the left of the logo.
Replace docs/brand/imprahari-logo.png (same layout: emblem left, wordmark
right, on a plain background) and run this again.
"""

import os
import sys

from PIL import Image, ImageChops, ImageFilter

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "docs", "brand", "imprahari-logo.png")
logo = Image.open(src).convert("RGB")
NAVY = logo.getpixel((10, 10))  # the logo's own background


def _emblem_box(img):
    """Square around the bright area left of the wordmark (the gap between them is background)."""
    gray = ImageChops.difference(img, Image.new("RGB", img.size, NAVY)).convert("L").point(lambda v: 255 if v > 60 else 0)
    w, h = gray.size
    cols = [any(gray.getpixel((x, y)) for y in range(0, h, 2)) for x in range(w)]
    x0 = cols.index(True)
    x1 = x0
    gap = 0
    for x in range(x0, w):  # walk right until a wide empty strip: the emblem ends there
        if cols[x]:
            x1, gap = x, 0
        else:
            gap += 1
            if gap > w // 60:
                break
    y0, y1 = gray.crop((x0, 0, x1 + 1, h)).getbbox()[1::2]
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    half = max(x1 - x0, y1 - y0) // 2 + 15
    return cx - half, cy - half, cx + half, cy + half


emblem = logo.crop(_emblem_box(logo))
diff = ImageChops.difference(emblem, Image.new("RGB", emblem.size, NAVY)).convert("L")
mask = diff.point(lambda v: 0 if v < 40 else min(255, (v - 40) * 3)).filter(ImageFilter.MaxFilter(3))


def on_navy(size, fill):
    """The emblem filling `fill` of a `size` square, centred on the logo's background."""
    canvas = Image.new("RGB", (size, size), NAVY)
    inner = max(1, round(size * fill))
    img = emblem.resize((inner, inner), Image.LANCZOS)
    if size <= 48:  # tiny icons: sharpen so the shield edge survives
        img = img.filter(ImageFilter.UnsharpMask(radius=1, percent=120, threshold=2))
    canvas.paste(img, ((size - inner) // 2, (size - inner) // 2))
    return canvas


def silhouette(size, fill):
    """The emblem's shape in white, for places that take a shape only."""
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    inner = max(1, round(size * fill))
    shape = Image.new("RGBA", (inner, inner), (255, 255, 255, 255))
    shape.putalpha(mask.resize((inner, inner), Image.LANCZOS))
    canvas.paste(shape, ((size - inner) // 2, (size - inner) // 2), shape)
    return canvas


def out(*parts):
    path = os.path.join(REPO, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


# Web app. "any" icons nearly fill; maskable ones stay inside the 80% safe circle.
for name, size, fill in [
    ("favicon-16", 16, 1.0), ("favicon-32", 32, 1.0), ("apple-touch-icon", 180, 0.92),
    ("icon-192", 192, 0.96), ("icon-512", 512, 0.96),
    ("icon-192-maskable", 192, 0.70), ("icon-512-maskable", 512, 0.70),
]:
    on_navy(size, fill).save(out("frontend", "public", "icons", f"{name}.png"), optimize=True)
silhouette(96, 0.92).save(out("frontend", "public", "icons", "badge-96.png"), optimize=True)
on_navy(48, 1.0).save(out("frontend", "public", "favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)])

# Android: adaptive icon layers are 108dp with the middle 66dp always shown -> 60%.
for density, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
    res = ("android", "app", "src", "main", "res", f"mipmap-{density}")
    on_navy(px, 0.60).save(out(*res, "ic_launcher_foreground.png"), optimize=True)
    silhouette(px, 0.60).save(out(*res, "ic_launcher_monochrome.png"), optimize=True)
on_navy(288, 0.96).save(out("android", "app", "src", "main", "res", "drawable-nodpi", "logo_emblem.png"), optimize=True)

# Home Assistant brands format, the emblem alone, and GitHub's social preview.
on_navy(256, 0.96).save(out("homeassistant", "brand", "icon.png"), optimize=True)
on_navy(512, 0.96).save(out("homeassistant", "brand", "icon@2x.png"), optimize=True)
on_navy(512, 0.96).save(out("docs", "brand", "imprahari-emblem-512.png"), optimize=True)
social = Image.new("RGB", (1280, 640), NAVY)
w = 1240
h = round(logo.height * w / logo.width)
social.paste(logo.resize((w, h), Image.LANCZOS), ((1280 - w) // 2, (640 - h) // 2))
social.save(out("docs", "brand", "imprahari-social-preview.png"), optimize=True)
print(f"Made the icons from {src} (emblem box {_emblem_box(logo)}, background #{'%02X%02X%02X' % NAVY})")
