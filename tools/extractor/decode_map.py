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
        # 팔레트: 오프셋 4부터 768바이트 (4+768=772)
        f.seek(4)
        pal_raw = f.read(768)
        palette = []
        for i in range(256):
            palette.extend([
                min(255, pal_raw[i*3] * 4),
                min(255, pal_raw[i*3+1] * 4),
                min(255, pal_raw[i*3+2] * 4)
            ])

        # 타일 데이터는 오프셋 774부터 16x16(256바이트) 단위로 연속 저장됨
        f.seek(774)
        tiles_raw = f.read()
        num_tiles = len(tiles_raw) // 256
        print(f"Extracting {num_tiles} tiles from {os.path.basename(soc_path)} (offset 774)...")

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

        # LibGDX 및 현대 OpenGL 호환을 위해 반드시 32비트 RGBA 트루컬러로 변환
        sheet_rgba = sheet.convert('RGBA')

        # 타일 0번(에디터용 가이드 격자 무늬)은 투명 처리
        datas = sheet_rgba.load()
        for py in range(16):
            for px in range(16):
                datas[px, py] = (0, 0, 0, 0)

        sheet_rgba.save(out_png_path)
        print(f"Saved RGBA tileset sheet ({sheet_rgba.width}x{sheet_rgba.height}) -> {out_png_path}")
        return num_tiles

def extract_area_map(map_path, out_json_path):
    with open(map_path, 'rb') as f:
        w, h = struct.unpack('<HH', f.read(4))
        tileset_ref = f.read(16).split(b'\x00')[0].decode('latin1', 'ignore')
        total_tiles = w * h

        # Layer 1: 오프셋 32부터 total_tiles * 2 바이트
        f.seek(32)
        layer1_raw = f.read(total_tiles * 2)
        layer1 = list(struct.unpack(f'<{total_tiles}H', layer1_raw))

        # Layer 2: 바로 뒤 이어서 total_tiles * 2 바이트
        layer2_raw = f.read(total_tiles * 2)
        layer2 = list(struct.unpack(f'<{total_tiles}H', layer2_raw))

        map_info = {
            "name": os.path.basename(map_path),
            "width": w,
            "height": h,
            "tile_width": 16,
            "tile_height": 16,
            "tileset": tileset_ref,
            "layer1": layer1,
            "layer2": layer2
        }

        with open(out_json_path, 'w', encoding='utf-8') as out_f:
            json.dump(map_info, out_f)

        print(f"Extracted map {os.path.basename(map_path)}: {w}x{h} ({total_tiles} tiles, layer1 + layer2)")
        return map_info

def main():
    game_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/MAP"
    out_sprites_dir = "/Users/df/github/protocoss-remaster/assets/sprites/maps"
    out_data_dir = "/Users/df/github/protocoss-remaster/assets/data/maps"
    os.makedirs(out_sprites_dir, exist_ok=True)
    os.makedirs(out_data_dir, exist_ok=True)

    soc_00 = os.path.join(game_dir, "AREA00.SOC")
    map_00 = os.path.join(game_dir, "AREA00.MAP")

    if os.path.exists(soc_00):
        extract_area_tileset(soc_00, os.path.join(out_sprites_dir, "area00_tileset.png"))
    if os.path.exists(map_00):
        extract_area_map(map_00, os.path.join(out_data_dir, "area00_map.json"))

if __name__ == "__main__":
    main()
