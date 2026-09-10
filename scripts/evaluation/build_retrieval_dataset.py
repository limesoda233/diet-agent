"""Rebuild the frozen benchmark from seed SQL and independently authored judgments.

This script never calls a retriever or model. Recipe details are synthetic fixtures.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "src/test/resources/evaluation/retrieval"
SLOTS = ["mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"]
RECIPE_NAMES = [
    "番茄鸡蛋面","清汤馄饨","鸡胸肉轻食碗","麻辣香锅","土豆炖牛肉",
    "燕麦牛奶香蕉杯","小米南瓜粥","鲜虾蔬菜粥","全麦鸡蛋三明治","牛肉蔬菜沙拉",
    "清蒸鲈鱼套餐","香菇鸡肉饭","番茄牛腩饭","黑椒牛肉意面","日式鸡肉荞麦面",
    "韩式石锅拌饭","咖喱鸡肉饭","云南过桥米线","粤式叉烧饭","新疆鸡肉抓饭",
    "蒜香虾仁西兰花","豆腐菌菇汤","麻婆豆腐盖饭","东北酸菜炖粉条","水果酸奶杯",
    "私房葱油拌面","家常青椒肉丝饭","低油版宫保鸡丁","空气炸锅鸡翅","蒜蓉粉丝蒸虾",
    "冬瓜排骨汤","紫菜虾皮蛋花汤","韭菜鸡蛋盒子","自制牛肉卷饼","金枪鱼饭团",
    "照烧鸡腿便当","香煎三文鱼","番茄豆腐煲","凉拌鸡丝","香菇青菜面",
    "红豆燕麦粥","鸡蛋蔬菜饼","西红柿牛肉汤","酸辣土豆丝盖饭","自制蔬菜火锅",
]

# Major ingredients only; these are deliberately explicit fixture assumptions.
RECIPES = [
    ("番茄,鸡蛋,面条", "热汤面，番茄酸香，单人一碗", "炒番茄鸡蛋，加水煮面"),
    ("猪肉,小麦面皮", "热汤馄饨，猪肉馅，汤水较多", "包猪肉馄饨，煮熟配清汤"),
    ("鸡胸肉,米饭,西兰花", "鸡肉搭配米饭和蔬菜的一人份餐碗", "煎熟鸡胸肉，搭配米饭和焯西兰花"),
    ("牛肉,虾,土豆,花生,辣椒", "麻辣干锅，多种荤素混合，适合分享", "将食材做熟后与辣椒、花生炒拌"),
    ("土豆,牛肉,辣椒", "土豆牛肉炖菜，热食，微辣", "牛肉炖软后加入土豆和辣椒"),
    ("燕麦,牛奶,香蕉", "冷食早餐杯，甜味，可以携带", "燕麦浸泡牛奶后加香蕉"),
    ("小米,南瓜", "温热粥，南瓜自然甜味", "小米和南瓜加水煮粥"),
    ("虾,米饭,青菜", "温热咸粥，虾仁和青菜搭配", "米煮粥后加入虾仁、青菜煮熟"),
    ("全麦面包,鸡蛋,生菜", "便携三明治，适合早餐", "熟鸡蛋和生菜夹入全麦面包"),
    ("牛肉,生菜,番茄", "冷食蔬菜沙拉，搭配熟牛肉", "牛肉煎熟切片，与蔬菜拌匀"),
    ("鲈鱼,米饭,青菜", "热食鱼肉套餐，清蒸做法", "清蒸鲈鱼，搭配米饭与青菜"),
    ("香菇,鸡肉,米饭", "温热盖饭，鸡肉菌菇搭配，方便打包", "香菇鸡肉烧熟后盖在米饭上"),
    ("番茄,牛肉,米饭", "番茄牛腩热盖饭，酸香", "番茄牛腩炖熟，搭配米饭"),
    ("牛肉,青椒,黑胡椒,意大利面", "黑椒牛肉热意面，椒香", "牛肉青椒炒熟，加入煮熟意面与黑胡椒拌匀"),
    ("鸡肉,荞麦面,海苔", "鸡肉荞麦热汤面，咸鲜", "鸡肉充分煮熟，加入荞麦面煮熟，配海苔"),
    ("牛肉,鸡蛋,米饭,青菜,辣椒", "石锅热拌饭，荤素混合微辣", "肉蛋蔬菜做熟，与米饭辣酱拌匀"),
    ("鸡肉,土豆,米饭,咖喱", "热咖喱饭，味道浓郁，适合一人食", "鸡肉土豆煮咖喱，配米饭"),
    ("鸡肉,米线,青菜", "热汤米线，汤水充足", "米线、肉片和蔬菜在汤中充分煮熟"),
    ("猪肉,米饭", "甜咸酱香叉烧饭，可打包", "猪肉烤制成叉烧，切片配米饭"),
    ("鸡肉,胡萝卜,米饭", "鸡肉抓饭，热食，适合分享", "鸡肉胡萝卜和米饭焖熟"),
    ("虾,西兰花,大蒜", "蒜香虾仁配蔬菜，热食家常菜", "虾仁与西兰花焯熟后加蒜炒匀"),
    ("豆腐,香菇", "豆腐菌菇热汤，无肉配方", "豆腐香菇加水煮汤"),
    ("豆腐,猪肉,米饭,辣椒", "麻辣豆腐盖饭，配方包含肉末", "肉末豆腐加辣椒烧熟后盖饭"),
    ("酸菜,粉条,猪肉", "酸菜炖粉条，热炖菜，配方包含猪肉", "酸菜、猪肉和粉条炖熟"),
    ("酸奶,苹果,草莓", "冷食水果酸奶杯，酸甜，便携", "水果切块拌入酸奶"),
    ("面条,葱", "葱油干拌面，热食，少餐具", "葱熬油，拌入煮熟的面条"),
    ("青椒,猪肉,米饭", "青椒肉丝盖饭，微辣，可备餐", "青椒肉丝炒熟，配米饭"),
    ("鸡肉,花生,黄瓜,辣椒", "宫保鸡丁，酸甜微辣，配方含花生", "鸡丁黄瓜炒熟，加花生和调味汁"),
    ("鸡翅,大蒜", "蒜香烤鸡翅，适合分享", "鸡翅腌制后空气炸锅烤熟"),
    ("虾,粉丝,大蒜", "热蒸虾，蒜香，搭配粉丝", "虾与粉丝加蒜蓉蒸熟"),
    ("冬瓜,猪排骨", "冬瓜排骨热汤，适合多人分享", "排骨炖熟后加冬瓜"),
    ("紫菜,虾皮,鸡蛋", "热蛋花汤，做法较快，配方含虾皮", "水煮紫菜虾皮，加入蛋液煮熟"),
    ("韭菜,鸡蛋,小麦面皮", "韭菜鸡蛋馅面食，便携", "面皮包韭菜熟鸡蛋后煎熟"),
    ("牛肉,小麦面皮,生菜", "便携牛肉卷饼，适合通勤", "熟牛肉生菜卷入饼皮"),
    ("金枪鱼,米饭,海苔", "金枪鱼饭团，便携，少餐具", "熟金枪鱼包入米饭并裹上海苔"),
    ("鸡腿,米饭,西兰花", "酱香鸡腿便当，适合备餐", "鸡腿煎熟刷酱，配米饭西兰花"),
    ("三文鱼,黄油", "香煎鱼肉，热食，配方包含黄油", "三文鱼用黄油煎熟"),
    ("番茄,豆腐", "番茄豆腐热煲，无肉配方", "番茄炒软后加入豆腐炖熟"),
    ("鸡肉,黄瓜,大蒜,辣椒", "凉拌鸡丝，冷食，蒜香微辣", "鸡肉煮熟撕丝，与黄瓜调味拌匀"),
    ("香菇,青菜,面条", "菌菇青菜热汤面，无肉配方", "香菇青菜煮汤，加入面条煮熟"),
    ("红豆,燕麦", "红豆燕麦热粥，无奶配方", "红豆煮软，加入燕麦煮粥"),
    ("鸡蛋,青菜,面粉", "便携鸡蛋蔬菜饼，适合早餐", "鸡蛋蔬菜和面粉调糊后煎熟"),
    ("番茄,牛肉", "番茄牛肉热汤，适合分享", "牛肉炖软后加入番茄"),
    ("土豆,辣椒,米饭", "酸辣土豆丝盖饭，无肉配方", "土豆丝加辣椒炒熟，配米饭"),
    ("白菜,香菇,豆腐,辣椒", "蔬菜豆腐火锅，无肉配方，适合多人", "蔬菜豆腐在微辣汤底中煮熟"),
]

def seeds():
    base = ROOT / "src/main/resources/db/diet_db.sql"
    extra = ROOT / "src/main/resources/db/add_40_meal_items.sql"
    result = []
    for row in re.findall(r"INSERT INTO `meal_item` VALUES \((.*?)\);", base.read_text(encoding="utf-8")):
        tokens = [a.replace('\\"', '"') if a else b for a, b in re.findall(r"'((?:\\.|[^'])*)'|\b(NULL|\d+)\b", row)]
        result.append(dict(id=int(tokens[0]), sourceMode=tokens[1], ownerUserId=None if tokens[2]=='NULL' else int(tokens[2]), name=tokens[3], slots=dict(zip(SLOTS,[json.loads(x) for x in tokens[4:11]])), updatedAt=tokens[12].replace(' ','T'), source="diet_db.sql"))
    pattern = r"\('(PUBLIC|PERSONAL)', (NULL|@personal_owner_user_id), '([^']+)',\s*((?:JSON_ARRAY\([^)]*\),\s*){6}JSON_ARRAY\([^)]*\))"
    for mode, owner, name, arrays in re.findall(pattern, extra.read_text(encoding="utf-8")):
        values=[re.findall(r"'([^']*)'",x) for x in re.findall(r"JSON_ARRAY\(([^)]*)\)",arrays)]
        result.append(dict(id=len(result)+1,sourceMode=mode,ownerUserId=None if owner=='NULL' else 1,name=name,slots=dict(zip(SLOTS,values)),updatedAt="2026-09-01T00:00:00",source="add_40_meal_items.sql"))
    assert len(result)==45
    assert len(RECIPES)==45
    recipes_by_name = dict(zip(RECIPE_NAMES, RECIPES, strict=True))
    assert [m['name'] for m in result] == RECIPE_NAMES, 'Seed order changed: explicitly version IDs and judgments before rebuilding.'
    for meal in result:
        ingredients,description,preparation = recipes_by_name[meal['name']]
        meal.update(ingredients=ingredients.split(','),description=description,preparation=preparation,provenance=dict(seedFile=meal.pop('source'),recipeStatus="synthetic_recipe_assumption",annotationAuthor="assistant"))
    return result

def build():
    meals=seeds()
    groups=json.loads((Path(__file__).with_name("query-groups.json")).read_text(encoding="utf-8"))
    queries=[]
    for index,g in enumerate(groups):
        allowed=[m for m in meals if m['sourceMode']==g.get('sourceMode','PUBLIC') and (m['ownerUserId'] is None or m['ownerUserId']==g.get('userId',1))]
        preferred=g.get('preferred',[]); acceptable=g.get('acceptable',[])
        assert not set(preferred)&set(acceptable)
        assert set(preferred+acceptable)<=set(m['id'] for m in allowed),g['groupId']
        for variant,utterance in enumerate(g['utterances']):
            queries.append(dict(id=f"{g['groupId']}-{variant+1}",groupId=g['groupId'],split=g['split'],slice=g['slice'],utterance=utterance,context=g.get('context',[]),sourceMode=g.get('sourceMode','PUBLIC'),userId=g.get('userId',1),slots={k:g.get('slots',{}).get(k,[]) for k in SLOTS},excludeMealIds=g.get('excludeMealIds',[]),excludedIngredients=g.get('excludedIngredients',[]),requiredIngredients=g.get('requiredIngredients',[]),relevanceGrades={str(m['id']):2 if m['id'] in preferred else 1 if m['id'] in acceptable else 0 for m in allowed},rationale=g['rationale'],annotationStatus=g.get('annotationStatus','draft')))
    DEST.mkdir(parents=True,exist_ok=True)
    for filename,key,values in [('corpus.json','meals',meals),('queries.json','queries',queries)]:
        statuses={q['annotationStatus'] for q in queries} if key=='queries' else {'draft'}
        document=dict(version="1.0",annotationStatus=next(iter(statuses)) if len(statuses)==1 else 'mixed',**{key:values})
        if key=='meals':
            # Legal ingredients without a corpus meal are explicit; never derive this list from query spelling.
            document['ingredientVocabulary']=sorted({i for m in meals for i in m['ingredients']}|{'龙虾','燕麦奶','鸭肉'})
        (DEST/filename).write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n',encoding="utf-8")
    lines=['# 初稿标注审阅表','','初稿标注者：assistant；所有配方为评测假设，draft 等级待用户复核。0=不相关，1=可接受，2=优先。未列入优先/可接受的域内餐食明确标为 0；理由和复核状态按组列出。','', '| ID | 数据域 | 名称 | 假设主要食材 | 假设特点 |','| --- | --- | --- | --- | --- |']
    lines += [f"| {m['id']} | {m['sourceMode']} / {m['ownerUserId']} | {m['name']} | {'、'.join(m['ingredients'])} | {m['description']} |" for m in meals]
    lines += ['','## 查询组','','| 组 / 集合 / 状态 | 查询原话（两种表达） | 等级 2 | 等级 1 | 判定依据 |','| --- | --- | --- | --- | --- |']
    lines += [f"| {g['groupId']} / {g['split']} / {g.get('annotationStatus','draft')} | {' / '.join(g['utterances'])} | {g.get('preferred',[])} | {g.get('acceptable',[])} | {g['rationale']} |" for g in groups]
    (ROOT/'docs/specs/001-meal-retrieval-evaluation/annotations.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(f"Built {len(meals)} meals, {len(queries)} queries; annotation statuses: {sorted({q['annotationStatus'] for q in queries})}.")

if __name__=='__main__':
    build()
