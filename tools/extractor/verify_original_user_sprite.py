#!/usr/bin/env python3
"""USER.SPR의 PNG·색 번호·투명도를 원작 x86 함수와 비교한다.

원본 게임의 압축 해제와 판본 확인은 기존 맵 검증 도구를 재사용한다.
실행 파일은 저장하지 않으며 Pillow와 Unicorn이 필요하다.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from PIL import Image
from unicorn import Uc, UC_ARCH_X86, UC_MODE_32, UC_HOOK_INSN, UC_HOOK_MEM_WRITE
from unicorn.x86_const import (UC_X86_INS_OUT, UC_X86_REG_EAX, UC_X86_REG_EBX,
                              UC_X86_REG_ECX, UC_X86_REG_EDX, UC_X86_REG_EIP,
                              UC_X86_REG_ESP)

from verify_original_map import unpack_game


CHARACTER_FRAME_COUNTS = (55, 66, 44, 55, 58, 43, 63, 48, 72, 47, 48)


def reference_frames(source, character_index=0):
    """제품 파서와 별개로 확인된 판본의 프레임 오프셋 목록을 읽는다."""
    if len(source) < 44 or struct.unpack_from('<I', source)[0] != 44:
        raise ValueError('분석한 USER.SPR 캐릭터 목록과 다릅니다.')
    if character_index not in range(11):
        raise ValueError('검증할 캐릭터 번호가 범위를 벗어났습니다.')
    starts = struct.unpack_from('<11I', source)
    start = starts[character_index]
    end = starts[character_index + 1] if character_index < 10 else len(source)
    if not 44 <= start < end <= len(source) or end - start < 8:
        raise ValueError('캐릭터 데이터 범위가 잘못되었습니다.')
    count = struct.unpack_from('<I', source, start)[0]
    if count != CHARACTER_FRAME_COUNTS[character_index]:
        raise ValueError('분석한 캐릭터의 프레임 수와 다릅니다.')
    offsets = struct.unpack_from(f'<{count}I', source, start + 4)
    blob = start + 4 + count * 4 + 4
    length = struct.unpack_from('<I', source, blob - 4)[0]
    if length != end - blob or offsets[0] != 0:
        raise ValueError('캐릭터 그래픽 길이가 맞지 않습니다.')
    bounds = (*offsets, length)
    if any(a >= b for a, b in zip(bounds, bounds[1:])):
        raise ValueError('캐릭터 프레임 범위가 잘못되었습니다.')
    return [(blob + a, source[blob + a:blob + b]) for a, b in zip(bounds, bounds[1:])]


class OriginalSpriteRenderer:
    VRAM = 0xa0000
    SOURCE = 0x100000
    RETURN = 0x3f0000

    def __init__(self, executable):
        le = executable.find(b'LE')
        objects = struct.unpack_from('<I', executable, le + 0x40)[0]
        data_pages = struct.unpack_from('<I', executable, le + 0x80)[0]
        size, base, _, page, _, _ = struct.unpack_from('<6I', executable, le + objects)
        start = data_pages + (page - 1) * 4096
        self.cpu = Uc(UC_ARCH_X86, UC_MODE_32)
        self.cpu.mem_map(0, 0x400000)
        self.cpu.mem_write(base, executable[start:start + size])
        self.cpu.hook_add(UC_HOOK_INSN, self._port, None, 1, 0, UC_X86_INS_OUT)
        self.cpu.hook_add(UC_HOOK_MEM_WRITE, self._write,
                          begin=self.VRAM, end=self.VRAM + 256 * 88 - 1)
        self.width = self.height = 0
        self.plane_mask = 1
        self.indices = self.alpha = bytearray()

    def _port(self, _cpu, port, _size, value, _user):
        if port == 0x3c5:
            self.plane_mask = value

    def _write(self, _cpu, _access, address, size, value, _user):
        row, column = divmod(address - self.VRAM, 88)
        if row >= self.height:
            raise ValueError('원작 함수가 프레임 높이 밖에 픽셀을 썼습니다.')
        for byte in range(size):
            for plane in range(4):
                if self.plane_mask & (1 << plane):
                    x = (column + byte) * 4 + plane
                    if x >= self.width:
                        raise ValueError('원작 함수가 프레임 폭 밖에 픽셀을 썼습니다.')
                    pixel = row * self.width + x
                    self.indices[pixel] = (value >> (byte * 8)) & 255
                    self.alpha[pixel] = 255

    def render(self, raw, palette):
        self.width, self.height = struct.unpack_from('<HH', raw)
        self.indices = bytearray(self.width * self.height)
        self.alpha = bytearray(self.width * self.height)
        self.plane_mask = 1
        cpu = self.cpu
        cpu.mem_write(self.SOURCE, raw[4:])
        cpu.mem_write(0x167c, b''.join(struct.pack('<I', row * 88) for row in range(self.height)))
        cpu.mem_write(0x300000, struct.pack('<I', self.RETURN))
        for register, value in ((UC_X86_REG_ESP, 0x300000), (UC_X86_REG_EAX, 0),
                                (UC_X86_REG_EDX, 0), (UC_X86_REG_EBX, self.SOURCE),
                                (UC_X86_REG_ECX, self.VRAM)):
            cpu.reg_write(register, value)
        # 픽셀 해석은 Python에서 흉내 내지 않고 원작 기계어를 실행한다.
        cpu.emu_start(0x2d11a, self.RETURN, count=3_000_000)
        if cpu.reg_read(UC_X86_REG_EIP) != self.RETURN:
            raise ValueError('원작 스프라이트 함수가 끝나지 않았습니다.')
        rgba = bytearray()
        for index, alpha in zip(self.indices, self.alpha):
            if alpha:
                rgba.extend(channel * 4 for channel in palette[index * 3:index * 3 + 3])
                rgba.append(255)
            else:
                rgba.extend(b'\0\0\0\0')
        return Image.frombytes('RGBA', (self.width, self.height), bytes(rgba))


def verify_characters(game_dir, output_root, character_indices=None):
    source = (game_dir / 'GRP/USER.SPR').read_bytes()
    palette = (game_dir / 'DATA/PROTOCOS.PAL').read_bytes()
    if len(palette) != 768 or max(palette) > 63:
        raise ValueError('원작 VGA 팔레트가 잘못되었습니다.')
    selected = tuple(range(11)) if character_indices is None else tuple(character_indices)
    if (not selected or len(set(selected)) != len(selected)
            or any(index not in range(11) for index in selected)):
        raise ValueError('검증할 캐릭터 선택이 잘못되었습니다.')
    renderer = OriginalSpriteRenderer(unpack_game(game_dir))
    pixel_count = frame_count = 0
    for character in selected:
        references = reference_frames(source, character)
        directory = output_root / f'assets/sprites/user/character_{character:02d}'
        manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        if (manifest['source'] != 'GRP/USER.SPR' or manifest['palette'] != 'DATA/PROTOCOS.PAL'
                or manifest['source_sha256'] != hashlib.sha256(source).hexdigest()
                or manifest['palette_sha256'] != hashlib.sha256(palette).hexdigest()
                or manifest['character_index'] != character or manifest['character_count'] != 11
                or manifest['frame_count'] != len(references) or len(manifest['frames']) != len(references)):
            raise ValueError('프레임 목록의 원본 식별 정보가 다릅니다.')
        for index, (offset, raw) in enumerate(references):
            expected = renderer.render(raw, palette)
            record = manifest['frames'][index]
            filename = f'frame_{index:03d}.png'
            index_filename = f'indices/{filename}'
            if record != {'index': index, 'file': filename, 'width': expected.width,
                           'height': expected.height, 'source_offset': offset,
                           'source_size': len(raw), 'index_alpha_file': index_filename}:
                raise ValueError(f'캐릭터 {character} 프레임 {index}의 원본 위치·크기가 다릅니다.')
            with Image.open(directory / filename) as image:
                if image.size != expected.size or image.convert('RGBA').tobytes() != expected.tobytes():
                    raise ValueError(f'캐릭터 {character} 프레임 {index}의 RGBA가 원작과 다릅니다.')
            native_indices = bytes(value for pair in zip(renderer.indices, renderer.alpha) for value in pair)
            with Image.open(directory / index_filename) as image:
                if image.mode != 'LA' or image.size != expected.size or image.tobytes() != native_indices:
                    raise ValueError(f'캐릭터 {character} 프레임 {index}의 색 번호·투명도가 원작과 다릅니다.')
            if character == 0 and index == 0:
                with Image.open(output_root / 'assets/sprites/kaman_test.png') as image:
                    if image.size != expected.size or image.convert('RGBA').tobytes() != expected.tobytes():
                        raise ValueError('현재 데모 텍스처가 원작 첫 프레임과 다릅니다.')
            pixel_count += expected.width * expected.height
            frame_count += 1
        print(f'캐릭터 {character:02d}: {len(references)}프레임 일치', flush=True)
    print(f'USER.SPR {len(selected)}개 캐릭터·{frame_count}프레임: {pixel_count:,}픽셀의 RGBA·색 번호·투명도 전체 일치')
    return pixel_count


def verify_kaman(game_dir, output_root):
    return verify_characters(game_dir, output_root, [0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument('--character', type=int, default=0)
    selection.add_argument('--all-characters', action='store_true')
    args = parser.parse_args()
    selected = None if args.all_characters else [args.character]
    verify_characters(args.game_dir, args.output_root, selected)


if __name__ == '__main__':
    main()
