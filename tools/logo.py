"""Draws mcbot's logo and banner.

Everything here is painted on a 32x32 grid — the size of a Minecraft block texture — and scaled up
with nearest-neighbour, so every edge stays a hard pixel at 40px and at 512px alike. Nothing is
resampled, so there is no size at which the artwork goes soft.

The subject is one object filling the frame, which is what the mod icons that survive being shrunk to
a 40px search result all have in common: Sodium is a flame, Jade is a letter, Simple Voice Chat is a
microphone. A scene at that size is a smudge.

    python tools/logo.py

Writes:
    src/main/resources/assets/mcbot/icon.png   128px, ships in the jar for Mod Menu
    branding/icon-512.png                      512px, the Modrinth project icon
    branding/banner-1280x640.png               the gallery / social-preview banner
"""

import os

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- palette

GROUND = (35, 45, 61, 255)      # slate, light enough to stay a tile on Modrinth's dark theme
INK = (12, 16, 22, 255)         # the outline; near-black, never pure black
BODY = (124, 227, 78, 255)      # the route green the path renderer draws in
BODY_HI = (176, 245, 133, 255)  # lit top face
BODY_LO = (74, 165, 46, 255)    # shaded right face
VISOR = (17, 23, 31, 255)
EYE = (245, 255, 240, 255)
EYE_HI = (255, 255, 255, 255)

N = 32


def _fill(p, x0, y0, x1, y1, colour):
    for y in range(max(0, y0), min(N - 1, y1) + 1):
        for x in range(max(0, x0), min(N - 1, x1) + 1):
            p[x, y] = colour


def icon():
    """A waypoint marker that looks back at you — the two halves of the mod in one silhouette.

    A marker alone would be a map mod and a face alone would be a robot mod; the point of fusing them
    is that this is the only shape that is neither. The pixel-stepped taper is what keeps it
    Minecraft's rather than a generic map pin: the same staircase edge every block in the game has.
    """
    image = Image.new("RGBA", (N, N), GROUND)
    p = image.load()

    # Head, as large as the frame allows once the point below is accounted for.
    _fill(p, 4, 1, 27, 18, INK)
    _fill(p, 5, 2, 26, 17, BODY)
    _fill(p, 5, 2, 26, 4, BODY_HI)
    _fill(p, 25, 2, 26, 17, BODY_LO)

    # The point: one pixel narrower each side per row, which is what makes it read as a marker
    # rather than a badge. A shorter taper looks like a shield, and a shield means a different mod.
    left, right, y = 5, 26, 18
    while left < right:
        _fill(p, left - 1, y, right + 1, y, INK)
        _fill(p, left, y, right, y, BODY)
        _fill(p, right - 1, y, right, y, BODY_LO)
        left, right, y = left + 1, right - 1, y + 1
    _fill(p, left - 1, y, right + 1, y, INK)

    # Visor and eyes. Two blocks with a gap is the whole face: at 40px there is room for nothing
    # else, and two lit rectangles are still unmistakably looking at you.
    _fill(p, 7, 6, 24, 15, INK)
    _fill(p, 8, 7, 23, 14, VISOR)
    for x0 in (10, 17):
        _fill(p, x0, 9, x0 + 4, 12, EYE)
        _fill(p, x0, 9, x0 + 4, 9, EYE_HI)
    return image


# ---------------------------------------------------------------- wordmark

# Five letters is not worth a font dependency, and a real font next to pixel artwork looks borrowed.
# 1 is ink, 0 is ground; x-height letters are five rows and sit on the same baseline as the tall two.
GLYPHS = {
    "m": ["11111", "10101", "10101", "10101", "10101"],
    "c": ["01111", "10000", "10000", "10000", "01111"],
    "o": ["01110", "10001", "10001", "10001", "01110"],
    "b": ["10000", "10000", "11110", "10001", "10001", "10001", "11110"],
    "t": ["0100", "0100", "1111", "0100", "0100", "0100", "0011"],
}
WORDMARK_ROWS = 7


def wordmark(scale, colour):
    """Renders "mcbot" as pixels, on the same baseline, at an exact integer scale."""
    letters = [GLYPHS[c] for c in "mcbot"]
    width = sum(len(g[0]) for g in letters) + (len(letters) - 1)
    image = Image.new("RGBA", (width * scale, WORDMARK_ROWS * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    x = 0
    for glyph in letters:
        top = WORDMARK_ROWS - len(glyph)          # x-height letters drop to the baseline
        for row, bits in enumerate(glyph):
            for column, bit in enumerate(bits):
                if bit == "1":
                    left, up = (x + column) * scale, (top + row) * scale
                    draw.rectangle([left, up, left + scale - 1, up + scale - 1], fill=colour)
        x += len(glyph[0]) + 1
    return image


# Minecraft's own front-view proportions, in skin units: an 8x8 head, an 8x12 body, 4x12 arms and
# 4x12 legs. Getting these right is the difference between "a little Minecraft guy" and "a cartoon
# character standing in Minecraft".
#
# But proportions alone give the T-pose look. Minecraft's limbs *rotate about their joint*, and a
# rigid box turned on the diagonal is drawn in pixel art as stacked segments stepping sideways — so
# the raised arm is three segments, and the figure is caught mid-step rather than stood to attention.
# Each part: box, which tone, and whether it takes the lit top row (only the leading segment of a
# limb does, or the arm ends up striped).
HEAD = (6, 5, 13, 12)
TORSO = (6, 13, 13, 24)
FIGURE = [
    # the resting arm, swinging gently out at the bottom
    ((2, 13, 5, 18), "shirt", True),
    ((1, 19, 4, 24), "shirt", False),
    # the waving arm, stepping up and away from the shoulder
    ((14, 10, 17, 13), "shirt", False),
    ((16, 6, 19, 9), "shirt", False),
    ((18, 2, 21, 5), "shirt", True),
    (TORSO, "shirt", True),
    (HEAD, "head", True),
    # Two 4-wide legs filling the 8-wide torso exactly, touching, feet planted. Both earlier tries
    # broke this and both looked wrong for the same reason — the legs stopped belonging to the body.
    # A lifted leg shifted sideways reads as a dislocated hip, because in a *front* view a Minecraft
    # walk swings the legs forward and back and they never leave their own columns. And a gap between
    # them is not a gap: the outline pass fills it from both sides, so it renders as a black slot.
    # The only separation needed is the left leg's own shaded right column.
    ((6, 25, 9, 36), "trousers", True),
    ((10, 25, 13, 36), "trousers", True),
]

TONES = {
    "head": (BODY_HI, BODY, BODY_LO),            # the logo's own green, up top
    "shirt": ((120, 214, 80, 255), (94, 192, 60, 255), (58, 134, 36, 255)),
    "trousers": ((82, 164, 54, 255), (64, 142, 42, 255), (40, 96, 26, 255)),
}


def mascot():
    """The little one, waving — Minecraft's skin proportions, caught mid-stride.

    Same green and the same visor as the marker, so it reads as that logo with a body rather than as
    a second character. Three tones down the figure the way every skin is built — face, shirt,
    trousers — because the head and torso are both eight wide and nothing but colour tells them apart
    in a front view. An original figure, not a copy of anyone's mascot or mark: this goes on a public
    page, where a borrowed mark reads as an endorsement nobody gave.
    """
    W, H = 23, 38                 # one pixel of margin all round, for the outline to live in
    image = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    p = image.load()

    filled = set()
    for (x0, y0, x1, y1), tone, cap in FIGURE:
        hi, mid, lo = TONES[tone]
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                p[x, y] = hi if (cap and y == y0) else (lo if x == x1 else mid)
                filled.add((x, y))

    # The waving arm gets a dark left edge where it passes the head. Without it the two are green
    # boxes touching, and the face then looks off-centre in a head that appears twice as wide.
    for y in range(10, 14):
        p[14, y] = TONES["shirt"][2]

    # The outline goes outside the silhouette, never into it — an inner border would eat half of a
    # four-pixel arm.
    for x, y in list(filled):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if n not in filled and 0 <= n[0] < W and 0 <= n[1] < H:
                p[n] = INK

    # The face: the marker's visor, at the height Minecraft puts eyes.
    for y in range(8, 11):
        for x in range(7, 13):
            p[x, y] = VISOR
    for x0 in (7, 11):
        for y in (8, 9):
            p[x0, y], p[x0 + 1, y] = EYE, EYE
    return image


def _sans(size, bold=False):
    """Windows' UI font for the tagline — a clean sans beside a pixel wordmark, not competing."""
    for name in (("seguisb.ttf", "segoeui.ttf") if bold else ("segoeui.ttf",)):
        path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def banner(width=1280, height=640):
    """The wide version: the marker, the name, and one line saying what it does.

    A banner may be a scene where the icon may not — it is read at full size, once, and the extra
    width is dead space otherwise. So the terrain and the route the icon deliberately gave up appear
    here instead, kept dark enough that the wordmark still wins.
    """
    image = Image.new("RGB", (width, height), GROUND[:3])
    draw = ImageDraw.Draw(image)

    # A faint block lattice, so the ground is a world rather than a colour swatch.
    for x in range(0, width, 32):
        draw.line([(x, 0), (x, height)], fill=(42, 54, 72), width=1)
    for y in range(0, height, 32):
        draw.line([(0, y), (width, y)], fill=(42, 54, 72), width=1)

    # A low horizon on the same 32px lattice, rising left to right. Monotonic on purpose: a stepped
    # skyline that goes back down reads as floating slabs once each step is filled to the bottom.
    horizon = [(0, 576), (224, 544), (448, 512), (704, 480), (1024, 448)]
    for index, (left, top) in enumerate(horizon):
        right = horizon[index + 1][0] if index + 1 < len(horizon) else width
        draw.rectangle([left, top, right, height], fill=(44, 56, 74))
        draw.rectangle([left, top, right, top + 3], fill=(58, 74, 96))

    # The route climbing those steps — kept to the left half, under nothing, so the type stays clear.
    # Each block rests exactly on a surface rather than hovering near one. The lowest step is left
    # bare because that is where the little one stands, at the foot of its own route.
    for index in range(1, 3):
        left, top = horizon[index]
        for x in (left + 64, left + 128):
            draw.rectangle([x, top - 24, x + 23, top - 1], fill=(86, 148, 70))

    # The little one waves from the lowest step, under the marker: the only warm thing in a cold
    # picture, so the eye finds it last and is pleased rather than distracted. It went here rather
    # than beside the tagline because the corner it had there was 30px wide and looked like a squeeze.
    little = mascot()
    little = little.resize((little.width * 4, little.height * 4), Image.NEAREST)
    image.paste(little, (48, horizon[0][1] - little.height), little)

    mark = icon().resize((320, 320), Image.NEAREST)
    image.paste(mark, (160, 132), mark)

    name = wordmark(scale=16, colour=(255, 255, 255, 255))
    image.paste(name, (556, 202), name)

    draw.text((560, 202 + name.height + 34), "a pathfinding bot you can talk to",
              font=_sans(38), fill=(170, 187, 208))
    draw.text((560, 202 + name.height + 92), "FABRIC   ·   MINECRAFT 26.2   ·   CLIENT-SIDE",
              font=_sans(22, bold=True), fill=(124, 227, 78))
    return image


if __name__ == "__main__":
    art = icon()
    for size, path in ((128, "src/main/resources/assets/mcbot/icon.png"),
                       (512, "branding/icon-512.png")):
        art.resize((size, size), Image.NEAREST).save(path)
        print("wrote", path)
    banner().save("branding/banner-1280x640.png")
    print("wrote branding/banner-1280x640.png")
