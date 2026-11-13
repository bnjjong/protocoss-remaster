#!/usr/bin/env python3
"""마을 출구의 재합류·출발 영상·북마겔 진입을 원작과 대조한다."""
import argparse,base64,gzip,hashlib,json,struct
from pathlib import Path
from PIL import Image
from decode_opening import animation,glyph,extract_field_map
from decode_title import decode_img_frame
from decode_encounter import decode_vis,OriginalVisDecoder
from verify_original_map import unpack_game,read_offsets,EXPECTED_HASHES
from verify_original_title_save import objects
from verify_opening import OpeningReference
from verify_event_story import StoryServicesReference,animation_record
from original_field import OriginalFieldMachine

def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir);raw=(game_dir/'DATA/AREA00.G&M').read_bytes();start=(game_dir/'DATA/START.DAT').read_bytes();cases=[]
    for stage in (0,1,2,3,4,5,65535):
        for variant,(warn,seen,weapon) in enumerate(((0,0,0),(1,1,0),(65535,65535,1))):
            for profile in (0,1):
                source=bytearray(start);struct.pack_into('<5I',source,96,12,80,50,200,184);source[116]=4;source[117]=0;source[118]=8
                for i,v in {0:3,1:77,4:24,8:stage,9:warn,10:seen,54:weapon,99:123,299:54321}.items():struct.pack_into('<H',source,2342+i*2,v)
                source[500]=0
                if profile:
                    struct.pack_into('<4H',source,505,3,45,2,50);source[540]=1;source[601]=2;source[613]=17;source[630]=163;source[325]=9
                result=StoryServicesReference(exe).run(game_dir,raw,source,22,real_approach=True,keep_npcs=True)
                cases.append(dict(name=f'{stage}-{variant}-{profile}',stage=stage,variant=variant,profile=profile,before=base64.b64encode(source).decode(),**result))
    opening=json.loads((output_root/'assets/opening/opening.json').read_text());story=json.loads((output_root/'assets/data/events/story.json').read_text());previous=next((r for r in story['animations'] if r['id']==9),None)
    index=previous['program'] if previous else len(opening['programs']);program=animation((game_dir/'DATA/AUTOANI.009').read_bytes());program['continues_field']=True
    if previous:opening['programs'][index]=program;story['animations'].remove(previous)
    else:opening['programs'].append(program)
    story['animations'].append(animation_record(game_dir,exe,9,index,0,24))
    mapraw=(game_dir/'MAP/AREA00.MAP').read_bytes();off=read_offsets(game_dir/'MAP/AREA00.MDA');soc=(game_dir/'MAP/AREA00.SOC').read_bytes();so=read_offsets(game_dir/'MAP/AREA00.SDA')
    sub=mapraw[off[13]:off[14]];block=soc[so[sub[32]]:so[sub[32]]+70818];people=(game_dir/'GRP/PEOPLE.SPR').read_bytes();sda=(game_dir/'GRP/PEOPLE.SDA').read_bytes();blocks={}
    for sprite in opening['sprites']:
        pos,length=struct.unpack_from('<II',sda,4+sprite*8);b=people[pos:pos+length];ends=(*struct.unpack_from('<12I',b),length-48);blocks[sprite]=[b[48+a:48+z] for a,z in zip(ends,ends[1:])]
    atlas=Image.open(output_root/'assets/opening/people.png').convert('RGBA')
    opening['maps']['13']=extract_field_map(output_root/'assets/opening',13,sub,block,opening['sprites'],blocks,atlas)
    field=json.loads((output_root/'assets/data/field/field.json').read_text());m=OriginalFieldMachine(exe);m.load_map(sub);m.word(0xe1e0,0);movement=bytearray()
    for layer in range(3):
        for y in range(m.height):
            for x in range(m.width):
                for d in (1,2,4,8):
                    allowed,height=m.query(x*8,y*8,d,layer);movement.append(allowed | height<<4)
    (output_root/'assets/data/field/movement-13.bin').write_bytes(movement);tail=48+m.width*m.height*4+sub[33]*8+sub[34]*54
    field['maps']['13']=dict(width=m.width,height=m.height,file='movement-13.bin',sha256=hashlib.sha256(movement).hexdigest(),rectangles=[list(struct.unpack_from('<5I',sub,tail+i*20)) for i in range(sub[35])],attributes=[a for _,a in struct.iter_unpack('<HH',sub[48:48+m.width*m.height*4])],dynamic_npcs=True)
    video=(game_dir/'GRP/OPEN.VIS').read_bytes();palette,frames=decode_vis(video);native=OriginalVisDecoder(exe).decode(video)
    if frames!=native:raise ValueError('출발 영상의 원작 디코더 결과가 다릅니다.')
    folder=output_root/'assets/departure';folder.mkdir(exist_ok=True)
    stream=[(2*65536/1193182,palette+f) for f in frames]+[(1/70,bytes(v*level//64 for v in palette)+frames[-1]) for level in range(64,-1,-1)]
    data=bytearray(struct.pack('>4sHHI',b'PV01',320,200,len(stream)));last=bytes(64768)
    for seconds,f in stream:data.extend(struct.pack('>d',seconds));data.extend(a^b for a,b in zip(last,f));last=f
    (folder/'departure.pvid').write_bytes(gzip.compress(data,mtime=0))
    for i in (0,len(frames)//2,len(frames)-1):
        im=Image.frombytes('P',(320,200),frames[i]);im.putpalette([v*4 for v in palette]);im.save(folder/f'reference-{i}.png')
    manifest=dict(source_sha256=hashlib.sha256(video).hexdigest(),frames=len(frames),compared_pixels=len(frames)*64000,frame_state_sha256=[hashlib.sha256(palette+f).hexdigest() for f in frames],duration_seconds=sum(t for t,_ in stream),frame_ticks=2,tick_seconds=65536/1193182)
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    videos=json.loads((output_root/'assets/data/events/videos.json').read_text());videos['videos']=[r for r in videos['videos'] if r['name'].lower()!='open.vis']
    videos['videos'].append(dict(name='open.vis',ticks=2,music=30,restore=0,return_music=24,nested=False,frames=len(frames),video='assets/departure/departure.pvid',audio='assets/opening/music-30/music.ogg'))
    def pages(row):
        for r in row.get('requests',[]):
            yield from r.get('pages',[])
            for child in r.get('calls',[]):yield from pages(child)
    text=''.join(p['speaker']+p['text'] for row in cases for p in pages(row))+'북마겔 산맥'
    chars=sorted(set(text)-{'\n'});(_,code),(_,data)=objects(exe);font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes();reference=OpeningReference(code,data,font)
    glyphs=Image.new('RGBA',(256,16*max(1,(len(chars)+15)//16)));widths={}
    for i,c in enumerate(chars):
        im=glyph(c,font,english,code[0x4040:0x4108]);widths[c]=im.width
        if len(c.encode('cp1361'))==2:
            bits=reference.glyph(c.encode('cp1361'))
            if im.getchannel('A').tobytes()!=bytes(255 if bits[y*2+x//8]&(128>>(x%8)) else 0 for y in range(16) for x in range(16)):raise ValueError(c)
        glyphs.paste(im,(i%16*16,i//16*16))
    glyphs.save(folder/'font.png')
    payload=dict(font=dict(characters=''.join(chars),widths=widths))
    (output_root/'assets/data/events/departure.json').write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n')
    for path,value in [('assets/opening/opening.json',opening),('assets/data/events/story.json',story),('assets/data/events/videos.json',videos),('assets/data/field/field.json',field)]:
        (output_root/path).write_text(json.dumps(value,ensure_ascii=False,separators=(',',':'))+'\n')
    report=dict(executable_sources=EXPECTED_HASHES,sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('DATA/AREA00.G&M','DATA/AUTOANI.009','GRP/OPEN.VIS','MAP/AREA00.MAP','MAP/AREA00.SOC')},cases=cases,movement_queries=len(movement),video_pixels=manifest['compared_pixels'],stubs='실제 VM·접근·AUTOANI·중첩 대화·파티·영상 함수의 분기/프레임 반복/음악 선택·맵 이동·장소명·저장을 실행한다. 화면/오디오 I/O·입력/IRQ 대기와 접근 중 NPC 시계의 BIOS 공급은 생략한다. VIS 디코딩과 선택 화면·실제 입력은 별도 대조한다.')
    (output_root/'docs/restoration/town-departure-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('출발 원작:',len(cases),'상태, 이동',len(movement),'판정, 영상',len(frames),'프레임',flush=True);return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
