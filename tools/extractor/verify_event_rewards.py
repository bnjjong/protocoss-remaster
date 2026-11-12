#!/usr/bin/env python3
"""원작 보상 명령·수량/소지금·안내창·저장 상태를 실제 함수로 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import *
from sword_story import StoryReference
from event_archive import EventArchive
from verify_original_map import unpack_game,EXPECTED_HASHES
from decode_opening import dialogue,glyph
from verify_original_title_save import objects
from verify_opening import OpeningReference
from PIL import Image

def synthetic(item,count,category):
    code=b'\xfa\x03\0\x19\x11'+struct.pack('<3H',item,count,category)+b'\x18\xf2'+struct.pack('<HH',99,456)+b'\xf1'
    return struct.pack('<IHH',len(code)+2,1,0)+bytes(b^127 for b in code)

class RewardReference(StoryReference):
    def parser(self,args):
        c=self.cpu;c.mem_write(0x240000,struct.pack('<3H',*args));self.dword(0x15a4,0x240000);captured=[]
        def hook(cpu,address,size,user):
            if address==0x12924:
                captured.append([cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX)]);self.return_from(cpu)
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25f84)
        finally:c.hook_del(token)
        return dict(input=args,args=captured[0],advanced=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000)

    def event_reward(self,game_dir,raw,source,argument):
        self.load_save(source);c=self.cpu;archive=EventArchive(raw)
        c.mem_write(0x230000,raw[6:6+len(archive.groups)*2]);c.mem_write(0x240000,archive.code)
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000);self.word(0xe50c,len(archive.groups));self.word(0x15b0,0)
        for name,address,pointer in [('ITEM',0x280000,0x1504),('WEAPON',0x290000,0x1508)]:
            c.mem_write(address,(game_dir/f'DATA/{name}.LST').read_bytes());self.dword(pointer,address)
        self.dword(0x1598,0x2a0000);self.dword(0x159c,0x2b0000)
        c.mem_write(0x3e0000,b'\xc3')
        for pointer in (0xfd40,0xe054,0xe050):self.dword(pointer,0x3e0000)
        requests=[];ending=[];steps=0;last=0;panel=None
        def pc():return struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000
        def snapshot(kind,**extra):return dict(kind=kind,at=last,offset=pc(),instructions=steps,variables=hashlib.sha256(bytes(c.mem_read(0x7e2a0,600))).hexdigest(),**extra)
        def string(p):return bytes(c.mem_read(p,4096)).split(b'\0')[0]
        skips={0x284b8:0,0x23da4:0,0x1dd64:0,0x2c184:0,0x2ca5e:8}
        def hook(cpu,address,size,user):
            nonlocal last,steps,panel
            if address==0x25361:
                last=pc();op=archive.code[last]
                if op not in set(range(0xf0,0xfb))|{3,0x11,0x18,0x19}:
                    ending.append(snapshot('unhandled',opcode=op));cpu.emu_stop();return
                steps+=1
            elif address==0x1f8b0:
                text=string(cpu.reg_read(UC_X86_REG_EAX));requests.append(snapshot('dialogue',raw=base64.b64encode(text).decode(),pages=dialogue(text)));self.return_from(cpu)
            elif address in (0x132a4,0x133e0):
                panel=snapshot('panel',opening=address==0x133e0,frames=[]);requests.append(panel)
            elif address==0x2cf9d:
                args=[cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
                panel['frames'].append(args);self.return_from(cpu,4)
            elif address==0x12924:
                args=[cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX)]
                requests.append(snapshot('reward',item=args[0],count=args[1],category=args[2]))
            elif address==0x12aa4:
                requests[-1]['credited']=base64.b64encode(self.saved()).decode();requests[-1]['notice']=string(0x77690).decode('cp1361');cpu.reg_write(UC_X86_REG_EIP,0x12dbb)
            elif address==0x679da:
                sp=cpu.reg_read(UC_X86_REG_ESP);dest,fmt=struct.unpack('<2I',cpu.mem_read(sp+4,8));format=string(fmt)
                args=struct.unpack('<2I',cpu.mem_read(sp+12,8))
                if b'%s' in format:value=format.decode('cp1361')%(string(args[0]).decode('cp1361'),args[1])
                elif b'%d' in format:value=format.decode('cp1361')%args[0]
                else:value=format.decode('cp1361')
                cpu.mem_write(dest,value.encode('cp1361')+b'\0');self.return_from(cpu)
            elif address==0x67b85:cpu.reg_write(UC_X86_REG_EAX,len(string(cpu.reg_read(UC_X86_REG_EAX))));self.return_from(cpu)
            elif address in skips:self.return_from(cpu,skips[address])
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25304,argument)
        except ValueError:
            if not ending:raise
        finally:c.hook_del(token)
        if not ending:ending.append(snapshot('finished'))
        return dict(requests=requests,end=ending[0],after=base64.b64encode(self.saved()).decode())

def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir);ref=RewardReference(exe)
    raw=(game_dir/'DATA/AREA00.G&M').read_bytes();cases=[]
    indoor=json.loads((output_root/'docs/indoor-native.json').read_text())['cases']
    sword=[v for v in json.loads((output_root/'docs/sword-native-verification.json').read_text())['cases'] if v['group']==4]
    for row in indoor+sword:
        argument=row.get('event',row.get('argument'));source=base64.b64decode(row['before'])
        cases.append(dict(name=f"event-{len(cases)}",argument=argument,before=row['before'],**ref.event_reward(game_dir,raw,source,argument)))
    start=(game_dir/'DATA/START.DAT').read_bytes();vectors=[]
    for category,n in ((0,39),(1,175)):
        for item in range(n):vectors.append((item,1,category,17,123456))
        for item in (0,n-1):
            for count in (0,1,99,100,255,256,65535):
                for owned in (0,98,99,255):vectors.append((item,count,category,owned,123456))
    for count in (0,1,50,65535):
        for money in (-2147483648,-50,0,999949,999950,999999,1000000,2147483640,2147483647):vectors.append((0,count,2,17,money))
    for item,count,category,owned,money in vectors:
        source=bytearray(start);struct.pack_into('<i',source,119,money)
        if category<2:source[(123 if category==0 else 162)+item]=owned
        for i,v in {0:0,1:77,99:123,299:54321}.items():struct.pack_into('<H',source,2342+i*2,v)
        program=synthetic(item,count,category)
        cases.append(dict(name=f'reward-{len(cases)}',argument=7,raw=base64.b64encode(program).decode(),before=base64.b64encode(source).decode(),**ref.event_reward(game_dir,program,source,7)))
    formats={name:bytes(ref.cpu.mem_read(address,100)).split(b'\0')[0].decode('cp1361') for name,address in [('item',0x702a0),('money',0x702b8)]}
    frames=next(v['frames'] for c in cases for v in c['requests'] if v['kind']=='panel' and v['opening'])
    equipment=json.loads((output_root/'assets/ui/equipment/equipment.json').read_text())
    opening=json.loads((output_root/'assets/opening/opening.json').read_text())
    indoorfont=json.loads((output_root/'assets/data/field/indoor-dialogues.json').read_text())['font']
    available=set(opening['font']['characters']+indoorfont['characters'])|{chr(int(k[6:])) for k in equipment['rects'] if k.startswith('glyph-')}
    text=''.join(r['name'] for key in ('weapons','items') for r in equipment[key])+formats['item'].replace('%s','').replace('%d','')+formats['money'].replace('%d','')+'0123456789'
    characters=sorted(set(text)-available);font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes();(_,code),(_,data)=objects(exe)
    nativefont=OpeningReference(code,data,font);atlas=Image.new('RGBA',(256,16*max(1,(len(characters)+15)//16)));widths={}
    for i,ch in enumerate(characters):
        im=glyph(ch,font,english,code[0x4040:0x4108]);widths[ch]=im.width
        if len(ch.encode('cp1361'))==2:
            bits=nativefont.glyph(ch.encode('cp1361'))
            if im.getchannel('A').tobytes()!=bytes(255 if bits[y*2+x//8]&(128>>(x%8)) else 0 for y in range(16) for x in range(16)):raise ValueError(ch)
        atlas.paste(im,(i%16*16,i//16*16))
    folder=output_root/'assets/ui/rewards';folder.mkdir(exist_ok=True);atlas.save(folder/'font.png')
    payload=dict(formats=formats,panel_open=[[x-16,y-16,w,h] for x,y,w,h in frames],font=dict(characters=''.join(characters),widths=widths),sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('DATA/AREA00.G&M','DATA/ITEM.LST','DATA/WEAPON.LST','HANGUL.FNT','ENGLISH.FNT')})
    (folder/'rewards.json').write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n')
    report=dict(executable_sources=EXPECTED_HASHES,sources=payload['sources'],cases=cases,parser=[ref.parser(args) for args in [(0,1,1),(65535,32768,2),(32768,65535,65535)]],stubs='대화·보상 안내의 입력/그리기는 생략한다. 보상의 실제 수량/소지금 변경과 문자열 포맷, 안내창의 사각형 계산은 원작 함수로 실행한다. 저장은 원작 로더의 저장 메모리 구간을 비교한다.')
    (output_root/'docs/restoration/event-rewards-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('보상 명령 원작 기록:',len(cases),'조합, 추가 글꼴',len(characters),'자',flush=True)
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
