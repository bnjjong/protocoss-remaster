#!/usr/bin/env python3
"""원작의 8×8 VGA 타일과 맵 셀을 PNG·JSON으로 추출한다.

주소 변환 근거와 블록 구조는 docs/map-format.md에 기록한다.
"""
import argparse
import json
from pathlib import Path
import re
import struct

from PIL import Image

TILE_SIZE = 8
TILE_COUNT = 1000
SHEET_COLS = 40
PALETTE_OFFSET = 2
PIXELS_OFFSET = 770
PIXELS_SIZE = TILE_COUNT * TILE_SIZE * TILE_SIZE
BLOCK_SIZE = PIXELS_OFFSET + PIXELS_SIZE + 2000 + 2000 + 2048


def read_offsets(path):
    data = Path(path).read_bytes()
    if len(data) < 4:
        raise ValueError(f'오프셋 목록이 잘렸습니다: {path}')
    count = struct.unpack_from('<I', data)[0]
    if len(data) != 4 + count * 4:
        raise ValueError(f'오프셋 목록 크기가 맞지 않습니다: {path}')
    return struct.unpack_from(f'<{count}I', data, 4)


def decode_tile_pixels(raw):
    """네 VGA 메모리 평면에 나뉜 열을 왼쪽부터 순서대로 합친다."""
    if len(raw) != 64:
        raise ValueError('8×8 타일에는 64바이트가 필요합니다.')
    return bytes(raw[(x % 4) * 16 + y * 2 + x // 4]
                 for y in range(TILE_SIZE) for x in range(TILE_SIZE))


def render_map_preview(map_info, sheet):
    """JSON의 위→아래 행 순서로 배경 전체를 합성한다."""
    width, height = map_info['width'], map_info['height']
    tw, th = map_info['tile_width'], map_info['tile_height']
    cols = sheet.width // tw
    result = Image.new('RGBA', (width * tw, height * th))
    for layer in ('layer1', 'layer2'):
        for i, tile_id in enumerate(map_info[layer]):
            if tile_id == 0:
                continue
            sx, sy = tile_id % cols * tw, tile_id // cols * th
            tile = sheet.crop((sx, sy, sx + tw, sy + th))
            result.alpha_composite(tile, (i % width * tw, i // width * th))
    return result


def extract_area_tileset_and_map(soc_path, sda_path, map_path, mda_path,
                                 out_sprites_dir, out_data_dir, sub_map_index=1,
                                 preview_path=None):
    offsets = read_offsets(sda_path)
    map_offsets = read_offsets(mda_path)
    if not 0 <= sub_map_index < len(map_offsets):
        raise ValueError(f'서브맵 번호 범위 오류: {sub_map_index}')
    data = Path(map_path).read_bytes()
    start = map_offsets[sub_map_index]
    end = map_offsets[sub_map_index + 1] if sub_map_index + 1 < len(map_offsets) else len(data)
    submap = data[start:end]
    if len(submap) < 48:
        raise ValueError('맵 헤더가 잘렸습니다.')
    width, height = submap[2] * 40, submap[3] * 25
    name = submap[4:20].split(b'\0')[0].decode('ascii')
    match = re.fullmatch(r'SA(\d{2})\.PCX', name)
    if match is None:
        raise ValueError(f'AREA00 타일셋 이름이 아닙니다: {name}')
    block_index = int(match.group(1))
    if not width or not height or block_index >= len(offsets):
        raise ValueError('맵 크기 또는 타일셋 참조 번호가 잘못되었습니다.')
    cells_end = 48 + width * height * 4
    if len(submap) < cells_end:
        raise ValueError('맵 셀 배열이 잘렸습니다.')
    cells = list(struct.iter_unpack('<HH', submap[48:cells_end]))
    tile_ids = [tid & 0x0fff for tid, _ in cells]
    if any(tid >= TILE_COUNT for tid in tile_ids):
        raise ValueError('1,000개 원본 타일 범위를 벗어난 참조가 있습니다.')

    soc = Path(soc_path).read_bytes()
    start = offsets[block_index]
    end = offsets[block_index + 1] if block_index + 1 < len(offsets) else len(soc)
    block = soc[start:end]
    if len(block) != BLOCK_SIZE or block[:2] != b'\x01\x01':
        raise ValueError('지원하는 SOC 블록 구조가 아닙니다.')
    palette = block[PALETTE_OFFSET:PIXELS_OFFSET]
    if any(channel > 63 for channel in palette):
        raise ValueError('6비트 VGA 팔레트 범위를 벗어났습니다.')
    palette = [channel * 4 for channel in palette]

    # 0번은 엔진의 빈 칸이다. 원본 타일 번호에는 1을 더해 모두 보존한다.
    sheet_rows = (TILE_COUNT + 1 + SHEET_COLS - 1) // SHEET_COLS
    sheet = Image.new('RGBA', (SHEET_COLS * TILE_SIZE, sheet_rows * TILE_SIZE))
    for tid in range(TILE_COUNT):
        start = PIXELS_OFFSET + tid * 64
        tile = Image.frombytes('P', (TILE_SIZE, TILE_SIZE), decode_tile_pixels(block[start:start + 64]))
        tile.putpalette(palette)
        slot = tid + 1
        sheet.paste(tile.convert('RGBA'), (slot % SHEET_COLS * TILE_SIZE, slot // SHEET_COLS * TILE_SIZE))

    map_info = {
        'name': f'Area00_Sub_{sub_map_index}_{name}',
        'width': width, 'height': height,
        'tile_width': TILE_SIZE, 'tile_height': TILE_SIZE,
        'tileset': 'area00_tileset.png',
        # 원작의 기본 배경 그리기는 하위 12비트를 그대로 사용하며 불투명하다.
        'layer1': [tid + 1 for tid in tile_ids],
        'layer2': [0] * len(cells),
    }
    sprites_dir, data_dir = Path(out_sprites_dir), Path(out_data_dir)
    sprites_dir.mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)
    sheet.save(sprites_dir / 'area00_tileset.png')
    (data_dir / 'area00_map.json').write_text(json.dumps(map_info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if preview_path:
        Path(preview_path).parent.mkdir(parents=True, exist_ok=True)
        render_map_preview(map_info, sheet).save(preview_path)
    print(f'{name}: {width}×{height}셀, {TILE_SIZE}×{TILE_SIZE}픽셀, 1,000개 타일 추출 완료')
    return map_info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True, help='원본 게임 폴더')
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--sub-map', type=int, default=1)
    parser.add_argument('--preview', type=Path, help='배경 전체 확인 이미지')
    args = parser.parse_args()
    source = args.game_dir / 'MAP'
    extract_area_tileset_and_map(
        source / 'AREA00.SOC', source / 'AREA00.SDA',
        source / 'AREA00.MAP', source / 'AREA00.MDA',
        args.output_root / 'assets/sprites/maps', args.output_root / 'assets/data/maps',
        args.sub_map, args.preview,
    )


if __name__ == '__main__':
    main()
