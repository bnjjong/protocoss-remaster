#!/usr/bin/env python3
"""원작 실행 파일의 VGA 타일 함수를 실행해 추출 배경과 픽셀 단위로 비교한다.

Pillow와 Unicorn이 필요하다. 원본 실행 파일은 사용자 폴더에서만 읽는다.
주소가 확인된 판본의 SHA-256만 허용하며, 원본·압축 해제한 EXE는 저장하지 않는다.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from PIL import Image
from unicorn import Uc, UC_ARCH_X86, UC_MODE_16, UC_MODE_32, UC_HOOK_CODE, UC_HOOK_INSN, UC_HOOK_MEM_WRITE
from unicorn.x86_const import (UC_X86_REG_AX, UC_X86_REG_DX, UC_X86_REG_DS,
                              UC_X86_REG_SS, UC_X86_REG_SP, UC_X86_REG_CS,
                              UC_X86_REG_IP, UC_X86_REG_EIP, UC_X86_REG_ESP, UC_X86_REG_EAX,
                              UC_X86_REG_EDX, UC_X86_REG_EBX, UC_X86_REG_ECX,
                              UC_X86_INS_OUT)

from decode_map import read_offsets, render_map_preview

EXPECTED_HASHES = {
    'AMOS.EXE': 'f1d41530a2db4205e30bdbb4a4c8e1d6a88ab55dd65e754e716741531360c434',
    'PROTOCOS.FNT': '5c46d2e89007baf00cb463c549e5cb6bbdffafe15859415dcbf595181fe6d6e7',
}


def unpack_game(game_dir):
    files = {}
    for name, expected in EXPECTED_HASHES.items():
        content = (game_dir / name).read_bytes()
        if hashlib.sha256(content).hexdigest() != expected:
            raise ValueError(f'분석한 원작 판본과 다릅니다: {name}')
        files[name] = content
    exe = bytearray(files['AMOS.EXE'])
    archive = files['PROTOCOS.FNT']
    name_length = archive[14]
    assert bytes(c ^ 0x7f for c in archive[15:15 + name_length]) == b'PROTOCOS.EXE'
    source = archive[15 + name_length:]
    original_size = struct.unpack_from('<I', archive, 10)[0]
    base = 0x1000
    # MZ 재배치 항목을 적용해 로더의 실제 압축 해제 코드를 실행한다.
    for i in range(struct.unpack_from('<H', exe, 6)[0]):
        off, seg = struct.unpack_from('<HH', exe, 0x3e + i * 4)
        pos = 2048 + seg * 16 + off
        struct.pack_into('<H', exe, pos, (struct.unpack_from('<H', exe, pos)[0] + base) & 65535)
    cpu = Uc(UC_ARCH_X86, UC_MODE_16)
    cpu.mem_map(0, 2 * 1024 * 1024)
    cpu.mem_write(base * 16, bytes(exe[2048:54896]))
    ds = (base + 0x6e7) * 16
    for reg, value in ((UC_X86_REG_DS, base + 0x6e7), (UC_X86_REG_SS, 0x8000),
                       (UC_X86_REG_SP, 0xff00), (UC_X86_REG_CS, base + 0x437),
                       (UC_X86_REG_IP, 0x5e6)):
        cpu.reg_write(reg, value)
    cpu.mem_write(0x8ff00, struct.pack('<HH', 0, 0x9000))
    cpu.mem_write(ds + 0x5e10, struct.pack('<I', original_size))
    output = bytearray()
    bit_position = 0

    def return_far():
        sp = cpu.reg_read(UC_X86_REG_SP)
        stack = cpu.reg_read(UC_X86_REG_SS) * 16
        ip, cs = struct.unpack('<HH', cpu.mem_read(stack + sp, 4))
        cpu.reg_write(UC_X86_REG_SP, sp + 4)
        cpu.reg_write(UC_X86_REG_CS, cs)
        cpu.reg_write(UC_X86_REG_IP, ip)

    def argument(index):
        address = cpu.reg_read(UC_X86_REG_SS) * 16 + cpu.reg_read(UC_X86_REG_SP) + 4 + index * 2
        return struct.unpack('<H', cpu.mem_read(address, 2))[0]

    def refresh_bits():
        offset, shift = divmod(bit_position, 8)
        chunk = source[offset:offset + 4].ljust(4, b'\0')
        value = int.from_bytes(chunk, 'big') >> (16 - shift)
        cpu.mem_write(ds + 0x5e06, struct.pack('<H', value & 65535))

    def hook(_cpu, address, _size, _user):
        nonlocal bit_position
        relative = address - base * 16
        if relative == 0x4da * 16 + 0x697:  # 메모리 확보 결과를 돌려준다.
            cpu.reg_write(UC_X86_REG_AX, 0)
            cpu.reg_write(UC_X86_REG_DX, 0x7000)
        elif relative == 0x4da * 16 + 0x3da:
            bit_position = 0
            refresh_bits()
        elif relative == 0x4da * 16 + 0x3fa:
            bit_position += argument(0)
            refresh_bits()
        elif relative == 0x4da * 16 + 0x311:
            off, seg, size = argument(0), argument(1), argument(2)
            output.extend(cpu.mem_read(seg * 16 + off, size))
        elif relative != 0x1b41:
            return
        return_far()

    cpu.hook_add(UC_HOOK_CODE, hook)
    cpu.emu_start((base + 0x437) * 16 + 0x5e6, 0x90000, timeout=120_000_000, count=200_000_000)
    # 로더는 마지막 복사 단위를 완성한 뒤 길이를 검사한다.
    if len(output) < original_size or output[:2] != b'MZ':
        raise ValueError('원작 실행 파일 압축 해제에 실패했습니다.')
    return bytes(output[:original_size])


def render_original(game_exe, block, cells, width, height):
    le = game_exe.find(b'LE')
    object_table = struct.unpack_from('<I', game_exe, le + 0x40)[0]
    data_pages = struct.unpack_from('<I', game_exe, le + 0x80)[0]
    size, base, _, page, _, _ = struct.unpack_from('<6I', game_exe, le + object_table)
    start = data_pages + (page - 1) * 4096
    cpu = Uc(UC_ARCH_X86, UC_MODE_32)
    cpu.mem_map(0, 0x400000)
    cpu.mem_write(base, game_exe[start:start + size])
    cpu.mem_write(0x100000, block[770:64770])
    cpu.mem_write(0x1568, struct.pack('<I', 0x100000))
    # 원작 화면 버퍼는 가로 352픽셀로, VGA 평면 하나의 행은 88바이트다.
    cpu.mem_write(0x167c, b''.join(struct.pack('<I', y * 88) for y in range(height * 8)))
    pixels = bytearray(width * height * 64)
    plane_mask = 1

    def port(_cpu, number, _size, value, _user):
        nonlocal plane_mask
        if number == 0x3c5:
            plane_mask = value

    def write(_cpu, _access, address, size, value, _user):
        if not 0xa0000 <= address < 0xa0000 + height * 8 * 88:
            return
        y, column = divmod(address - 0xa0000, 88)
        for byte in range(size):
            for plane in range(4):
                x = (column + byte) * 4 + plane
                if plane_mask & (1 << plane) and x < width * 8:
                    pixels[y * width * 8 + x] = (value >> (byte * 8)) & 255

    cpu.hook_add(UC_HOOK_INSN, port, None, 1, 0, UC_X86_INS_OUT)
    cpu.hook_add(UC_HOOK_MEM_WRITE, write)
    for index, (tid, _) in enumerate(cells):
        cpu.reg_write(UC_X86_REG_ESP, 0x300000)
        cpu.mem_write(0x300000, struct.pack('<I', 0x200000))
        for reg, value in ((UC_X86_REG_EAX, index % width * 8),
                           (UC_X86_REG_EDX, index // width * 8),
                           (UC_X86_REG_EBX, tid & 0x0fff), (UC_X86_REG_ECX, 0xa0000)):
            cpu.reg_write(reg, value)
        cpu.emu_start(0x2d19e, 0x200000, count=1000)
        if cpu.reg_read(UC_X86_REG_EIP) != 0x200000:
            raise ValueError('원작 타일 그리기가 끝나지 않았습니다.')
    image = Image.frombytes('P', (width * 8, height * 8), bytes(pixels))
    image.putpalette([channel * 4 for channel in block[2:770]])
    return image.convert('RGBA')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--preview', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    source = args.game_dir / 'MAP'
    # 이번 감사 대상은 첫 오두막의 정적 배경이다.
    soc_offsets = read_offsets(source / 'AREA00.SDA')
    map_offsets = read_offsets(source / 'AREA00.MDA')
    soc = (source / 'AREA00.SOC').read_bytes()
    block = soc[soc_offsets[1]:soc_offsets[2]]
    data = (source / 'AREA00.MAP').read_bytes()[map_offsets[1]:map_offsets[2]]
    width, height = data[2] * 40, data[3] * 25
    if (width, height) != (40, 25) or data[4:12] != b'SA01.PCX':
        raise ValueError('검증 대상 오두막 맵과 다릅니다.')
    cells = list(struct.iter_unpack('<HH', data[48:48 + width * height * 4]))
    expected = render_original(unpack_game(args.game_dir), block, cells, width, height)
    with Image.open(args.preview) as actual:
        actual = actual.convert('RGBA')
        if actual.size != expected.size or actual.tobytes() != expected.tobytes():
            raise ValueError('원작 그리기 결과와 배경 이미지가 다릅니다.')
    info = json.loads((args.output_root / 'assets/data/maps/area00_map.json').read_text())
    with Image.open(args.output_root / 'assets/sprites/maps/area00_tileset.png') as atlas:
        generated = render_map_preview(info, atlas.convert('RGBA'))
    if generated.size != expected.size or generated.tobytes() != expected.tobytes():
        raise ValueError('실제 PNG·JSON 조합이 원작 배경과 다릅니다.')
    print(f'원작 VGA 함수와 {width * height * 64:,}픽셀 전체 일치')


if __name__ == '__main__':
    main()
