#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 맵 타일셋(AREA*.SOC, SDA) 및 맵 데이터(AREA*.MAP, MDA) 추출기

- AREA00.SDA / AREA00.SOC: 19개의 서브 타일셋 컨테이너 (각 블록당 768B DAC 팔레트 + 256개 16x16 타일 + 512B LUT + 4000B 맵 셀)
- AREA00.MDA / AREA00.MAP: 59개의 서브 맵 컨테이너 (각 서브맵 가로 sw*40, 세로 sh*25 타일 그리드)

원작 도스 맵 에디터는 640x400(가로 40열) 기준으로 타일을 관리하며, 512바이트 LUT 테이블을 통해
0~999 확장 타일 ID를 각 블록의 실제 16x16 픽셀 타일 슬롯으로 매핑합니다.
"""
import os
import struct
import json
from PIL import Image

def extract_area_tileset_and_map(soc_path, sda_path, map_path, mda_path, out_sprites_dir, out_data_dir, sub_map_index=1):
    os.makedirs(out_sprites_dir, exist_ok=True)
    os.makedirs(out_data_dir, exist_ok=True)

    with open(soc_path, 'rb') as f:
        soc = f.read()
    with open(sda_path, 'rb') as f:
        cnt = struct.unpack('<I', f.read(4))[0]
        offs = [struct.unpack('<I', f.read(4))[0] for _ in range(cnt)]

    # 1. 서브맵 메타데이터 읽기
    with open(mda_path, 'rb') as f:
        cnt_m = struct.unpack('<I', f.read(4))[0]
        mda_offs = [struct.unpack('<I', f.read(4))[0] for _ in range(cnt_m)]

    with open(map_path, 'rb') as f:
        f.seek(mda_offs[sub_map_index])
        hdr = f.read(32)
        sw, sh = hdr[2], hdr[3]
        cols = sw * 40
        rows = sh * 25
        ts_name = hdr[4:20].split(b'\x00')[0].decode('latin1', 'ignore')
        b_idx = int(ts_name[2:4]) if 'SA' in ts_name else 1

        f.seek(mda_offs[sub_map_index] + 48)
        raw_cells = f.read(cols * rows * 4)
        cells = [struct.unpack('<HH', raw_cells[i*4:(i+1)*4]) for i in range(cols * rows)]

    # 2. 블록 팔레트 및 타일 추출
    pal_raw = soc[offs[b_idx] + 2 : offs[b_idx] + 770]
    pal = []
    for p in range(256):
        pal.extend([
            min(255, pal_raw[p * 3] * 4),
            min(255, pal_raw[p * 3 + 1] * 4),
            min(255, pal_raw[p * 3 + 2] * 4)
        ])
    b_data = soc[offs[b_idx] + 770 : offs[b_idx] + 70818]

    # 512-byte LUT 추출
    lut_raw = soc[offs[b_idx] + 770 + 65536 : offs[b_idx] + 770 + 65536 + 512]
    lut = [struct.unpack('<H', lut_raw[i*2:(i+1)*2])[0] for i in range(256)]

    # 3. 40열 기준 1,000개 타일 아틀라스 시트 생성 (640x400)
    sheet_cols = 40
    sheet_rows = 25
    total_sheet_tiles = sheet_cols * sheet_rows
    sheet = Image.new('RGBA', (sheet_cols * 16, sheet_rows * 16), (0, 0, 0, 0))

    valid_slots = set()
    for t in range(256):
        raw_tile = b_data[t * 256 : (t + 1) * 256]
        im = Image.frombytes('P', (16, 16), raw_tile)
        im.putpalette(pal)
        rgba = im.convert('RGBA')

        # 0번 타일(가이드 격자)은 투명화
        if t == 0:
            rgba = Image.new('RGBA', (16, 16), (0, 0, 0, 0))

        dst_idx = lut[t] if lut[t] != 0 else t
        if dst_idx < total_sheet_tiles:
            gx = (dst_idx % sheet_cols) * 16
            gy = (dst_idx // sheet_cols) * 16
            sheet.paste(rgba, (gx, gy))
            if rgba.getbbox():
                valid_slots.add(dst_idx)

    # 기본 바닥 타일 등록 (나무 마루: 인덱스 82, 마당 흙길: 인덱스 74)
    raw_floor = b_data[82 * 256 : 83 * 256]
    im_floor = Image.frombytes('P', (16, 16), raw_floor)
    im_floor.putpalette(pal)
    floor_slot = 998
    sheet.paste(im_floor.convert('RGBA'), ((floor_slot % sheet_cols) * 16, (floor_slot // sheet_cols) * 16))

    raw_yard = b_data[74 * 256 : 75 * 256]
    im_yard = Image.frombytes('P', (16, 16), raw_yard)
    im_yard.putpalette(pal)
    yard_slot = 999
    sheet.paste(im_yard.convert('RGBA'), ((yard_slot % sheet_cols) * 16, (yard_slot // sheet_cols) * 16))

    ts_out_path = os.path.join(out_sprites_dir, 'area00_tileset.png')
    sheet.save(ts_out_path)
    print(f'Saved RGBA tileset sheet ({sheet.width}x{sheet.height}) -> {ts_out_path}')

    # 4. 맵 레이어1(바닥) 및 레이어2(오브젝트) 매핑 생성
    ground = []
    overlay = []
    for ty in range(rows):
        for tx in range(cols):
            tid, _ = cells[ty * cols + tx]
            pid = tid & 0x0FFF

            # 실내(0~20행)는 원목 마루, 실외(21~24행)는 마당 흙길
            if ty < 21:
                ground.append(floor_slot)
            else:
                ground.append(yard_slot)

            if pid in valid_slots and pid != 0:
                overlay.append(pid)
            else:
                overlay.append(0)

    map_info = {
        'name': f'Area00_Sub_{sub_map_index}_{ts_name}',
        'width': cols,
        'height': rows,
        'tile_width': 16,
        'tile_height': 16,
        'tileset': 'area00_tileset.png',
        'layer1': ground,
        'layer2': overlay
    }

    json_out_path = os.path.join(out_data_dir, 'area00_map.json')
    with open(json_out_path, 'w', encoding='utf-8') as f:
        json.dump(map_info, f, indent=2)

    print(f'Saved map JSON [{cols}x{rows}] -> {json_out_path}')
    return map_info

def main():
    game_dir = '/Users/df/Downloads/Protocoss/Protocoss_Game/MAP'
    out_sprites_dir = '/Users/df/github/protocoss-remaster/assets/sprites/maps'
    out_data_dir = '/Users/df/github/protocoss-remaster/assets/data/maps'

    soc_00 = os.path.join(game_dir, 'AREA00.SOC')
    sda_00 = os.path.join(game_dir, 'AREA00.SDA')
    map_00 = os.path.join(game_dir, 'AREA00.MAP')
    mda_00 = os.path.join(game_dir, 'AREA00.MDA')

    if os.path.exists(soc_00) and os.path.exists(sda_00) and os.path.exists(map_00) and os.path.exists(mda_00):
        extract_area_tileset_and_map(soc_00, sda_00, map_00, mda_00, out_sprites_dir, out_data_dir, sub_map_index=1)

if __name__ == '__main__':
    main()
