-- 为现有 45 道种子餐食创建并补齐结构化知识。
-- 前置条件：已依次执行 diet_db.sql 和 add_40_meal_items.sql。
-- 本脚本按餐食名称关联 meal_item，不依赖各环境的自增 ID，可重复执行。
-- 与 add_40_meal_items.sql 一致，个人种子餐默认属于用户 1；需要时先修改此变量。

SET @personal_owner_user_id := 1;

CREATE TABLE IF NOT EXISTS `meal_knowledge` (
  `meal_id` BIGINT NOT NULL,
  `ingredients` JSON NOT NULL,
  `description` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `preparation` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_type` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_reference` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`meal_id`),
  CONSTRAINT `fk_meal_knowledge_meal_item`
    FOREIGN KEY (`meal_id`) REFERENCES `meal_item` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TEMPORARY TABLE IF EXISTS `tmp_meal_knowledge_seed`;
CREATE TEMPORARY TABLE `tmp_meal_knowledge_seed` (
  `meal_source_type` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `meal_name` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `ingredients` JSON NOT NULL,
  `description` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `preparation` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`meal_source_type`, `meal_name`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `tmp_meal_knowledge_seed`
  (`meal_source_type`, `meal_name`, `ingredients`, `description`, `preparation`)
VALUES
('PUBLIC', '番茄鸡蛋面', JSON_ARRAY('番茄', '鸡蛋', '面条', '葱', '食用油', '盐'),
 '番茄与鸡蛋搭配的家常汤面。', '番茄切块、鸡蛋炒散；番茄炒出汁后加水煮开，下入面条，放回鸡蛋并调味。'),
('PUBLIC', '清汤馄饨', JSON_ARRAY('小麦面皮', '猪肉', '紫菜', '虾皮', '葱', '盐'),
 '清淡暖胃的猪肉馄饨。', '猪肉调味后包入面皮；水开下馄饨，煮熟后加入紫菜、虾皮和葱。'),
('PUBLIC', '鸡胸肉轻食碗', JSON_ARRAY('鸡胸肉', '生菜', '黄瓜', '番茄', '胡萝卜', '米饭'),
 '以鸡胸肉和蔬菜为主的高蛋白轻食。', '鸡胸肉煎熟切片，蔬菜洗净切好，与少量米饭一起装碗。'),
('PUBLIC', '麻辣香锅', JSON_ARRAY('鸡肉', '猪肉', '土豆', '白菜', '香菇', '辣椒', '花生'),
 '多种肉类与蔬菜炒制的麻辣共享餐。', '肉类和耐煮蔬菜预先处理至熟；炒香辣椒后依次加入食材，翻炒均匀。'),
('PERSONAL', '土豆炖牛肉', JSON_ARRAY('牛肉', '土豆', '胡萝卜', '番茄', '葱'),
 '土豆与牛肉慢炖的个人家常餐。', '牛肉焯水后炖至变软，加入番茄、土豆和胡萝卜继续炖熟，最后调味。'),

('PUBLIC', '燕麦牛奶香蕉杯', JSON_ARRAY('燕麦', '牛奶', '香蕉'),
 '适合早餐或加餐的即食燕麦杯。', '燕麦加入牛奶浸泡，香蕉切片后铺在表面即可。'),
('PUBLIC', '小米南瓜粥', JSON_ARRAY('小米', '南瓜', '水'),
 '口感柔和的小米南瓜粥。', '小米洗净，南瓜切块，加水煮开后转小火煮至软糯。'),
('PUBLIC', '鲜虾蔬菜粥', JSON_ARRAY('虾', '米饭', '青菜', '胡萝卜'),
 '含虾和蔬菜的咸鲜粥。', '米饭加水煮散，加入胡萝卜、虾和青菜，煮熟后调味。'),
('PUBLIC', '全麦鸡蛋三明治', JSON_ARRAY('全麦面包', '鸡蛋', '生菜', '番茄'),
 '便于携带的全麦鸡蛋早餐。', '鸡蛋煎熟，与生菜和番茄夹入全麦面包。'),
('PUBLIC', '牛肉蔬菜沙拉', JSON_ARRAY('牛肉', '生菜', '黄瓜', '番茄', '胡萝卜'),
 '牛肉搭配多种蔬菜的高蛋白沙拉。', '牛肉煎熟切片，蔬菜洗净切好，混合后加入少量调味汁。'),
('PUBLIC', '清蒸鲈鱼套餐', JSON_ARRAY('鲈鱼', '葱', '米饭', '青菜'),
 '以清蒸鲈鱼为主的低油套餐。', '鲈鱼处理干净后蒸熟，撒葱并搭配米饭和焯青菜。'),
('PUBLIC', '香菇鸡肉饭', JSON_ARRAY('鸡肉', '香菇', '米饭', '胡萝卜'),
 '鸡肉香菇搭配米饭的家常主食。', '鸡肉、香菇和胡萝卜炒熟调味，盖在热米饭上。'),
('PUBLIC', '番茄牛腩饭', JSON_ARRAY('牛肉', '番茄', '土豆', '米饭'),
 '番茄牛肉炖煮后搭配米饭。', '牛肉炖至变软，加入番茄和土豆煮熟，连汤汁浇在米饭上。'),
('PUBLIC', '黑椒牛肉意面', JSON_ARRAY('牛肉', '意大利面', '黑胡椒'),
 '黑胡椒风味的牛肉意面。', '意面煮熟；牛肉炒至变色后加入黑胡椒调味，与意面拌匀。'),
('PUBLIC', '日式鸡肉荞麦面', JSON_ARRAY('鸡肉', '荞麦面', '海苔', '青菜'),
 '鸡肉与荞麦面搭配的清爽面食。', '荞麦面煮熟，鸡肉煎熟切片，与青菜和海苔一同装碗。'),
('PUBLIC', '韩式石锅拌饭', JSON_ARRAY('米饭', '牛肉', '鸡蛋', '胡萝卜', '青菜'),
 '米饭搭配牛肉、鸡蛋和蔬菜的拌饭。', '牛肉和蔬菜分别炒熟，与煎蛋一起铺在米饭上，食用前拌匀。'),
('PUBLIC', '咖喱鸡肉饭', JSON_ARRAY('鸡肉', '咖喱', '土豆', '胡萝卜', '米饭'),
 '咖喱鸡肉和根茎蔬菜搭配米饭。', '鸡肉、土豆和胡萝卜炒香，加水与咖喱煮至浓稠，浇在米饭上。'),
('PUBLIC', '云南过桥米线', JSON_ARRAY('米线', '鸡肉', '青菜', '香菇'),
 '鸡肉、蔬菜和香菇搭配的热汤米线。', '米线煮熟；汤中煮熟鸡肉、香菇和青菜，最后放入米线。'),
('PUBLIC', '粤式叉烧饭', JSON_ARRAY('猪肉', '米饭', '青菜', '酱油'),
 '酱香叉烧搭配米饭和青菜。', '猪肉腌制后烤熟或煎熟切片，与米饭和焯青菜装盘。'),
('PUBLIC', '新疆鸡肉抓饭', JSON_ARRAY('鸡肉', '米饭', '胡萝卜'),
 '鸡肉与胡萝卜焖制的米饭。', '鸡肉和胡萝卜炒香，加米和水焖熟，出锅前翻拌均匀。'),
('PUBLIC', '蒜香虾仁西兰花', JSON_ARRAY('虾', '西兰花', '大蒜'),
 '虾仁与西兰花快炒的高蛋白菜。', '西兰花焯水；大蒜炒香后加入虾仁和西兰花，快速翻炒调味。'),
('PUBLIC', '豆腐菌菇汤', JSON_ARRAY('豆腐', '香菇', '青菜'),
 '豆腐、香菇和青菜制作的清淡汤品。', '香菇入水煮出鲜味，加入豆腐和青菜煮熟后调味。'),
('PUBLIC', '麻婆豆腐盖饭', JSON_ARRAY('豆腐', '猪肉', '辣椒', '米饭'),
 '麻辣豆腐和肉末搭配米饭。', '猪肉炒散，加入辣椒和豆腐烧至入味，浇在米饭上。'),
('PUBLIC', '东北酸菜炖粉条', JSON_ARRAY('酸菜', '粉条', '猪肉'),
 '酸菜、猪肉和粉条炖制的家常菜。', '猪肉炒香后加入酸菜和水炖煮，最后加入泡软的粉条煮熟。'),
('PUBLIC', '水果酸奶杯', JSON_ARRAY('酸奶', '香蕉', '苹果', '草莓'),
 '酸奶搭配多种水果的冷食甜品。', '水果洗净切块，分层放入酸奶杯中即可。'),

('PERSONAL', '私房葱油拌面', JSON_ARRAY('面条', '葱', '食用油', '酱油'),
 '葱油和酱油调味的个人拌面。', '葱段用油小火炸香，加入酱油调成葱油汁，与煮熟的面条拌匀。'),
('PERSONAL', '家常青椒肉丝饭', JSON_ARRAY('猪肉', '青椒', '米饭'),
 '青椒肉丝搭配米饭的个人家常餐。', '猪肉切丝炒至变色，加入青椒快速炒熟，搭配米饭。'),
('PERSONAL', '低油版宫保鸡丁', JSON_ARRAY('鸡胸肉', '花生', '辣椒', '葱'),
 '减少用油量的宫保鸡丁。', '鸡胸肉切丁煎熟，加入辣椒和调味汁翻炒，最后放花生和葱。'),
('PERSONAL', '空气炸锅鸡翅', JSON_ARRAY('鸡翅', '大蒜', '酱油'),
 '使用空气炸锅制作的蒜香鸡翅。', '鸡翅划口，用大蒜和酱油腌制，放入空气炸锅加热至熟并翻面一次。'),
('PERSONAL', '蒜蓉粉丝蒸虾', JSON_ARRAY('虾', '粉丝', '大蒜', '葱'),
 '蒜蓉虾与粉丝一同蒸制。', '粉丝泡软铺盘，放上处理好的虾和蒜蓉，蒸熟后撒葱。'),
('PERSONAL', '冬瓜排骨汤', JSON_ARRAY('冬瓜', '猪排骨', '葱'),
 '冬瓜与排骨慢炖的清汤。', '排骨焯水后炖至接近软烂，加入冬瓜继续煮熟，最后撒葱。'),
('PERSONAL', '紫菜虾皮蛋花汤', JSON_ARRAY('紫菜', '虾皮', '鸡蛋', '葱'),
 '快速制作的紫菜蛋花汤。', '水开后加入紫菜和虾皮，淋入蛋液，凝固后调味并撒葱。'),
('PERSONAL', '韭菜鸡蛋盒子', JSON_ARRAY('韭菜', '鸡蛋', '面粉'),
 '韭菜鸡蛋馅的家常面食。', '面粉和面醒发；鸡蛋炒散后与韭菜拌馅，包好后在锅中煎熟。'),
('PERSONAL', '自制牛肉卷饼', JSON_ARRAY('牛肉', '面粉', '生菜', '黄瓜'),
 '牛肉与蔬菜制作的便携卷饼。', '面粉制成薄饼并烙熟，卷入煎熟的牛肉、生菜和黄瓜。'),
('PERSONAL', '金枪鱼饭团', JSON_ARRAY('金枪鱼', '米饭', '海苔'),
 '金枪鱼馅的便携饭团。', '金枪鱼调味后包入温热米饭，捏成饭团并裹上海苔。'),
('PERSONAL', '照烧鸡腿便当', JSON_ARRAY('鸡腿', '米饭', '西兰花', '胡萝卜', '酱油'),
 '照烧鸡腿搭配米饭和蔬菜的便当。', '鸡腿煎熟后用酱油调味收汁，切块并与米饭、焯蔬菜装盒。'),
('PERSONAL', '香煎三文鱼', JSON_ARRAY('三文鱼', '黑胡椒', '黄油'),
 '黑胡椒调味的香煎三文鱼。', '三文鱼擦干并撒黑胡椒，用少量黄油将两面煎熟。'),
('PERSONAL', '番茄豆腐煲', JSON_ARRAY('番茄', '豆腐', '香菇'),
 '番茄、豆腐和香菇制作的家常煲。', '番茄炒出汁，加入香菇和豆腐，加少量水煮至入味。'),
('PERSONAL', '凉拌鸡丝', JSON_ARRAY('鸡胸肉', '黄瓜', '辣椒', '大蒜'),
 '鸡丝和黄瓜制作的低油凉菜。', '鸡胸肉煮熟后撕丝，加入黄瓜、辣椒和蒜蓉拌匀。'),
('PERSONAL', '香菇青菜面', JSON_ARRAY('香菇', '青菜', '面条'),
 '香菇和青菜搭配的清汤面。', '香菇煮出鲜味后下入面条，面条将熟时加入青菜并调味。'),
('PERSONAL', '红豆燕麦粥', JSON_ARRAY('红豆', '燕麦', '牛奶', '水'),
 '红豆和燕麦制作的早餐粥。', '红豆提前浸泡并煮软，加入燕麦继续煮，食用前加入牛奶。'),
('PERSONAL', '鸡蛋蔬菜饼', JSON_ARRAY('鸡蛋', '面粉', '胡萝卜', '青菜'),
 '鸡蛋与蔬菜制作的早餐饼。', '鸡蛋、面粉和切碎蔬菜调成面糊，倒入锅中煎至两面熟。'),
('PERSONAL', '西红柿牛肉汤', JSON_ARRAY('番茄', '牛肉', '土豆'),
 '番茄、牛肉和土豆炖制的个人汤品。', '牛肉炖至变软，加入番茄和土豆继续煮熟并调味。'),
('PERSONAL', '酸辣土豆丝盖饭', JSON_ARRAY('土豆', '辣椒', '米饭'),
 '酸辣土豆丝搭配米饭的快手餐。', '土豆切丝冲洗，和辣椒大火快炒至熟，盖在米饭上。'),
('PERSONAL', '自制蔬菜火锅', JSON_ARRAY('白菜', '香菇', '豆腐', '土豆', '青菜', '粉条'),
 '以蔬菜、豆腐和粉条为主的家庭火锅。', '准备汤底，先煮土豆、香菇和豆腐，再加入白菜、青菜和泡软的粉条。');

START TRANSACTION;

INSERT INTO `meal_knowledge`
  (`meal_id`, `ingredients`, `description`, `preparation`, `source_type`, `source_reference`, `updated_at`)
SELECT
  m.`id`,
  s.`ingredients`,
  s.`description`,
  s.`preparation`,
  'SYNTHETIC_EVALUATION',
  CONCAT('seed:', s.`meal_name`),
  NOW(6)
FROM `tmp_meal_knowledge_seed` s
JOIN `meal_item` m
  ON m.`source_type` = s.`meal_source_type`
 AND m.`name` = s.`meal_name`
 AND (m.`source_type` = 'PUBLIC' OR m.`owner_user_id` = @personal_owner_user_id)
ON DUPLICATE KEY UPDATE
  `ingredients` = VALUES(`ingredients`),
  `description` = VALUES(`description`),
  `preparation` = VALUES(`preparation`),
  `source_type` = VALUES(`source_type`),
  `source_reference` = VALUES(`source_reference`),
  `updated_at` = VALUES(`updated_at`);

COMMIT;

-- 期望 seed_rows=45、matched_meals=45、knowledge_rows_for_seed=45。
SELECT COUNT(*) AS `seed_rows` FROM `tmp_meal_knowledge_seed`;
SELECT COUNT(*) AS `matched_meals`
FROM `tmp_meal_knowledge_seed` s
JOIN `meal_item` m
  ON m.`source_type` = s.`meal_source_type`
 AND m.`name` = s.`meal_name`
 AND (m.`source_type` = 'PUBLIC' OR m.`owner_user_id` = @personal_owner_user_id);
SELECT COUNT(*) AS `knowledge_rows_for_seed`
FROM `meal_knowledge` k
JOIN `meal_item` m ON m.`id` = k.`meal_id`
JOIN `tmp_meal_knowledge_seed` s
  ON m.`source_type` = s.`meal_source_type`
 AND m.`name` = s.`meal_name`
 AND (m.`source_type` = 'PUBLIC' OR m.`owner_user_id` = @personal_owner_user_id);

-- 如 matched_meals 小于 45，用下面的结果检查尚未导入的 meal_item。
SELECT s.`meal_source_type`, s.`meal_name`
FROM `tmp_meal_knowledge_seed` s
LEFT JOIN `meal_item` m
  ON m.`source_type` = s.`meal_source_type`
 AND m.`name` = s.`meal_name`
 AND (m.`source_type` = 'PUBLIC' OR m.`owner_user_id` = @personal_owner_user_id)
WHERE m.`id` IS NULL;

DROP TEMPORARY TABLE `tmp_meal_knowledge_seed`;
