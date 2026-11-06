#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 맵 타일셋(AREA*.SOC) 및 맵 데이터(AREA*.MAP) 추출기
"""
import os
import struct
import json
from PIL import Image

def extract_area_tileset(soc_path, out_png_path):
    with open(soc_path, 'rb') as f:
        f.seek(4)
        pal_raw = f.read(768)
        palette = []
        for i in range(256):
            palette.extend([
                min(255, pal_raw[i*3] * 4),
                min(255, pal_raw[i*3+1] * 4),
                min(255, pal_raw[i*3+2] * 4)
            ])

        # Tiles data starts at offset 834
        f.seek(834)
        tiles_raw = f.read()
        num_tiles = len(tiles_raw) // 256
        print(f"Extracting {num_tiles} tiles from {os.path.basename(soc_path)}...")

        # Create tileset sheet: 32 tiles per row (512px wide)
        cols = 32
        rows = (num_tiles + cols - 1) // cols
        sheet = Image.new('P', (cols * 16, rows * 16))
        sheet.putpalette(palette)

        for idx in range(num_tiles):
            tile_bytes = tiles_raw[idx * 256 : (idx + 1) * 256]
            t_img = Image.frombytes('P', (16, 16), tile_bytes)
            cx = (idx % cols) * 16
            cy = (idx // cols) * 16
            sheet.paste(t_img, (cx, cy))

        sheet.save(out_png_path)
        print(f"Saved tileset sheet ({sheet.width}x{sheet.height}) -> {out_png_path}")
        return num_tiles

def extract_area_map(map_path, out_json_path):
    with open(map_path, 'rb') as f:
        w, h = struct.unpack('<HH', f.read(4))
        tileset_ref = f.read(16).split(b'\x00')[0].decode('latin1')
        f.seek(32) # Data start

        total_tiles = w * h
        # Layer 1: Ground layer
        layer1 = []
        for _ in range(total_tiles):
            tid = struct.unpack('<H', f.read(2))[0]
            layer1.append(tid)

        map_info = {
            "name": os.path.basename(map_path),
            "width": w,
            "height": h,
            "tile_width": 16,
            "tile_height": 16,
            "tileset": tileset_ref,
            "layer1": layer1[:2000] # Export first section or summary for engine
        }

        with open(out_json_path, 'w', encoding='utf-8') as out_f:
            json.dump(map_info, out_f, indent=2)

        print(f"Extracted map {os.path.basename(map_path)}: {w}x{h} ({total_tiles} tiles)")
        return map_info

def main():
    game_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/MAP"
    out_sprites_dir = "/Users/df/github/protocoss-remaster/assets/sprites/maps"
    out_data_dir = "/Users/df/github/protocoss-remaster/assets/data/maps"
    os.makedirs(out_sprites_dir, exist_ok=True)
    os.makedirs(out_data_dir, exist_ok=True)

    # Extract AREA00 (벨슨그의 오두막 및 시작 필드)
    soc_00 = os.path.join(game_dir, "AREA00.SOC")
    map_00 = os.path.join(game_dir, "AREA00.MAP")

    if os.path.exists(soc_00):
        extract_area_tileset(soc_00, os.path.join(out_sprites_dir, "area00_tileset.png"))
    if os.path.exists(map_00):
        extract_area_map(map_00, os.path.join(out_data_dir, "area00_map.json"))

if __name__ == "__main__":
    main()
