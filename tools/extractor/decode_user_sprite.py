#!/usr/bin/env python3
"""USER.SPR의 캐릭터 0 프레임을 원작 VGA 열 순서로 추출한다.

원본 포맷과 이번 완료 범위는 docs/sprite-format.md에 기록한다.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import struct

from PIL import Image, ImageDraw


@dataclass(frozen=True)
class EncodedFrame:
    source_offset: int
    data: bytes


@dataclass(frozen=True)
class SpriteFrame:
    width: int
    height: int
    indices: bytes
    alpha: bytes

    def to_image(self, palette):
        if len(palette) != 768 or any(channel > 63 for channel in palette):
            raise ValueError('768바이트의 6비트 VGA 팔레트가 필요합니다.')
        rgba = bytearray()
        for index, alpha in zip(self.indices, self.alpha):
            if alpha:
                rgba.extend(channel * 4 for channel in palette[index * 3:index * 3 + 3])
                rgba.append(255)
            else:
                rgba.extend(b'\0\0\0\0')
        return Image.frombytes('RGBA', (self.width, self.height), bytes(rgba))


def parse_user_spr(data):
    """외부 캐릭터 목록과 내부 프레임 목록을 읽고 모든 범위를 검사한다."""
    if len(data) < 4:
        raise ValueError('USER.SPR 헤더가 잘렸습니다.')
    header_size = struct.unpack_from('<I', data)[0]
    if header_size < 4 or header_size % 4 or header_size >= len(data):
        raise ValueError('캐릭터 오프셋 목록 크기가 잘못되었습니다.')
    offsets = struct.unpack_from(f'<{header_size // 4}I', data)
    if any(a >= b for a, b in zip(offsets, offsets[1:])) or offsets[-1] >= len(data):
        raise ValueError('캐릭터 오프셋 순서 또는 범위가 잘못되었습니다.')

    characters = []
    for index, start in enumerate(offsets):
        end = offsets[index + 1] if index + 1 < len(offsets) else len(data)
        if end - start < 8:
            raise ValueError(f'캐릭터 {index}의 프레임 목록이 잘렸습니다.')
        count = struct.unpack_from('<I', data, start)[0]
        blob_start = start + 8 + count * 4
        if count == 0 or blob_start > end:
            raise ValueError(f'캐릭터 {index}의 프레임 개수가 잘못되었습니다.')
        frame_offsets = struct.unpack_from(f'<{count}I', data, start + 4)
        blob_size = struct.unpack_from('<I', data, blob_start - 4)[0]
        if blob_size != end - blob_start:
            raise ValueError(f'캐릭터 {index}의 그래픽 길이가 맞지 않습니다.')
        if (frame_offsets[0] != 0 or frame_offsets[-1] >= blob_size
                or any(a >= b for a, b in zip(frame_offsets, frame_offsets[1:]))):
            raise ValueError(f'캐릭터 {index}의 프레임 오프셋 범위가 잘못되었습니다.')
        frames = []
        for frame_index, frame_start in enumerate(frame_offsets):
            frame_end = frame_offsets[frame_index + 1] if frame_index + 1 < count else blob_size
            absolute = blob_start + frame_start
            frames.append(EncodedFrame(absolute, data[absolute:blob_start + frame_end]))
        characters.append(tuple(frames))
    return tuple(characters)


def decode_frame(data):
    """4개 평면 × 세로 열 × skip/count 구간으로 저장된 프레임을 복원한다."""
    if len(data) < 4:
        raise ValueError('프레임 크기 헤더가 잘렸습니다.')
    width, height = struct.unpack_from('<HH', data)
    if not (0 < width <= 352 and 0 < height <= 256):
        raise ValueError('원작 화면 버퍼 범위를 벗어난 프레임 크기입니다.')
    indices, alpha = bytearray(width * height), bytearray(width * height)
    position = 4

    def read_byte():
        nonlocal position
        if position >= len(data):
            raise ValueError('열 또는 구간 정보가 잘렸습니다.')
        value = data[position]
        position += 1
        return value

    for plane in range(4):
        columns = read_byte()
        expected_columns = max(0, (width + 3 - plane) // 4)
        if columns != expected_columns:
            raise ValueError('VGA 평면의 열 수와 프레임 폭이 맞지 않습니다.')
        for column in range(columns):
            spans = read_byte()
            y = 0
            for _ in range(spans):
                y += read_byte()
                count = read_byte()
                if count and y + count > height:
                    raise ValueError('픽셀 구간이 프레임 높이를 벗어났습니다.')
                if position + count > len(data):
                    raise ValueError('픽셀 구간 데이터가 잘렸습니다.')
                x = plane + column * 4
                for color in data[position:position + count]:
                    pixel = y * width + x
                    indices[pixel], alpha[pixel] = color, 255
                    y += 1
                position += count
    if position != len(data):
        raise ValueError('프레임 뒤에 해석하지 않은 바이트가 남았습니다.')
    return SpriteFrame(width, height, bytes(indices), bytes(alpha))


def make_contact_sheet(images):
    cell_width = max(image.width for image in images) + 8
    cell_height = max(image.height for image in images) + 20
    columns = 8
    sheet = Image.new('RGB', (columns * cell_width, ((len(images) + columns - 1) // columns) * cell_height), '#18222e')
    draw = ImageDraw.Draw(sheet)
    for index, image in enumerate(images):
        x, y = index % columns * cell_width, index // columns * cell_height
        draw.text((x + 4, y + 2), f'{index:03d}', fill='#d9e3ed')
        sheet.paste(image, (x + (cell_width - image.width) // 2, y + 16), image)
    return sheet


def extract_kaman(game_dir, output_root, preview_path=None):
    game_dir, output_root = Path(game_dir), Path(output_root)
    source = (game_dir / 'GRP/USER.SPR').read_bytes()
    palette = (game_dir / 'DATA/PROTOCOS.PAL').read_bytes()
    characters = parse_user_spr(source)
    # 선택한 모든 프레임을 검사한 뒤 출력 파일을 갱신한다.
    frames = [decode_frame(entry.data) for entry in characters[0]]
    images = [frame.to_image(palette) for frame in frames]
    relative_dir = Path('assets/sprites/user/character_00')
    directory = output_root / relative_dir
    directory.mkdir(parents=True, exist_ok=True)
    manifest = {
        'source': 'GRP/USER.SPR',
        'source_sha256': hashlib.sha256(source).hexdigest(),
        'palette': 'DATA/PROTOCOS.PAL',
        'palette_sha256': hashlib.sha256(palette).hexdigest(),
        'character_index': 0,
        'character_count': len(characters),
        'frame_count': len(frames),
        'frames': [],
    }
    for index, (entry, frame, image) in enumerate(zip(characters[0], frames, images)):
        filename = f'frame_{index:03d}.png'
        image.save(directory / filename)
        manifest['frames'].append({
            'index': index, 'file': filename, 'width': frame.width, 'height': frame.height,
            'source_offset': entry.source_offset, 'source_size': len(entry.data),
        })
    (directory / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # 현재 데모가 참조하는 경로를 유지하고 첫 프레임으로 교체한다.
    images[0].save(output_root / 'assets/sprites/kaman_test.png')
    if preview_path:
        preview_path = Path(preview_path)
        preview_path.parent.mkdir(parents=True, exist_ok=True)
        make_contact_sheet(images).save(preview_path)
    print(f'USER.SPR 캐릭터 0: {len(frames)}프레임 추출 완료')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--preview', type=Path)
    args = parser.parse_args()
    extract_kaman(args.game_dir, args.output_root, args.preview)


if __name__ == '__main__':
    main()
