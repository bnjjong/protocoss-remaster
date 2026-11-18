#!/usr/bin/env python3
"""북마겔 전투 위치·편성·초기 상태와 몬스터 그림을 원작 함수로 검증한다."""
import argparse, base64, hashlib, json, struct
from pathlib import Path
from PIL import Image
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EDX, UC_X86_REG_EBX, UC_X86_REG_ECX, UC_X86_REG_ESP, UC_X86_REG_EIP
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game, read_offsets, EXPECTED_HASHES
from verify_original_user_sprite import OriginalSpriteRenderer
from decode_title import parse_img
from decode_user_sprite import decode_frame


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def native_formation(exe, background, enemies, sprites, palette):
    """배경에 원작 VGA 스프라이트 함수를 호출한다. 파티·메뉴·진입 효과는 그리지 않는다."""
    renderer = OriginalSpriteRenderer(exe)
    renderer.width, renderer.height = 352, 232
    renderer.indices = bytearray(352*232)
    renderer.alpha = bytearray([255])*(352*232)
    for y in range(200):
        renderer.indices[(y+16)*352+16:(y+16)*352+336] = background[y*320:(y+1)*320]
    c = renderer.cpu
    c.mem_write(0x167c,b''.join(struct.pack('<I',y*88) for y in range(232)))
    for enemy in enemies:
        c.mem_write(renderer.SOURCE, sprites[enemy['monster']][4:])
        c.mem_write(0x300000,struct.pack('<I',renderer.RETURN))
        for register, value in ((UC_X86_REG_ESP,0x300000),(UC_X86_REG_EAX,enemy['x']+16),
                (UC_X86_REG_EDX,enemy['y']+16),(UC_X86_REG_EBX,renderer.SOURCE),(UC_X86_REG_ECX,renderer.VRAM)):
            c.reg_write(register,value)
        c.emu_start(0x2d11a,renderer.RETURN,count=3000000)
        if c.reg_read(UC_X86_REG_EIP) != renderer.RETURN:
            raise ValueError('원작 편성 그림 함수가 종료되지 않았습니다.')
    image=Image.frombytes('P',(352,232),bytes(renderer.indices))
    image.putpalette([v*4 for v in palette])
    return image.crop((16,16,336,216)).convert('RGBA')


def formations(raw):
    if len(raw) % 32:
        raise ValueError('MON_GRP 레코드가 잘렸습니다.')
    result = []
    for group in range(len(raw)//32):
        row = struct.unpack_from('<15hBB', raw, group*32)
        enemies = []
        for i in range(5):
            if row[10+i] == 0:
                break
            if not 1 <= row[10+i] <= 123:
                raise ValueError('몬스터 번호 범위 오류')
            enemies.append(dict(monster=row[10+i]-1, x=row[i], y=row[5+i]))
        result.append(dict(id=group, background=row[15], enemies=enemies))
    return result


class BattleReference(OriginalFieldMachine):
    """전투 알고리즘은 실제 기계어로 실행하고 파일·할당·화면 I/O만 공급한다."""
    def __init__(self, executable, game):
        super().__init__(executable)
        self.cpu.mem_map(0x400000, 0x1c00000)
        self.game = game
        self.files = {}
        self.heap = 0x400000
        self.requests = []
        self.cpu.hook_add(UC_HOOK_CODE, self.hook)

    def hook(self, c, address, size, user):
        targets = (0x682f2, 0x67f5a, 0x67a0a, 0x10590, 0x681ad, 0x681a8, 0x66f6e, 0x68357, 0x68332, 0x1217c, 0x122b0, 0x106b0)
        if address not in targets:
            return
        a, d, b, sp = [c.reg_read(r) for r in (UC_X86_REG_EAX, UC_X86_REG_EDX, UC_X86_REG_EBX, UC_X86_REG_ESP)]
        cleanup = 0
        if address == 0x682f2:
            cleanup = 4
        elif address == 0x67f5a:
            pointer = struct.unpack('<I', c.mem_read(sp+4, 4))[0]
            name = bytes(c.mem_read(pointer, 200)).split(b'\0')[0].decode('ascii').replace('\\', '/').removeprefix('./')
            handle = len(self.files)+1
            self.files[handle] = [(self.game/name).read_bytes(), 0]
            c.reg_write(UC_X86_REG_EAX, handle)
        elif address == 0x67a0a:
            c.reg_write(UC_X86_REG_EAX, len(self.files[a][0]))
        elif address == 0x10590:
            if self.heap+a > 0x2000000:
                raise ValueError('분석 메모리 할당 범위 초과')
            c.reg_write(UC_X86_REG_EAX, self.heap)
            c.mem_write(self.heap, bytes(a))
            self.heap += (a+15)//16*16
        elif address == 0x681ad:
            raw, pos = self.files[a]
            chunk = raw[pos:pos+b]
            if len(chunk) != b:
                raise ValueError('원작 파일 읽기가 범위를 넘었습니다.')
            c.mem_write(d, chunk)
            self.files[a][1] += b
            c.reg_write(UC_X86_REG_EAX, b)
        elif address == 0x68357:
            if b != 0:
                raise ValueError('미지원 파일 탐색 모드')
            self.files[a][1] = d
            c.reg_write(UC_X86_REG_EAX, d)
        elif address == 0x68332:
            # DOS 세그먼트 레지스터를 바꾸는 memcpy의 요청 범위만 복사한다.
            c.mem_write(a, bytes(c.mem_read(d, b)))
        elif address == 0x122b0:
            self.requests.append([a, d])
        elif address == 0x106b0:
            raise ValueError('원작 전투 로더가 오류를 보고했습니다.')
        ret = struct.unpack('<I', c.mem_read(sp, 4))[0]
        c.reg_write(UC_X86_REG_ESP, sp+4+cleanup)
        c.reg_write(UC_X86_REG_EIP, ret)

    def formation(self, group):
        self.heap = 0x400000
        self.files.clear()
        self.call(0x5f96b, group)
        background = self.cpu.reg_read(UC_X86_REG_EAX)
        if background == 0xffffffff:
            return None
        self.call(0x5fb3f)
        self.call(0x5fc84)
        self.call(0x605f9, background)
        ptr = struct.unpack('<I', self.cpu.mem_read(0x72008, 4))[0]
        enemies = []
        for i in range(self.byte(0x2015)):
            row = bytes(self.cpu.mem_read(ptr+i*141, 141))
            monster = struct.unpack_from('<I', row, 12)[0]
            x, y = struct.unpack_from('<hh', row, 36)
            sprite = struct.unpack_from('<I', row, 48)[0]
            raw_sprite = parse_img((self.game/'GRP/MONSTER.SPR').read_bytes())[monster]
            if bytes(self.cpu.mem_read(sprite, len(raw_sprite))) != raw_sprite:
                raise ValueError('원작이 읽은 몬스터 프레임이 다릅니다.')
            enemies.append(dict(monster=monster, x=x-16, y=y-16, parameter_hex=row[52:].hex(),
                                initial_state=list(struct.unpack_from('<9I', row)), counter=struct.unpack_from('<I', row, 24)[0]))
        back = struct.unpack('<I', self.cpu.mem_read(0x72010, 4))[0]
        return dict(background=background, enemies=enemies, background_sha256=digest(bytes(self.cpu.mem_read(back, 64000))))

    def poll(self, x, y, sx=0, sy=0, busy=0, enabled=1, protected=0):
        self.requests.clear()
        self.dword(0xd7fc, sx); self.dword(0xd800, sy)
        self.dword(0xe1e4, x-sx*8+16); self.dword(0xe1e8, y-sy*8+16)
        self.word(0xe512, busy); self.word(0x1574, enabled); self.word(0xe042, protected)
        c = self.cpu
        c.mem_write(0x300000, struct.pack('<I', 0x3f0000))
        c.reg_write(UC_X86_REG_ESP, 0x300000)
        c.emu_start(0x13148, 0x13199, count=20000)
        if c.reg_read(UC_X86_REG_EIP) != 0x13199:
            raise ValueError('원작 전투 위치 검사 종료 실패')
        return self.requests[:]


def extract(game, output, executable=None):
    game, output = Path(game).resolve(), Path(output).resolve()
    if output == game or game in output.parents:
        raise ValueError('원본 폴더에는 쓰지 않습니다.')
    exe = executable if executable is not None else unpack_game(game)
    raw = (game/'DATA/MON_GRP.LST').read_bytes()
    groups = formations(raw)
    offsets = read_offsets(game/'MAP/AREA00.MDA')
    sub = (game/'MAP/AREA00.MAP').read_bytes()[offsets[13]:offsets[14]]
    width, height = sub[2]*40, sub[3]*25
    bat = (game/'MAP/BAT013.MAP').read_bytes()
    cells = list(struct.unpack(f'<{width*height}H', bat))
    used = sorted(set(cells)-{65535})
    reference = BattleReference(exe, game)
    reference.load_map(sub)
    reference.dword(0x156c, 0x260000)
    outcomes = bytearray()
    for i in range(width*height):
        reference.cpu.mem_write(0x260000, bat)
        result = reference.poll((i%width-1)*8, (i//width-2)*8)
        expected = [] if cells[i] == 65535 else [[cells[i], 0]]
        if result != expected:
            raise ValueError(f'전투 위치 검사 불일치: {i}')
        outcomes.extend(struct.pack('<H', result[0][0] if result else 65535))
    if outcomes != bat:
        raise ValueError('원작 전투 위치 결과가 다릅니다.')
    cases = []
    for i, group in enumerate(cells):
        if group == 65535:
            continue
        x, y = (i%width-1)*8, (i//width-2)*8
        for busy, enabled, protected in ((0,1,0),(1,1,0),(65535,1,0),(0,0,0),(0,1,1),(0,1,65535)):
            for sx, sy, dx, dy in ((0,0,0,0),(17,9,7,7)):
                reference.cpu.mem_write(0x260000, bat)
                result = reference.poll(x+dx,y+dy,sx,sy,busy,enabled,protected)
                second = reference.poll(x+dx,y+dy,sx,sy,busy,enabled,protected)
                after = struct.unpack('<H', reference.cpu.mem_read(0x260000+i*2,2))[0]
                cases.append(dict(x=x+dx,y=y+dy,scroll=[sx,sy],busy=busy,enabled=enabled,protected=protected,
                                  group=group,first=result,second=second,after=after))
    native_groups = []
    for group in groups:
        native = reference.formation(group['id'])
        if not group['enemies']:
            if native is not None:
                raise ValueError('빈 편성 처리 불일치')
            continue
        if native['background'] != group['background'] or [{k:r[k] for k in ('monster','x','y')} for r in native['enemies']] != group['enemies']:
            raise ValueError(f"원작 적 편성 불일치: {group['id']}")
        if group['id'] in used:
            native_groups.append(dict(id=group['id'], **native))
    palette = (game/'DATA/PROTOCOS.PAL').read_bytes()
    sprites = parse_img((game/'GRP/MONSTER.SPR').read_bytes())
    renderer = OriginalSpriteRenderer(exe)
    pixels = 0
    selected_sprites = sorted({r['monster'] for g in native_groups for r in g['enemies']})
    folder = output/'assets/battle'; folder.mkdir(parents=True, exist_ok=True)
    records = []
    for i, raw_sprite in enumerate(sprites):
        frame = decode_frame(raw_sprite)
        image = frame.to_image(palette)
        expected = renderer.render(raw_sprite, palette)
        if image.tobytes() != expected.tobytes() or frame.indices != renderer.indices or frame.alpha != renderer.alpha:
            raise ValueError(f'몬스터 그림 불일치: {i}')
        pixels += image.width*image.height
        if i in selected_sprites:
            image.save(folder/f'monster-{i}.png')
            records.append(dict(id=i,width=image.width,height=image.height,rgba_sha256=digest(image.tobytes())))
    parameters = (game/'DATA/MON_PARA.LST').read_bytes()
    back_file = (game/'GRP/BACK.DAT').read_bytes()
    for g in native_groups:
        background = back_file[g['background']*64000:(g['background']+1)*64000]
        if digest(background) != g['background_sha256']:
            raise ValueError('원작 배경 로더 결과가 다릅니다.')
        canvas=Image.frombytes('P',(320,200),background)
        canvas.putpalette([v*4 for v in palette]);canvas=canvas.convert('RGBA')
        with Image.open(output/f"assets/backgrounds/battle_back_{g['background']:02d}.png") as packaged:
            if packaged.convert('RGBA').tobytes()!=canvas.tobytes():
                raise ValueError('패키지 전투 배경이 원작과 다릅니다.')
        for enemy in g['enemies']:
            canvas.alpha_composite(decode_frame(sprites[enemy['monster']]).to_image(palette),(enemy['x'],enemy['y']))
        native = native_formation(exe,background,g['enemies'],sprites,palette)
        if native.tobytes()!=canvas.tobytes():
            raise ValueError('원작 편성 미리보기 픽셀이 다릅니다.')
        canvas.save(folder/f"formation-{g['id']}.png")
        for enemy in g['enemies']:
            original = parameters[enemy['monster']*89:enemy['monster']*89+89]
            expected = bytearray(original)
            # 0x5FC84는 매개변수 +67의 int32를 최대 400으로 제한한다.
            struct.pack_into('<i', expected, 67, min(struct.unpack_from('<i',original,67)[0],400))
            if enemy['parameter_hex'] != expected.hex():
                raise ValueError('원작 초기 매개변수 불일치')
            enemy['name'] = original[:21].split(b'\0')[0].decode('ascii')
    data = dict(version=1,area=0,map=13,width=width,height=height,cells=cells,
                formations=native_groups,sprites=records)
    (folder/'first-battle.json').write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n')
    report = dict(executable_sources=EXPECTED_HASHES,sources={p:digest((game/p).read_bytes()) for p in
        ('MAP/AREA00.MAP','MAP/BAT013.MAP','DATA/MON_GRP.LST','DATA/MON_PARA.LST','GRP/MONSTER.SPR','GRP/BACK.DAT','DATA/PROTOCOS.PAL')},
        encounter_cells=width*height,trigger_count=sum(v!=65535 for v in cells),outcomes=base64.b64encode(outcomes).decode(),
        cases=cases,formation_records=len(groups),sprite_frames=len(sprites),sprite_pixels=pixels,formation_preview_pixels=len(native_groups)*64000,
        stubs='0x13148→0x124B4의 조건·좌표·소모는 실제 실행. 0x1217C 화면 전환과 0x122B0 전투 본체는 호출만 기록한다. 편성/몬스터/수치/배경 로더 0x5F96B·5FB3F·5FC84·605F9는 파일·할당·스택 검사·memcpy I/O를 공급해 실행한다. 몬스터 123장은 실제 VGA 출력 0x2D11A와 비교한다. 전투 명령·턴·피해·승패·음악과 전체 전투 화면은 이번 비교 범위에 포함하지 않는다.')
    (output/'docs/restoration/first-battle-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print(f"전투 위치 {width*height}칸·조건/반복 {len(cases)}개·편성 {len(groups)}개·몬스터 {len(sprites)}장 {pixels:,}픽셀 일치",flush=True)
    return data, report


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    a=p.parse_args();extract(a.game_dir,a.output_root)
