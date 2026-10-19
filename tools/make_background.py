"""Render the Nocturne home background (light silver/teal blobs, a glass orb, fine grain) as a 1920x1080 JPEG.

Pre-rendered so the launcher draws one bitmap instead of running blur on a 1-2 GB Fire Stick.
Usage: python3 tools/make_background.py launcher/app/src/main/res/drawable-nodpi/nocturne_bg.jpg  (needs numpy and pillow)
"""
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
BASE = (243, 245, 246)


def blob(canvas, box, color, alpha, blur):
    layer = Image.new("RGBA", (W, H), color + (0,))
    d = ImageDraw.Draw(layer)
    d.ellipse(box, fill=color + (int(255 * alpha),))
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer)


def main(out):
    img = Image.new("RGBA", (W, H), BASE + (255,))
    # Soft colour fields (teal mood from the mockup).
    blob(img, (-260, 120, 740, 820), (158, 214, 209), 0.70, 150)
    blob(img, (560, -280, 1460, 320), (205, 213, 219), 0.80, 140)
    blob(img, (1220, 460, 2080, 1000), (127, 195, 190), 0.60, 160)
    blob(img, (420, 300, 1500, 660), (255, 255, 255), 0.85, 110)

    # Glass orb bleeding off the top right: radial shading, bright rim, specular streaks.
    cx, cy, r = 1600, 280, 580
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    inside = dist <= 1.0
    # light from lower left inside the sphere, darker toward the rim
    lx, ly = cx - 0.4 * r, cy + 0.4 * r
    dl = np.sqrt((xx - lx) ** 2 + (yy - ly) ** 2) / r
    shade = np.clip(1.0 - dl, 0, 1)
    arr = np.array(img).astype(np.float32)
    white = np.array([255, 255, 255], np.float32)
    grey = np.array([120, 135, 145], np.float32)
    teal = np.array([14, 124, 125], np.float32)
    a_white = (0.55 * shade ** 1.5) * inside
    a_grey = (0.22 * np.clip((dist - 0.55) / 0.45, 0, 1) ** 2) * inside
    a_teal = (0.10 * np.clip((dist - 0.7) / 0.3, 0, 1)) * inside * np.clip((cx - xx) / (0.6 * r) + 0.5, 0, 1)
    for a, col in ((a_grey, grey), (a_teal, teal), (a_white, white)):
        arr[..., :3] = arr[..., :3] * (1 - a[..., None]) + col * a[..., None]
    # rim: thin bright ring plus soft outer shadow
    rim = np.exp(-((dist - 1.0) * r / 2.2) ** 2) * 0.9
    arr[..., :3] = arr[..., :3] * (1 - rim[..., None]) + white * rim[..., None]
    shadow = np.exp(-((dist - 1.04) * r / 40.0) ** 2) * 0.10 * (dist > 1.0)
    arr[..., :3] = arr[..., :3] * (1 - shadow[..., None]) + grey * shadow[..., None]
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")

    streak = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    d = ImageDraw.Draw(streak)
    d.ellipse((1080, 470, 1500, 540), fill=(255, 255, 255, 215))
    streak = streak.rotate(52, center=(1290, 505)).filter(ImageFilter.GaussianBlur(18))
    img.alpha_composite(streak)
    glint = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    d = ImageDraw.Draw(glint)
    d.ellipse((1150, 330, 1310, 352), fill=(255, 255, 255, 255))
    glint = glint.rotate(52, center=(1230, 341)).filter(ImageFilter.GaussianBlur(3))
    img.alpha_composite(glint)

    # Fine monochrome grain (also hides JPEG banding in the soft gradients).
    rgb = np.array(img.convert("RGB")).astype(np.float32)
    rng = np.random.default_rng(7)
    noise = rng.normal(0, 3.2, (H, W, 1)).astype(np.float32)
    rgb = np.clip(rgb + noise, 0, 255).astype(np.uint8)
    Image.fromarray(rgb, "RGB").save(out, quality=88, optimize=True, progressive=True)


if __name__ == "__main__":
    main(sys.argv[1])
