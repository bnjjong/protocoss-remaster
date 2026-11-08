"""원작 LE 주소 재배치와 필드 판정 함수 실행을 위한 분석 도구."""
from collections import Counter
import struct


def load_game_memory(executable):
    """분석 판본의 내부 32비트 주소 항목을 적용한다. 미지원 항목은 거부한다.

    형식 상수의 근거: Open Watcom bld/watcom/h/exeflat.h.
    원본 EXE와 재배치한 메모리는 파일로 저장하지 않는다.
    """
    le=executable.find(b'LE')
    if le<0: raise ValueError('LE 헤더가 없습니다.')
    table=le+struct.unpack_from('<I',executable,le+0x40)[0]
    pages=struct.unpack_from('<I',executable,le+0x80)[0]
    memory=bytearray(0x100000)
    objects=[]
    for i in range(struct.unpack_from('<I',executable,le+0x44)[0]):
        size,base,_,page,count,_=struct.unpack_from('<6I',executable,table+i*24)
        start=pages+(page-1)*4096
        content=executable[start:start+size]
        memory[base:base+len(content)]=content
        objects.append((base,page,count))
    ft=le+struct.unpack_from('<I',executable,le+0x68)[0]
    rt=le+struct.unpack_from('<I',executable,le+0x6c)[0]
    kinds=Counter()
    for base,first,count in objects:
        for local in range(count):
            page=first-1+local
            pos=rt+struct.unpack_from('<I',executable,ft+page*4)[0]
            end=rt+struct.unpack_from('<I',executable,ft+(page+1)*4)[0]
            while pos<end:
                kind,flags,source,target=struct.unpack_from('<BBhB',executable,pos)
                if kind not in (5,7) or flags not in (0,16):
                    raise ValueError(f'미지원 LE 재배치: {kind:#x}, {flags:#x}, 파일 {pos:#x}')
                width=4 if flags&16 else 2
                offset=int.from_bytes(executable[pos+5:pos+5+width],'little')
                if not 1<=target<=len(objects):raise ValueError('LE 재배치 대상이 없습니다.')
                struct.pack_into('<I' if kind==7 else '<H',memory,base+local*4096+source,(objects[target-1][0]+offset) & (0xffffffff if kind==7 else 0xffff))
                kinds[(kind,flags)]+=1
                pos+=5+width
            if pos!=end:raise ValueError('LE 재배치 항목 길이가 다릅니다.')
    return memory,objects,kinds

from unicorn import Uc, UC_ARCH_X86, UC_MODE_32, UC_HOOK_CODE
from unicorn.x86_const import (UC_X86_REG_ESP, UC_X86_REG_EIP, UC_X86_REG_EAX,
    UC_X86_REG_EDX, UC_X86_REG_EBX, UC_X86_REG_ECX)


class OriginalFieldMachine:
    DATA=0x70000
    def __init__(self,executable):
        memory,self.objects,self.fixups=load_game_memory(executable)
        self.cpu=Uc(UC_ARCH_X86,UC_MODE_32)
        self.cpu.mem_map(0,0x400000);self.cpu.mem_write(0,bytes(memory))
        self.queries=0

    def word(self,address,value=None):
        address+=self.DATA
        if value is None:return struct.unpack('<H',self.cpu.mem_read(address,2))[0]
        self.cpu.mem_write(address,struct.pack('<H',value&65535))

    def dword(self,address,value):self.cpu.mem_write(self.DATA+address,struct.pack('<I',value&0xffffffff))
    def byte(self,address,value=None):
        address+=self.DATA
        if value is None:return self.cpu.mem_read(address,1)[0]
        self.cpu.mem_write(address,bytes([value]))

    def call(self,address,eax=0):
        c=self.cpu;c.mem_write(0x300000,struct.pack('<I',0x3f0000))
        c.reg_write(UC_X86_REG_ESP,0x300000);c.reg_write(UC_X86_REG_EAX,eax)
        c.emu_start(address,0x3f0000,count=200000)
        if c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError(f'원작 필드 함수 종료 실패: {address:#x}')

    def load_map(self,source):
        self.width,self.height=source[2]*40,source[3]*25
        end=48+self.width*self.height*4
        self.cpu.mem_write(0x200000,source[48:end]+b'\x00\x10\x00\x00'*self.width)
        self.dword(0x1560,0x200000);self.dword(0x1564,0x210000)
        self.cpu.mem_write(0x210000,b''.join(struct.pack('<I',y*self.width) for y in range(self.height+1)))
        self.dword(0xd814,self.width)
        self.word(0xe1e0,source[34]);self.dword(0x157c,0x220000)
        start=end+source[33]*8
        if source[34]:self.cpu.mem_write(0x220000,source[start:start+source[34]*54])
        start+=source[34]*54
        self.byte(0xe50e,source[35]);self.dword(0x15a0,0x225000)
        if source[35]:self.cpu.mem_write(0x225000,source[start:start+source[35]*20])

    def query(self,x,y,direction,layer=0):
        sx=max(0,min(self.width-40,(x-144)//8));sy=max(0,min(self.height-25,(y-88)//8))
        self.dword(0xd7fc,sx);self.dword(0xd800,sy)
        self.dword(0xe1e4,x-sx*8+16);self.dword(0xe1e8,y-sy*8+16)
        self.byte(0xe1f8,direction);self.byte(0xe1fb,layer);self.byte(0xe1fc,0)
        self.call(0x23154)
        self.queries+=1
        return self.byte(0xe1f8),self.byte(0xe1fb)

    def animation_checkpoints(self, program):
        actors=[];checkpoints=[];step=1
        def sync():
            self.dword(0x76f4,len(actors))
            for i,a in enumerate(actors):
                value=struct.pack('<B6I5B',a['sprite'],0,0,a['x']&0xffffffff,a['y']&0xffffffff,0,0,a['direction'],a['animate'],a['frame'],a['speed'],a['mask'])
                self.cpu.mem_write(self.DATA+0x76f8+i*30,value)
        for command in program['commands']:
            op,value=command['op'],command['value']
            if op=='S':actors=[dict(a,direction=0,animate=0,speed=0,mask=0) for a in value]
            elif op=='M':
                for actor,row in zip(actors,value):actor.update(zip(['sprite','direction','animate','frame','speed','mask'],row))
            elif op=='L':step=value[1]
            elif op=='G':
                sync();self.word(0xe510,1);self.call(0x11328)
                for _ in range(8//step):self.call(0x10810)
                for i,actor in enumerate(actors):
                    base=self.DATA+0x76f8+i*30
                    actor['x'],actor['y']=struct.unpack('<ii',self.cpu.mem_read(base+9,8))
                    actor['frame']=self.cpu.mem_read(base+27,1)[0]
            elif op in ('C','E'):
                checkpoints.append(dict(op=op,dialogue=value,actors=[dict(a) for a in actors]))
        return checkpoints

    def open_cabin_door(self, soc):
        return self.open_door(soc,336,216,0,1)

    def open_door(self, soc, x, y, pose=0, layer=0):
        """0x11CDC의 실제 타일·통과 플래그 변경을 실행한다. 화면 출력과 음악 대기만 생략한다."""
        self.cpu.mem_write(self.DATA+0xb880,soc[66770:68770])
        self.query(x,y,1,layer)
        self.byte(0xe1fc,pose)
        self.cpu.mem_write(0x3e0000,b'\xc3');self.dword(0xfd40,0x3e0000)
        cleanup={0x2d1e6:0,0x2cb5f:8,0x243f0:0,0x65760:0,0x22148:0,0x2ca5e:8}
        def hook(c,address,size,user):
            if address not in cleanup:return
            sp=c.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',c.mem_read(sp,4))[0]
            c.reg_write(UC_X86_REG_ESP,sp+4+cleanup[address]);c.reg_write(UC_X86_REG_EIP,ret)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x11cdc,0)
        finally:self.cpu.hook_del(token)
        return bytes(self.cpu.mem_read(0x200000,self.width*self.height*4))

    def trace_event(self,raw,group,argument,variables,capture_dialogue=False):
        count=struct.unpack_from('<H',raw,4)[0]
        offsets=raw[6:6+count*2];script=bytes(b^127 for b in raw[6+count*2:])
        self.cpu.mem_write(0x230000,offsets);self.cpu.mem_write(0x240000,script)
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000)
        self.word(0x15b0,0);self.word(0xe50c,count)
        self.cpu.mem_write(self.DATA+0xe2a0,bytes(600))
        for index,value in variables.items():self.word(0xe2a0+index*2,value)
        self.word(0xe2a0,group);events=[]
        def hook(c,address,size,user):
            if address==0x67b85:
                pointer=c.reg_read(UC_X86_REG_EAX)
                length=bytes(c.mem_read(pointer,4096)).index(0)
                c.reg_write(UC_X86_REG_EAX,length)
                sp=c.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',c.mem_read(sp,4))[0]
                c.reg_write(UC_X86_REG_ESP,sp+4);c.reg_write(UC_X86_REG_EIP,ret)
                return
            if address not in (0x1ee50,0x1f188,0x10a54,0x1f8b0,0x2b6f0,0x12f4c,0x1eb30,0x134b8,0x11cdc):return
            sp=c.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',c.mem_read(sp,4))[0]
            args=[c.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
            if address in (0x1ee50,0x1f188):
                args+=list(struct.unpack('<4I',c.mem_read(sp+4,16)))
                events.append(dict(kind='warp',args=args));sp+=16
            elif address==0x10a54:events.append(dict(kind='animation',id=args[0]))
            elif address==0x11cdc:events.append(dict(kind='door',id=args[0]))
            elif address==0x1f8b0:
                event=dict(kind='dialogue')
                if capture_dialogue:
                    event['text']=bytes(c.mem_read(args[0],8192)).split(b'\0')[0].decode('cp1361')
                events.append(event)
            elif address==0x12f4c:events.append(dict(kind='party_remove',id=args[0]))
            elif address==0x1eb30:events.append(dict(kind='place',name=bytes(c.mem_read(args[0],200)).split(b'\0')[0].decode('cp1361')))
            elif address==0x134b8:events.append(dict(kind='video',name=bytes(c.mem_read(args[0],200)).split(b'\0')[0].decode('ascii'),args=args[1:]))
            else:events.append(dict(kind='effect',id=args[0]))
            c.reg_write(UC_X86_REG_ESP,sp+4);c.reg_write(UC_X86_REG_EIP,ret)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25304,argument)
        finally:self.cpu.hook_del(token)
        return dict(events=events,variables={str(i):self.word(0xe2a0+i*2) for i in (0,1,2,6,7,8)})


def extract_field(game_directory, output_root, executable=None):
    """패키지 맵의 8픽셀 보행 단위와 세 높이 층을 원작 함수로 판정한다."""
    import hashlib
    import json
    from pathlib import Path
    from verify_original_map import unpack_game, read_offsets
    root = Path(game_directory)
    output = Path(output_root) / 'assets/data/field'
    output.mkdir(parents=True, exist_ok=True)
    machine = OriginalFieldMachine(executable if executable is not None else unpack_game(root))
    offsets = read_offsets(root / 'MAP/AREA00.MDA')
    raw = (root / 'MAP/AREA00.MAP').read_bytes()
    metadata = dict(version=1, directions=[1,2,4,8], maps={}, source_sha256=hashlib.sha256(raw).hexdigest(), soc_sha256=hashlib.sha256((root/'MAP/AREA00.SOC').read_bytes()).hexdigest())
    soc_offsets=read_offsets(root/'MAP/AREA00.SDA')
    soc_all=(root/'MAP/AREA00.SOC').read_bytes()
    for key,number,opened in [(0,0,False),(1,1,False),(2,2,False),("0-open",0,True),(3,3,False),*[(i,i,False) for i in range(4,13)]]:
        source = raw[offsets[number]:offsets[number+1]]
        machine.load_map(source)
        # 주민은 초기 위치를 굳힌 판정표 대신 실행 중의 목표 좌표로 검사한다.
        if number in range(4,13):machine.word(0xe1e0,0)
        width, height = machine.width, machine.height
        changed=None
        if opened:
            start=soc_offsets[source[32]]
            changed=machine.open_cabin_door(soc_all[start:start+70818])
        results = bytearray()
        for layer in range(3):
            for y in range(height):
                for x in range(width):
                    for direction in (1,2,4,8):
                        allowed, next_layer = machine.query(x*8,y*8,direction,layer)
                        if allowed not in (0,1,2,3,4,6,8,9,12) or next_layer not in range(3):
                            raise ValueError('원작 이동 판정 범위 오류')
                        results.append(allowed | next_layer << 4)
        filename = f'movement-{key}.bin'
        (output / filename).write_bytes(results)
        start = 48+width*height*4+source[33]*8+source[34]*54
        rectangles = [list(struct.unpack_from('<5I',source,start+i*20)) for i in range(source[35])]
        metadata['maps'][str(key)] = dict(width=width,height=height,file=filename,
            sha256=hashlib.sha256(results).hexdigest(), rectangles=rectangles,
            attributes=[a for _,a in struct.iter_unpack('<HH',changed or source[48:48+width*height*4])])
        if number in range(4,13):
            metadata['maps'][str(key)]['dynamic_npcs']=True
        if changed:
            metadata['door_changes']=[dict(index=i,tid=t,attr=a) for i,((t,a),before) in enumerate(zip(struct.iter_unpack('<HH',changed),struct.iter_unpack('<HH',source[48:48+width*height*4]))) if (t,a)!=before]
        print('원작 이동 판정 추출:',key,len(results),flush=True)
    (output/'field.json').write_text(json.dumps(metadata,ensure_ascii=False,separators=(',',':'))+'\n')
    return metadata


def verify_party_removal(executable, start):
    """원작 저장 읽기→파티 이탈→저장 메모리의 2,942바이트를 대조한다."""
    machine=OriginalFieldMachine(executable);c=machine.cpu
    position=0;regions=[]
    def hook(cpu,address,size,user):
        nonlocal position
        if address not in (0x67163,0x671f9,0x67c9c,0x673c3,0x66368):return
        if address==0x67163:cpu.reg_write(UC_X86_REG_EAX,0x200000)
        elif address==0x671f9:
            destination=cpu.reg_read(UC_X86_REG_EAX)
            length=cpu.reg_read(UC_X86_REG_EDX)*cpu.reg_read(UC_X86_REG_EBX)
            chunk=start[position:position+length]
            if len(chunk)!=length:raise ValueError('원작 저장 읽기 범위 오류')
            cpu.mem_write(destination,chunk);regions.append((position,length,destination));position+=length
            cpu.reg_write(UC_X86_REG_EAX,1)
        elif address==0x67c9c:
            guard=struct.unpack_from('<H',start,2942)[0]^0x7f7f
            cpu.mem_write(cpu.reg_read(UC_X86_REG_EBX),struct.pack('<I',guard))
            cpu.mem_write(cpu.reg_read(UC_X86_REG_EDX),bytes(4))
        sp=cpu.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',cpu.mem_read(sp,4))[0]
        cpu.reg_write(UC_X86_REG_ESP,sp+4);cpu.reg_write(UC_X86_REG_EIP,ret)
    token=c.hook_add(UC_HOOK_CODE,hook)
    try:machine.call(0x1bb74,0xffffffff)
    finally:c.hook_del(token)
    if position!=2944:raise ValueError('원작 저장 읽기가 끝나지 않았습니다.')
    before={500:1,540:1,630:163,631:174,325:9,336:20}
    for offset,value in before.items():
        for begin,length,destination in regions:
            if begin<=offset<begin+length:c.mem_write(destination+offset-begin,bytes([value]))
    machine.call(0x12f4c,1)
    actual=bytearray(start);expected=bytearray(start)
    after={500:0,540:0,630:255,631:255,325:10,336:21}
    for begin,length,destination in regions[:-1]:actual[begin:begin+length]=c.mem_read(destination,length)
    for offset,value in after.items():expected[offset]=value
    if actual!=expected:raise ValueError('파티 이탈 후 원작 저장 상태 불일치')
    return dict(compared_bytes=2942,before=before,after=after)


def verify_field(game_directory, output_root, executable=None):
    """컴파일 때 쓰지 않은 비정렬 좌표와 출입구 분기를 원작 함수로 다시 확인한다."""
    import hashlib
    import json
    import random
    from pathlib import Path
    from verify_original_map import unpack_game, read_offsets
    root=Path(game_directory);output=Path(output_root)
    exe=executable if executable is not None else unpack_game(root)
    folder=output/'assets/data/field'
    metadata=json.loads((folder/'field.json').read_text())
    maps=(root/'MAP/AREA00.MAP').read_bytes();offsets=read_offsets(root/'MAP/AREA00.MDA')
    soc=(root/'MAP/AREA00.SOC').read_bytes();soc_offsets=read_offsets(root/'MAP/AREA00.SDA')
    machine=OriginalFieldMachine(exe);cases=[]
    for key,number,opened in [(0,0,False),(1,1,False),(2,2,False),("0-open",0,True),(3,3,False),(4,4,False),(12,12,False)]:
        source=maps[offsets[number]:offsets[number+1]];machine.load_map(source)
        if number in range(4,13):machine.word(0xe1e0,0)
        if opened:
            start=soc_offsets[source[32]];machine.open_cabin_door(soc[start:start+70818])
        data=(folder/f'movement-{key}.bin').read_bytes()
        digest=hashlib.sha256(data).hexdigest()
        if digest!=metadata['maps'][str(key)]['sha256']:raise ValueError('이동 자산 해시 불일치')
        width,height=source[2]*40,source[3]*25;rng=random.Random(1996+number+(100 if opened else 0))
        for _ in range(250):
            x=rng.randrange(1,width-4)*8+rng.randrange(8)
            y=rng.randrange(1,height-4)*8+rng.randrange(8)
            layer=rng.randrange(3);direction=rng.choice([1,2,4,8])
            actual=machine.query(x,y,direction,layer)
            value=data[((layer*height+y//8)*width+x//8)*4+[1,2,4,8].index(direction)]
            if actual!=(value&15,value>>4):raise ValueError(f'원작 비정렬 이동 판정 불일치: {number,x,y,layer,direction}')
        cases.append(dict(map=key,unaligned_queries=250,sha256=digest))
    script=(root/'DATA/AREA00.G&M').read_bytes()
    doors=[]
    for group in (0,1):
        for stage in range(4):
            result=OriginalFieldMachine(exe).trace_event(script,group,1,{8:stage})
            doors.append(dict(group=group,stage=stage,result=result))
    report=dict(compiled_queries=sum(len((folder/m["file"]).read_bytes()) for m in metadata["maps"].values()),unaligned_checks=1750,maps=cases,
                door_changes=metadata['door_changes'],native_doors=doors,
                party_removal=verify_party_removal(exe,(root/'DATA/START.DAT').read_bytes()))
    (output/'docs/field-native-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print('원작 필드 교차 검사: 1,750개 비정렬 좌표·8개 출입구 분기 일치')
    return report


def verify_town(game_directory, output_root, executable=None):
    import hashlib
    import json
    from pathlib import Path
    from verify_original_map import unpack_game
    root=Path(game_directory)
    exe=executable if executable is not None else unpack_game(root)
    raw=(root/'DATA/AREA00.G&M').read_bytes();cases=[]
    for group,argument in [(1,2),(2,0),(2,1),(2,2),(2,4),(3,1)]:
        for seen in (0,1):
            for stage in (0,2,3):
                result=OriginalFieldMachine(exe).trace_event(raw,group,argument,{7:seen,8:stage})
                cases.append(dict(group=group,argument=argument,seen=seen,stage=stage,result=result))
    report=dict(source_sha256=hashlib.sha256(raw).hexdigest(),cases=cases)
    (Path(output_root)/'docs/town-native-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('원작 마을 이벤트 36개 분기 확인')
    return report


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('game_directory',type=Path)
    parser.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument('--verify',action='store_true',help='추출 뒤 원작 교차 검사 기록을 만든다.')
    args=parser.parse_args()
    from verify_original_map import unpack_game
    executable=unpack_game(args.game_directory)
    extract_field(args.game_directory,args.output_root,executable)
    if args.verify:
        verify_field(args.game_directory,args.output_root,executable)
        verify_town(args.game_directory,args.output_root,executable)
