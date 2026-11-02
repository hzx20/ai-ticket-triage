#!/usr/bin/env python3
"""把浅色版 SVG 转成暗色版，供 GitHub 暗色主题使用。

做法：只改颜色字面量，不动任何几何坐标，保证两张图的版式完全一致。
画布底色和卡片底色都是 #ffffff，语义不同必须分开处理，所以先把带尺寸的画布矩形单独换掉。
"""
import pathlib
import re

IMG = pathlib.Path(__file__).resolve().parent

# 画布尺寸 -> 暗色画布底色（顺序执行，先吃掉落单的 #ffffff）
CANVAS = [
    ('width="980" height="640" fill="#ffffff"', 'width="980" height="640" fill="#0d1117"'),
    ('width="940" height="760" fill="#ffffff"', 'width="940" height="760" fill="#0d1117"'),
]

# 其余颜色：浅色 -> 暗色
PALETTE = {
    # 卡片 / 容器底
    "#ffffff": "#161b22",   # 剩下的白都是卡片底
    "#fbfdff": "#10161d",   # 虚线大容器
    "#f1f5f9": "#1c2128",   # 灰底框
    "#e0f2fe": "#0c2233",   # 蓝底框
    "#ecfdf5": "#0d2b21",   # 绿底框
    "#f0fdf4": "#0d2b21",
    "#fffbeb": "#2a2110",   # 琥珀底框
    "#fef3c7": "#2a2110",
    "#ede9fe": "#211a33",   # 紫底框
    # 文字
    "#0f172a": "#e6edf3",   # 主文字
    "#475569": "#9aa7b4",   # 次文字
    "#0369a1": "#58a6ff",   # 蓝色小标注
    "#7c3aed": "#a78bfa",
    "#059669": "#34d399",
    "#d97706": "#fbbf24",
    # 线条
    "#64748b": "#7d8998",   # 箭头
    "#94a3b8": "#566171",
    "#cbd5e1": "#30363d",   # 虚线边框
    "#6ee7b7": "#2f9e79",   # 浅绿描边，暗底上压暗一点
    "#fffbeb80": "#2a211080",
}

for name in ("architecture.svg", "classify-flow.svg"):
    src = IMG / name
    text = src.read_text(encoding="utf-8")
    dark = text
    for a, b in CANVAS:
        dark = dark.replace(a, b)
    for a, b in PALETTE.items():
        dark = dark.replace(f'"{a}"', f'"{b}"')
    # 暗色版默认底色改为透明，避免 GitHub 深色卡片上再套一层黑
    out = IMG / name.replace(".svg", "-dark.svg")
    out.write_text(dark, encoding="utf-8")
    left = re.findall(r"#[0-9a-fA-F]{6}", dark)
    print(f"{out.name}: {len(text)} -> {len(dark)} 字节，剩余颜色 {len(set(left))} 种")
