#!/usr/bin/env python3
"""원본 전체 목록과 구조 검사 결과를 기록한다. 파일 존재를 플레이 완료로 계산하지 않는다."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
from decode_map import read_offsets, BLOCK_SIZE
from decode_opening import animation
from decode_title import parse_img
from decode_user_sprite import parse_user_spr
from event_archive import EventArchive


def digest(raw):return hashlib.sha256(raw).hexdigest()


def chunks(blob, offsets):
    if not offsets or offsets[0]!=0 or any(a>=b for a,b in zip(offsets,offsets[1:])) or offsets[-1]>=len(blob):
        raise ValueError('컨테이너 오프셋 순서·범위 오류')
    return [blob[a:b] for a,b in zip(offsets,(*offsets[1:],len(blob)))]


def area_catalogue(root, area):
    stem=f'MAP/AREA{area:02}'
    soc=(root/(stem+'.SOC')).read_bytes();tiles=chunks(soc,read_offsets(root/(stem+'.SDA')))
    for block in tiles:
        if len(block)!=BLOCK_SIZE or block[:2]!=b'\x01\x01' or max(block[2:770])>63:
            raise ValueError(f'SOC 블록 구조 오류: {stem}')
    offsets=read_offsets(root/(stem+'.MDA'));raw=(root/(stem+'.MAP')).read_bytes();maps=[]
    for number,source in enumerate(chunks(raw,offsets)):
        if len(source)<48:raise ValueError(f'잘린 맵 헤더: {area,number}')
        width,height=source[2]*40,source[3]*25;tail=48+width*height*4
        expected=tail+source[33]*8+source[34]*54+source[35]*20
        if not width or not height or expected!=len(source) or source[32]>=len(tiles):
            raise ValueError(f'맵 크기·타일셋·레코드 범위 오류: {area,number}')
        ids=[t&4095 for t,_ in struct.iter_unpack('<HH',source[48:tail])]
        if max(ids)>=1000:raise ValueError(f'타일 번호 범위 오류: {area,number}')
        npc_start=tail+source[33]*8
        sprites=sorted({struct.unpack_from('<I',source,npc_start+i*54)[0] for i in range(source[34])})
        maps.append(dict(map=number,offset=offsets[number],bytes=len(source),width=width,height=height,
            tileset=source[4:20].split(b'\0')[0].decode('ascii'),tileset_index=source[32],
            animated_tiles=source[33],npc_records=source[34],event_rectangles=source[35],npc_sprites=sprites,
            minimum_tile=min(ids),maximum_tile=max(ids)))
    archive=EventArchive((root/f'DATA/AREA{area:02}.G&M').read_bytes())
    groups=[dict(group=i,offset=g.offset,routes=len(g.routes),fallback=g.fallback,
        entry_opcodes=dict(sorted(Counter(f'{archive.code[d]:02X}' for _,d in g.routes).items())))
        for i,g in enumerate(archive.groups)]
    return dict(area=area,tilesets=len(tiles),maps=maps,event_groups=groups)


def resource_catalogue(root):
    results={}
    user=parse_user_spr((root/'GRP/USER.SPR').read_bytes())
    results['user']=dict(characters=len(user),frames=sum(map(len,user)),check='container_ranges',native_evidence='docs/sprite-format.md')
    people=(root/'GRP/PEOPLE.SPR').read_bytes();sda=(root/'GRP/PEOPLE.SDA').read_bytes()
    count=struct.unpack_from('<I',sda)[0]
    if len(sda)!=4+count*8:raise ValueError('PEOPLE.SDA 크기 오류')
    previous=0;frames=0
    for start,length in struct.iter_unpack('<II',sda[4:]):
        if start!=previous or length<=48 or start+length>len(people):raise ValueError('PEOPLE.SPR 블록 범위 오류')
        block=people[start:start+length];bounds=(*struct.unpack_from('<12I',block),length-48)
        if bounds[0]!=0 or any(a>=b for a,b in zip(bounds,bounds[1:])):raise ValueError('주민 프레임 목록 오류')
        previous=start+length;frames+=12
    if previous!=len(people):raise ValueError('주민 블록 뒤에 데이터가 남았습니다.')
    results['people']=dict(blocks=count,frames=frames,check='container_ranges',pixel_comparison='selected_field_sprites_only')
    results['sprite_tables']={}
    for name in ('BAT-MENU.SPR','EFFECT.SPR','MONSTER.SPR','SMALL.SPR'):
        results['sprite_tables'][name]=dict(frames=len(parse_img((root/'GRP'/name).read_bytes())),check='container_ranges')
    results['extra']=dict(file='GRP/EXTRA.SPR',check='not_interpreted')
    results['img']={}
    for path in sorted((root/'GRP').glob('*.IMG')):
        try:results['img'][path.name]=dict(frames=len(parse_img(path.read_bytes())),check='container_ranges')
        except ValueError as error:results['img'][path.name]=dict(check='not_interpreted',error=str(error))
    results['vis']=dict(files=len(list((root/'GRP').glob('*.VIS'))),check='file_inventory_only',
        note='기존 일괄 VIS 산출물은 전체 픽셀 검증이 끝나지 않았다.')
    results['ending']=dict(file='GRP/END.MOV',bytes=(root/'GRP/END.MOV').stat().st_size,check='file_inventory_only')
    results['battle_maps']=dict(files=len(list((root/'MAP').glob('BAT*.MAP'))),check='file_inventory_only')
    parsed=[];failures=[];ops=Counter()
    for path in sorted((root/'DATA').glob('AUTOANI.*')):
        try:
            program=animation(path.read_bytes());counts=Counter(c['op'] for c in program['commands']);ops.update(counts)
            parsed.append(dict(file=path.name,area=program['area'],map=program['map'],commands=len(program['commands'])))
        except (ValueError,IndexError,struct.error) as error:failures.append(dict(file=path.name,error=str(error)))
    results['autoani']=dict(files=len(parsed)+len(failures),check='current_parser_syntax_only',parsed=parsed,failures=failures,commands=dict(sorted(ops.items())))
    results['audio']=dict(bgm_files=len(list((root/'S&W').glob('*.MFA'))),sfx_files=len(list((root/'S&W').glob('*.EFF'))),check='file_inventory_only')
    results['database']={}
    for name,size in (('ITEM.LST',58),('MAGIC.LST',89),('MON_PARA.LST',89),('WEAPON.LST',84)):
        length=(root/'DATA'/name).stat().st_size
        if length%size:raise ValueError(f'기존 DB 레코드 크기와 맞지 않습니다: {name}')
        results['database'][name]=dict(record_bytes=size,records=length//size,check='record_size_only')
    results['other_database_files']=[p.name for p in sorted((root/'DATA').glob('*.LST')) if p.name not in results['database']]
    return results


def catalogue(game_dir, output_root):
    game_dir=game_dir.resolve();output_root=output_root.resolve()
    if game_dir==output_root or game_dir in output_root.parents:raise ValueError('원본 폴더에는 산출물을 쓰지 않습니다.')
    files=[]
    paths=[p for folder in ('MAP','DATA','GRP','S&W') for p in (game_dir/folder).iterdir()
        if p.is_file() and not re.fullmatch(r'SAVE\d+\.DAT',p.name,re.IGNORECASE)]
    executable_names=('AMOS.EXE','PROTOCOS.FNT','PROLOGUE.EXE','ENDING.EXE','START.EXE')
    reference_names=('메뉴얼.txt','암호표.jpg','암호표2.jpg')
    paths += [game_dir/name for name in ('HANGUL.FNT','ENGLISH.FNT','PASS.GRP',*executable_names,*reference_names)]
    for path in sorted(paths):
        raw=path.read_bytes();files.append(dict(file=path.relative_to(game_dir).as_posix(),bytes=len(raw),sha256=digest(raw)))
    areas=[area_catalogue(game_dir,i) for i in range(6)]
    resources=resource_catalogue(game_dir)
    rejected={f"DATA/{r['file']}" for r in resources['autoani']['failures']}
    for row in files:
        path=Path(row['file']);name=path.name;check='file_inventory_only';family='unclassified'
        if path.parent.name=='MAP':
            if name.startswith('AREA') and path.suffix in ('.MAP','.MDA','.SOC','.SDA'):family='field_container';check='container_ranges'
            elif name.startswith('BAT') and path.suffix=='.MAP':family='battle_map'
        elif path.parent.name=='DATA':
            if path.suffix=='.G&M':family='event_archive';check='entry_table_ranges'
            elif name.startswith('AUTOANI.'):
                family='autoani';check='parser_rejected' if row['file'] in rejected else 'current_parser_syntax_only'
            elif path.suffix=='.LST':
                family='database';check='record_size_only' if name in resources['database'] else 'not_interpreted'
            elif name=='START.DAT':family='initial_save'
            elif path.suffix=='.PAL':family='palette'
            elif path.suffix=='.ANI':family='motion_table';check='not_interpreted'
            elif path.suffix=='.GUP':family='auxiliary_table';check='not_interpreted'
        elif path.parent.name=='GRP':
            if name in ('USER.SPR','PEOPLE.SPR','PEOPLE.SDA') or name in resources['sprite_tables']:family='sprite';check='container_ranges'
            elif name=='EXTRA.SPR':family='sprite';check='not_interpreted'
            elif path.suffix=='.IMG':family='ui';check=resources['img'][name]['check']
            elif path.suffix=='.VIS':family='visual'
            elif name=='END.MOV':family='ending'
            elif name=='BACK.DAT':family='battle_background'
            elif path.suffix=='.PAL':family='palette'
        elif path.parent.name=='S&W':family='audio'
        elif name in executable_names:family='executable_reference';check='hash_only'
        elif name in reference_names:family='supplementary_reference'
        elif path.suffix=='.FNT':family='font'
        elif name=='PASS.GRP':family='password_background'
        row.update(family=family,check=check)
    opening=json.loads((output_root/'assets/opening/opening.json').read_text())
    result=dict(schema=1,interpretation='구조 검사·파일 목록이며 플레이 복원율이 아니다.',
        excluded='사용자 SAVE*.DAT·설정·백업·설치 도구·압축 배포본은 게임 리소스 수에 포함하지 않는다.',
        totals=dict(areas=len(areas),maps=sum(len(a['maps']) for a in areas),
            npc_records=sum(m['npc_records'] for a in areas for m in a['maps']),
            event_rectangles=sum(m['event_rectangles'] for a in areas for m in a['maps']),
            event_groups=sum(len(a['event_groups']) for a in areas),
            event_routes=sum(g['routes'] for a in areas for g in a['event_groups'])),
        packaged_opening=dict(area=0,map_ids=sorted(int(k) for k in opening['maps']),programs=len(opening['programs']),
            note='패키지에 담긴 범위다. 각 맵의 모든 동작을 검증했다는 뜻이 아니다.'),
        areas=areas,resources=resources,
        unclassified_sources=[r['file'] for r in files if r['family']=='unclassified'],source_files=files)
    directory=output_root/'docs/restoration';directory.mkdir(parents=True,exist_ok=True)
    (directory/'source-inventory.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    events=output_root/'assets/data/events';events.mkdir(parents=True,exist_ok=True);manifest=[]
    for area in areas:
        i=area['area'];name=f'DATA/AREA{i:02}.G&M';raw=(game_dir/name).read_bytes();filename=f'area{i:02}.gm'
        (events/filename).write_bytes(raw)
        manifest.append(dict(area=i,file=filename,source=name,sha256=digest(raw),groups=len(area['event_groups']),
            routes=sum(g['routes'] for g in area['event_groups'])))
    (events/'manifest.json').write_text(json.dumps(dict(format='original-gm-xor7f',archives=manifest),indent=2)+'\n')
    print('원본 목록:',result['totals'],'AUTOANI 미해석:',len(resources['autoani']['failures']),flush=True)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    catalogue(a.game_dir,a.output_root)
