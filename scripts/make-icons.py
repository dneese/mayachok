#!/usr/bin/env python3
"""Генерує іконки застосунку (PNG у кількох щільностях + adaptive для API 26+).

Без зовнішніх залежностей: мінімальний PNG-енкодер на zlib.
"""

import os
import struct
import zlib

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res")

# щільність -> сторона в пікселях
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

BG = (37, 99, 235)       # accent --accent
PIN = (255, 255, 255)
DOT = (30, 158, 74)      # свіжа позиція


def write_png(path, size, pixels):
    """pixels: список рядків, кожен рядок — список (r,g,b,a)."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as fh:
        fh.write(png)


def blend(base, color, alpha):
    """Накладає color на base з прозорістю alpha (0..1)."""
    return tuple(int(base[i] * (1 - alpha) + color[i] * alpha) for i in range(3))


def make_icon(size):
    """Крапка на стилизованій карті: квадрат + коло + крапка."""
    s = size
    radius = s * 0.5
    corner = s * 0.22
    cx = cy = radius

    rows = []
    for y in range(s):
        row = []
        for x in range(s):
            # 1. Фон зі скругленими кутами (superellipse наближено еліпсом)
            dx, dy = abs(x + 0.5 - cx), abs(y + 0.5 - cy)
            in_box = dx <= radius - corner * 0.5 and dy <= radius - corner * 0.5
            dist = (dx ** 2 + dy ** 2) ** 0.5
            if in_box or dist <= radius - corner * 0.5:
                r, g, b = BG
                a = 255
            else:
                row.append((0, 0, 0, 0))
                continue

            # 2. Сітка «вулиць»
            grid = (x % max(1, int(s * 0.16))) < max(1, int(s * 0.045)) or (
                y % max(1, int(s * 0.16))
            ) < max(1, int(s * 0.045))
            if grid:
                r, g, b = blend((r, g, b), PIN, 0.13)

            # 3. Білий круг маркера
            d = dist
            if d <= s * 0.235:
                if d >= s * 0.235 - max(1.0, s * 0.055):
                    r, g, b = blend((r, g, b), PIN, 1.0)
                else:
                    r, g, b = PIN

            # 4. Зелена точка в центрі
            if d <= s * 0.088:
                r, g, b = DOT

            row.append((r, g, b, a))
        rows.append(row)
    return rows


def main():
    for density, size in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        write_png(os.path.join(folder, "ic_launcher.png"), size, make_icon(size))
        print(f"  mipmap-{density}/ic_launcher.png  {size}x{size}")

    # Adaptive icon для API 26+ (векторний передній план)
    adaptive = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(adaptive, exist_ok=True)
    with open(os.path.join(adaptive, "ic_launcher.xml"), "w", encoding="utf-8") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            "</adaptive-icon>\n"
        )
    print("  mipmap-anydpi-v26/ic_launcher.xml")


if __name__ == "__main__":
    main()
