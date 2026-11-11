#!/usr/bin/env python3
"""STATMENU.IMG의 원작 시작 메뉴를 복원한다. IMG에는 프레임 크기 헤더가 없다."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from decode_user_sprite import SpriteFrame
from decode_map import read_offsets


def parse_img(data):
    if len(data) < 8:
        raise ValueError('IMG 헤더가 잘렸습니다.')
    count = struct.unpack_from('<I', data)[0]
    start = 8 + count * 4
    if not 0 < count < 4096 or start > len(data):
        raise ValueError('IMG 프레임 목록이 잘못되었습니다.')
    offsets = struct.unpack_from(f'<{count}I', data, 4)
    length = struct.unpack_from('<I', data, start - 4)[0]
    if length != len(data) - start or offsets[0] != 0 or offsets[-1] >= length:
        raise ValueError('IMG 데이터 길이가 맞지 않습니다.')
    if any(a >= b for a, b in zip(offsets, offsets[1:])):
        raise ValueError('IMG 오프셋 순서가 잘못되었습니다.')
    return [data[start+a:start+b] for a, b in zip(offsets, (*offsets[1:], length))]


def decode_img_frame(data):
    position = 0
    pixels = []
    width = height = 0
    def byte():
        nonlocal position
        if position >= len(data):
            raise ValueError('IMG 픽셀 구간이 잘렸습니다.')
        value = data[position]
        position += 1
        return value
    for plane in range(4):
        columns = byte()
        if columns == 0:
            continue
        width = max(width, plane + (columns - 1) * 4 + 1)
        for column in range(columns):
            spans = byte()
            y = 0
            for _ in range(spans):
                y += byte()
                count = byte()
                if y + count > 256:
                    raise ValueError('IMG 높이가 화면 버퍼를 넘었습니다.')
                for _ in range(count):
                    pixels.append((plane + column * 4, y, byte()))
                    y += 1
                height = max(height, y)
    if position != len(data) or not 0 < width <= 352 or height == 0:
        raise ValueError('IMG 프레임 범위가 잘못되었습니다.')
    indices, alpha = bytearray(width * height), bytearray(width * height)
    for x, y, color in pixels:
        indices[y * width + x], alpha[y * width + x] = color, 255
    return SpriteFrame(width, height, bytes(indices), bytes(alpha))


def menu_image(frames, selection):
    image = frames[0].copy()
    # 원작 0x1D330의 좌표에서 VGA 화면 가장자리 16픽셀을 뺀다.
    button, x, y, cursor_y = (1, 100, 152, 148) if selection == 0 else (7, 104, 174, 170)
    image.alpha_composite(frames[button], (x, y))
    image.alpha_composite(frames[13], (71, cursor_y))
    return image


def extract(game_dir, output_root):
    source = (game_dir / 'GRP/STATMENU.IMG').read_bytes()
    palette = (game_dir / 'DATA/PROTOCOS.PAL').read_bytes()
    frames = [decode_img_frame(raw).to_image(palette) for raw in parse_img(source)]
    if len(frames) != 18 or frames[0].size != (320, 200):
        raise ValueError('분석한 시작 메뉴 판본과 다릅니다.')
    out = output_root / 'assets/ui/title'
    out.mkdir(parents=True, exist_ok=True)
    for selection, name in enumerate(('new-game', 'continue')):
        menu_image(frames, selection).save(out / f'{name}.png')
    (out / 'manifest.json').write_text(json.dumps({
        'source': 'GRP/STATMENU.IMG', 'sha256': hashlib.sha256(source).hexdigest(),
        'palette': 'DATA/PROTOCOS.PAL', 'palette_sha256': hashlib.sha256(palette).hexdigest(),
        'frame_count': 18, 'selection_function': '0x1D330', 'menu_function': '0x1D674'
    }, indent=2) + '\n')
    initial = output_root / 'assets/data/original'
    initial.mkdir(parents=True, exist_ok=True)
    (initial / 'start.dat').write_bytes((game_dir / 'DATA/START.DAT').read_bytes())
    offsets = read_offsets(game_dir / 'MAP/AREA00.MDA')
    raw_map = (game_dir / 'MAP/AREA00.MAP').read_bytes()[offsets[2]:offsets[3]]
    width, height = raw_map[2] * 40, raw_map[3] * 25
    cells = list(struct.iter_unpack('<HH', raw_map[48:48 + width * height * 4]))
    fallback = {'name': 'Area00_Sub_2_SA01.PCX', 'width': width, 'height': height,
                'tile_width': 8, 'tile_height': 8, 'tileset': 'area00_tileset.png',
                'layer1': [(tile & 0xfff) + 1 for tile, _ in cells], 'layer2': [0] * len(cells)}
    (output_root / 'assets/data/maps/new_game_map.json').write_text(
        json.dumps(fallback, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    extract(args.game_dir, args.output_root)
