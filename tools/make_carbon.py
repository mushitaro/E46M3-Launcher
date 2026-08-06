"""
Renders the launcher's carbon-fibre field exactly as the design specifies it.

The design draws the weave in CSS as six stacked linear-gradients over a 20x20
tile, on a layer that is oversized to 164% and rotated 45 degrees, then clipped
to the 1024x600 panel:

    background-image:
      linear-gradient( 27deg, cf1 5px, transparent 5px),
      linear-gradient(207deg, cf1 5px, transparent 5px),
      linear-gradient( 27deg, cf2 5px, transparent 5px),
      linear-gradient(207deg, cf2 5px, transparent 5px),
      linear-gradient( 90deg, cf3 10px, transparent 10px),
      linear-gradient(180deg, cf4 25%, cf5 25%, cf5 50%,
                             transparent 50%, transparent 75%, cf6 75%, cf6);
    background-position: 0 5px, 10px 0, 0 10px, 10px 5px, 0 0, 0 0;
    background-size: 20px 20px;

Why this is baked to a PNG rather than tiled at runtime: rotating a 20px lattice
by 45 degrees gives a screen-space period of 20*sqrt(2) = 28.284px, which is not
an integer. There is no small tile that repeats seamlessly, so any runtime
tiling would show a seam. The panel is exactly 1024x600 physical pixels, so one
full-size image is pixel-exact and decodes once.

The colour stops are hard (`colour 5px, transparent 5px`), so every layer is a
solid region — no interpolation is involved despite the gradient syntax.
"""
import math
import sys
from PIL import Image, ImageDraw, ImageFilter

W, H = 1024, 600
TILE = 20

# The design's own day tones spanned #07080a..#15171a: fourteen levels of
# near-black. On a desk that is a weave. Through a windscreen it is nothing —
# daylight reflected off the glass adds the same amount to every one of those
# tones, and a fourteen-level spread does not survive it, so the panel reads as
# a dead black rectangle rather than as a fascia that is switched on.
#
# The day set below is lifted about four times in luminance AND widened: base
# 0.009 to bright thread 0.030, a 3.3:1 spread instead of 4.4:1 on numbers too
# small to see. The keys were lifted with it (values/design.xml) so the console
# still sits ON the weave rather than in it — raising a background without
# raising what stands on it is how you lose the buttons instead of finding them.
#
# NIGHT is the design's, untouched. It never had the problem, and a bright
# fascia at night is a fault of its own.
DAY = dict(cf0="#15171b", cf1="#191c20", cf2="#2a2e35",
           cf3="#1c1f24", cf4="#1e2127", cf5="#16181c", cf6="#282c32")
NIGHT = dict(cf0="#040405", cf1="#060708", cf2="#0e1012",
             cf3="#070809", cf4="#08090a", cf5="#050506", cf6="#0d0e10")


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hard_stop_layer(size, angle_deg, stop_px, color, offset):
    """One `linear-gradient(Adeg, C Npx, transparent Npx)` on a `size` tile.

    CSS measures position along a gradient line through the box centre, running
    in direction (sin A, -cos A) in screen coordinates, whose length is
    |W sin A| + |H cos A|. Everything nearer than `stop_px` to the line's start
    takes the colour; the rest is transparent.
    """
    w = h = size
    a = math.radians(angle_deg)
    dx, dy = math.sin(a), -math.cos(a)
    line = abs(w * math.sin(a)) + abs(h * math.cos(a))
    cx, cy = w / 2.0, h / 2.0
    sx, sy = cx - dx * line / 2.0, cy - dy * line / 2.0

    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    r, g, b = rgb(color)
    ox, oy = offset
    for y in range(h):
        for x in range(w):
            # Undo the background-position shift before measuring.
            fx, fy = (x - ox) % w + 0.5, (y - oy) % h + 0.5
            t = (fx - sx) * dx + (fy - sy) * dy
            if t < stop_px:
                px[x, y] = (r, g, b, 255)
    return img


def vertical_bands(size, c4, c5, c6):
    """The 180deg layer: 0-25% c4, 25-50% c5, 50-75% clear, 75-100% c6."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    q = size / 4.0
    d.rectangle([0, 0, size, q - 1], fill=rgb(c4) + (255,))
    d.rectangle([0, q, size, 2 * q - 1], fill=rgb(c5) + (255,))
    d.rectangle([0, 3 * q, size, size], fill=rgb(c6) + (255,))
    return img


def build_tile(c):
    """Compose the six layers. In CSS the FIRST listed layer paints on top."""
    base = Image.new("RGBA", (TILE, TILE), rgb(c["cf0"]) + (255,))
    layers = [
        hard_stop_layer(TILE, 27, 5, c["cf1"], (0, 5)),
        hard_stop_layer(TILE, 207, 5, c["cf1"], (10, 0)),
        hard_stop_layer(TILE, 27, 5, c["cf2"], (0, 10)),
        hard_stop_layer(TILE, 207, 5, c["cf2"], (10, 5)),
        hard_stop_layer(TILE, 90, 10, c["cf3"], (0, 0)),
        vertical_bands(TILE, c["cf4"], c["cf5"], c["cf6"]),
    ]
    for layer in reversed(layers):        # bottom-most first
        base = Image.alpha_composite(base, layer)
    return base


def build(colors, out_path):
    tile = build_tile(colors)

    # The design oversizes the weave layer to 164% before rotating it, which is
    # enough in a browser but leaves bare diagonal corners here. To cover a WxH
    # canvas with a square rotated 45 degrees about its centre the side must be
    # at least (W+H)/sqrt(2); rounded up to a whole number of tiles so the
    # lattice stays aligned.
    side = math.ceil(((W + H) / math.sqrt(2) + 4 * TILE) / TILE) * TILE
    field = Image.new("RGBA", (side, side))
    for y in range(0, side, TILE):
        for x in range(0, side, TILE):
            field.paste(tile, (x, y))
    # Bicubic, not nearest: the browser antialiases the rotated layer, and
    # nearest-neighbour turns the weave into hard blocks that read far coarser
    # than the design.
    field = field.rotate(-45, resample=Image.BICUBIC, expand=True)

    canvas = Image.new("RGBA", (W, H), rgb(colors["cf0"]) + (255,))
    canvas.paste(field, ((W - field.width) // 2, (H - field.height) // 2), field)

    # radial-gradient(120% 82% at 30% -16%, rgba(255,255,255,.038), transparent 54%)
    sheen = Image.new("L", (W, H), 0)
    sp = sheen.load()
    ox, oy = 0.30 * W, -0.16 * H
    rx, ry = 1.20 * W, 0.82 * H
    for y in range(H):
        for x in range(W):
            t = math.hypot((x - ox) / rx, (y - oy) / ry) / 0.54
            if t < 1.0:
                sp[x, y] = int(255 * 0.038 * (1.0 - t))
    canvas = Image.composite(Image.new("RGBA", (W, H), (255, 255, 255, 255)), canvas, sheen)

    # box-shadow: inset 0 0 140px 50px rgba(0,0,0,.62)
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rectangle([50, 50, W - 51, H - 51], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(70))
    vig = Image.eval(mask, lambda v: int((255 - v) * 0.62))
    canvas = Image.composite(Image.new("RGBA", (W, H), (0, 0, 0, 255)), canvas, vig)

    # The weave uses a handful of near-black tones, so an indexed palette is
    # lossless in practice and roughly halves the file. Android still decodes to
    # a full bitmap, which is why the loader asks for RGB_565 (see CarbonBg.kt).
    out = canvas.convert("RGB").quantize(colors=64, method=Image.MEDIANCUT, dither=Image.NONE)
    out.save(out_path, optimize=True)
    print(f"{out_path}  {W}x{H}  field={side}x{side}")


if __name__ == "__main__":
    out_dir = sys.argv[1]
    build(DAY, f"{out_dir}/carbon_bg.png")
    build(NIGHT, f"{out_dir}/carbon_bg_night.png")
