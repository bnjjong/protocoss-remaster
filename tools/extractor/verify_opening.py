"""원작의 필드 이동·모션·글꼴 합성 함수를 직접 실행한다."""
import struct
from unicorn import Uc, UC_ARCH_X86, UC_MODE_32, UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ESP,UC_X86_REG_EIP


class OpeningReference:
    def __init__(self,code,data,font):
        self.cpu=Uc(UC_ARCH_X86,UC_MODE_32);c=self.cpu
        c.mem_map(0,0x400000);c.mem_write(0x110000,code);c.mem_write(0,data)
        c.mem_write(0x4040,code[0x4040:0x4108])
        c.mem_write(0x9320,font[:5120]);c.mem_write(0x8820,font[5120:7936]);c.mem_write(0x7a20,font[7936:])
        # LE의 CS 상대 주소를 이 검사에서 사용하는 평면 주소로 옮긴다.
        for start,count in [(0x840,12),(0x1368,12)]:
            offsets=struct.unpack_from(f'<{count}I',code,start)
            c.mem_write(start,struct.pack(f'<{count}I',*(0x110000+v for v in offsets)))
        def hook(cpu,address,size,user):
            if address!=0x167ba0:return
            dest=cpu.reg_read(UC_X86_REG_EAX);value=cpu.reg_read(UC_X86_REG_EDX)&255;length=cpu.reg_read(UC_X86_REG_EBX)
            cpu.mem_write(dest,bytes([value])*length)
            sp=cpu.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',cpu.mem_read(sp,4))[0]
            cpu.reg_write(UC_X86_REG_ESP,sp+4);cpu.reg_write(UC_X86_REG_EIP,ret)
        c.hook_add(UC_HOOK_CODE,hook)

    def call(self,address,eax=0,edx=0):
        c=self.cpu;c.mem_write(0x300000,struct.pack('<I',0x3f0000))
        for r,v in [(UC_X86_REG_ESP,0x300000),(UC_X86_REG_EAX,eax),(UC_X86_REG_EDX,edx)]:c.reg_write(r,v)
        c.emu_start(address+0x100000,0x3f0000,count=100000)
        if c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('원작 시작 함수가 끝나지 않았습니다.')

    def glyph(self,encoded):
        self.call(0x14108,encoded[0],encoded[1])
        return bytes(self.cpu.mem_read(0x79f0,32))

    def motions(self,program):
        actors=[];checkpoints=[];c=self.cpu
        def sync():
            c.mem_write(0x76f4,struct.pack('<I',len(actors)))
            for i,a in enumerate(actors):
                address=0x76f8+i*30
                c.mem_write(address,struct.pack('<B6I5B',a['sprite'],0,0,a['x'],a['y'],0,0,a['direction'],a['animate'],a['frame'],a['speed'],a['mask']))
                c.mem_write(address+29,bytes([a['mask']]))
        for command in program['commands']:
            op,value=command['op'],command['value']
            if op=='S':actors=[dict(a,direction=0,animate=0,speed=0,mask=0) for a in value]
            elif op=='M':
                for a,row in zip(actors,value):a.update(zip(['sprite','direction','animate','frame','speed','mask'],row))
            elif op=='G':
                sync();c.mem_write(0xe510,struct.pack('<H',1));self.call(0x11328)
                for _ in range(8):self.call(0x10810)
                for i,a in enumerate(actors):
                    base=0x76f8+i*30
                    a['x'],a['y']=struct.unpack('<II',c.mem_read(base+9,8))
                    a['frame']=c.mem_read(base+27,1)[0]
            elif op=='C':checkpoints.append(dict(dialogue=value,actors=[dict(a) for a in actors]))
        return checkpoints


def scene_reference(renderer, submap, block, checkpoint, sprite_blocks, frame_map, palette):
    """VGA 여백을 포함한 화면에서 원작 인물·겹침 함수를 실행한다."""
    from decode_map import decode_tile_pixels
    from unicorn.x86_const import UC_X86_REG_ECX
    from PIL import Image
    c=renderer.cpu;renderer.width=352;renderer.height=232
    renderer.indices=bytearray(352*232);renderer.alpha=bytearray([255])*(352*232)
    renderer.plane_mask=1
    c.mem_write(0x167c,b''.join(struct.pack('<I',y*88) for y in range(256)))
    c.mem_write(0x180000,block[770:64770]);c.mem_write(0x1568,struct.pack('<I',0x180000))
    c.mem_write(0xd868,block[64770:66770]);c.mem_write(0x190000,submap[48:4048])
    c.mem_write(0x1560,struct.pack('<I',0x190000));c.mem_write(0x1564,struct.pack('<I',0x195000))
    c.mem_write(0x195000,b''.join(struct.pack('<I',y*40) for y in range(25)))
    for a,v in [(0xd814,40),(0xd80c,0),(0xd810,0),(0xe52c,0),(0xe530,0),(0xe548,0)]:c.mem_write(a,struct.pack('<I',v))
    for i,(tid,_) in enumerate(struct.iter_unpack('<HH',submap[48:4048])):
        ids=decode_tile_pixels(block[770+(tid&4095)*64:834+(tid&4095)*64])
        for y in range(8):
            a=(i//40*8+16+y)*352+i%40*8+16
            renderer.indices[a:a+8]=ids[y*8:y*8+8]
    def call(address,eax,edx,ebx,ecx):
        c.mem_write(0x300000,struct.pack('<I',0x3f0000))
        for r,v in [(UC_X86_REG_ESP,0x300000),(UC_X86_REG_EAX,eax),(UC_X86_REG_EDX,edx),(UC_X86_REG_EBX,ebx),(UC_X86_REG_ECX,ecx)]:c.reg_write(r,v)
        c.emu_start(address,0x3f0000,count=200000)
        if c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('원작 장면 그리기가 끝나지 않았습니다.')
    for actor in checkpoint['actors']:
        raw=sprite_blocks[actor['sprite']][frame_map[actor['frame']]]
        c.mem_write(0x1a0000,raw)
        call(0x2d11a,actor['x']+16,actor['y']+16,0x1a0000,0xa0000)
        call(0x20ce8,actor['x']+16,actor['y']+16,0xa0000,actor['mask'])
    indices=bytes(renderer.indices[(y+16)*352+16+x] for y in range(200) for x in range(320))
    im=Image.frombytes('P',(320,200),indices);im.putpalette([v*4 for v in palette])
    return im.convert('RGBA'),indices
