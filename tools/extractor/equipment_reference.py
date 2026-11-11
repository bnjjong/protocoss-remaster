"""원작 무기 선택·장착·해제·능력치와 메뉴 그리기 명령을 실행한다."""
import struct
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX,UC_X86_REG_ESP
from sword_story import StoryReference
from decode_title import parse_img


class EquipmentReference(StoryReference):
    def prepare(self, game_dir, source):
        self.load_save(source)
        self.cpu.mem_write(0x180000,(game_dir/'DATA/WEAPON.LST').read_bytes());self.dword(0x1508,0x180000)
        self.cpu.mem_write(0x188000,(game_dir/'DATA/ITEM.LST').read_bytes());self.dword(0x1504,0x188000)
        self.cpu.mem_write(0x7f240,(game_dir/'GRP/BACK.FXP').read_bytes());self.invoke(0x2c184,4)
        self.images={};self.raw={}
        for name,table,blob,pointer in [('COMMON',0x190000,0x191000,0x1598),('IMENU',0x240000,0x241000,0x1630)]:
            frames=parse_img((game_dir/f'GRP/{name}.IMG').read_bytes());offset=0
            for i,raw in enumerate(frames):
                self.cpu.mem_write(table+i*4,struct.pack('<I',offset));self.cpu.mem_write(blob+offset,raw)
                key=f'{name.lower()}-{i}';self.images[blob+offset]=key;self.raw[key]=raw;offset+=len(raw)
            self.dword(pointer,table);self.dword(pointer+4,blob)
        people=(game_dir/'GRP/PEOPLE.SPR').read_bytes();sda=(game_dir/'GRP/PEOPLE.SDA').read_bytes()
        start,length=struct.unpack_from('<II',sda,4);block=people[start:start+length]
        self.cpu.mem_write(0x310000,block[:48]+struct.pack('<I',0x311000));self.cpu.mem_write(0x311000,block[48:])
        self.dword(0x1638,0x310000)
        ends=(*struct.unpack_from('<12I',block),length-48)
        for i,(a,b) in enumerate(zip(ends,ends[1:])):
            key=f'kaman-{i}';self.images[0x311000+a]=key;self.raw[key]=block[48+a:48+b]
        self.cpu.mem_write(0x7ea2a,b'\x00\x01');self.byte(0xf1a4,1)
        self.cpu.mem_write(0x3f0100,b'\xc3');self.dword(0xfd40,0x3f0100)

    def invoke(self,address,eax=0,edx=0,ebx=0,keys=()):
        events=[];keys=iter(keys)
        def string(pointer):return bytes(self.cpu.mem_read(pointer,4096)).split(b'\0')[0].decode('cp1361')
        def hook(c,address,size,user):
            a,d,b,x=[c.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
            if address in (0x2d11a,0x2cf03):
                if b not in self.images:raise ValueError(f'알 수 없는 메뉴 그림: {b:x}')
                events.append(dict(kind='image',image=self.images[b],x=a-16,y=d-16,muted=address==0x2cf03));self.return_from(c)
            elif address in (0x2ca5e,0x2cb5f):self.return_from(c,8)
            elif address==0x2cd51:self.return_from(c)
            elif address==0x2cf9d:
                events.append(dict(kind='rect',x=a-16,y=d-16,width=b,height=x));self.return_from(c,4)
            elif address in (0x14398,0x28834,0x2887c):
                value=string(b);stack=struct.unpack('<I',c.mem_read(c.reg_read(UC_X86_REG_ESP)+4,4))[0]
                events.append(dict(kind='text' if address==0x14398 else 'number',text=value,x=a-16,y=d-16,color=x if address==0x14398 else stack,right=address==0x28834))
                self.return_from(c,4)
            elif address==0x679da:
                sp=c.reg_read(UC_X86_REG_ESP);dest,fmt=struct.unpack('<II',c.mem_read(sp+4,8));f=string(fmt)
                values=struct.unpack('<'+'I'*f.count('%'),c.mem_read(sp+12,4*f.count('%')))
                c.mem_write(dest,(f%values).encode('cp1361')+b'\0');self.return_from(c)
            elif address==0x148b4:c.reg_write(UC_X86_REG_EAX,next(keys,1));self.return_from(c)
            elif address==0x2b6f0:events.append(dict(kind='effect',id=a));self.return_from(c)
        self.cpu.reg_write(UC_X86_REG_EDX,edx);self.cpu.reg_write(UC_X86_REG_EBX,ebx)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(address,eax)
        finally:self.cpu.hook_del(token)
        return events

    def candidates(self):
        self.invoke(0x15298,2,0)
        count=struct.unpack('<I',self.cpu.mem_read(0x7e550,4))[0]
        return [struct.unpack('<H',self.cpu.mem_read(0x7e610+i*6,2))[0] for i in range(count)]

    def begin_weapon(self):return self.invoke(0x160d8,2,0,keys=(1,))
    def equip(self,item):return self.invoke(0x15e88,0,2,item)
