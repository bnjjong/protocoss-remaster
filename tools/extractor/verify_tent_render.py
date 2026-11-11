"""텐트 전환의 실제 화면을 원작 휴식 그림·실행한 DAC 변환표와 비교한다."""
import argparse,hashlib,json,struct
from pathlib import Path
from PIL import Image
from tent_rest import TentReference,palette_levels
from decode_title import decode_img_frame
from verify_original_user_sprite import OriginalSpriteRenderer
from verify_original_map import unpack_game


def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    ref=TentReference(exe);ref.prepare(game_dir,(game_dir/'DATA/START.DAT').read_bytes())
    record=json.loads((output_root/'docs/tent-native.json').read_text());levels=palette_levels(ref)
    assert levels==record['dac_levels']
    raw=ref.raw['imenu-289'];frame=decode_img_frame(raw);palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes()
    source=OriginalSpriteRenderer(exe).render(struct.pack('<HH',frame.width,frame.height)+raw,palette)
    native=Image.new('RGBA',(320,200),(0,0,0,255));native.alpha_composite(source,(88,48))
    folder=output_root/'desktop/build/reports/tent'
    def read(name):return Image.open(folder/(name+'.png')).convert('RGBA').resize((320,200),Image.Resampling.NEAREST)
    bases=dict(menu=read('menu'),field=read('field'),sleep=native)
    assert read('returned').tobytes()==bases['field'].tobytes(), '휴식 전후 필드가 다릅니다.'
    assert Image.open(output_root/'assets/tent/rest.png').tobytes()==native.tobytes()
    scenes=json.loads((folder/'scenes.json').read_text());results=[];cache={}
    for row in scenes:
        key=(row['source'],row['level'])
        if key not in cache:
            before=bases[row['source']].tobytes();after=bytearray(before);table=levels[row['level']]
            for i in range(0,len(before),4):
                for j in range(3):
                    assert before[i+j]%4==0
                    after[i+j]=table[before[i+j]//4]*4
            cache[key]=bytes(after)
        actual=read(row['name']).tobytes()
        expected=cache[key]
        if actual!=expected:
            diffs=[i//4 for i in range(0,len(actual),4) if actual[i:i+4]!=expected[i:i+4]]
            raise ValueError(f'텐트 화면 불일치: {row["name"]} {row["phase"]} 강도 {row["level"]}: {len(diffs)}픽셀, 처음 {diffs[:5]}')
        results.append(dict(name=row['name'],phase=row['phase'],level=row['level'],source=row['source'],pixels=64000))
    audio=json.loads((output_root/'assets/tent/audio.json').read_text())
    assert hashlib.sha256((output_root/'assets/tent/music.ogg').read_bytes()).hexdigest()==audio['ogg_sha256']
    assert audio['rms']>0 and audio['source']=='S&W/SLEEP.MFA'
    result=dict(scenes=results,compared_pixels=len(results)*64000,dac_entries=65*64,
        source_basis='휴식: 원작 그림 함수, 메뉴: 별도 메뉴 원작 비교, 필드: 실행 전후 동일 화면; 전환: 원작 DAC 함수 실행 표',
        audio_frames=audio['sample_frames'],audio_duration=audio['duration_seconds'])
    (output_root/'docs/tent-render.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('텐트 화면 일치:',len(results),'장,',result['compared_pixels'],'픽셀',flush=True)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
