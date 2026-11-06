#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 배경 일러스트 (BACK.DAT & PASS.GRP) 추출기
"""
import os
from PIL import Image

def extract_battle_backgrounds(game_dir, out_dir):
    pal_path = os.path.join(game_dir, 'DATA', 'PROTOCOS.PAL')
    with open(pal_path, 'rb') as f:
        pal_raw = f.read(768)

    palette = []
    for i in range(256):
        palette.extend([
            min(255, pal_raw[i*3] * 4),
            min(255, pal_raw[i*3+1] * 4),
            min(255, pal_raw[i*3+2] * 4)
        ])

    back_path = os.path.join(game_dir, 'GRP', 'BACK.DAT')
    with open(back_path, 'rb') as f:
        for idx in range(32):
            raw_pixels = f.read(320 * 200)
            img = Image.frombytes('P', (320, 200), raw_pixels)
            img.putpalette(palette)
            out_file = os.path.join(out_dir, f'battle_back_{idx:02d}.png')
            img.convert('RGB').save(out_file)

    print("Extracted 32 battle backgrounds from BACK.DAT.")

def main():
    game_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game"
    out_dir = "/Users/df/github/protocoss-remaster/assets/backgrounds"
    os.makedirs(out_dir, exist_ok=True)
    extract_battle_backgrounds(game_dir, out_dir)

if __name__ == "__main__":
    main()
