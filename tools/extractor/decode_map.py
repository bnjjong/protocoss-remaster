#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 맵 타일셋(AREA*.SOC, SDA) 및 맵 데이터(AREA*.MAP, MDA) 추출기

- AREA00.SDA / AREA00.SOC: 19개의 서브 타일셋 컨테이너 (각 블록당 768B DAC 팔레트 + 256개 16x16 타일)
- AREA00.MDA / AREA00.MAP: 59개의 서브 맵 컨테이너 (각 서브맵 가로 sw*40, 세로 sh*25 타일 그리드)
"""
import os
import struct
import json
from PIL import Image, ImageDraw

def extract_area_tileset(soc_path, sda_path, out_png_path):
    with open(sda_path, 'rb') as f:
        cnt = struct.unpack('<I', f.read(4))[0]
        sda_offs = [struct.unpack('<I', f.read(4))[0] for _ in range(cnt)]

    with open(soc_path, 'rb') as f:
        soc_data = f.read()

    total_tiles = cnt * 256
    cols = 32
    rows = (total_tiles + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * 16, rows * 16), (0, 0, 0, 0))

    print(f"Extracting {cnt} tileset blocks ({total_tiles} tiles total)...")

    for b_idx in range(cnt):
        block = soc_data[sda_offs[b_idx] : sda_offs[b_idx] + 70818]
        # 팔레트: 오프셋 2부터 770까지 768바이트
        pal_raw = block[2:770]
        pal = []
        for p in range(256):
            pal.extend([
                min(255, pal_raw[p * 3] * 4),
                min(255, pal_raw[p * 3 + 1] * 4),
                min(255, pal_raw[p * 3 + 2] * 4)
            ])

        for t_idx in range(256):
            global_tile_id = b_idx * 256 + t_idx
            raw_tile = block[770 + t_idx * 256 : 770 + (t_idx + 1) * 256]

            t_p = Image.frombytes('P', (16, 16), raw_tile)
            t_p.putpalette(pal)
            t_rgba = t_p.convert('RGBA')

            # 0번 타일(가이드 그리드)은 투명화
            if t_idx == 0:
                t_rgba = Image.new('RGBA', (16, 16), (0, 0, 0, 0))

            gx = (global_tile_id % cols) * 16
            gy = (global_tile_id // cols) * 16
            sheet.paste(t_rgba, (gx, gy))

    sheet.save(out_png_path)
    print(f"Saved RGBA tileset sheet ({sheet.width}x{sheet.height}) -> {out_png_path}")
    return total_tiles

def extract_area_map(map_path, mda_path, out_json_path, sub_map_index=1):
    with open(mda_path, 'rb') as f:
        cnt = struct.unpack('<I', f.read(4))[0]
        mda_offs = [struct.unpack('<I', f.read(4))[0] for _ in range(cnt)]

    with open(map_path, 'rb') as f:
        f.seek(mda_offs[sub_map_index])
        hdr = f.read(32)
        sw, sh = hdr[2], hdr[3]
        w = sw * 40
        h = sh * 25
        tileset_ref = hdr[4:20].split(b'\x00')[0].decode('latin1', 'ignore')

        f.seek(mda_offs[sub_map_index] + 48)
        raw_cells = f.read(w * h * 4)
        cells = [struct.unpack('<HH', raw_cells[i * 4 : (i + 1) * 4]) for i in range(w * h)]

        ground = []
        overlay = []
        for tid, attr in cells:
            flag = tid >> 12
            pure_id = tid & 0x0FFF
            global_id = 1 * 256 + pure_id if pure_id < 256 else pure_id

            if flag == 0:
                ground.append(global_id)
                overlay.append(0)
            else:
                ground.append(1 * 256 + 40)
                overlay.append(global_id)

        map_info = {
            "name": f"Area00_Sub_{sub_map_index}",
            "width": w,
            "height": h,
            "tile_width": 16,
            "tile_height": 16,
            "tileset": "area00_tileset.png",
            "layer1": ground,
            "layer2": overlay
        }

        with open(out_json_path, 'w', encoding='utf-8') as out_f:
            json.dump(map_info, out_f)

        print(f"Extracted sub-map [{sub_map_index}] ({w}x{h}, {w*h} tiles) -> {out_json_path}")
        return map_info

def main():
    game_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/MAP"
    out_sprites_dir = "/Users/df/github/protocoss-remaster/assets/sprites/maps"
    out_data_dir = "/Users/df/github/protocoss-remaster/assets/data/maps"
    os.makedirs(out_sprites_dir, exist_ok=True)
    os.makedirs(out_data_dir, exist_ok=True)

    soc_00 = os.path.join(game_dir, "AREA00.SOC")
    sda_00 = os.path.join(game_dir, "AREA00.SDA")
    map_00 = os.path.join(game_dir, "AREA00.MAP")
    mda_00 = os.path.join(game_dir, "AREA00.MDA")

    if os.path.exists(soc_00) and os.path.exists(sda_00):
        extract_area_tileset(soc_00, sda_00, os.path.join(out_sprites_dir, "area00_tileset.png"))
    if os.path.exists(map_00) and os.path.exists(mda_00):
        extract_area_map(map_00, mda_00, os.path.join(out_data_dir, "area00_map.json"), sub_map_index=1)

if __name__ == '__main__':
    main()
