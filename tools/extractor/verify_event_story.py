#!/usr/bin/env python3
"""합류·쪽지 연출의 원본 이벤트 재진입·파티·최종 저장을 실행한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import *
from sword_story import StoryReference
from event_archive import EventArchive
from decode_opening import animation,dialogue
from verify_original_map import unpack_game,read_offsets,EXPECTED_HASHES

class StoryServicesReference(StoryReference):
    def parser(self,opcode,args):
        c=self.cpu;c.mem_write(0x240000,struct.pack(f'<{len(args)}H',*args));self.dword(0x15a4,0x240000);captured=[]
        address,target={0:(0x25c18,0x10a54),0xd:(0x25f04,0x1255c),0x13:(0x26034,0x12f4c)}[opcode]
        def hook(cpu,at,size,user):
            if at==target:
                captured.append([cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX)[:len(args)]]);self.return_from(cpu)
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(address)
        finally:c.hook_del(token)
        return dict(opcode=opcode,input=args,args=captured[0],advanced=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000)

    def run(self,game_dir,raw,source,argument,real_approach=False):
        self.load_save(source);c=self.cpu;archive=EventArchive(raw)
        c.mem_write(0x230000,raw[6:6+len(archive.groups)*2]);c.mem_write(0x240000,archive.code)
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000);self.word(0xe50c,len(archive.groups));self.word(0x15b0,0)
        maps=(game_dir/'MAP/AREA00.MAP').read_bytes();offs=read_offsets(game_dir/'MAP/AREA00.MDA')
        def load_map(number):
            self.load_map(maps[offs[number]:offs[number+1]]);self.word(0xe1e0,0)
            self.dword(0xd804,self.width-40);self.dword(0xd808,self.height-25)
        load_map(struct.unpack_from('<I',source,96)[0])
        self.word(0xe510,1);self.word(0xe512,0)
        for p in (0xe52c,0xe530,0xe548,0x164c):self.dword(p,0)
        self.byte(0x1660,0)
        for p in (0xd80c,0xd810):self.dword(p,struct.unpack_from('<I',source,100+(p-0xd80c))[0])
        c.mem_write(0x3e0000,b'\xc3')
        for p in (0xfd40,0xfd44):self.dword(p,0x3e0000)
        stack=[];animations=[];roots=[];filedata=b'';heap=0x280000
        def saved():return base64.b64encode(self.saved()).decode()
        def pc():return struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000
        def snapshot(kind,**extra):
            f=stack[-1]
            return dict(kind=kind,at=f['last'],offset=pc(),instructions=f['steps'],variables=hashlib.sha256(bytes(c.mem_read(0x7e2a0,600))).hexdigest(),**extra)
        def string(p):return bytes(c.mem_read(p,4096)).split(b'\0')[0]
        def unwind():
            bp=c.reg_read(UC_X86_REG_EBP);values=struct.unpack('<7I',c.mem_read(bp,28))
            for reg,val in zip((UC_X86_REG_EBP,UC_X86_REG_EDI,UC_X86_REG_ESI,UC_X86_REG_EDX,UC_X86_REG_ECX,UC_X86_REG_EBX),values):c.reg_write(reg,val)
            c.reg_write(UC_X86_REG_ESP,bp+28);c.reg_write(UC_X86_REG_EIP,values[-1])
        skips={0x11700:0,0x11694:0,0x1186c:0,0x11468:0,0x23da4:0,0x1dd64:0,0x243f0:0,0x22148:0,
            0x26674:0,0x66025:0,0x66368:0,0x2b838:0,0x1dc54:0,0x235e4:0,0x263a0:0,0x1f108:4,0x2ca5e:8,0x2cb5f:8,0x66f6e:0,0x673c3:0}
        def hook(cpu,address,size,user):
            nonlocal filedata,heap
            regs=[cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
            if address==0x25304:
                f=dict(argument=regs[0],requests=[],steps=0,last=0)
                if stack:animations[-1]['calls'].append(f)
                else:roots.append(f)
                stack.append(f)
            elif address==0x25361:
                f=stack[-1];f['last']=pc();op=archive.code[pc()]
                if op not in set(range(0xf0,0xfb))|{0,2,3,0xd,0x13,0x15,0x17}:raise ValueError(f'미지원 스토리 명령 {op:#x}')
                f['steps']+=1
            elif address==0x2572c:
                f=stack[-1];f['end']=snapshot('finished');f['after']=saved();stack.pop()
            elif address==0x10a54:
                if regs[0] not in (5,6):raise ValueError('이번 범위는 AUTOANI.005·006이다.')
                r=snapshot('animation',id=regs[0],mode=regs[1],music=regs[2],calls=[]);stack[-1]['requests'].append(r);animations.append(r)
            elif address==0x1131e:
                r=animations.pop();r['after']=saved()
                r['hero']=list(struct.unpack('<ii',cpu.mem_read(0x77701,8)))+[self.byte(0x7713)]
            elif address==0x1255c or address==0x12f4c:stack[-1]['requests'].append(snapshot('party',joining=address==0x1255c,character=regs[0]))
            elif address in (0x25f2f,0x2605f):stack[-1]['requests'][-1]['after']=saved()
            elif address==0x1f8b0:
                text=string(regs[0]);stack[-1]['requests'].append(snapshot('dialogue',raw=base64.b64encode(text).decode(),pages=dialogue(text)));self.return_from(cpu)
            elif address==0x1ee50:
                sp=cpu.reg_read(UC_X86_REG_ESP);stack[-1]['requests'].append(snapshot('warp',args=regs+list(struct.unpack('<4I',cpu.mem_read(sp+4,16)))))
            elif address==0x2b6f0:stack[-1]['requests'].append(snapshot('effect',id=regs[0]));self.return_from(cpu)
            elif address==0x1eb30:stack[-1]['requests'].append(snapshot('place',text=string(regs[0]).decode('cp1361')))
            elif address==0x1e1cd:cpu.reg_write(UC_X86_REG_EIP,0x1e213)
            elif address==0x1e21b:
                number=struct.unpack('<I',cpu.mem_read(0x7d7f8,4))[0];load_map(number);unwind()
            elif address==0x1eb5e:unwind()
            elif address==0x11b20 and not real_approach:
                # 접근 보행은 생략하고 그 함수의 목표 위치에 둔다. 배우 이동·자세는 원작 함수가 실행한다.
                for p,v in ((0xd7fc,regs[0]),(0xd800,regs[1]),(0xd80c,regs[0]),(0xd810,regs[1]),(0xe1e4,regs[2]),(0xe1e8,regs[3])):self.dword(p,v)
                self.byte(0xe1f8,0);self.return_from(cpu)
            elif address==0x67163:
                name=string(regs[0]).decode('cp1361').replace('\\','/').split('/')[-1]
                filedata=(game_dir/'DATA'/name.upper()).read_bytes();cpu.mem_write(0x3c0010,struct.pack('<I',123));cpu.reg_write(UC_X86_REG_EAX,0x3c0000);self.return_from(cpu)
            elif address==0x67a0a:cpu.reg_write(UC_X86_REG_EAX,len(filedata));self.return_from(cpu)
            elif address==0x10590:
                cpu.reg_write(UC_X86_REG_EAX,heap);heap+=(regs[0]+255)//256*256;self.return_from(cpu)
            elif address==0x671f9:
                length=regs[1]*regs[2];cpu.mem_write(regs[0],filedata[:length]);cpu.reg_write(UC_X86_REG_EAX,regs[2]);self.return_from(cpu)
            elif address==0x679da:
                sp=cpu.reg_read(UC_X86_REG_ESP);dest,fmt,arg=struct.unpack('<3I',cpu.mem_read(sp+4,12));format=string(fmt).decode('cp1361')
                value=format%(string(arg).decode('cp1361') if '%s' in format else arg);cpu.mem_write(dest,value.encode('cp1361')+b'\0');self.return_from(cpu)
            elif address==0x67b85:cpu.reg_write(UC_X86_REG_EAX,len(string(regs[0])));self.return_from(cpu)
            elif address==0x2b650:cpu.reg_write(UC_X86_REG_EAX,0);self.return_from(cpu)
            elif address==0x106b0:raise ValueError('원작 파티·파일 조건 오류')
            elif address in skips and not (real_approach and address==0x26674):self.return_from(cpu,skips[address])
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:
            c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000);c.reg_write(UC_X86_REG_EAX,argument)
            c.emu_start(0x25304,0x3f0000,count=4000000)
            if c.reg_read(UC_X86_REG_EIP)!=0x3f0000 or stack or animations:raise ValueError('스토리 명령 실행 종료 실패')
        except ValueError as error:
            if str(error)!='원작 파티·파일 조건 오류':raise
            roots[0]['end']=snapshot('error');roots[0]['after']=saved();roots[0]['error']=str(error)
        finally:c.hook_del(token)
        return roots[0]

def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir);ref=StoryServicesReference(exe)
    raw=(game_dir/'DATA/AREA00.G&M').read_bytes();source=json.loads((output_root/'docs/sword-native-verification.json').read_text())['cases'];cases=[]
    for row in source:
        if row['group']==4:continue
        result=ref.run(game_dir,raw,base64.b64decode(row['before']),row['argument'])
        cases.append(dict(name=f"story-{len(cases)}",before=row['before'],**result))
    party=[];start=(game_dir/'DATA/START.DAT').read_bytes()
    for character in range(11):
        for joining in (True,False):
            for profile in range(5):
                before=bytearray(start);offset=153*character
                for i in range(11):before[347+153*i]=0
                if joining:
                    count={0:0,1:4,2:5,3:0,4:0}[profile]
                    for i in [i for i in range(11) if i!=character][:count]:before[347+153*i]=1
                    before[347+offset]=1 if profile==3 else 2 if profile==4 else 0
                else:before[347+offset]=0 if profile==3 else 2 if profile==4 else 1
                before[472+offset]=5;before[387+offset]=(0,1,12,0,1)[profile]
                struct.pack_into('<4H',before,352+offset,1,500,2,90)
                for form in range(1,13):before[447+offset+form]=1;before[459+offset+form]=form+10
                for slot,value in zip((477+offset,478+offset),[(163,163),(162,174),(255,255),(163,174),(174,255)][profile]):before[slot]=value
                before[325]=255;before[336]=255
                for i,v in {0:0,1:77,99:123,299:54321}.items():struct.pack_into('<H',before,2342+i*2,v)
                code=b'\xfa\x03\0'+bytes([0xd if joining else 0x13])+struct.pack('<H',character)+b'\xf2'+struct.pack('<HH',99,456)+b'\xf1'
                program=struct.pack('<IHH',len(code)+2,1,0)+bytes(v^127 for v in code)
                party.append(dict(name=f'party-{len(party)}',joining=joining,character=character,profile=profile,before=base64.b64encode(before).decode(),raw=base64.b64encode(program).decode(),**ref.run(game_dir,program,before,7)))
    opening=json.loads((output_root/'assets/opening/opening.json').read_text());records=[]
    for number,index,mode,music in [(5,5,0,24),(6,3,1,27)]:
        original=animation((game_dir/f'DATA/AUTOANI.{number:03}').read_bytes());program=opening['programs'][index]
        observed=next(r for c in cases for r in c['requests'] if r['kind']=='animation' and r['id']==number)
        records.append(dict(id=number,program=index,mode=mode,music=music,map=original['map'],scroll=[v//8 for v in original['scroll']],
            start=original['commands'][0]['value'][0],hero=observed['hero'],events={str(c['offset']):c['value'] for c in original['commands'] if c['op']=='C'},source_sha256=hashlib.sha256((game_dir/f'DATA/AUTOANI.{number:03}').read_bytes()).hexdigest()))
    assets=output_root/'assets/data/events/story.json';assets.write_text(json.dumps(dict(animations=records),ensure_ascii=False,separators=(',',':'))+'\n')
    report=dict(executable_sources=EXPECTED_HASHES,source_sha256=hashlib.sha256(raw).hexdigest(),cases=cases,party=party,parser=[ref.parser(op,args) for op,args in [(0,[5,0,24]),(0,[65535,32768,65535]),(0xd,[1]),(0xd,[65535]),(0x13,[1]),(0x13,[32768])]],stubs='화면·음악·파일 I/O와 NPC 시계를 생략한다. mode=0의 시작 위치 접근과 카메라 방향 0의 화면 갱신을 생략하고 목표 위치에 직접 둔다. 원작 연출 해석·인물 이동/자세·중첩 이벤트·파티·음악 변수·이동 후 저장 메모리는 실행한다.')
    (output_root/'docs/restoration/event-story-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('합류·쪽지 원작 기록:',len(cases),'스토리·',len(party),'파티 조합',flush=True);return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
