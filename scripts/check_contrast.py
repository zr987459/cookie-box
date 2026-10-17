#!/usr/bin/env python3
"""对比度门禁：读取 values/ 与 values-night/ 的色值，校验所有「前景 × 背景」组合。
文字 ≥ 7.0（WCAG AAA 正文），图标/控件 ≥ 4.5（高于 AA 非文字 3.0 的要求）。
用法：python3 scripts/check_contrast.py [res 目录]，不达标时退出码 1。"""
import re, sys, pathlib

RES = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res")
TEXT, ICON = 7.0, 4.5
# (前景, 背景, 阈值) —— 覆盖布局与 Kotlin 中实际出现的组合
PAIRS = [(f, b, TEXT) for f in ("on_surface", "on_surface_variant", "primary", "danger", "success")
         for b in ("surface", "background", "surface_variant", "cb_floating_surface")] + [
    ("on_background", "background", TEXT),
    ("on_primary", "primary", TEXT),
    ("on_primary_container", "primary_container", TEXT),
    ("on_secondary", "secondary", TEXT),
    ("on_secondary_container", "secondary_container", TEXT),
    ("cookie_chip_text", "cookie_chip_bg", TEXT),
    ("code_text", "code_bg", TEXT),
    ("on_surface", "cb_lock_chip", TEXT),
    ("cb_icon", "cb_floating_surface", ICON),
    ("cb_icon", "surface", ICON),
    ("cb_icon", "cb_lock_chip", ICON),
    ("primary", "cb_lock_chip", ICON),
    ("on_surface_variant", "cb_lock_chip", ICON),
]

def load(d):
    c = {}
    for f in sorted((RES / d).glob("colors*.xml")):
        for n, v in re.findall(r'<color name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>', f.read_text("utf-8")):
            c[n] = v
    return c

def lum(h):
    h = h.lstrip("#")[-6:]
    ch = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    ch = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in ch]
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]

def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)

fail = 0
light = load("values")
for mode, colors in (("浅色", light), ("深色", {**light, **load("values-night")})):
    print(f"== {mode}")
    for fg, bg, need in PAIRS:
        if fg not in colors or bg not in colors:
            print(f"  缺色 {fg}/{bg}"); fail += 1; continue
        r = ratio(colors[fg], colors[bg])
        ok = r >= need
        fail += not ok
        print(f"  {'OK ' if ok else 'BAD'} {r:5.2f} ≥{need}  {fg} {colors[fg]} on {bg} {colors[bg]}")
print("全部通过" if not fail else f"{fail} 项不达标")
sys.exit(1 if fail else 0)
