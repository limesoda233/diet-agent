-- Experience seed. Apply after experience_schema.sql. Never import the local
-- development dump: it contains user 1 PERSONAL data.
INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene,
                      health_goal, cuisine, taste, convenience, created_at, updated_at)
SELECT 'PUBLIC', NULL, '番茄鸡蛋面', '["午餐","晚餐"]', '[]', '[]',
       '[]', '["家常"]', '["清淡"]', '["快速"]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND name='番茄鸡蛋面');

INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene,
                      health_goal, cuisine, taste, convenience, created_at, updated_at)
SELECT 'PUBLIC', NULL, '清汤馄饨', '["早餐","午餐","晚餐"]', '[]', '[]',
       '[]', '["家常"]', '["清淡"]', '["快速"]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND name='清汤馄饨');

INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene,
                      health_goal, cuisine, taste, convenience, created_at, updated_at)
SELECT 'PUBLIC', NULL, '鸡胸肉轻食碗', '["午餐","晚餐"]', '["平静"]', '["工作","运动后"]',
       '["减脂","高蛋白"]', '["轻食"]', '["清淡"]', '["快速","一人食"]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND name='鸡胸肉轻食碗');

INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene,
                      health_goal, cuisine, taste, convenience, created_at, updated_at)
SELECT 'PUBLIC', NULL, '麻辣香锅', '["午餐","晚餐"]', '["开心"]', '["周末","聚餐"]',
       '["均衡"]', '["川菜"]', '["麻辣"]', '["慢享","多人共享"]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND name='麻辣香锅');
