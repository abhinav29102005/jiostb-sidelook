#!/usr/bin/env python3
"""
generate_startup_video.py
Generates a 1080p @ 60fps Google TV style minimalist startup video.
Uses a matte blue-grey palette, four harmonic floating dots, and crisp typography.
"""

import sys
import math
import subprocess
from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1920, 1080
FPS = 60
DURATION = 5.0
TOTAL_FRAMES = int(FPS * DURATION)

# Deep matte Google TV blue-grey
BG_COLOR = (24, 32, 44)
BG_COLOR_DEEP = (14, 18, 25)

# Google TV pastel dots
DOT_BLUE1 = (138, 180, 248)
DOT_BLUE2 = (66, 133, 244)
DOT_CYAN  = (120, 217, 236)
DOT_WHITE = (232, 240, 254)

TEXT_WHITE = (248, 249, 250)

def main():
    out_file = sys.argv[1] if len(sys.argv) > 1 else "abhinav_google_tv_startup.mp4"
    name = sys.argv[2] if len(sys.argv) > 2 else "abhinav"
    subtext = sys.argv[3] if len(sys.argv) > 3 else "t v"

    # Fallback to default sans if Inter is not found
    font_path_bold = "/usr/share/fonts/rsms-inter-fonts/InterDisplay-SemiBold.ttf"
    font_path_med  = "/usr/share/fonts/rsms-inter-fonts/InterDisplay-Medium.ttf"

    try:
        font_title = ImageFont.truetype(font_path_bold, 128)
        font_sub   = ImageFont.truetype(font_path_med, 42)
    except Exception:
        font_title = ImageFont.load_default()
        font_sub   = ImageFont.load_default()

    # Create radial background
    bg_img = Image.new("RGB", (WIDTH, HEIGHT), BG_COLOR_DEEP)
    draw_bg = ImageDraw.Draw(bg_img)
    cx, cy = WIDTH // 2, HEIGHT // 2
    max_r = int(math.hypot(cx, cy))
    for r in range(max_r, 0, -4):
        factor = min(1.0, r / (WIDTH * 0.55))
        col = (
            int(BG_COLOR[0] + (BG_COLOR_DEEP[0] - BG_COLOR[0]) * factor),
            int(BG_COLOR[1] + (BG_COLOR_DEEP[1] - BG_COLOR[1]) * factor),
            int(BG_COLOR[2] + (BG_COLOR_DEEP[2] - BG_COLOR[2]) * factor),
        )
        draw_bg.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

    ffmpeg_cmd = [
        "ffmpeg", "-y",
        "-f", "rawvideo",
        "-vcodec", "rawvideo",
        "-s", f"{WIDTH}x{HEIGHT}",
        "-pix_fmt", "rgb24",
        "-r", str(FPS),
        "-i", "-",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "17",
        out_file
    ]

    proc = subprocess.Popen(ffmpeg_cmd, stdin=subprocess.PIPE)

    dummy = Image.new("RGBA", (1, 1))
    d = ImageDraw.Draw(dummy)
    tb = d.textbbox((0, 0), name, font=font_title)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    sub_tb = d.textbbox((0, 0), subtext, font=font_sub)
    sub_w, sub_h = sub_tb[2] - sub_tb[0], sub_tb[3] - sub_tb[1]

    text_x = (WIDTH - tw) // 2
    text_y = (HEIGHT - th) // 2 - 25
    sub_x = (WIDTH - sub_w) // 2
    sub_y = text_y + th + 24

    dots = [DOT_BLUE1, DOT_CYAN, DOT_BLUE2, DOT_WHITE]

    for f in range(TOTAL_FRAMES):
        t = f / FPS
        frame = bg_img.copy()
        overlay = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)

        if t < 0.4:
            global_a = t / 0.4
        elif t > 4.2:
            global_a = max(0.0, (5.0 - t) / 0.8)
        else:
            global_a = 1.0

        if t < 1.7:
            if t < 0.9:
                conv = 0.0
                spread = 65
            else:
                p = (t - 0.9) / 0.6
                conv = p * p * (3 - 2 * p)
                spread = 65 * (1.0 - conv)

            rad = int(14 * (1.0 - conv * 0.4))
            offsets = [-1.5, -0.5, 0.5, 1.5]
            for i, off in enumerate(offsets):
                dx = cx + off * spread
                dy = cy + math.sin(t * 5.0 + i * 0.8) * 16 * (1.0 - conv)
                c = dots[i]
                a = int(255 * global_a * (1.0 - conv if t > 1.3 else 1.0))
                if a > 0 and rad > 0:
                    draw.ellipse([dx - rad - 4, dy - rad - 4, dx + rad + 4, dy + rad + 4],
                                 fill=(c[0], c[1], c[2], int(a * 0.3)))
                    draw.ellipse([dx - rad, dy - rad, dx + rad, dy + rad],
                                 fill=(c[0], c[1], c[2], a))

        if t >= 1.2:
            prog = min(1.0, (t - 1.2) / 0.7)
            alpha = prog * prog * (3 - 2 * prog)
            
            a_title = int(255 * alpha * global_a)
            a_sub = int(200 * alpha * global_a)

            draw.text((text_x, text_y), name, font=font_title, 
                      fill=(DOT_BLUE1[0], DOT_BLUE1[1], DOT_BLUE1[2], int(a_title * 0.15)))
            draw.text((text_x, text_y), name, font=font_title, 
                      fill=(TEXT_WHITE[0], TEXT_WHITE[1], TEXT_WHITE[2], a_title))
            draw.text((sub_x, sub_y), subtext, font=font_sub, 
                      fill=(DOT_BLUE1[0], DOT_BLUE1[1], DOT_BLUE1[2], a_sub))

        frame.paste(overlay, (0, 0), overlay)
        proc.stdin.write(frame.tobytes())

    proc.stdin.close()
    proc.wait()
    print(f"[+] Output saved: {out_file}")

if __name__ == "__main__":
    main()

