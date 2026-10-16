"""Generate only public Flip Seven head models; preserve table and inventory card art.

Run after generate_assets.py when rebuilding all art. This small generator needs
only Python's standard library and does not rewrite textures or other game assets.
"""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
MODELS=ROOT/'src/main/resources/assets/tabletop/models/item'
PUBLIC_MODEL=121101
def write(name,payload):
    (MODELS/(name+'.json')).write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
    # Both physical faces show the public card, including the camera-facing north
    # face used by ItemDisplay FIXED. Private/inventory/table card backs stay intact.
    write('_public_card',{'parent':'tabletop:item/_card','textures':{'back':'#front'}})
    cards=['number_%d'%v for v in range(13)]+['plus_%d'%v for v in (2,4,6,8,10)]+['times_two','freeze','flip_three','second_chance']
    for name in cards:
        path=MODELS/(name+'.json');model=json.loads(path.read_text('utf-8'))
        assert model['parent']=='tabletop:item/_card'
        write('_public_'+name,{'parent':'tabletop:item/_public_card','textures':{'front':model['textures']['front']}})
        overrides=[o for o in model.get('overrides',[]) if o.get('predicate',{}).get('custom_model_data')!=PUBLIC_MODEL]
        overrides.append({'predicate':{'custom_model_data':PUBLIC_MODEL},'model':'tabletop:item/_public_'+name})
        model['overrides']=overrides;write(name,model)
    print('PUBLIC_HEAD_MODELS_GENERATED %d cards, both faces public'%len(cards))
if __name__=='__main__':main()
