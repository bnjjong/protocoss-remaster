#!/usr/bin/env python3
"""전투 파티 초기화·행동 대기·방향키 명령 분기를 원작과 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX,UC_X86_REG_ESP,UC_X86_REG_EIP
from first_battle import BattleReference,native_formation
from original_field import load_game_memory
from sword_story import StoryReference
from verify_original_map import unpack_game,EXPECTED_HASHES
from decode_user_sprite import parse_user_spr,decode_frame
from decode_title import parse_img
from verify_original_user_sprite import OriginalSpriteRenderer


class StartReference(BattleReference):
    def __init__(self,exe,game):
        super().__init__(exe,game)
        self.save_reference=StoryReference(exe)

    def prepare(self,source,group=50):
        self.source=bytes(source)
        self.save_reference.load_save(source)
        for _,n,p in self.save_reference.regions:
            self.cpu.mem_write(p,bytes(self.save_reference.cpu.mem_read(p,n)))
        self.call(0x615e3)
        self.formation(group)
        self.call(0x5f12f)
        if self.cpu.reg_read(UC_X86_REG_EAX)&255:
            return False
        for f in (0x5f5a8,0x5f6b7,0x602fa,0x5a1ea,0x5a26b):self.call(f)
        self.cpu.mem_write(0x72211,bytes(8))
        self.byte(0x21a8,1)
        self.party=struct.unpack('<I',self.cpu.mem_read(0x72004,4))[0]
        self.monsters=struct.unpack('<I',self.cpu.mem_read(0x72008,4))[0]
        return True

    def party_state(self):
        result=[]
        for i in range(self.byte(0x2014)):
            p=self.party+i*0x93f5
            def integer(o):return struct.unpack('<i',self.cpu.mem_read(p+o,4))[0]
            def word(o):return struct.unpack('<h',self.cpu.mem_read(p+o,2))[0]
            parameters=bytes(self.cpu.mem_read(p+0x935c,153))
            result.append(dict(character=integer(4),pose=integer(12),role=integer(20),effect=integer(16),
                stats=[integer(o) for o in (32,36,40,44)],parameters=parameters.hex(),
                x=word(0x9328)-16,y=word(0x932a)-16,home_x=word(0x932c)-16,home_y=word(0x932e)-16,
                hud_x=word(0x934c)-16,hud_y=word(0x934e)-16,gauge=integer(0x9340)))
        return result

    def clock_state(self):
        def ints(p,n):return list(struct.unpack('<'+'I'*n,self.cpu.mem_read(p,n*4)))
        return dict(time=ints(0xc5c64,1)[0],party_ticks=ints(0xc5c7c,self.byte(0x2014)),
            enemy_ticks=ints(0xc5c3c,self.byte(0x2015)),
            party_gauge=[ints(self.party+i*0x93f5+0x9340,1)[0] for i in range(self.byte(0x2014))],
            enemy_gauge=[ints(self.monsters+i*141+20,1)[0] for i in range(self.byte(0x2015))],
            hp_bars=[ints(self.party+i*0x93f5+0x9344,1)[0] for i in range(self.byte(0x2014))],
            mp_bars=[ints(self.party+i*0x93f5+0x9348,1)[0] for i in range(self.byte(0x2014))],
            queue=list(self.cpu.mem_read(0x72213,6)),head=self.byte(0x2211),tail=self.byte(0x2212))

    def layers(self):
        layers=[]
        user=parse_user_spr((self.game/'GRP/USER.SPR').read_bytes())
        def hook(c,a,size,u):
            if a==0x63af4:self.current_actor=c.reg_read(UC_X86_REG_EBX)
            if a!=0x639bf:return
            x,y,p=[c.reg_read(v) for v in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX)]
            actor=self.current_actor
            base=self.party+actor*0x93f5
            offsets=struct.unpack('<I',c.mem_read(base+0x9338,4))[0]
            blob=struct.unpack('<I',c.mem_read(base+0x933c,4))[0]
            character=struct.unpack('<I',c.mem_read(base+4,4))[0]
            candidates=[(i,f) for i,f in enumerate(user[character]) if blob+struct.unpack('<I',c.mem_read(offsets+i*4,4))[0]==p]
            if len(candidates)!=1:raise ValueError('원작 파티 프레임 위치 불일치')
            frame,raw=candidates[0]
            if bytes(c.mem_read(p,len(raw.data)))!=raw.data:raise ValueError('원작 파티 그림 불일치')
            layers.append(dict(character=character,frame=frame,x=x-16,y=y-16))
            StoryReference.return_from(c)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:
            self.call(0x64b69)
        finally:self.cpu.hook_del(token)
        return layers

    def commands(self,keys,result,selected=0):
        self.word(0x21bd,selected);self.byte(0x80d44-0x70000,0)
        actor=self.party+selected*0x93f5
        self.cpu.mem_write(actor+0x9340,struct.pack('<I',28))
        keys=iter(keys);events=[];paused=False
        noops={0x5e70f,0x5ec37,0x5dd18,0x6414f,0x64e1e,0x5eecb}
        services={0x5aa86:'up',0x551ee:'right',0x559f8:'down',0x5839d:'left'}
        self.cpu.mem_write(0x3e0000,b'\xc3');self.dword(0xfd40,0x3e0000)
        def hook(c,a,size,u):
            nonlocal paused
            if a==0x148b4:
                try:key=next(keys)
                except StopIteration:paused=True;c.emu_stop();return
                events.append(dict(kind='key',value=key));c.reg_write(UC_X86_REG_EAX,key)
            elif a in services:
                events.append(dict(kind='service',direction=services[a],result=result));c.reg_write(UC_X86_REG_EAX,result)
            elif a in (0x61291,0x5e8c1):
                event=dict(kind='sound' if a==0x61291 else 'effect',a=c.reg_read(UC_X86_REG_EAX))
                if a==0x5e8c1:event['d']=c.reg_read(UC_X86_REG_EDX)
                events.append(event)
            elif a not in noops:return
            StoryReference.return_from(c)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:
            c=self.cpu;c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000)
            c.emu_start(0x5f0bd,0x3f0000,count=200000)
            if not paused and c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('전투 명령 검사 종료 실패')
        finally:self.cpu.hook_del(token)
        return dict(events=events,closed=not paused,gauge=struct.unpack('<I',self.cpu.mem_read(actor+0x9340,4))[0],
            selected=self.word(0x21bd),mode=self.byte(0x80d44-0x70000))


def extract(game,output,exe=None):
    game,output=Path(game),Path(output)
    if game.resolve()==output.resolve() or game.resolve() in output.resolve().parents:raise ValueError('원본 폴더에는 쓰지 않습니다.')
    exe=exe if exe is not None else unpack_game(game)
    memory,_,_=load_game_memory(exe)
    user=parse_user_spr((game/'GRP/USER.SPR').read_bytes());ani=(game/'DATA/USER.ANI').read_bytes()
    offsets=struct.unpack_from('<11I',ani,2)
    poses={}
    for character in range(11):
        poses[str(character)]={}
        for action in (0,1,2,10):
            at=offsets[character]+2+action*0x92f
            parts=[]
            for i in range(ani[at+6]):
                frame=struct.unpack_from('<H',ani,at+7+i*2)[0]
                parts.append(dict(frame=frame,dx=struct.unpack_from('<h',ani,at+29+i*2)[0],dy=struct.unpack_from('<h',ani,at+51+i*2)[0]))
            poses[str(character)][str(action)]=parts
    meta=dict(version=1,anchors=[[list(struct.unpack_from('<hh',memory,0x720e6+n*20+i*4)) for i in range(n+1)] for n in range(5)],
        dead_offsets=[list(struct.unpack_from('<hh',memory,0x7214a+i*4)) for i in range(11)],
        roles=list(struct.unpack_from('<13i',memory,0x72172)),delays=list(struct.unpack_from('<11i',memory,0x721c1)),
        poses=poses,sizes={str(c):[list(struct.unpack_from('<HH',f.data)) for f in frames] for c,frames in enumerate(user)})
    ref=StartReference(exe,game);base=bytearray((game/'DATA/START.DAT').read_bytes());cases=[]
    for party in ([0],[0,1],[2,5,10],[0,1,2,3,4],list(range(11)),[]):
        for profile in range(6):
            source=bytearray(base)
            for c in range(11):
                source[347+c*153]=int(c in party)
                source[472+c*153]=(profile+c)%5+1 if profile else 1
                if profile in (2,3):source[387+c*153]=(c%12)+1
            accepted=ref.prepare(source)
            cases.append(dict(before=base64.b64encode(source).decode(),accepted=accepted,party=ref.party_state() if accepted else [],layers=ref.layers() if accepted else []))
    for c in range(11):
        for status in (1,2,3,5):
            source=bytearray(base)
            for k in range(11):source[347+k*153]=int(k==c)
            source[472+c*153]=status
            ref.prepare(source)
            cases.append(dict(before=base64.b64encode(source).decode(),accepted=True,party=ref.party_state(),layers=ref.layers()))
    for form in range(13):
        source=bytearray(base);source[500]=1;source[387]=form;ref.prepare(source)
        cases.append(dict(before=base64.b64encode(source).decode(),accepted=True,party=ref.party_state(),layers=ref.layers()))
    clocks=[]
    for profile in range(13):
        source=bytearray(base);source[500]=1
        if profile==12:source[472]=5
        ref.prepare(source,50+profile%4)
        if profile==1:
            ref.cpu.mem_write(ref.party+0x937d,struct.pack('<H',65535))
        if profile==2:
            ref.cpu.mem_write(ref.party+0x93e3,b'\x02')
        if profile==3:
            ref.cpu.mem_write(ref.monsters+16,struct.pack('<I',2))
        if profile==8:ref.cpu.mem_write(ref.party+0x937d,bytes(2))
        if profile==9:ref.cpu.mem_write(ref.party+0x936b,bytes(8))
        if profile==10:ref.cpu.mem_write(0xc5c68,struct.pack('<I',1))
        if profile==11:
            for i in range(2):ref.cpu.mem_write(ref.party+i*0x93f5+0x937d,struct.pack('<H',65535))
        ref.call(0x5a1ea);ref.call(0x5a26b)
        initial=dict(party=[dict(status=ref.cpu.mem_read(ref.party+i*0x93f5+0x93e3,1)[0],speed=struct.unpack('<H',ref.cpu.mem_read(ref.party+i*0x93f5+0x937d,2))[0],
            hp=struct.unpack('<H',ref.cpu.mem_read(ref.party+i*0x93f5+0x936b,2))[0],max_hp=struct.unpack('<H',ref.cpu.mem_read(ref.party+i*0x93f5+0x936d,2))[0],
            mp=struct.unpack('<H',ref.cpu.mem_read(ref.party+i*0x93f5+0x936f,2))[0],max_mp=struct.unpack('<H',ref.cpu.mem_read(ref.party+i*0x93f5+0x9371,2))[0],
            gauge=struct.unpack('<I',ref.cpu.mem_read(ref.party+i*0x93f5+0x9340,4))[0],charging=bool(struct.unpack('<I',ref.cpu.mem_read(0xc5c68+i*4,4))[0])) for i in range(ref.byte(0x2014))],
            enemies=[dict(status=struct.unpack('<I',ref.cpu.mem_read(ref.monsters+i*141+16,4))[0],speed=struct.unpack('<i',ref.cpu.mem_read(ref.monsters+i*141+119,4))[0],gauge=1) for i in range(ref.byte(0x2015))])
        steps=[]
        for tick in range(240):
            enabled=not(profile==4 and tick%7<3);ref.byte(0x21a8,int(enabled));ref.call(0x6155d)
            ref.call(0x647de);enemy=ref.cpu.reg_read(UC_X86_REG_EAX)
            if enemy!=0xffffffff:ref.cpu.mem_write(ref.monsters+enemy*141+20,bytes(4))
            op='refresh' if profile in (5,6,11) and tick%23<18 else 'select'
            ref.call(0x642eb if op=='refresh' else 0x6450c)
            selected=ref.cpu.reg_read(UC_X86_REG_EAX) if op=='select' else 0xffffffff
            if selected!=0xffffffff:ref.cpu.mem_write(ref.party+selected*0x93f5+0x9340,bytes(4))
            steps.append(dict(enabled=enabled,op=op,enemy=enemy if enemy!=0xffffffff else -1,selected=selected if selected!=0xffffffff else -1,after=ref.clock_state()))
        clocks.append(dict(initial=initial,steps=steps))
    commands=[]
    for keys in ([],[72],[77],[80],[75],[1],[28],[57],[28,57,72],[1,77],[72,80,75,77]):
        for result in (0,1):
            for selected in (0,1):
                source=bytearray(base);source[500]=1;ref.prepare(source)
                commands.append(dict(keys=keys,result=result,party_index=selected,**ref.commands(keys,result,selected)))
    folder=output/'assets/battle';folder.mkdir(exist_ok=True,parents=True)
    guide=parse_img((game/'GRP/BAT-MENU.SPR').read_bytes())[74];palette=(game/'DATA/PROTOCOS.PAL').read_bytes()
    picture=decode_frame(guide).to_image(palette);native=OriginalSpriteRenderer(exe).render(guide,palette)
    if picture.tobytes()!=native.tobytes():raise ValueError('명령 안내 그림 불일치')
    picture.save(folder/'command-guide.png')
    # 파티 전체와 명령 안내만 비교한다. 상태창·이펙트·명령의 실행 결과는 포함하지 않는다.
    scenes=[]
    monster=parse_img((game/'GRP/MONSTER.SPR').read_bytes())[0]
    background=(game/'GRP/BACK.DAT').read_bytes()[23*64000:24*64000]
    from PIL import Image
    for case in (cases[0],cases[6],cases[8],cases[12],cases[18],cases[24]):
        if not case['accepted']:continue
        for command in (False,True):
            sprite_data=[monster];layers=[dict(monster=0,x=50,y=98)]
            for part in case['layers']:
                sprite_data.append(user[part['character']][part['frame']].data)
                layers.append(dict(monster=len(sprite_data)-1,x=part['x'],y=part['y']))
            if command:sprite_data.append(guide);layers.append(dict(monster=len(sprite_data)-1,x=11,y=13))
            image=Image.frombytes('P',(320,200),background);image.putpalette([v*4 for v in palette]);image=image.convert('RGBA')
            for layer in layers:image.alpha_composite(decode_frame(sprite_data[layer['monster']]).to_image(palette),(layer['x'],layer['y']))
            native=native_formation(exe,background,layers,sprite_data,palette)
            if native.tobytes()!=image.tobytes():raise ValueError('원작 파티 배치 픽셀 불일치')
            name=f'start-{len(scenes)}.png';image.save(folder/name)
            scenes.append(dict(before=case['before'],guide=command,file=name))
    meta['scenes']=scenes
    (folder/'start.json').write_text(json.dumps(meta,separators=(',',':'))+'\n')
    report=dict(executable_sources=EXPECTED_HASHES,sources={p:hashlib.sha256((game/p).read_bytes()).hexdigest() for p in ('DATA/START.DAT','DATA/USER.ANI','GRP/USER.SPR','GRP/BAT-MENU.SPR')},
        party_cases=cases,clock_cases=clocks,commands=commands,guide_pixels=picture.width*picture.height,scene_pixels=len(scenes)*64000,
        stubs='파티 초기화 5F12F·5F5A8·5F6B7·602FA와 파티 그림 63AF4의 프레임/좌표를 실행한다. 원작 시계 6155D·선택 647DE/6450C·대기 642EB를 수동 틱으로 실행하며 완료한 행동의 게이지만 0으로 복구한다. 실제 시간 간격·피해·효과·전투 결과는 포함하지 않는다. 명령 5F0BD→5EF04는 키를 공급하고 4개 하위 메뉴의 반환값을 지정한다. 하위 메뉴 본체·화면·음향 I/O는 생략한다. EXE/재배치 메모리는 저장하지 않는다.')
    (output/'docs/restoration/battle-start-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('전투 시작 원작:',len(cases),'파티,',sum(len(c['steps']) for c in clocks),'시계/선택,',len(commands),'명령',flush=True)
    return meta,report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
