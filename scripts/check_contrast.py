#!/usr/bin/env python3
"""WCAG 对比度门禁：所有文字色 × 所有可能出现的底色，浅/深两套都必须 ≥ 7:1（AAA）。
用法：python3 scripts/check_contrast.py   （CI 中失败即阻断构建）"""
import re, sys, pathlib

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
TEXT = ["on_surface", "on_surface_variant", "primary", "danger", "success", "cb_icon"]
SURFACES = ["background", "surface", "cb_floating_surface", "surface_variant", "cb_lock_chip"]
PAIRS = [("on_primary", "primary"), ("on_primary_container", "primary_container"),
         ("on_secondary_container", "secondary_container"), ("cookie_chip_text", "cookie_chip_bg"),
         ("code_text", "code_bg")]
MIN = 7.0
# 非文字 UI 边界（WCAG 1.4.11）：输入框 / 描边按钮的 colorOutline 在所有面上 ≥ 3:1
NONTEXT = [("outline", b) for b in SURFACES]
MIN_NONTEXT = 3.0


def load(folder):
    c = {}
    for f in ("colors.xml", "colors_ui.xml"):
        c.update(re.findall(r'<color name="(\w+)">(#[0-9A-Fa-f]{6,8})<', (RES / folder / f).read_text()))
    return c


def lum(h):
    h = h.lstrip("#")[-6:]
    ch = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    ch = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in ch]
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]


def ratio(a, b):
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


light = load("values")
night = {**light, **load("values-night")}
failed = 0
for mode, pal in (("light", light), ("night", night)):
    checks = [(f, b) for f in TEXT for b in SURFACES] + PAIRS
    worst = min(ratio(pal[f], pal[b]) for f, b in checks)
    for f, b in checks:
        r = ratio(pal[f], pal[b])
        if r < MIN:
            failed += 1
            print(f"FAIL [{mode}] {f} {pal[f]} on {b} {pal[b]}: {r:.2f}:1")
    print(f"[{mode}] worst text contrast {worst:.2f}:1")
    for f, b in NONTEXT:
        r = ratio(pal[f], pal[b])
        if r < MIN_NONTEXT:
            failed += 1
            print(f"FAIL [{mode}] {f} {pal[f]} on {b} {pal[b]}: {r:.2f}:1 (non-text, need 3:1)")
    print(f"[{mode}] worst outline contrast {min(ratio(pal[f], pal[b]) for f, b in NONTEXT):.2f}:1")
missing = set(load("values")) - set(load("values-night"))
if missing:
    failed += 1
    print(f"FAIL night 未覆盖色名: {sorted(missing)}")
sys.exit(1 if failed else 0)
