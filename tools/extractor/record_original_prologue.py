#!/usr/bin/env python3
"""PROLOGUE.EXE의 원작 코드를 실행해 VGA 화면과 시간 흐름을 기록한다."""
from pathlib import Path
import struct,hashlib,gzip,json,argparse
from PIL import Image, ImageDraw
from unicorn import *
from unicorn.x86_const import *

EXPECTED_PROLOGUE_SHA256 = 'ef4fdae66d694d1de99f45e2fa7bf32a9a4043d69d4dce4be287529c18cc7470'

def load_executable(executable):
    le = executable.find(b'LE')
    table = struct.unpack_from('<I', executable, le + 0x40)[0]
    pages = struct.unpack_from('<I', executable, le + 0x80)[0]
    memory = bytearray(0x100000)
    objects = []
    for i in range(struct.unpack_from('<I', executable, le + 0x44)[0]):
        size, base, flags, page, count, _ = struct.unpack_from('<6I', executable, le + table + i * 24)
        start = pages + (page - 1) * 4096
        data = executable[start:start + size]
        memory[base:base + len(data)] = data
        objects.append((base, page, count))
    # 이 판본은 내부 객체의 32비트 절대 주소를 고치는 7바이트 항목만 쓴다.
    ft = le + struct.unpack_from('<I', executable, le + 0x68)[0]
    rt = le + struct.unpack_from('<I', executable, le + 0x6c)[0]
    for base, first, count in objects:
        for local in range(count):
            page = first - 1 + local
            pos = rt + struct.unpack_from('<I', executable, ft + page * 4)[0]
            end = rt + struct.unpack_from('<I', executable, ft + (page + 1) * 4)[0]
            while pos < end:
                kind, flags, source, target, offset = struct.unpack_from('<BBhBH', executable, pos)
                if (kind, flags) != (7, 0): raise ValueError('분석하지 않은 LE 재배치입니다.')
                struct.pack_into('<I', memory, base + local * 4096 + source, objects[target - 1][0] + offset)
                pos += 7
    return memory

class PrologueMachine:
    BASE = 0x20000
    def __init__(self, game_dir):
        self.game_dir = Path(game_dir)
        self.cpu = Uc(UC_ARCH_X86, UC_MODE_32)
        self.cpu.mem_map(0, 32 * 1024 * 1024)
        executable = (self.game_dir / 'PROLOGUE.EXE').read_bytes()
        if hashlib.sha256(executable).hexdigest() != EXPECTED_PROLOGUE_SHA256:
            raise ValueError('분석한 PROLOGUE.EXE 판본이 아닙니다.')
        self.cpu.mem_write(0, bytes(load_executable(executable)))
        # 음악 루틴의 push/pop segment도 32비트 스택을 사용하도록 평면 세그먼트를 설정한다.
        self.cpu.mem_write(0x1c00000, bytes(8) + bytes.fromhex('ff ff 00 00 00 9a cf 00 ff ff 00 00 00 92 cf 00'))
        self.cpu.reg_write(UC_X86_REG_GDTR, (0, 0x1c00000, 23, 0))
        for register, value in ((UC_X86_REG_CS, 8), (UC_X86_REG_DS, 16), (UC_X86_REG_ES, 16), (UC_X86_REG_SS, 16)):
            self.cpu.reg_write(register, value)
        self.heap = 0x100000
        self.files = {}
        self.vga = [bytearray(65536) for _ in range(4)]
        self.latches = [0] * 4
        self.mask = 15
        self.seq = self.gc = self.mode = self.read_plane = 0
        self.palette = bytearray(768)
        self.dac = self.crtc = self.display = self.pan = 0
        self.attribute = False
        self.retrace = 0
        self.time = 0.0
        self.frames = []
        self.trace = []
        self.hooks = {0x14448:self.malloc, 0x10010:self.malloc, 0x1452b:self.noop,
                      0x147ae:self.fopen, 0x14844:self.fread, 0x14a0e:self.fclose,
                      0x14f0c:self.fseek, 0x102bc:self.wait, 0x100d0:self.wait,
                      0x14612:self.delay, 0x1275c:self.load_file,
                      0x137b5:self.music_start, 0x13af8:self.volume,
                      0x12e40:self.noop,
                      0x126e0:self.noop}
        self.cpu.hook_add(UC_HOOK_BLOCK, self.block)
        self.cpu.hook_add(UC_HOOK_INSN, self.port_in, None, 1, 0, UC_X86_INS_IN)
        self.cpu.hook_add(UC_HOOK_INSN, self.port_out, None, 1, 0, UC_X86_INS_OUT)
        self.cpu.hook_add(UC_HOOK_MEM_WRITE, self.write_vga, None, 0xa0000, 0xaffff)
        self.cpu.hook_add(UC_HOOK_MEM_READ, self.read_vga, None, 0xa0000, 0xaffff)
        self.cpu.hook_add(UC_HOOK_INTR, lambda c,n,u: None)
        self.cpu.hook_add(UC_HOOK_CODE, lambda c,a,s,u: self.advance(1/70), None, 0x11d29, 0x11d29)
        self.cpu.hook_add(UC_HOOK_CODE, lambda c,a,s,u: self.advance(1/70), None, 0x11d8e, 0x11d8e)

    def reg(self, name): return self.cpu.reg_read(name)
    def dword(self, offset): return struct.unpack('<I', self.cpu.mem_read(self.BASE + offset, 4))[0]
    def set_dword(self, offset, value): self.cpu.mem_write(self.BASE + offset, struct.pack('<I', value))
    def allocate(self, length):
        result = self.heap
        self.heap += (length + 15) // 16 * 16
        if self.heap > 28 * 1024 * 1024: raise ValueError('원작 분석 메모리가 부족합니다.')
        return result
    def ret(self, value=None):
        if value is not None: self.cpu.reg_write(UC_X86_REG_EAX, value)
        sp = self.reg(UC_X86_REG_ESP)
        address = struct.unpack('<I', self.cpu.mem_read(sp, 4))[0]
        self.cpu.reg_write(UC_X86_REG_ESP, sp + 4)
        self.cpu.reg_write(UC_X86_REG_EIP, address)
    def block(self, c, address, size, user):
        if address in self.hooks: self.hooks[address]()
    def noop(self): self.ret()
    def malloc(self): self.ret(self.allocate(self.reg(UC_X86_REG_EAX)))
    def filename(self, pointer):
        raw = bytes(self.cpu.mem_read(pointer, 260)).split(b'\0')[0].decode('ascii')
        parts = raw.replace('\\','/').lstrip('./').split('/')
        path = self.game_dir
        for part in parts:
            path = next((p for p in path.iterdir() if p.name.upper() == part.upper()), path / part)
        return path
    def fopen(self):
        path = self.filename(self.reg(UC_X86_REG_EAX))
        if not path.exists(): raise ValueError(f'원본 파일이 없습니다: {path}')
        handle = 0x1e00000 + len(self.files) * 32
        self.files[handle] = [path.read_bytes(), 0]
        self.trace.append({'time':self.time,'file':path.name})
        print('원작 파일',path.name,round(self.time,3),flush=True)
        self.ret(handle)
    def fread(self):
        data, pos = self.files[self.reg(UC_X86_REG_ECX)]
        size = self.reg(UC_X86_REG_EDX); count = self.reg(UC_X86_REG_EBX)
        chunk = data[pos:pos + size * count]
        if len(chunk) != size * count: raise ValueError('원작 fread 범위 오류')
        self.cpu.mem_write(self.reg(UC_X86_REG_EAX),chunk)
        self.files[self.reg(UC_X86_REG_ECX)][1] += len(chunk)
        self.ret(count)
    def fclose(self): self.ret(0)
    def fseek(self):
        file = self.files[self.reg(UC_X86_REG_EAX)]
        origin = self.reg(UC_X86_REG_EBX)
        position = self.reg(UC_X86_REG_EDX)
        if position & 0x80000000: position -= 0x100000000
        file[1] = position + (0 if origin == 0 else file[1] if origin == 1 else len(file[0]))
        self.ret(0)
    def load_file(self):
        path = self.filename(self.reg(UC_X86_REG_EAX));data=path.read_bytes();address=self.allocate(len(data))
        self.cpu.mem_write(address,data);self.ret(address)
    def music_start(self): self.trace.append({'time':self.time,'music':'S0000.MFA'});self.ret()
    def volume(self): self.trace.append({'time':self.time,'volume':self.reg(UC_X86_REG_EAX)});self.ret()
    def wait(self): self.advance(self.reg(UC_X86_REG_EAX) * self.reg(UC_X86_REG_EDX) * 65536 / 1193182);self.ret()
    def delay(self): self.advance(self.reg(UC_X86_REG_EAX)/1000);self.ret()
    def pixels(self):
        result = bytearray(64000)
        for out_plane in range(4):
            source_plane = (self.pan + out_plane) % 4
            shift = (self.pan + out_plane) // 4
            data = self.vga[source_plane]
            raw = bytearray()
            for y in range(200):
                start = (self.display + y * 88 + shift) % 65536
                chunk = data[start:start + 80]
                if len(chunk) < 80: chunk += data[:80-len(chunk)]
                raw.extend(chunk)
            result[out_plane::4] = raw
        return bytes(self.palette) + bytes(result)
    def advance(self, seconds):
        pixels = self.pixels()
        if self.frames and self.frames[-1][1] == pixels: self.frames[-1][0] += seconds
        else: self.frames.append([seconds,pixels])
        self.time += seconds
        if self.time > 600 or len(self.frames) > 20000: raise ValueError('분석한 프롤로그 범위를 넘었습니다.')
    def read_vga(self,c,access,address,size,value,user):
        offset = address - 0xa0000
        for p in range(4): self.latches[p] = self.vga[p][offset]
        c.mem_write(address, bytes(self.vga[self.read_plane][offset:offset+size]))
    def write_vga(self,c,access,address,size,value,user):
        offset = address - 0xa0000
        for p in range(4):
            if self.mask & (1 << p):
                if self.mode == 1:
                    if size != 1: raise ValueError('분석하지 않은 VGA 래치 쓰기')
                    self.vga[p][offset] = self.latches[p]
                else: self.vga[p][offset:offset+size] = value.to_bytes(size,'little')
    def port_in(self,c,port,size,user):
        if port == 0x3da:
            self.retrace ^= 8
            return self.retrace
        if port == 0x3cf: return self.mode if self.gc == 5 else self.read_plane if self.gc == 4 else 0
        return 0
    def port_out(self,c,port,size,value,user):
        if size > 1:
            for byte in range(size): self.port_out(c,port+byte,1,(value>>(8*byte))&255,user)
            return
        if port == 0x3c4: self.seq = value
        elif port == 0x3c5 and self.seq == 2: self.mask = value & 15
        elif port == 0x3ce: self.gc = value
        elif port == 0x3cf:
            if self.gc == 5: self.mode = value & 3
            elif self.gc == 4: self.read_plane = value & 3
        elif port == 0x3c8: self.dac = value * 3
        elif port == 0x3c9:
            self.palette[self.dac % 768] = value & 63; self.dac += 1
        elif port == 0x3d4: self.crtc = value
        elif port == 0x3d5:
            if self.crtc == 12: self.display = (self.display & 255) | value << 8
            elif self.crtc == 13: self.display = (self.display & 0xff00) | value
        elif port == 0x3c0:
            if self.attribute: self.pan = value & 7; self.attribute = False
            elif value == 0x33: self.attribute = True
    def call(self,address, eax=0):
        self.cpu.reg_write(UC_X86_REG_ESP,0x1d00000)
        self.cpu.mem_write(0x1d00000,struct.pack('<I',0x1f00000))
        self.cpu.reg_write(UC_X86_REG_EAX,eax)
        self.cpu.emu_start(address,0x1f00000,count=800_000_000)
        if self.reg(UC_X86_REG_EIP)!=0x1f00000: raise ValueError(f'원작 함수가 끝나지 않았습니다: {address:x}')
    def prepare(self):
        self.set_dword(0x5a2c,0);self.set_dword(0x5a48,0x4fc0);self.set_dword(0x5a58,0x9f80)
        self.set_dword(0x5a5c,self.allocate(64000))
        self.call(0x11b74);self.call(0x122d4)

def record(game_dir, output_root):
    machine = PrologueMachine(game_dir)
    machine.prepare()
    chapters = []
    for scene in [0x10560,0x1098c,0x10d3c,0x10f94,0x110f4,0x112d4,0x11434]:
        chapters.append({'function':hex(scene),'time':machine.time,'frame':len(machine.frames)})
        print('원작 장면 실행',hex(scene),flush=True)
        machine.call(scene)
    machine.call(0x1030c)
    out_dir = output_root / 'assets/prologue'
    out_dir.mkdir(parents=True, exist_ok=True)
    output = out_dir / 'prologue.pvid'
    hashes = []
    with output.open('wb') as raw, gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as stream:
        stream.write(struct.pack('>4sHHI', b'PV01',320,200,len(machine.frames)))
        previous = bytes(64768)
        for duration,pixels in machine.frames:
            stream.write(struct.pack('>d',duration))
            stream.write(bytes(a ^ b for a,b in zip(pixels,previous)))
            hashes.append(hashlib.sha256(pixels).hexdigest())
            previous=pixels
    sources = ['PROLOGUE.EXE','GRP/PRO0.DAT','GRP/TXT.VIS'] + [f'GRP/PRO{i}.VIS' for i in range(1,7)] + ['S&W/S0000.MFA']
    manifest = {'width':320,'height':200,'frame_count':len(machine.frames),'duration_seconds':machine.time,
                'nominal_vblank_hz':70, 'bios_tick_seconds':65536/1193182,
                'sources':{name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in sources},
                'movie_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
                'chapters':chapters, 'trace':machine.trace}
    (out_dir/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (out_dir/'frame-hashes.json').write_text(json.dumps(hashes,indent=2)+'\n')
    sample_dir = output_root/'docs/images'
    sample_dir.mkdir(parents=True,exist_ok=True)
    sheet = Image.new('RGB',(960,880))
    draw = ImageDraw.Draw(sheet)
    samples = [3,10,25,45,60,72,90,101,115,130,137]
    time=0;index=0;references=[]
    for frame, (duration,pixels) in enumerate(machine.frames):
        if index < len(samples) and time+duration >= samples[index]:
            image=Image.frombytes('P',(320,200),pixels[768:])
            image.putpalette([c*4 for c in pixels[:768]])
            x,y=index%3*320,index//3*220
            filename=f'prologue-reference-{index:02}.png'
            image.convert('RGBA').save(sample_dir/filename)
            references.append({'file':filename,'frame':frame,'time':time+duration/2})
            sheet.paste(image.convert('RGB'),(x,y+20))
            draw.text((x+4,y+4),f'{samples[index]}s / frame {frame}',fill='white')
            index+=1
        time+=duration
    sheet.save(sample_dir/'prologue-contact.png')
    manifest['references']=references
    (out_dir/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print('프롤로그 화면 기록 완료:',len(machine.frames),'프레임',round(machine.time,3),'초',flush=True)
    return manifest

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir',type=Path,required=True)
    parser.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    args=parser.parse_args()
    record(args.game_dir,args.output_root)
