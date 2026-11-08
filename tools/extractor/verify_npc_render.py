#!/usr/bin/env python3
"""실제 GPU의 필드 화면을 같은 상태의 원작 VGA 함수 출력과 대조한다."""
import argparse
import json
from pathlib import Path
import struct
from PIL import Image
from decode_map import read_offsets
from decode_opening import glyph
from decode_title import parse_img, decode_img_frame
from verify_opening import scene_reference, OpeningReference
from verify_original_map import unpack_game
from verify_original_title_save import objects
from verify_original_user_sprite import OriginalSpriteRenderer


def verify(game_dir, output_root, executable=None, folder=None, report_name='npc-render-verification.json'):
    exe=executable if executable is not None else unpack_game(game_dir)
    (_,code),(_,data)=objects(exe)
    folder=folder if folder is not None else output_root/'desktop/build/reports/npcs'
    scenes=json.loads((folder/'scenes.json').read_text());report=[]
    offsets=read_offsets(game_dir/'MAP/AREA00.MDA');maps=(game_dir/'MAP/AREA00.MAP').read_bytes()
    so=read_offsets(game_dir/'MAP/AREA00.SDA');soc=(game_dir/'MAP/AREA00.SOC').read_bytes()
    people=(game_dir/'GRP/PEOPLE.SPR').read_bytes();sda=(game_dir/'GRP/PEOPLE.SDA').read_bytes()
    blocks={}
    for number in {a[0] for scene in scenes for a in scene['actors']}:
        start,length=struct.unpack_from('<II',sda,4+number*8);block=people[start:start+length]
        ends=(*struct.unpack_from('<12I',block),length-48)
        blocks[number]=[block[48+a:48+b] for a,b in zip(ends,ends[1:])]
    common=parse_img((game_dir/'GRP/COMMON.IMG').read_bytes());palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes()
    font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes();tables=code[0x4040:0x4108]
    renderer=OriginalSpriteRenderer(exe);font_reference=OpeningReference(code,data,font)
    for scene in scenes:
        number=scene['map'];sx,sy=scene['scroll'];source=maps[offsets[number]:offsets[number+1]];width=source[2]*40
        cropped=bytearray(source[:48]);cropped[2]=1;cropped[3]=1
        for row in range(25):
            for column in range(40):
                _,attr=struct.unpack_from('<HH',source,48+((row+sy//8)*width+column+sx//8)*4)
                cropped.extend(struct.pack('<HH',scene['tiles'][row*40+column],attr))
        block=soc[so[source[32]]:so[source[32]]+70818];pal=block[2:770]
        visible=[]
        for sprite,x,y,frame,mask in scene['actors']:
            x-=sx;y-=sy
            if x+24<=0 or y+24<=0 or x>=320 or y>=200:continue
            if not (0<=x<=296 and 0<=y<=176):raise ValueError(f'비교 장면에 잘린 인물이 있습니다: {scene["name"]} {x,y}')
            visible.append(dict(sprite=sprite,x=x,y=y,frame=frame,mask=mask))
        expected,indices=scene_reference(renderer,bytes(cropped),block,dict(actors=visible),blocks,list(data[0x1588:0x1598]),pal)
        if 'page' in scene:
            remap=block[68770+4*256:68770+5*256]
            for y in range(134,174):
                for x in range(320):
                    index=remap[indices[y*320+x]]
                    expected.putpixel((x,y),tuple(v*4 for v in pal[index*3:index*3+3])+(255,))
            page=scene['page'];portrait=page['portrait']
            if page.get('kind')=='reward':
                decoded=decode_img_frame(common[page['icon']])
                expected.alpha_composite(renderer.render(struct.pack('<HH',decoded.width,decoded.height)+common[page['icon']],palette),(30,134))
            elif portrait>=0:
                decoded=decode_img_frame(common[portrait])
                expected.alpha_composite(renderer.render(struct.pack('<HH',decoded.width,decoded.height)+common[portrait],palette),(14,62))
            text_rows=[(page['text'],14,158,(252,252,252))] if page.get('kind')=='reward' else [(page['speaker'],94 if portrait>=0 else 14,118,(252,252,0)),(page['text'][:scene['visible']],14,139,(252,252,252))]
            for text,x,y,color in text_rows:
                left=x
                for ch in text:
                    if ch=='\n':x=left;y+=16;continue
                    g=glyph(ch,font,english,tables)
                    if len(ch.encode('cp1361'))==2:
                        bits=font_reference.glyph(ch.encode('cp1361'))
                        if g.getchannel('A').tobytes()!=bytes(255 if bits[row*2+col//8] & (128>>(col%8)) else 0 for row in range(16) for col in range(16)):
                            raise ValueError(f'원작 글꼴 불일치: {ch}')
                    if color[2]==0:
                        shadow=Image.new('RGBA',g.size,(0,0,0,255));shadow.putalpha(g.getchannel('A'));expected.alpha_composite(shadow,(x+1,y+1))
                    ink=Image.new('RGBA',g.size,(*color,255));ink.putalpha(g.getchannel('A'));expected.alpha_composite(ink,(x,y));x+=g.width
        expected.save(folder/(scene['name']+'-native.png'))
        actual=Image.open(folder/(scene['name']+'.png')).convert('RGBA').resize((320,200),Image.Resampling.NEAREST)
        different=[(x,y) for y in range(200) for x in range(320) if expected.getpixel((x,y))!=actual.getpixel((x,y))]
        if different:raise ValueError(f'필드 화면 불일치: {scene["name"]}, {len(different)}픽셀, 처음 {different[:5]}')
        report.append(dict(name=scene['name'],pixels=64000))
    result=dict(compared_pixels=sum(c['pixels'] for c in report),scenes=report)
    (output_root/'docs'/report_name).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('필드 원작 화면 일치:',len(report),'장,',result['compared_pixels'],'픽셀')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify(a.game_dir,a.output_root)
