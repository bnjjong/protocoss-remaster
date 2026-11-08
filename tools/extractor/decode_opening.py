#!/usr/bin/env python3
"""원작의 두 시작 연출과 대화·필드 인물·조합형 글꼴을 추출한다."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from PIL import Image
from decode_map import read_offsets, decode_tile_pixels
from decode_title import parse_img, decode_img_frame
from verify_original_map import unpack_game
from verify_original_title_save import objects
from verify_original_user_sprite import OriginalSpriteRenderer
from verify_opening import OpeningReference, scene_reference


def animation(raw):
    area, submap, sx, sy, count = struct.unpack_from('<5I', raw)
    sprites = list(struct.unpack_from(f'<{count}I', raw, 20))
    p = 20 + count * 4
    commands = []
    actor_count = 0
    while p < len(raw):
        offset = p
        if raw[p] != 47:
            raise ValueError(f'AUTOANI 명령 구분자 오류: {p}')
        op = chr(raw[p + 1]); p += 2
        if op == 'S':
            actors = []
            while raw[p] != 47:
                sprite, x, y, frame = struct.unpack_from('<BIIB', raw, p); p += 10
                actors.append(dict(sprite=sprite, x=x * 8, y=y * 8, frame=frame))
            actor_count = len(actors); value = actors
        elif op == 'M':
            value = [list(raw[p+i*6:p+(i+1)*6]) for i in range(actor_count)]
            p += actor_count * 6
        elif op == 'L':
            value = list(raw[p:p+2]); p += 2
        elif op in ('C', 'F'):
            value = raw[p]; p += 1
        elif op in ('G', 'E'):
            value = None
        else:
            raise ValueError(f'지원하지 않는 시작 연출 명령: {op}')
        commands.append(dict(offset=offset, op=op, value=value))
        if op == 'E':
            if p != len(raw): raise ValueError('연출 종료 뒤 데이터가 남았습니다.')
    return dict(area=area, map=submap, scroll=[sx*8, sy*8], sprites=sprites, commands=commands)


def dialogue(raw):
    text = raw.decode('cp1361')
    pages = []
    speaker, portrait, lines = '', -1, ['']
    # /D 뒤에는 마지막 줄을 남기고 한 줄 위로 올리는 원작 동작을 보존한다.
    tokens = re.split(r'(/\[[^]]+\]|/N|/D|/E)', text)
    for token in tokens:
        if not token: continue
        if token.startswith('/['):
            tag = re.fullmatch(r'/\[(\d+)(?:S(\d+))?([^]]*)\]', token)
            if tag is None: raise ValueError(token)
            speaker, portrait = tag[3], int(tag[2]) if tag[2] else -1
            lines = ['']
        elif token in ('/D', '/E'):
            pages.append(dict(speaker=speaker, portrait=portrait, text='\n'.join(lines[-2:])))
            lines = [lines[-1], '']
        elif token == '/N': lines.append('')
        else: lines[-1] += token
    return pages


def glyph(char, font, english, tables):
    encoded = char.encode('cp1361')
    if len(encoded) == 1:
        bits = english[encoded[0]*16:(encoded[0]+1)*16]; width = 8
    else:
        a,b = encoded
        cho, jung, jong = tables[(a & 124) >> 2], tables[31+((a&3)*8+(b>>5))], tables[62+(b&31)]
        vcho = tables[(177 if jong else 155)+jung]  # 0x40f1 또는 0x40db
        vjung = tables[(113 if jong else 93)+cho]       # 0x40b1 또는 0x409d
        vjong = tables[133+jung]                        # 0x40c5
        bits = bytearray(32)
        for index, start in [(cho, vcho*640+cho*32), (jung, 5120+vjung*704+jung*32), (jong, 7936+vjong*896+jong*32)]:
            if index:
                chunk = font[start:start+32]
                if len(chunk)!=32: raise ValueError('조합 글꼴 범위 오류')
                for n,value in enumerate(chunk): bits[n] |= value
        width = 16
    image = Image.new('RGBA', (width,16))
    for y in range(16):
        row = int.from_bytes(bits[y*(width//8):(y+1)*(width//8)], 'big')
        for x in range(width):
            if row & (1 << (width-1-x)): image.putpixel((x,y),(255,255,255,255))
    return image


def extract(game_dir, output_root, executable=None):
    directory = output_root/'assets/opening'; directory.mkdir(parents=True,exist_ok=True)
    executable = executable if executable is not None else unpack_game(game_dir)
    from original_field import OriginalFieldMachine
    (_,code),(_,data) = objects(executable)
    renderer = OriginalSpriteRenderer(executable)
    palette = (game_dir/'DATA/PROTOCOS.PAL').read_bytes()
    sources = {}
    def read(name):
        raw = (game_dir/name).read_bytes(); sources[name]=hashlib.sha256(raw).hexdigest(); return raw
    programs = [animation(read(f'DATA/AUTOANI.{i:03}')) for i in (0,1,3,6,4,5)]
    # 0x10A54의 mode=0은 현재 맵·주민을 유지하고 화면 전환 없이 실행한다.
    programs[5]['continues_field']=True
    for command in programs[3]['commands']:
        if command['op']=='C':command['value']+=100
    for command in programs[4]['commands']:
        if command['op']=='C':command['value']+=200
    for command in programs[5]['commands']:
        if command['op']=='C':command['value']+=300
    script = read('DATA/AREA00.G&M'); count=struct.unpack_from('<H',script,4)[0]
    script = bytes(v^127 for v in script[6+count*2:])
    dialogues = {}
    npc_dialogues = {}
    event_machine=OriginalFieldMachine(executable)
    gate_trace=event_machine.trace_event(read('DATA/AREA00.G&M'),3,9,{8:0},capture_dialogue=True)
    building_dialogues={'locked':[page for e in gate_trace['events'] for page in dialogue(e['text'].encode('cp1361'))]}
    from sword_story import StoryReference
    weapon=read('DATA/WEAPON.LST');weapon_name=weapon[:21].split(b'\0')[0].decode('cp1361')
    item_icon=struct.unpack_from('<H',weapon,21)[0]
    notice_format=bytes(event_machine.cpu.mem_read(0x702a0,100)).split(b'\0')[0].decode('cp1361')
    shop={}
    for stage in (0,1,3,4):
        source=bytearray(read('DATA/START.DAT'));struct.pack_into('<H',source,2358,stage)
        native=StoryReference(executable);native.load_save(source)
        events=native.event(game_dir,4,2);pages=[]
        for event in events:
            if event['kind']=='dialogue':pages+=dialogue(event['text'].encode('cp1361'))
            elif event['kind']=='item':
                if event!={'kind':'item','id':0,'count':1,'category':1}:raise ValueError('검 수리 보상 형식이 다릅니다.')
                pages.append(dict(kind='reward',speaker='',portrait=-1,text=notice_format%(weapon_name,1),icon=item_icon))
        shop[str(stage)]=pages
    sword_story=dict(shop=shop,weapon_name=weapon_name,icon=item_icon,join=dict(program=5,scroll=programs[5]['scroll'],music=24,last_dialogue=17))
    for number in (10,11,12,13,14,18,19):
        variants={}
        for stage in (0,3,4):
            trace=event_machine.trace_event(read('DATA/AREA00.G&M'),3,number,{8:stage},capture_dialogue=True)
            if any(e['kind']!='dialogue' for e in trace['events']):raise ValueError('주민 대화에 미지원 명령이 있습니다.')
            variants[str(stage)]=[page for e in trace['events'] for page in dialogue(e['text'].encode('cp1361'))]
        npc_dialogues[str(number)]=variants
    group_offsets=struct.unpack_from(f'<{count}H',read('DATA/AREA00.G&M'),6)
    for group,numbers in [(0,(2,3,14,17)),(1,(4,5,6,7)),(2,(3,)),(3,(16,17))]:
        p=group_offsets[group];entries={}
        while script[p]==0xf0:
            argument,destination=struct.unpack_from('<HH',script,p+3)
            entries[argument]=destination;p+=7
        for number in numbers:
            if number not in entries:
                dialogues[str(group*100+number)]=[]
                continue
            start=entries[number]
            if script[start]!=3:raise ValueError('대화 명령이 다릅니다.')
            end=script.index(b'\x00',start)
            dialogues[str(group*100+number)]=dialogue(script[start+1:end])
    people = read('GRP/PEOPLE.SPR'); sda = read('GRP/PEOPLE.SDA')
    sprite_blocks={}
    mo=read_offsets(game_dir/'MAP/AREA00.MDA');maps=read('MAP/AREA00.MAP')
    npc_sprites=set()
    for number in range(13):
        sub=maps[mo[number]:mo[number+1]];start=48+sub[2]*40*sub[3]*25*4+sub[33]*8
        npc_sprites.update(struct.unpack_from('<I',sub,start+i*54)[0] for i in range(sub[34]))
    sprite_ids = sorted({0,12,*npc_sprites,*[i for program in programs for i in program['sprites']]})
    atlas = Image.new('RGBA',(12*24,len(sprite_ids)*24)); verified_pixels=0
    for row,number in enumerate(sprite_ids):
        start,length=struct.unpack_from('<II',sda,4+number*8); block=people[start:start+length]
        offsets=(*struct.unpack_from('<12I',block),length-48)
        sprite_blocks[number]=[]
        for frame in range(12):
            raw=block[48+offsets[frame]:48+offsets[frame+1]]
            sprite_blocks[number].append(raw)
            decoded=decode_img_frame(raw)
            if (decoded.width,decoded.height)!=(24,24): raise ValueError('필드 인물 크기가 다릅니다.')
            image=decoded.to_image(palette)
            native=renderer.render(struct.pack('<HH',24,24)+raw,palette)
            if image.tobytes()!=native.tobytes(): raise ValueError('원작 필드 인물 픽셀이 다릅니다.')
            atlas.paste(image,(frame*24,row*24));verified_pixels+=576
    atlas.save(directory/'people.png')
    common=parse_img(read('GRP/COMMON.IMG'))
    item=decode_img_frame(common[item_icon])
    icon_image=item.to_image(palette)
    if icon_image.tobytes()!=renderer.render(struct.pack('<HH',item.width,item.height)+common[item_icon],palette).tobytes():raise ValueError('검 보상 아이콘 픽셀 불일치')
    icon_image.save(directory/'sword-reward.png')
    for number in (0,1,17):
        f=decode_img_frame(common[number]); im=f.to_image(palette)
        if im.tobytes()!=renderer.render(struct.pack('<HH',f.width,f.height)+common[number],palette).tobytes():raise ValueError('초상화 픽셀 불일치')
        im.save(directory/f'portrait-{number}.png')
    mo=read_offsets(game_dir/'MAP/AREA00.MDA');so=read_offsets(game_dir/'MAP/AREA00.SDA')
    maps=read('MAP/AREA00.MAP');soc=read('MAP/AREA00.SOC');map_info={}
    for number in range(13):
        sub=maps[mo[number]:mo[number+1]];w,h=sub[2]*40,sub[3]*25; block=soc[so[sub[32]]:so[sub[32]]+70818]
        pal=block[2:770]; cells=list(struct.iter_unpack('<HH',sub[48:48+w*h*4])); frames=[]
        for i in range(1000):
            im=Image.frombytes('P',(8,8),decode_tile_pixels(block[770+i*64:834+i*64]));im.putpalette([v*4 for v in pal]);frames.append(im.convert('RGBA'))
        tiles=Image.new('RGBA',(320,200)); overlays=Image.new('RGBA',(320,200)); fronts=Image.new('RGBA',(320,200))
        lut=struct.unpack_from('<1000H',block,64770)
        for i,im in enumerate(frames):
            tiles.paste(im,(i%40*8,i//40*8))
            over=frames[lut[i]&4095].copy()
            # 겹침 타일의 팔레트 255만 투명하다.
            ids=decode_tile_pixels(block[770+(lut[i]&4095)*64:834+(lut[i]&4095)*64])
            over.putalpha(Image.frombytes('L',(8,8),bytes(0 if c==255 else 255 for c in ids)))
            overlays.paste(over,(i%40*8,i//40*8))
            chosen=(lut[i]&4095) or i
            ids=decode_tile_pixels(block[770+chosen*64:834+chosen*64])
            front=frames[chosen].copy();front.putalpha(Image.frombytes('L',(8,8),bytes(0 if c==255 else 255 for c in ids)))
            fronts.paste(front,(i%40*8,i//40*8))
        tiles.save(directory/f'tiles-{number}.png');overlays.save(directory/f'overlays-{number}.png');fronts.save(directory/f'fronts-{number}.png')
        remap=block[68770+4*256:68770+5*256]
        def remapped(indices,alpha=None,size=(8,8)):
            image=Image.frombytes('P',size,bytes(remap[c] for c in indices));image.putpalette([v*4 for v in pal])
            image=image.convert('RGBA')
            if alpha is not None:image.putalpha(Image.frombytes('L',size,alpha))
            return image
        dark_tiles=Image.new('RGBA',(320,200))
        for tile in range(1000):
            ids=decode_tile_pixels(block[770+tile*64:834+tile*64])
            dark_tiles.paste(remapped(ids),(tile%40*8,tile//40*8))
        dark_tiles.save(directory/f'tiles-dialogue-{number}.png')
        dark_people=Image.new('RGBA',atlas.size)
        for row,sprite in enumerate(sprite_ids):
            for frame,raw in enumerate(sprite_blocks[sprite]):
                decoded=decode_img_frame(raw)
                dark_people.paste(remapped(decoded.indices,decoded.alpha,(24,24)),(frame*24,row*24))
        dark_people.save(directory/f'people-dialogue-{number}.png')
        for name,use_original in [('overlays',False),('fronts',True)]:
            image=Image.new('RGBA',(320,200))
            for tile in range(1000):
                chosen=(lut[tile]&4095) or (tile if use_original else 0)
                ids=decode_tile_pixels(block[770+chosen*64:834+chosen*64])
                image.paste(remapped(ids,bytes(0 if c==255 else 255 for c in ids)),(tile%40*8,tile//40*8))
            image.save(directory/f'{name}-dialogue-{number}.png')

        dark=Image.new('RGBA',(w*8,h*8))
        remap=block[68770+4*256:68770+5*256]
        for i,(tid,_) in enumerate(cells):
            ids=decode_tile_pixels(block[770+(tid&4095)*64:834+(tid&4095)*64])
            im=Image.frombytes('P',(8,8),bytes(remap[c] for c in ids)); im.putpalette([v*4 for v in pal])
            dark.paste(im.convert('RGBA'),(i%w*8,i//w*8))
        dark.save(directory/f'dialogue-background-{number}.png')
        tail=48+w*h*4
        animations=[list(struct.unpack_from('<4H',sub,tail+i*8)) for i in range(sub[33])]
        tail+=sub[33]*8
        npcs=[]
        for i in range(sub[34]):
            values=struct.unpack_from('<12I6B',sub,tail+i*54)
            npcs.append(dict(sprite=values[0],x=values[1],y=values[2],tile_x=values[5],tile_y=values[6],
                bounds=list(values[7:11]),event=values[11],direction=values[12],frame=values[13],
                kind=values[14],speed=values[15],mode=values[16],mask=values[17]))
        map_info[str(number)]=dict(width=w,height=h,cells=[list(c) for c in cells],animations=animations,
                                  next_tiles=list(struct.unpack_from('<1000H',block,66770)),npcs=npcs)
    all_pages=[p for pages in dialogues.values() for p in pages]+[p for variants in npc_dialogues.values() for pages in variants.values() for p in pages]+building_dialogues['locked']+[p for pages in shop.values() for p in pages]
    chars=sorted((set(''.join(p['speaker']+p['text'] for p in all_pages)) | set('엘가임 마을벨슨그의 오두막')) - {'\n'})
    font=read('HANGUL.FNT');english=read('ENGLISH.FNT');tables=code[0x4040:0x4108]
    reference=OpeningReference(code,data,font)
    checkpoints=reference.motions(programs[1])
    field_checkpoints={str(i):OriginalFieldMachine(executable).animation_checkpoints(programs[i]) for i in (2,3,4,5)}
    sword_story['join']['hero']=[field_checkpoints['5'][-1]['actors'][0][key] for key in ('x','y','frame')]
    glyphs=Image.new('RGBA',(256,((len(chars)+15)//16)*16)); widths={}
    for i,c in enumerate(chars):
        im=glyph(c,font,english,tables)
        if len(c.encode('cp1361'))==2:
            bits=reference.glyph(c.encode('cp1361'))
            expected=bytes(255 if bits[y*2+x//8] & (128>>(x%8)) else 0 for y in range(16) for x in range(16))
            if im.getchannel('A').tobytes()!=expected:raise ValueError(f'원작 조합 글꼴과 다릅니다: {c}')
        glyphs.paste(im,(i%16*16,i//16*16));widths[c]=im.width
    glyphs.save(directory/'font.png')
    sub=maps[mo[1]:mo[2]]; block=soc[so[1]:so[1]+70818];pal=block[2:770]
    refs=[]
    jobs=[(1,c) for c in checkpoints]+[(i,c) for i in (2,3,4,5) for c in field_checkpoints[str(i)] if c['op']=='C' and dialogues.get(str(c['dialogue']))]
    for program_index,checkpoint in jobs:
        number=programs[program_index]['map'];sub=maps[mo[number]:mo[number+1]]
        start=so[sub[32]];block=soc[start:start+70818];pal=block[2:770]
        if program_index==5:
            sx,sy=programs[program_index]['scroll'];w=sub[2]*40
            cropped=bytearray(sub[:48]);cropped[2]=1;cropped[3]=1
            for y in range(sy//8,sy//8+25):cropped.extend(sub[48+(y*w+sx//8)*4:48+(y*w+sx//8+40)*4])
            sub=bytes(cropped)
            checkpoint=dict(checkpoint,actors=[dict(a,x=a['x']-sx,y=a['y']-sy) for a in checkpoint['actors'] if -24<a['x']-sx<320 and -24<a['y']-sy<200])
        im,indices=scene_reference(renderer,sub,block,checkpoint,
            [sprite_blocks[i] for i in programs[program_index]['sprites']],list(data[0x1588:0x1598]),pal)
        remap=block[68770+4*256:68770+5*256]
        for y in range(134,174):
            for x in range(320):
                index=remap[indices[y*320+x]];im.putpixel((x,y),tuple(v*4 for v in pal[index*3:index*3+3])+(255,))
        page=dialogues[str(checkpoint['dialogue'])][0]
        if page['portrait']>=0:
            portrait=decode_img_frame(common[page['portrait']]).to_image(palette)
            im.alpha_composite(portrait,(14,62))
        for value,x,y,color in [(page['speaker'],94 if page['portrait']>=0 else 14,118,(252,252,0,255)),(page['text'],14,139,(252,252,252,255))]:
            left=x
            for ch in value:
                if ch=='\n':x=left;y+=16;continue
                g=glyph(ch,font,english,tables);g.putdata([color[:3]+(p[3],) for p in g.get_flattened_data()])
                if color[2]==0:
                    shadow=g.copy();shadow.putdata([(0,0,0,p[3]) for p in g.get_flattened_data()]);im.alpha_composite(shadow,(x+1,y+1))
                im.alpha_composite(g,(x,y));x+=g.width
        name=f"reference-{checkpoint['dialogue']}.png";im.save(directory/name);refs.append(name)
    for number in (4,12):
        sub=maps[mo[number]:mo[number+1]];width=sub[2]*40
        cropped=bytearray(sub[:48]);cropped[2]=1;cropped[3]=1
        for row in range(25):cropped.extend(sub[48+row*width*4:48+(row*width+40)*4])
        start=so[sub[32]];block=soc[start:start+70818]
        checkpoint=dict(actors=[dict(sprite=0,x=72,y=8,frame=8,mask=0)])
        image,_=scene_reference(renderer,bytes(cropped),block,checkpoint,[sprite_blocks[0]],list(data[0x1588:0x1598]),block[2:770])
        image.save(directory/f'reference-town-{number}.png')
    for command in programs[4]['commands']:
        if command['op']=='C' and command['value']==204:command['op']='V';command['value']='encounter'
    result=dict(programs=programs,dialogues=dialogues,npc_dialogues=npc_dialogues,building_dialogues=building_dialogues,sword_story=sword_story,maps=map_info,sprites=sprite_ids,
                frame_map=list(data[0x1588:0x1598]),motion_frame_bases=[{0x13ca:0,0x13e7:4,0x140e:8,0x1435:12,0x145a:-1}[v] for v in struct.unpack_from('<12I',code,0x1368)],font=dict(characters=''.join(chars),widths=widths),
                sources=sources,native_sprite_pixels=verified_pixels,native_checkpoints=checkpoints,field_native_checkpoints=field_checkpoints,references=refs)
    (directory/'opening.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print('시작 연출 추출:',len(chars),'글자,',verified_pixels,'필드 인물 픽셀 검증')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--game-dir',type=Path,required=True)
    parser.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=parser.parse_args();extract(a.game_dir,a.output_root)
