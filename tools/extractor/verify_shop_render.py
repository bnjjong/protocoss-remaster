"""실제 잡화점 화면을 원작 상점 그리기 함수의 출력과 픽셀 단위로 비교한다."""
import argparse,base64,json,struct
from pathlib import Path
from PIL import Image
from decode_title import decode_img_frame
from decode_opening import glyph
from shop_trading import ShopReference
from verify_original_map import unpack_game
from verify_original_title_save import objects


def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    ref=ShopReference(exe);folder=output_root/'desktop/build/reports/shop'
    scenes=json.loads((folder/'scenes.json').read_text());results=[]
    palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes();fxp=(game_dir/'GRP/BACK.FXP').read_bytes()
    colors=[tuple(v*4 for v in palette[i*3:i*3+3])+(255,) for i in range(256)];lookup={c:i for i,c in enumerate(colors)}
    (_,code),(_,data)=objects(exe);font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes()
    cached_images={}
    for scene in scenes:
        save=base64.b64decode(scene['save']);ref.prepare(game_dir,save);ref.word(0xeb0e,scene['choice']);ref.byte(0xf1a3,int(scene.get('blink',True)))
        expected=Image.open(folder/(scene['name']+'-field.png')).convert('RGBA')
        def image(name,muted=False):
            key=(name,muted)
            if key in cached_images:return cached_images[key]
            f=decode_img_frame(ref.raw[name])
            if muted:f=f.__class__(f.width,f.height,bytes(fxp[1024+c] for c in f.indices),f.alpha)
            cached_images[key]=f.to_image(palette)
            return cached_images[key]
        def draw(events):
            for e in events:
                kind=e['kind'];x=e.get('x');y=e.get('y')
                if kind=='image':expected.alpha_composite(image(e['image'],e.get('muted',False)),(x,y))
                elif kind=='mask':
                    f=decode_img_frame(ref.raw[e['image']])
                    for py in range(f.height):
                        for px in range(f.width):
                            if f.alpha[py*f.width+px] and 0<=x+px<320 and 0<=y+py<200:
                                index=lookup[expected.getpixel((x+px,y+py))]
                                expected.putpixel((x+px,y+py),colors[fxp[e['table']*256+index]])
                elif kind=='number':
                    if e['right']:x-=len(e['text'])*8
                    for c in e['text']:
                        if not c.isdigit():raise ValueError('원작에서 초기화하지 않는 음수 기호는 화면 비교 대상 밖입니다.')
                        expected.alpha_composite(image('imenu-'+str(int(c)+17)),(x,y));x+=8
                elif kind=='text':
                    left=x
                    for c in e['text']:
                        if c in '/\n':x=left;y+=16;continue
                        im=glyph(c,font,english,code[0x4040:0x4108]);a=im.getchannel('A')
                        ink=Image.new('RGBA',im.size,(0,0,0,255));ink.putalpha(a);expected.alpha_composite(ink,(x+1,y+1))
                        ink=Image.new('RGBA',im.size,colors[e['color']]);ink.putalpha(a);expected.alpha_composite(ink,(x,y));x+=im.width
        if scene['stage']=='HOME':draw(ref.invoke(0x28a68))
        else:
            draw(ref.invoke(0x29bb4))
            sell=scene['choice']==3;rows=ref.catalog(sell);item,price=rows[scene['selected']]
            if scene['stage']=='CATALOG':draw(ref.invoke(0x28f0c,scene['selected'],0,int(not sell)))
            else:
                events=ref.amount_frame(item,price,scene['quantity'],sell,scene['animation'] if scene['stage']=='TRADE' else None)
                draw(events)
        expected.save(folder/(scene['name']+'-native.png'))
        actual=Image.open(folder/(scene['name']+'.png')).convert('RGBA').resize((320,200),Image.Resampling.NEAREST)
        if expected.tobytes()!=actual.tobytes():
            diff=[(x,y) for y in range(200) for x in range(320) if expected.getpixel((x,y))!=actual.getpixel((x,y))]
            raise ValueError(f'잡화점 화면 불일치: {scene["name"]}, {len(diff)}픽셀, 처음 {diff[:8]}')
        results.append(dict(name=scene['name'],pixels=64000))
    report=dict(scenes=results,compared_pixels=len(results)*64000,field_source='앱에서 상점 진입 시 정지한 필드',native_functions=['0x28A68','0x29BB4','0x28F0C','0x29590'])
    (output_root/'docs/shop-render.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('잡화점 화면 일치:',len(results),'장,',report['compared_pixels'],'픽셀')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    a=p.parse_args();verify(a.game_dir,a.output_root)
