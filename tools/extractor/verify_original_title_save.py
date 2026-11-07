#!/usr/bin/env python3
"""원작 그림 함수·저장 로딩 함수를 실행해 시작 메뉴와 DOS 저장 구조를 대조한다."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import struct
from PIL import Image
from unicorn import Uc, UC_ARCH_X86, UC_MODE_32, UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EBX, UC_X86_REG_ECX, UC_X86_REG_EDX, UC_X86_REG_ESP, UC_X86_REG_EIP
from decode_title import parse_img, decode_img_frame, menu_image
from verify_original_map import unpack_game, render_original, read_offsets
from verify_original_user_sprite import OriginalSpriteRenderer


def objects(executable):
    le = executable.find(b'LE')
    table = struct.unpack_from('<I', executable, le + 0x40)[0]
    pages = struct.unpack_from('<I', executable, le + 0x80)[0]
    result = []
    for index in range(2):
        size, base, _, page, _, _ = struct.unpack_from('<6I', executable, le + table + index * 24)
        start = pages + (page - 1) * 4096
        result.append((base, executable[start:start + size]))
    return result


def read_original_save(executable, raw, dos_time=None):
    if len(raw) != 2944:
        raise ValueError('원작 저장 길이가 다릅니다.')
    code, data = objects(executable)
    cpu = Uc(UC_ARCH_X86, UC_MODE_32)
    cpu.mem_map(0, 0x400000)
    # 상대 분기만 쓰는 로딩 함수를 위로 옮겨 DS의 큰 전역 상태와 겹치지 않게 한다.
    cpu.mem_write(code[0] + 0x100000, code[1])
    cpu.mem_write(0, data[1])
    cpu.mem_write(0x300000, struct.pack('<I', 0x3f0000))
    cpu.reg_write(UC_X86_REG_ESP, 0x300000)
    cpu.reg_write(UC_X86_REG_EAX, 0xffffffff)
    reads = []
    position = 0
    def hook(c, address, _size, _user):
        nonlocal position
        target = address - 0x100000
        if target not in (0x67163, 0x671f9, 0x67c9c, 0x673c3, 0x66368):
            return
        if target == 0x67163:  # fopen: 사용자 파일을 읽는 가상 스트림
            c.reg_write(UC_X86_REG_EAX, 0x200000)
        elif target == 0x671f9:  # fread: 원작이 요청한 목적지·길이를 그대로 기록
            destination = c.reg_read(UC_X86_REG_EAX)
            length = c.reg_read(UC_X86_REG_EDX) * c.reg_read(UC_X86_REG_EBX)
            chunk = raw[position:position + length]
            if len(chunk) != length:
                raise ValueError('원작 로딩 함수가 파일 범위를 넘었습니다.')
            c.mem_write(destination, chunk)
            reads.append((position, length, destination))
            position += length
            c.reg_write(UC_X86_REG_EAX, 1)
        elif target == 0x67c9c:
            # 압축 배포 이후 바뀐 파일 시각 때문에 원작의 능력치 초기화 분기로 가지 않게 한다.
            time = (struct.unpack_from('<H', raw, 2942)[0] ^ 0x7f7f) if dos_time is None else dos_time
            c.mem_write(c.reg_read(UC_X86_REG_EBX), struct.pack('<I', time))
            c.mem_write(c.reg_read(UC_X86_REG_EDX), b'\0' * 4)
        sp = c.reg_read(UC_X86_REG_ESP)
        ret = struct.unpack('<I', c.mem_read(sp, 4))[0]
        c.reg_write(UC_X86_REG_ESP, sp + 4)
        c.reg_write(UC_X86_REG_EIP, ret)
    cpu.hook_add(UC_HOOK_CODE, hook)
    cpu.emu_start(0x11bb74, 0x3f0000, count=100000)
    if position != 2944 or cpu.reg_read(UC_X86_REG_EIP) != 0x3f0000:
        raise ValueError('원작 저장 로딩이 끝나지 않았습니다.')
    values = [struct.unpack('<I', cpu.mem_read(address, 4))[0]
              for address in (0xe03c, 0xd7f8, 0xd7fc, 0xd800, 0xe1e4, 0xe1e8)]
    if values != list(struct.unpack_from('<6I', raw, 92)):
        raise ValueError('지역·맵·위치 필드가 원작 결과와 다릅니다.')
    # 마지막 시각는 스택으로 읽는다. 그 밖의 2,942바이트는 원작 전역 상태와 모두 비교한다.
    for offset, length, address in reads[:-1]:
        if bytes(cpu.mem_read(address, length)) != raw[offset:offset + length]:
            raise ValueError(f'원작 저장 상태가 다릅니다: {offset}')
    return {'bytes': position, 'read_operations': len(reads), 'area_map_scroll_screen': values}


def verify(game_dir, output_root, report_path, save_file=None):
    executable = unpack_game(game_dir)
    renderer = OriginalSpriteRenderer(executable)
    palette = (game_dir / 'DATA/PROTOCOS.PAL').read_bytes()
    decoded = []
    pixels = 0
    for raw in parse_img((game_dir / 'GRP/STATMENU.IMG').read_bytes()):
        frame = decode_img_frame(raw)
        expected = renderer.render(struct.pack('<HH', frame.width, frame.height) + raw, palette)
        indices, alpha = bytes(renderer.indices), bytes(renderer.alpha)
        actual = frame.to_image(palette)
        if actual.tobytes() != expected.tobytes() or frame.indices != indices or frame.alpha != alpha:
            raise ValueError('원작 메뉴 픽셀이 다릅니다.')
        pixels += frame.width * frame.height
        decoded.append(expected)
    for selection, name in enumerate(('new-game', 'continue')):
        with Image.open(output_root / f'assets/ui/title/{name}.png') as actual:
            if actual.convert('RGBA').tobytes() != menu_image(decoded, selection).tobytes():
                raise ValueError('최종 메뉴 합성이 다릅니다.')
    saves = {name: read_original_save(executable, (game_dir / 'DATA' / name).read_bytes())
             for name in ('START.DAT', 'SAVE0.DAT', 'SAVE1.DAT', 'SAVE2.DAT', 'SAVE3.DAT')}
    script = (game_dir / 'DATA/AREA00.G&M').read_bytes()
    count = struct.unpack_from('<H', script, 4)[0]
    commands = bytes(value ^ 0x7f for value in script[6 + count * 2:])
    if commands[:7] != bytes.fromhex('f0 01 00 00 00 6c 00') or commands[122] != 0x16:
        raise ValueError('분석한 새 게임 초기 분기가 아닙니다.')
    initial_warp = struct.unpack_from('<8H', commands, 123)
    if initial_warp != (2, 0, 0, 96, 56, 8, 0, 25):
        raise ValueError('새 게임 최초 필드 진입 값이 다릅니다.')
    fields = {}
    for index, name in ((2, 'new-game-field'), (16, 'dos-save-field')):
        root = game_dir / 'MAP'
        mo, so = read_offsets(root / 'AREA00.MDA'), read_offsets(root / 'AREA00.SDA')
        maps, soc = (root / 'AREA00.MAP').read_bytes(), (root / 'AREA00.SOC').read_bytes()
        sub = maps[mo[index]:mo[index + 1]]
        tile = sub[32]
        block = soc[so[tile]:so[tile + 1]]
        width, height = sub[2] * 40, sub[3] * 25
        cells = list(struct.iter_unpack('<HH', sub[48:48 + width * height * 4]))
        expected = render_original(executable, block, cells, width, height)
        preview = output_root / f'docs/images/{name}.png'
        with Image.open(preview) as actual:
            if actual.convert('RGBA').tobytes() != expected.tobytes():
                raise ValueError('진입 맵 미리보기가 원작과 다릅니다.')
        fields[name] = width * height * 64
    result = {'title_frames': len(decoded), 'title_pixels': pixels, 'save_files': saves, 'field_pixels': fields, 'initial_warp': initial_warp}
    if save_file:
        modified = datetime.fromtimestamp(save_file.stat().st_mtime)
        dos_time = (modified.hour << 11) | (modified.minute << 5) | (modified.second // 2)
        result['written_save'] = read_original_save(executable, save_file.read_bytes(), dos_time)
    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    return result

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--report', type=Path)
    parser.add_argument('--save-file', type=Path, help='실제 수정 시각을 사용해 추가 검증할 DOS 저장 파일')
    args = parser.parse_args()
    verify(args.game_dir, args.output_root, args.report, args.save_file)
