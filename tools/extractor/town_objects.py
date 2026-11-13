#!/usr/bin/env python3
"""엘가임의 조작 타일·원본 조사/상자·연출·저장·글꼴을 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from PIL import Image
from verify_original_map import unpack_game,EXPECTED_HASHES
from verify_original_title_save import objects
from verify_event_rewards import RewardReference
from verify_event_story import StoryServicesReference,animation_record
from decode_opening import animation,glyph
from verify_opening import OpeningReference
from extract_buildings import front_event

GROUPS={0:1,4:3,5:4,6:9,7:10,8:5,9:6,10:7,11:8,12:3}
def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir);raw=(game_dir/'DATA/AREA00.G&M').read_bytes();start=(game_dir/'DATA/START.DAT').read_bytes()
    field=json.loads((output_root/'assets/data/field/field.json').read_text());cases=[];sites=[];fronts=[]
    from verify_original_map import read_offsets
    offs=read_offsets(game_dir/'MAP/AREA00.MDA');maps=(game_dir/'MAP/AREA00.MAP').read_bytes()
    for number,group in GROUPS.items():
        meta=field['maps'][str(number)];ref=RewardReference(exe);sub=maps[offs[number]:offs[number+1]];ref.load_map(sub);ref.word(0xe1e0,0)
        for rect in meta['rectangles']:
            x1,y1,x2,y2,event=rect;tiles=[(x,y) for y in range(y1,y2+1) for x in range(x1,x2+1) if meta['attributes'][y*meta['width']+x]&4]
            if not tiles:continue
            tx,ty=tiles[0];x,y=(tx-1)*8,(ty-1)*8
            sites.append(dict(map=number,group=group,event=event,x=x,y=y,tiles=tiles))
            for direction in (1,2,4,8):
                fronts.append([number,x,y,direction,front_event(ref,x,y,direction,True)])
            for stage in (0,3,4):
                for profile in (0,1,65535):
                    source=bytearray(start);struct.pack_into('<5I',source,96,number,0,0,x+16,y+16);source[116]=0;source[117]=0;source[118]=0
                    source[123:337]=bytes([0 if profile==0 else 98 if profile==1 else 255])*214
                    struct.pack_into('<i',source,119,200 if profile==0 else 999950)
                    for i in range(9,61):struct.pack_into('<H',source,2342+i*2,profile)
                    for i,v in {0:group,1:77,4:24 if number in (4,8,9,10,11,12) else 10 if number in (5,6,7) else 25,8:stage,99:123,299:54321}.items():struct.pack_into('<H',source,2342+i*2,v)
                    result=RewardReference(exe).event_reward(game_dir,raw,source,event,chests=True);mode='services'
                    if result['end'].get('opcode')==0:
                        result=StoryServicesReference(exe).run(game_dir,raw,source,event,real_approach=True,keep_npcs=True);mode='animation'
                    if result['end']['kind']!='finished':raise ValueError(f'미지원 사물 {number}/{event}: {result["end"]}')
                    cases.append(dict(name=f'{number}-{event}-{stage}-{profile}',map=number,group=group,event=event,stage=stage,profile=profile,x=x,y=y,mode=mode,before=base64.b64encode(source).decode(),**result))
    opening=json.loads((output_root/'assets/opening/opening.json').read_text());story=json.loads((output_root/'assets/data/events/story.json').read_text())
    for number,music in ((7,10),(8,24)):
        previous=next((r for r in story['animations'] if r['id']==number),None);index=previous['program'] if previous else len(opening['programs']);program=animation((game_dir/f'DATA/AUTOANI.{number:03}').read_bytes());program['continues_field']=True
        if previous:opening['programs'][index]=program;story['animations'].remove(previous)
        else:opening['programs'].append(program)
        story['animations'].append(animation_record(game_dir,exe,number,index,0,music))
    (output_root/'assets/opening/opening.json').write_text(json.dumps(opening,ensure_ascii=False,separators=(',',':'))+'\n')
    (output_root/'assets/data/events/story.json').write_text(json.dumps(story,ensure_ascii=False,separators=(',',':'))+'\n')
    def pages(row):
        for r in row.get('requests',[]):
            yield from r.get('pages',[])
            for call in r.get('calls',[]):yield from pages(call)
    empty=bytes(RewardReference(exe).cpu.mem_read(0x702d0,80)).split(b'\0')[0].decode('cp1361')
    text=''.join(p['speaker']+p['text'] for row in cases for p in pages(row))+empty
    (_,code),(_,data)=objects(exe);font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes();native=OpeningReference(code,data,font)
    characters=sorted(set(text)-{'\n'});atlas=Image.new('RGBA',(256,max(1,(len(characters)+15)//16)*16));widths={}
    for i,c in enumerate(characters):
        im=glyph(c,font,english,code[0x4040:0x4108]);widths[c]=im.width
        if len(c.encode('cp1361'))==2:
            bits=native.glyph(c.encode('cp1361'))
            if im.getchannel('A').tobytes()!=bytes(255 if bits[y*2+x//8]&(128>>(x%8)) else 0 for y in range(16) for x in range(16)):raise ValueError(c)
        atlas.paste(im,(i%16*16,i//16*16))
    folder=output_root/'assets/ui/objects';folder.mkdir(exist_ok=True);atlas.save(folder/'font.png')
    sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('DATA/AREA00.G&M','MAP/AREA00.MAP','MAP/AREA00.SOC','DATA/AUTOANI.007','DATA/AUTOANI.008','HANGUL.FNT','ENGLISH.FNT')}
    metadata=dict(sites=sites,font=dict(characters=''.join(characters),widths=widths),empty=empty,sources=sources)
    (output_root/'assets/data/field/objects.json').write_text(json.dumps(metadata,ensure_ascii=False,separators=(',',':'))+'\n')
    report=dict(executable_sources=EXPECTED_HASHES,sources=sources,fronts=fronts,cases=cases,stubs='사물의 실제 VM·보상·0x12720 타일 변경 또는 AUTOANI.007/008·접근·중첩 대화·복귀·저장 메모리를 실행한다. 화면/오디오 I/O·입력/IRQ 대기는 생략한다. 연출 접근 중 NPC 시계의 BIOS 공급은 생략한다. 픽셀·실제 키 입력은 별도 앱 검사로 대조한다.')
    (output_root/'docs/restoration/town-objects-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('엘가임 사물:',len(sites),'위치,',len(cases),'상태, 상자',sum(r['kind']=='chest' for c in cases for r in c['requests']),flush=True);return report
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
