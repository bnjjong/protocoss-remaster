#!/usr/bin/env python3
"""원작 기상·벽난로의 접근→연출→중첩 대화→상태 변경을 실행한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from verify_original_map import unpack_game,EXPECTED_HASHES
from verify_event_story import StoryServicesReference,animation_record
from verify_animation_approach import approach

def verify(game_dir,output_root,executable=None):
 exe=executable if executable is not None else unpack_game(game_dir)
 raw=(game_dir/'DATA/AREA00.G&M').read_bytes();start=(game_dir/'DATA/START.DAT').read_bytes();cases=[]
 for mapnum in (1,2):
  for event in (15,16):
   for state in (0,1,2,65535):
    for stage in (0,2,4):
     source=bytearray(start);x,y=(200,32) if event==15 else (80,40)
     struct.pack_into('<5I',source,96,mapnum,0,0,x+16,y+16);source[116]=0;source[117]=0;source[118]=0 if event==15 else 8
     for i,v in {0:0,1:77,2:0,4:24 if stage==2 else 25,6:state,8:stage,99:123,299:54321}.items():struct.pack_into('<H',source,2342+i*2,v)
     result=StoryServicesReference(exe).run(game_dir,raw,source,event,real_approach=True,keep_npcs=True)
     anim=next((r for r in result['requests'] if r['kind']=='animation'),None)
     walk=approach(exe,game_dir,mapnum,(x,y),target=(200,32) if event==15 else (56,40),pose=source[118],npcs=True) if anim else None
     if walk and not walk['ended']:raise ValueError('원작 접근이 도착하지 않았습니다.')
     cases.append(dict(name=f'cabin-{len(cases)}',map=mapnum,event=event,state=state,stage=stage,before=base64.b64encode(source).decode(),approach=walk,**result))
 opening=json.loads((output_root/'assets/opening/opening.json').read_text());opening['programs'][2]['continues_field']=True
 (output_root/'assets/opening/opening.json').write_text(json.dumps(opening,ensure_ascii=False,separators=(',',':'))+'\n')
 metadata=dict(animations=[animation_record(game_dir,exe,*spec) for spec in [(2,6,0,25),(3,2,0,25),(5,5,0,24),(6,3,1,27)]])
 (output_root/'assets/data/events/story.json').write_text(json.dumps(metadata,ensure_ascii=False,separators=(',',':'))+'\n')
 report=dict(executable_sources=EXPECTED_HASHES,sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('DATA/AREA00.G&M','DATA/AUTOANI.002','DATA/AUTOANI.003','MAP/AREA00.MAP')},
  cases=cases,stubs='실제 접근·연출 해석·배우 이동/자세·중첩 이벤트·상태 변경을 실행한다. 파일 읽기는 원본 AUTOANI를 메모리로 공급한다. 화면/오디오 I/O·NPC 시계는 생략한다. 접근은 별도 원작 틱 기록과도 대조한다.')
 (output_root/'docs/restoration/cabin-story-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
 print('오두막 원작:',len(cases),'상태, 연출',sum(r['approach'] is not None for r in cases),flush=True)
 return report

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
