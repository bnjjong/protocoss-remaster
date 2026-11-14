#!/usr/bin/env python3
"""첫 기본 공격의 대상 선택·피해·시각 요소를 원작 함수로 검증한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from PIL import Image
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX,UC_X86_REG_ESP,UC_X86_REG_EIP
from battle_start import StartReference
from sword_story import StoryReference
from original_field import load_game_memory
from verify_original_map import unpack_game,EXPECTED_HASHES
from decode_title import parse_img
from decode_user_sprite import parse_user_spr,decode_frame
from verify_original_user_sprite import OriginalSpriteRenderer


def program(raw,start):
    count=struct.unpack_from('<H',raw,start)[0]
    if not 1<=count<=35:raise ValueError('공격 연출 프레임 수 오류')
    frames=[]
    for f in range(count):
        at=start+6+f*67;parts=[]
        if raw[at]>11:raise ValueError('공격 연출 그림 수 오류')
        for i in range(raw[at]):
            frame=struct.unpack_from('<H',raw,at+1+i*2)[0]
            parts.append(dict(frame=frame,x=struct.unpack_from('<h',raw,at+23+i*2)[0],y=struct.unpack_from('<h',raw,at+45+i*2)[0]))
        frames.append(parts)
    return frames


class AttackReference(StartReference):
    def prepare(self,source,group=52,party=0,clock=1,counter=0):
        if not super().prepare(source,group):raise ValueError('공격 참가 파티가 없습니다.')
        self.cpu.mem_write(0x180000,(self.game/'DATA/WEAPON.LST').read_bytes());self.dword(0x1508,0x180000)
        self.word(0x21bd,party);self.word(0x21bf,65535)
        self.cpu.mem_write(0x46c,bytes([clock]));self.dword(0x10d45,counter)
        self.selected_party=party
        return True

    def target(self,keys):
        events=[];keys=iter(keys);paused=False
        noops={0x5db90,0x6414f,0x64e1e,0x5e759,0x58f8e,0x5d254,0x642eb}
        self.cpu.mem_write(0x3e0100,b'\xc3');self.dword(0xfd40,0x3e0100)
        def hook(c,a,size,u):
            nonlocal paused
            if a==0x148b4:
                try:key=next(keys)
                except StopIteration:
                    if getattr(self,'confirmed',False):key=0
                    else:paused=True;c.emu_stop();return
                c.reg_write(UC_X86_REG_EAX,key)
            elif a==0x5a58b:self.confirmed=True;return
            elif a==0x63ea3:
                events.append(dict(x=c.reg_read(UC_X86_REG_EAX)-16,y=c.reg_read(UC_X86_REG_EDX)-16));c.reg_write(UC_X86_REG_EAX,0)
            elif a==0x61291:events.append(dict(sound=c.reg_read(UC_X86_REG_EAX)))
            elif a==0x14398:StoryReference.return_from(c,4);return
            elif a==0x58f8e:events.append(dict(attack=self.byte(0x10d2a)))
            elif a not in noops:return
            StoryReference.return_from(c)
        self.confirmed=False
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:
            c=self.cpu;c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000)
            c.emu_start(0x5a3f7,0x3f0000,count=200000)
            if not paused and c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('공격 대상 검사 종료 실패')
        finally:self.cpu.hook_del(token)
        return dict(events=events,paused=paused,result=None if paused else self.cpu.reg_read(UC_X86_REG_EAX)&255,
                    selected=self.byte(0x10d2a) if self.confirmed else None)

    def trace(self,keys=(28,)):
        for f in (0x606a4,0x5f81c,0x5f52a,0x5f4ac):self.call(f)
        at=0x3e0000;wrapper=b'\x60\xe8'+struct.pack('<i',0x6155d-(at+6))+b'\x61\xc3'
        self.cpu.mem_write(at,wrapper);self.dword(0xfd40,at)
        self.cpu.mem_write(0x3e0100,b'\xc3');self.dword(0xfd44,0x3e0100)
        frames=[];events=[];phase='target';keys=iter(keys)
        self.cpu.mem_write(0x7f240,(self.game/'GRP/BACK.FXP').read_bytes())
        self.call(0x2c184,7)
        noops={0x5eb17,0x5eb99,0x5db90,0x6414f,0x64e1e,0x5e759,0x5d73a,0x5d87c,0x5e17c}
        pending=[];effect_frame=0
        def hook(c,a,size,u):
            nonlocal phase,effect_frame
            ax,dx,bx,cx=[c.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
            if a==0x58f8e:phase='windup'
            elif a==0x587c2:phase='damage'
            elif a==0x63c62:
                phase='miss' if bx==58 else 'impact';effect_frame=struct.unpack('<I',c.mem_read(0xa34ce+bx*2351,4))[0]
                events.append(dict(kind='effect',program=bx,x=ax-16,y=dx-16))
            elif a==0x2ef18:phase='shake'
            elif a==0x62f3b:phase='number';events.append(dict(kind='number',x=ax-16,y=dx-16,color=bx,value=cx if cx<0x80000000 else cx-0x100000000))
            elif a==0x2e1fe:phase='defeat'
            elif a==0x61291:events.append(dict(kind='sound',id=ax));StoryReference.return_from(c);return
            elif a==at:
                p=self.party+self.selected_party*0x93f5;pose=struct.unpack('<I',c.mem_read(p+12,4))[0]
                frames.append(dict(phase=phase,x=struct.unpack('<h',c.mem_read(p+0x9328,2))[0]-16,
                    y=struct.unpack('<h',c.mem_read(p+0x932a,2))[0]-16,pose=pose,frame=struct.unpack('<I',c.mem_read(p+pose*2351+58,4))[0],
                    hp=[struct.unpack('<i',c.mem_read(self.monsters+i*141+79,4))[0] for i in range(self.byte(0x2015))],
                    effect_frame=effect_frame if phase in ('impact','miss') else -1,draws=pending[:]))
                pending.clear()
            if a==0x148b4:c.reg_write(UC_X86_REG_EAX,next(keys,0));StoryReference.return_from(c)
            elif a==0x679da:
                sp=c.reg_read(UC_X86_REG_ESP);dest,fmt,val=struct.unpack('<III',c.mem_read(sp+4,12));val=val if val<0x80000000 else val-0x100000000
                c.mem_write(dest,str(val).encode()+b'\0');StoryReference.return_from(c)
            elif a==0x14398:
                pending.append(dict(kind='text',x=ax-16,y=dx-16,color=cx,text=bytes(c.mem_read(bx,100)).split(b'\0')[0].decode('cp1361')))
                StoryReference.return_from(c,4)
            elif a in (0x2d11a,0x639bf,0x2ce74,0x2cf03):
                table=(struct.unpack('<I',c.mem_read(0x71658,4))[0]-0x7f240)//256 if a==0x2cf03 else -1
                pending.append(dict(kind='sprite',address=bx,header=a==0x639bf,x=ax-16,y=dx-16,shade=cx if a==0x2ce74 else -1,table=table))
                StoryReference.return_from(c,4 if a==0x2ce74 else 0)
            elif a==0x2cf9d:StoryReference.return_from(c,4)
            elif a in noops:StoryReference.return_from(c)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x5aa86)
        finally:self.cpu.hook_del(token)
        # 상태 기록의 주소는 추출된 자산 이름으로 치환한다. 원작 메모리 덤프는 저장하지 않는다.
        user=parse_user_spr((self.game/'GRP/USER.SPR').read_bytes());effects=parse_img((self.game/'GRP/EFFECT.SPR').read_bytes());menu=parse_img((self.game/'GRP/BAT-MENU.SPR').read_bytes());names={};raws={}
        for i in range(self.byte(0x2014)):
            p=self.party+i*0x93f5;character=struct.unpack('<I',self.cpu.mem_read(p+4,4))[0]
            table,blob=struct.unpack('<II',self.cpu.mem_read(p+0x9338,8))
            for j,f in enumerate(user[character]):
                key=f'user-{character}-{j}';address=blob+struct.unpack('<I',self.cpu.mem_read(table+j*4,4))[0];names[address]=key;raws[key]=f.data
        for kind,table_ptr,blob_ptr,rows in [('effect',0xa34c4,0xa34c8,effects),('menu',0x80dac,0x80db0,menu)]:
            table=struct.unpack('<I',self.cpu.mem_read(table_ptr,4))[0];blob=struct.unpack('<I',self.cpu.mem_read(blob_ptr,4))[0]
            for j,raw in enumerate(rows):names[blob+struct.unpack('<I',self.cpu.mem_read(table+j*4,4))[0]]=f'{kind}-{j}';raws[f'{kind}-{j}']=raw
        monsters=parse_img((self.game/'GRP/MONSTER.SPR').read_bytes())
        for i in range(self.byte(0x2015)):
            p=self.monsters+i*141;index=struct.unpack('<I',self.cpu.mem_read(p+12,4))[0];address=struct.unpack('<I',self.cpu.mem_read(p+48,4))[0]
            names[address]=f'monster-{index}';raws[f'monster-{index}']=monsters[index]
        for frame in frames:
            for d in frame['draws']:
                if d['kind']=='sprite':d['sprite']=names[d.pop('address')-(0 if d.pop('header') else 4)]
        p=self.party+self.selected_party*0x93f5
        final=dict(hp=[struct.unpack('<i',self.cpu.mem_read(self.monsters+i*141+79,4))[0] for i in range(self.byte(0x2015))],
            status=[struct.unpack('<I',self.cpu.mem_read(self.monsters+i*141+16,4))[0] for i in range(self.byte(0x2015))],
            x=struct.unpack('<h',self.cpu.mem_read(p+0x9328,2))[0]-16,y=struct.unpack('<h',self.cpu.mem_read(p+0x932a,2))[0]-16,
            pose=struct.unpack('<I',self.cpu.mem_read(p+12,4))[0],luck=struct.unpack('<H',self.cpu.mem_read(p+0x9389,2))[0],
            counter=struct.unpack('<I',self.cpu.mem_read(0x80d45,4))[0],result=self.cpu.reg_read(UC_X86_REG_EAX)&255)
        return dict(events=events,frames=frames,final=final),raws


def extract(game,output,exe=None):
    from decode_opening import glyph
    from verify_original_title_save import objects
    from decode_user_sprite import SpriteFrame
    from first_battle import formations
    game,output=Path(game),Path(output)
    if output.resolve()==game.resolve() or game.resolve() in output.resolve().parents:raise ValueError('원본 폴더에는 쓰지 않습니다.')
    exe=exe if exe is not None else unpack_game(game);memory,_,_=load_game_memory(exe)
    ref=AttackReference(exe,game);start=bytearray((game/'DATA/START.DAT').read_bytes());start[500]=1
    randoms=[]
    for clock in range(256):
        for counter in (0,1,255,0xffffffff):
            ref.cpu.mem_write(0x46c,bytes([clock]));ref.dword(0x10d45,counter);ref.call(0x5e7b3,10)
            randoms.append(dict(clock=clock,counter=counter,bound=10,value=ref.cpu.reg_read(UC_X86_REG_EAX),after=struct.unpack('<I',ref.cpu.mem_read(0x80d45,4))[0]))
    for bound in (0,1,83,65535,0x7fffffff):
        ref.cpu.mem_write(0x46c,b'\xff');ref.dword(0x10d45,99);ref.call(0x5e7b3,bound)
        randoms.append(dict(clock=255,counter=99,bound=bound,value=ref.cpu.reg_read(UC_X86_REG_EAX),after=struct.unpack('<I',ref.cpu.mem_read(0x80d45,4))[0]))
    ref.prepare(start);p=ref.party;e=ref.monsters;original_p=bytes(ref.cpu.mem_read(p+0x935c,153));original_e=bytes(ref.cpu.mem_read(e,141))
    weapons=(game/'DATA/WEAPON.LST').read_bytes();damage=[]
    for profile in range(48):
        a=dict(attack=(0,1,4,10,100,32767,65535)[profile%7],magic=(3,30,65535)[profile%3],buff=(0,20,-2)[profile//7%3],
               luck=(0,3,10,65535)[profile%4],weapons=[(255,0,4,8)[profile%4],(255,4)[profile//4%2]])
        d=dict(hp=(0,1,50,80,65535)[profile%5],armor=(0,5,20,1000)[profile//3%4],evasion=(0,1,5,10,255)[profile//5%5],responses=[0]+[(profile+i)%3 for i in range(1,9)])
        for clock in (0,1,2,7,31,127,200,255):
            ref.cpu.mem_write(p+0x935c,original_p);ref.cpu.mem_write(e,original_e)
            for at,value in ((0x9375,a['attack']),(0x9381,a['magic']),(0x9389,a['luck'])):ref.cpu.mem_write(p+at,struct.pack('<H',value))
            ref.cpu.mem_write(p+28,struct.pack('<i',a['buff']));ref.cpu.mem_write(p+0x93e6,bytes(a['weapons']))
            ref.cpu.mem_write(e+79,struct.pack('<i',d['hp']));ref.cpu.mem_write(e+111,struct.pack('<i',d['armor']));ref.cpu.mem_write(e+124,bytes([d['evasion']]))
            ref.cpu.mem_write(e+72,bytes(d['responses'][:7]))
            counter=profile%4;ref.cpu.mem_write(0x46c,bytes([clock]));ref.dword(0x10d45,counter);ref.call(0x587c2,0)
            damage.append(dict(attacker=a,defender=d,clock=clock,counter=counter,value=ref.cpu.reg_read(UC_X86_REG_EAX),
                hp=struct.unpack('<i',ref.cpu.mem_read(e+79,4))[0],luck=struct.unpack('<H',ref.cpu.mem_read(p+0x9389,2))[0],after=struct.unpack('<I',ref.cpu.mem_read(0x80d45,4))[0]))
    targets=[]
    for group in (50,51,52,53):
        for variant in range(3):
            for keys in ([0],[28],[57],[1],[77,28],[80,80,57],[72,28],[75,75,28]):
                ref.prepare(start,group);rows=[]
                for i in range(ref.byte(0x2015)):
                    at=ref.monsters+i*141;status=5 if variant==1 and i==0 and ref.byte(0x2015)>1 else 1
                    ref.cpu.mem_write(at+16,struct.pack('<I',status))
                    if variant==2:ref.cpu.mem_write(at+36,struct.pack('<h',16+i*30))
                    row=bytes(ref.cpu.mem_read(at,141));x,y=struct.unpack_from('<hh',row,36);cx,cy=struct.unpack_from('<hh',row,137)
                    rows.append(dict(slot=i,status=status,x=x-16,y=y-16,cursor_x=cx,cursor_y=cy))
                ref.call(0x5a1ea);targets.append(dict(enemies=rows,keys=keys,**ref.target(keys)))
    cases=[];raw_images={}
    for i in range(8):
        source=bytearray(start);party=1 if i in (4,5) else 0;source[475+party*153]=255 if i in (0,4) else 0
        if i==3:struct.pack_into('<H',source,382+party*153,10)
        if i in (5,6):struct.pack_into('<H',source,362+party*153,100)
        if i==7:struct.pack_into('<H',source,362,0)
        group=50 if i==5 else 52;clock=0 if i==1 else 1
        ref.prepare(source,group,party,clock);trace,raw=ref.trace();raw_images.update(raw)
        cases.append(dict(before=base64.b64encode(source).decode(),party=party,group=group,clock=clock,counter=0,**trace))
    source=base64.b64decode(cases[2]['before']);ref.prepare(source,52,0,1)
    for turn in range(4):
        health=[struct.unpack('<i',ref.cpu.mem_read(ref.monsters+i*141+79,4))[0] for i in range(ref.byte(0x2015))]
        conditions=[struct.unpack('<I',ref.cpu.mem_read(ref.monsters+i*141+16,4))[0] for i in range(ref.byte(0x2015))]
        counter=struct.unpack('<I',ref.cpu.mem_read(0x80d45,4))[0]
        previous=struct.unpack('<I',ref.cpu.mem_read(ref.party+6*2351+58,4))[0]
        trace,raw=ref.trace();raw_images.update(raw)
        if turn:cases.append(dict(before=base64.b64encode(source).decode(),party=0,group=52,clock=1,counter=counter,
            health=health,conditions=conditions,previous=previous,**trace))
    lev=(game/'DATA/LEV_PARA.LST').read_bytes();user=(game/'DATA/USER.ANI').read_bytes();offsets=struct.unpack_from('<11I',user,2)
    effects=(game/'DATA/EFFECT.ANI').read_bytes();menu=(game/'DATA/MENU.ANI').read_bytes()
    meta=dict(version=1,weapons=[dict(element=weapons[i*84+51],type=weapons[i*84+52],proc=weapons[i*84+53]) for i in range(83)],
        attacks={str(i):dict(pose=lev[i*26*20+12],effect=lev[i*26*20+14],dx=struct.unpack_from('<b',lev,i*26*20+16)[0],dy=struct.unpack_from('<b',lev,i*26*20+17)[0]) for i in (0,1)},
        user={str(i):{str(lev[i*26*20+12]):program(user,offsets[i]+2+lev[i*26*20+12]*2351)} for i in (0,1)},
        effects={str(i):program(effects,2+i*2351) for i in (2,4,58)},target_cursor=program(menu,2+2351),
        shake=list(struct.unpack_from('<21h',memory,0x71c3d)),float=list(struct.unpack_from('<20h',memory,0x721e9)),images={})
    folder=output/'assets/battle/attack';folder.mkdir(parents=True,exist_ok=True)
    palette=(game/'DATA/PROTOCOS.PAL').read_bytes();fxp=(game/'GRP/BACK.FXP').read_bytes();renderer=OriginalSpriteRenderer(exe);images={}
    names={d['sprite'] for case in cases for f in case['frames'] for d in f['draws'] if d['kind']=='sprite'}
    for parts in meta['target_cursor']:
        for part in parts:names.add('menu-'+str(part['frame']))
    for i in (0,1):names.add(f'user-{i}-0')
    def image(name,shade=-1,table=-1):
        key=f'{name}-s{shade}-t{table}'
        if key in images:return images[key]
        raw=raw_images[name];frame=decode_frame(raw);native=renderer.render(raw,palette)
        if native.tobytes()!=frame.to_image(palette).tobytes():raise ValueError('공격 그림 원작 출력 불일치')
        ids=bytes(shade if shade>=0 else fxp[table*256+c] if table>=0 else c for c in frame.indices)
        result=SpriteFrame(frame.width,frame.height,ids,frame.alpha).to_image(palette)
        if shade>=0 or table>=0:
            c=renderer.cpu;renderer.indices=bytearray(frame.width*frame.height);renderer.alpha=bytearray(frame.width*frame.height)
            c.mem_write(0x1f240,fxp);c.mem_write(0x1658,struct.pack('<I',0x1f240+max(table,0)*256))
            c.mem_write(0x300000,struct.pack('<II',renderer.RETURN,renderer.VRAM));c.reg_write(UC_X86_REG_ESP,0x300000)
            c.reg_write(UC_X86_REG_EAX,0);c.reg_write(UC_X86_REG_EDX,0);c.reg_write(UC_X86_REG_EBX,renderer.SOURCE)
            c.reg_write(UC_X86_REG_ECX,shade if shade>=0 else renderer.VRAM)
            c.emu_start(0x2ce74 if shade>=0 else 0x2cf03,renderer.RETURN,count=3000000)
            native=SpriteFrame(frame.width,frame.height,bytes(renderer.indices),bytes(renderer.alpha)).to_image(palette)
            if native.tobytes()!=result.tobytes():raise ValueError('공격 색 변환 원작 출력 불일치')
        path='assets/sprites/user/character_%02d/frame_%03d.png'%tuple(map(int,name.split('-')[1:])) if name.startswith('user-') and shade<0 and table<0 else f'assets/battle/attack/{key}.png'
        if path.startswith('assets/battle/attack/') :result.save(output/path)
        meta['images'][key]=dict(file=path,width=frame.width,height=frame.height);images[key]=result
        return result
    for name in sorted(names):image(name)
    for case in cases:
        for f in case['frames']:
            for d in f['draws']:
                if d['kind']=='sprite':image(d['sprite'],d['shade'],d.get('table',-1))
    (_,code),_=objects(exe);font=(game/'HANGUL.FNT').read_bytes();english=(game/'ENGLISH.FNT').read_bytes()
    characters='0123456789-SUPER FLYWO';characters=''.join(dict.fromkeys(characters))
    atlas=Image.new('RGBA',(len(characters)*8,16));color=tuple(v*4 for v in palette[80*3:80*3+3])+(255,)
    for i,ch in enumerate(characters):
        g=glyph(ch,font,english,code[0x4040:0x4108]);ink=Image.new('RGBA',g.size,color);ink.putalpha(g.getchannel('A'));atlas.alpha_composite(ink,(i*8,0))
    atlas.save(folder/'font.png');meta['characters']=characters
    (folder/'attack.json').write_text(json.dumps(meta,ensure_ascii=False,separators=(',',':'))+'\n')
    groups=formations((game/'DATA/MON_GRP.LST').read_bytes());background=(game/'GRP/BACK.DAT').read_bytes()[23*64000:24*64000]
    # 화면 비교는 원작의 그리기 명령·프레임 상태를 조합한다. 전체 상태창·시간 재현과 구분한다.
    for case in cases:
        source=base64.b64decode(case['before']);ref.prepare(source,case['group'],case['party']);actors=ref.party_state();layers=ref.layers()
        for index,f in enumerate(case['frames']):
            if f['phase']=='target':continue
            im=Image.frombytes('P',(320,200),background);im.putpalette([v*4 for v in palette]);im=im.convert('RGBA')
            drawn_monsters=any(d.get('sprite','').startswith('monster-') for d in f['draws'])
            if not drawn_monsters:
                for i,enemy in enumerate(groups[case['group']]['enemies']):
                    if case.get('conditions',[1]*len(groups[case['group']]['enemies']))[i] not in (0,5):im.alpha_composite(image('monster-'+str(enemy['monster'])),(enemy['x'],enemy['y']))
            for part in layers:
                if part['character']==actors[case['party']]['character']:continue
                im.alpha_composite(image(f"user-{part['character']}-{part['frame']}"),(part['x'],part['y']))
            for part in meta['user'][str(actors[case['party']]['character'])][str(f['pose'])][f['frame']]:
                im.alpha_composite(image(f"user-{actors[case['party']]['character']}-{part['frame']}"),(f['x']+part['x'],f['y']+part['y']))
            for d in f['draws']:
                if d['kind']=='sprite':im.alpha_composite(image(d['sprite'],d['shade'],d.get('table',-1)),(d['x'],d['y']))
                else:
                    for k,ch in enumerate(d['text']):
                        g=atlas.crop((characters.index(ch)*8,0,characters.index(ch)*8+8,16));a=g.getchannel('A');shadow=Image.new('RGBA',g.size,(0,0,0,255));shadow.putalpha(a)
                        im.alpha_composite(shadow,(d['x']+k*8+1,d['y']+1));im.alpha_composite(g,(d['x']+k*8,d['y']))
            f['rgba_sha256']=hashlib.sha256(im.tobytes()).hexdigest()
            if case is cases[2] and index in (2,7,13,18,40):im.save(folder/f'reference-{index}.png')
    report=dict(executable_sources=EXPECTED_HASHES,randoms=randoms,damage=damage,targets=targets,cases=cases,
        sources={p:hashlib.sha256((game/p).read_bytes()).hexdigest() for p in ('DATA/START.DAT','DATA/MON_GRP.LST','DATA/MON_PARA.LST','DATA/WEAPON.LST','DATA/LEV_PARA.LST','DATA/USER.ANI','DATA/EFFECT.ANI','DATA/MENU.ANI','GRP/USER.SPR','GRP/MONSTER.SPR','GRP/EFFECT.SPR','GRP/BAT-MENU.SPR','GRP/BACK.DAT','GRP/BACK.FXP','DATA/PROTOCOS.PAL','HANGUL.FNT','ENGLISH.FNT')},
        stubs='실제 5E7B3·587C2·5A3F7·5AA86·58F8E·2EF18·2E1FE의 계산·분기·프레임 상태를 실행한다. 하위 화면/음향 I/O와 전체 상태창 그리기는 생략한다. 콜백마다 실제 6155D를 한 번 호출하고 BIOS 하위 시계 바이트를 고정한다. 그래픽 비교는 원작의 프레임/그리기 위치와 VGA 그림·색 변환 결과를 조합한다. 전체 전투 화면·실제 시간·음악·승패/보상은 포함하지 않는다.')
    (output/'docs/restoration/battle-attack-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('공격 원작:',len(randoms),'난수,',len(damage),'피해,',len(targets),'대상,',len(cases),'공격',sum(len(c['frames']) for c in cases),'시점',flush=True)
    return meta,report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
