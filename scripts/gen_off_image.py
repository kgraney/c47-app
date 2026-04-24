#!/usr/bin/env python3
# Generate a Floyd-Steinberg-dithered 400x240 NYC skyline as a hardcoded
# C byte array in lcd_buffer native layout.
#
# lcd_buffer row/byte layout (see c47-android/hal/lcd.c):
#   - 240 rows, 50 bytes of pixel data per row
#   - byte 0 of a row encodes the RIGHTMOST 8 pixels, byte 49 the LEFTMOST
#   - within a byte, LSB = rightmost pixel
#   - bit value 1 = dark pixel, 0 = parchment
#
# Writes the array to stdout; redirect to a file.

from PIL import Image, ImageDraw
import random
import math

W, H = 400, 240


def make_scene():
    img = Image.new("L", (W, H), 220)
    px = img.load()

    # Sky: vertical gradient, darker at top, with subtle horizontal banding.
    horizon_y = 176
    for y in range(horizon_y):
        t = y / (horizon_y - 1)
        # 70 (top) -> 205 (just above horizon)
        g = int(70 + t * 135)
        # gentle cloud band
        band = int(12 * math.sin(y * 0.09)) if 20 < y < 110 else 0
        for x in range(W):
            # slow horizontal variation
            h = int(8 * math.sin(x * 0.018 + y * 0.02))
            px[x, y] = max(0, min(255, g + band + h))

    # A big soft cloud sweeping across upper-middle sky.
    rnd = random.Random(1)
    for _ in range(220):
        cx = rnd.randint(40, W - 40)
        cy = rnd.randint(30, 110)
        r = rnd.randint(8, 22)
        lift = rnd.randint(12, 30)
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                d2 = dx * dx + dy * dy
                if d2 <= r * r:
                    x, y = cx + dx, cy + dy
                    if 0 <= x < W and 0 <= y < horizon_y:
                        strength = 1.0 - d2 / (r * r)
                        v = px[x, y] + int(lift * strength)
                        px[x, y] = min(255, v)

    # Stars: bright pinpoints.
    for _ in range(70):
        x = rnd.randint(0, W - 1)
        y = rnd.randint(2, 90)
        px[x, y] = 255
        if rnd.random() < 0.4 and x + 1 < W:
            px[x + 1, y] = 250

    # Moon: bright disc with a dimmer crescent-shadow disc.
    mcx, mcy, mr = 332, 42, 18
    for dy in range(-mr, mr + 1):
        for dx in range(-mr, mr + 1):
            d2 = dx * dx + dy * dy
            if d2 <= mr * mr:
                x, y = mcx + dx, mcy + dy
                if 0 <= x < W and 0 <= y < H:
                    edge = 1.0 - d2 / (mr * mr)
                    px[x, y] = min(255, int(215 + 40 * edge))
    scx, scy, sr = 344, 36, 15
    for dy in range(-sr, sr + 1):
        for dx in range(-sr, sr + 1):
            if dx * dx + dy * dy <= sr * sr:
                x, y = scx + dx, scy + dy
                if 0 <= x < W and 0 <= y < H:
                    # Cut out: bring back toward sky tone.
                    px[x, y] = max(90, px[x, y] - 130)
    # Moon glow halo
    for r in range(mr + 1, mr + 10):
        a = (mr + 10 - r) / 10
        for th in range(0, 360, 3):
            x = mcx + int(r * math.cos(math.radians(th)))
            y = mcy + int(r * math.sin(math.radians(th)))
            if 0 <= x < W and 0 <= y < H:
                v = px[x, y] + int(20 * a)
                px[x, y] = min(255, v)

    draw = ImageDraw.Draw(img)

    # Distant horizon buildings — faint, light gray, dithers into texture.
    rnd2 = random.Random(11)
    x = 0
    while x < W:
        bw = rnd2.randint(6, 18)
        bh = rnd2.randint(6, 22)
        top = 176 - bh
        draw.rectangle([x, top, x + bw, 180], fill=165)
        # A couple of bright windows.
        for _ in range(rnd2.randint(0, 3)):
            wx = rnd2.randint(x + 1, x + bw - 2)
            wy = rnd2.randint(top + 2, 178)
            draw.point([wx, wy], fill=230)
        x += bw + rnd2.randint(0, 2)

    # Mid-depth buildings.
    rnd3 = random.Random(27)
    mid = []  # (x, w, top, fill)
    x = -4
    while x < W + 4:
        bw = rnd3.randint(14, 36)
        bh = rnd3.randint(24, 78)
        top = 200 - bh
        fill = rnd3.randint(55, 95)
        mid.append((x, bw, top, fill))
        draw.rectangle([x, top, x + bw, 204], fill=fill)
        x += bw + rnd3.randint(-2, 4)

    # Window grids on mid-depth buildings (lit = brighter dots).
    rnd4 = random.Random(31)
    for (bx, bw, top, fill) in mid:
        if bw < 12:
            continue
        for wy in range(top + 4, 200, 5):
            for wx in range(bx + 2, bx + bw - 2, 4):
                if rnd4.random() < 0.55:
                    bright = rnd4.randint(170, 230)
                    draw.rectangle([wx, wy, wx + 1, wy + 1], fill=bright)

    # Foreground skyline landmarks: One WTC (left), Chrysler, Empire State,
    # Hearst, BoA. Each is drawn mostly dark (fill ~25) with a dense pattern
    # of window lights so the dither rendering reads as detailed silhouettes.
    rnd5 = random.Random(47)

    def tall(x, w, top, base=218, fill=28, lit=0.55):
        draw.rectangle([x, top, x + w, base], fill=fill)
        for wy in range(top + 5, base - 3, 5):
            for wx in range(x + 2, x + w - 2, 4):
                if rnd5.random() < lit:
                    b = rnd5.randint(170, 235)
                    draw.rectangle([wx, wy, wx + 1, wy + 2], fill=b)

    # One World Trade Center (far left) — tapered shaft + long antenna.
    wtc_cx = 60
    for i, (top, hh) in enumerate([(198, 20), (176, 22), (152, 24), (126, 26), (96, 30)]):
        step = i  # narrows going up
        draw.rectangle(
            [wtc_cx - 18 + step, top, wtc_cx + 18 - step, top + hh], fill=28
        )
    tall(wtc_cx - 14, 28, 94, base=124, fill=28, lit=0.5)
    # pyramid top
    for i in range(16):
        w = 18 - i
        y = 78 - i
        draw.rectangle([wtc_cx - w, y, wtc_cx + w, y + 1], fill=28)
    # antenna
    draw.rectangle([wtc_cx - 1, 28, wtc_cx + 1, 78], fill=45)

    # Chrysler (tapered crown).
    cx = 128
    draw.rectangle([cx - 22, 100, cx + 22, 218], fill=28)
    tall(cx - 22, 44, 100, base=218, fill=28, lit=0.58)
    for i, (yw, hh) in enumerate([(90, 12), (78, 10), (68, 8), (60, 6), (54, 4)]):
        draw.rectangle([cx - (18 - i * 3), yw, cx + (18 - i * 3), yw + hh], fill=28)
    draw.rectangle([cx - 1, 30, cx + 1, 54], fill=45)

    # Empire State Building.
    ex = 198
    draw.rectangle([ex - 30, 76, ex + 30, 218], fill=28)
    draw.rectangle([ex - 26, 64, ex + 26, 76], fill=28)
    draw.rectangle([ex - 16, 46, ex + 16, 64], fill=28)
    draw.rectangle([ex - 8, 26, ex + 8, 46], fill=28)
    draw.rectangle([ex - 2, 8, ex + 2, 26], fill=40)
    # Lots of window detail.
    for wy in range(82, 215, 5):
        for wx in range(ex - 28, ex + 28, 4):
            if rnd5.random() < 0.55:
                b = rnd5.randint(170, 235)
                draw.rectangle([wx, wy, wx + 1, wy + 2], fill=b)
    for wy in range(50, 64, 4):
        for wx in range(ex - 14, ex + 14, 4):
            if rnd5.random() < 0.55:
                draw.rectangle([wx, wy, wx + 1, wy + 1], fill=210)

    # Hearst Tower (diagrid glass) — chamfered apex.
    hx = 258
    draw.rectangle([hx - 14, 126, hx + 14, 218], fill=28)
    # Diagrid diagonals — bright lines crossing the facade.
    for d in range(-20, 100, 6):
        draw.line([(hx - 14, 126 + d), (hx + 14, 126 + d + 28)], fill=160, width=1)
    for d in range(-20, 100, 6):
        draw.line([(hx + 14, 126 + d), (hx - 14, 126 + d + 28)], fill=160, width=1)
    # Mask diagonals outside building.
    for y in range(126, 218):
        for xx in range(hx - 14, hx + 14):
            pass  # already bounded by line drawing overrun outside

    # Bank of America Tower (stepped shaft + tall spire).
    bax = 300
    draw.rectangle([bax - 18, 100, bax + 18, 218], fill=28)
    draw.rectangle([bax - 14, 78, bax + 14, 100], fill=28)
    draw.rectangle([bax - 8, 54, bax + 8, 78], fill=28)
    draw.rectangle([bax - 1, 14, bax + 1, 54], fill=45)
    tall(bax - 18, 36, 100, base=218, fill=28, lit=0.55)
    for wy in range(82, 100, 4):
        for wx in range(bax - 12, bax + 12, 4):
            if rnd5.random() < 0.55:
                draw.rectangle([wx, wy, wx + 1, wy + 1], fill=205)

    # Cluster of mid-tall buildings filling the right edge.
    for (bx, bw, top) in [(344, 20, 150), (362, 28, 126), (382, 18, 170)]:
        draw.rectangle([bx, top, bx + bw, 218], fill=32)
        for wy in range(top + 4, 216, 5):
            for wx in range(bx + 2, bx + bw - 2, 4):
                if rnd5.random() < 0.55:
                    draw.rectangle([wx, wy, wx + 1, wy + 2], fill=200)

    # Brooklyn Bridge-ish suspension on the far right edge (cables arcing).
    base_y = 212
    tower_y = 168
    # towers
    draw.rectangle([0, tower_y, 3, base_y], fill=40)
    # arch cable
    for i in range(0, 92):
        t = i / 91
        cy = int(tower_y + (1 - math.sin(math.pi * t)) * 10 + 2)
        # tapering so it sits behind foreground stuff
        if cy < base_y:
            px[i, cy] = min(px[i, cy], 80)
    # suspenders
    for i in range(4, 92, 4):
        t = (i) / 91
        cy = int(tower_y + (1 - math.sin(math.pi * t)) * 10 + 2)
        for y in range(cy, min(base_y, cy + 12)):
            if 0 <= i < W and 0 <= y < H:
                px[i, y] = min(px[i, y], 110)

    # Water: medium tone, then add streaky vertical reflections of the
    # building silhouettes, plus horizontal ripples.
    water_top = 218
    for y in range(water_top, H):
        for x in range(W):
            px[x, y] = 95

    # Mirror buildings downward with fading reflection.
    reflect_src = img.crop((0, 150, W, 218))
    rw, rh = reflect_src.size
    refl = reflect_src.transpose(Image.FLIP_TOP_BOTTOM)
    refl_pixels = refl.load()
    for dy in range(min(rh, H - water_top)):
        y = water_top + dy
        fade = 1.0 - dy / (H - water_top)  # bright near surface, darker deeper
        for x in range(W):
            src = refl_pixels[x, dy]
            # Blend toward water base (95).
            v = int(95 + (src - 95) * 0.35 * fade)
            px[x, y] = max(40, min(220, v))

    # Horizontal ripples — bright and dark dashes interleaved.
    rr = random.Random(71)
    for y in range(water_top, H):
        period = 3 + (y - water_top) // 4
        for x in range(0, W, period):
            if rr.random() < 0.55:
                L = rr.randint(1, 4)
                gray = 185 if rr.random() < 0.6 else 55
                for k in range(L):
                    if x + k < W:
                        px[x + k, y] = gray

    # Distant small boat silhouettes on the water.
    for bx, by, bw in [(40, 224, 10), (190, 230, 14), (320, 226, 12)]:
        draw.rectangle([bx, by, bx + bw, by + 3], fill=25)
        draw.rectangle([bx + bw // 2, by - 3, bx + bw // 2 + 1, by], fill=40)

    # Bird silhouettes.
    for (bx, by) in [(100, 70), (112, 76), (144, 62), (260, 54), (276, 60)]:
        draw.point([bx, by], fill=30)
        draw.point([bx + 1, by - 1], fill=30)
        draw.point([bx + 2, by], fill=30)
        draw.point([bx + 3, by - 1], fill=30)
        draw.point([bx + 4, by], fill=30)

    return img


def dither_and_pack(img):
    bw = img.convert("1", dither=Image.FLOYDSTEINBERG)
    pix = bw.load()
    rows = []
    for y in range(H):
        row = [0] * 50
        for x in range(W):
            # In '1' mode: 0 = black, 255 = white. Dark bit = 1.
            if pix[x, y] == 0:
                xr = W - 1 - x
                byte_i = xr >> 3
                bit = 1 << (xr & 7)
                row[byte_i] |= bit
        rows.append(row)
    return rows, bw


def emit_c(rows):
    print("// Auto-generated by scripts/gen_off_image.py -- do not hand-edit.")
    print("// 400x240 Floyd-Steinberg-dithered NYC skyline in native lcd_buffer")
    print("// layout: 240 rows x 50 bytes, byte 0 = rightmost 8 px (LSB-first),")
    print("// bit value 1 = dark pixel.")
    print("static const uint8_t OFF_IMAGE_BITS[240][50] = {")
    for y, row in enumerate(rows):
        hx = ", ".join(f"0x{b:02X}" for b in row)
        print(f"  {{ {hx} }}, // row {y}")
    print("};")


def main():
    import sys
    img = make_scene()
    rows, bw = dither_and_pack(img)
    # Optional preview
    if "--preview" in sys.argv:
        bw.save("C:/tmp/off_preview.png")
        img.save("C:/tmp/off_gray.png")
    emit_c(rows)


if __name__ == "__main__":
    main()
