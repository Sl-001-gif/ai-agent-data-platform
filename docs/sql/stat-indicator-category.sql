-- 统计月报指标分类树（阶段1：10 大类 × 34 中类 × 225 叶子）
-- 叶子全集 = stat_monthly.2025年1-9月 的 225 个 DISTINCT indicator_name；生成: tools/scraper/build_stat_category.py --tree
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT ... ON DUPLICATE KEY UPDATE，连跑两次结果一致
CREATE TABLE IF NOT EXISTS stat_indicator_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NULL COMMENT '父节点ID（大类=NULL，中类=大类id，叶子=中类id）',
    name VARCHAR(300) NOT NULL COMMENT '节点名称（大类/中类/指标名）',
    code VARCHAR(64) NOT NULL COMMENT '稳定编码（uk_code，可重跑）',
    level TINYINT NOT NULL COMMENT '1=大类 2=中类 3=叶子指标',
    sort INT DEFAULT 0 COMMENT '同级排序',
    color VARCHAR(20) NULL COMMENT '大类色值',
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    KEY idx_parent_level (parent_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计月报指标分类树（大类×中类×叶子）';

-- 一级：大类（10）
INSERT INTO stat_indicator_category (parent_id, name, code, level, sort, color, status) VALUES
(NULL, '经济核算', 'c01', 1, 1, '#2f6fed', 1),
(NULL, '工业经济', 'c02', 1, 2, '#e8743b', 1),
(NULL, '固定资产投资', 'c03', 1, 3, '#7b5cd6', 1),
(NULL, '消费市场', 'c04', 1, 4, '#d65c8b', 1),
(NULL, '外贸外资', 'c05', 1, 5, '#2a9d8f', 1),
(NULL, '财政收支', 'c06', 1, 6, '#e9a23b', 1),
(NULL, '金融运行', 'c07', 1, 7, '#3b82c4', 1),
(NULL, '居民收支', 'c08', 1, 8, '#6bbf59', 1),
(NULL, '交通运输', 'c09', 1, 9, '#3f8fa3', 1),
(NULL, '综合对比', 'c10', 1, 10, '#8d99ae', 1)
ON DUPLICATE KEY UPDATE name=VALUES(name), level=1, sort=VALUES(sort), color=VALUES(color), status=1;

-- 二级：中类（34，parent 按 code 关联大类）
INSERT INTO stat_indicator_category (parent_id, name, code, level, sort, color, status) 
SELECT p.id, v.name, v.code, 2, v.sort, NULL, 1
FROM (SELECT 'c01_gdp' AS code, 'GDP' AS name, 1 AS sort, 'c01' AS pcode
UNION ALL
SELECT 'c01_cyzjz' AS code, '产业增加值' AS name, 2 AS sort, 'c01' AS pcode
UNION ALL
SELECT 'c01_hyzjz' AS code, '行业增加值' AS name, 3 AS sort, 'c01' AS pcode
UNION ALL
SELECT 'c01_nyzcz' AS code, '农业总产值' AS name, 4 AS sort, 'c01' AS pcode
UNION ALL
SELECT 'c02_gmzjz' AS code, '规模工业增加值' AS name, 1 AS sort, 'c02' AS pcode
UNION ALL
SELECT 'c02_gmcz' AS code, '规模工业产值' AS name, 2 AS sort, 'c02' AS pcode
UNION ALL
SELECT 'c02_xjy' AS code, '经济效益' AS name, 3 AS sort, 'c02' AS pcode
UNION ALL
SELECT 'c02_hyzjz' AS code, '行业增加值' AS name, 4 AS sort, 'c02' AS pcode
UNION ALL
SELECT 'c03_tzze' AS code, '固定资产投资总额' AS name, 1 AS sort, 'c03' AS pcode
UNION ALL
SELECT 'c03_cytz' AS code, '产业投资' AS name, 2 AS sort, 'c03' AS pcode
UNION ALL
SELECT 'c03_jg' AS code, '投资结构与项目' AS name, 3 AS sort, 'c03' AS pcode
UNION ALL
SELECT 'c04_shzp' AS code, '社会消费品零售总额' AS name, 1 AS sort, 'c04' AS pcode
UNION ALL
SELECT 'c04_xes' AS code, '限额以上零售' AS name, 2 AS sort, 'c04' AS pcode
UNION ALL
SELECT 'c04_fqy' AS code, '分区域零售' AS name, 3 AS sort, 'c04' AS pcode
UNION ALL
SELECT 'c04_fpl' AS code, '分品类零售' AS name, 4 AS sort, 'c04' AS pcode
UNION ALL
SELECT 'c04_fhy' AS code, '分行业零售' AS name, 5 AS sort, 'c04' AS pcode
UNION ALL
SELECT 'c05_jck' AS code, '进出口' AS name, 1 AS sort, 'c05' AS pcode
UNION ALL
SELECT 'c05_wszj' AS code, '外商直接投资' AS name, 2 AS sort, 'c05' AS pcode
UNION ALL
SELECT 'c05_fqy' AS code, '分区域外贸' AS name, 3 AS sort, 'c05' AS pcode
UNION ALL
SELECT 'c05_fdc' AS code, '房地产' AS name, 4 AS sort, 'c05' AS pcode
UNION ALL
SELECT 'c06_sr' AS code, '财政收入' AS name, 1 AS sort, 'c06' AS pcode
UNION ALL
SELECT 'c06_ss' AS code, '税收收入' AS name, 2 AS sort, 'c06' AS pcode
UNION ALL
SELECT 'c06_zc' AS code, '财政支出' AS name, 3 AS sort, 'c06' AS pcode
UNION ALL
SELECT 'c07_ck' AS code, '存款' AS name, 1 AS sort, 'c07' AS pcode
UNION ALL
SELECT 'c07_dk' AS code, '贷款' AS name, 2 AS sort, 'c07' AS pcode
UNION ALL
SELECT 'c08_qt' AS code, '全体居民收入' AS name, 1 AS sort, 'c08' AS pcode
UNION ALL
SELECT 'c08_cz' AS code, '城镇居民收入' AS name, 2 AS sort, 'c08' AS pcode
UNION ALL
SELECT 'c08_nc' AS code, '农村居民收入' AS name, 3 AS sort, 'c08' AS pcode
UNION ALL
SELECT 'c08_jg' AS code, '收入结构' AS name, 4 AS sort, 'c08' AS pcode
UNION ALL
SELECT 'c08_xf' AS code, '消费支出' AS name, 5 AS sort, 'c08' AS pcode
UNION ALL
SELECT 'c09_ydl' AS code, '用电量' AS name, 1 AS sort, 'c09' AS pcode
UNION ALL
SELECT 'c09_ky' AS code, '客运' AS name, 2 AS sort, 'c09' AS pcode
UNION ALL
SELECT 'c09_hy' AS code, '货运' AS name, 3 AS sort, 'c09' AS pcode
UNION ALL
SELECT 'c10_jgzs' AS code, '价格指数' AS name, 1 AS sort, 'c10' AS pcode) v
JOIN stat_indicator_category p ON p.code = v.pcode
ON DUPLICATE KEY UPDATE name=VALUES(name), parent_id=VALUES(parent_id), level=2, sort=VALUES(sort), status=1;

-- 三级：叶子指标（225，parent 按 code 关联中类）
INSERT INTO stat_indicator_category (parent_id, name, code, level, sort, color, status) 
SELECT p.id, v.name, v.code, 3, v.sort, NULL, 1
FROM (SELECT 'ind_0001' AS code, '一般公共服务' AS name, 1 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0002' AS code, '一般公共预算支出' AS name, 2 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0003' AS code, '一般公共预算支出合计' AS name, 3 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0004' AS code, '一般公共预算支出排名' AS name, 4 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0005' AS code, '一般公共预算收入' AS name, 5 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0006' AS code, '一般公共预算收入排名' AS name, 6 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0007' AS code, '专用设备制造业' AS name, 7 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0008' AS code, '专项收入' AS name, 8 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0009' AS code, '两金占用(应收帐款和产成品存货)' AS name, 9 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0010' AS code, '个人所得税' AS name, 10 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0011' AS code, '中央项目' AS name, 11 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0012' AS code, '中长期贷款' AS name, 12 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0013' AS code, '乡村零售额' AS name, 13 AS sort, 'c04_fqy' AS pcode
UNION ALL
SELECT 'ind_0014' AS code, '书报杂志类零售额' AS name, 14 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0015' AS code, '亏损企业亏损额' AS name, 15 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0016' AS code, '亏损面' AS name, 16 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0017' AS code, '交通运输' AS name, 17 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0018' AS code, '交通运输、仓储和邮政业' AS name, 18 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0019' AS code, '交通运输、仓储和邮政业投资' AS name, 19 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0020' AS code, '产品销售成本' AS name, 20 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0021' AS code, '从业人员平均人数(人)' AS name, 21 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0022' AS code, '仪器仪表制造业' AS name, 22 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0023' AS code, '企业所得税' AS name, 23 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0024' AS code, '住宅销售面积' AS name, 24 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0025' AS code, '住宅销售额' AS name, 25 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0026' AS code, '住宿和餐饮业' AS name, 26 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0027' AS code, '住宿和餐饮业投资' AS name, 27 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0028' AS code, '住宿和餐饮业零售额' AS name, 28 AS sort, 'c04_fhy' AS pcode
UNION ALL
SELECT 'ind_0029' AS code, '住户存款' AS name, 29 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0030' AS code, '住户贷款' AS name, 30 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0031' AS code, '住房保障' AS name, 31 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0032' AS code, '信息传输、软件和信息技术服务业' AS name, 32 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0033' AS code, '信息传输、软件和信息技术服务业投资' AS name, 33 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0034' AS code, '全体居民人均可支配收入' AS name, 34 AS sort, 'c08_qt' AS pcode
UNION ALL
SELECT 'ind_0035' AS code, '全体居民人均可支配收入排名' AS name, 35 AS sort, 'c08_qt' AS pcode
UNION ALL
SELECT 'ind_0036' AS code, '全市居民人均消费支出' AS name, 36 AS sort, 'c08_xf' AS pcode
UNION ALL
SELECT 'ind_0037' AS code, '全市用电总量(亿千瓦小时)' AS name, 37 AS sort, 'c09_ydl' AS pcode
UNION ALL
SELECT 'ind_0038' AS code, '公共安全' AS name, 38 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0039' AS code, '公共管理、社会保障和社会组织投资' AS name, 39 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0040' AS code, '公路(万人)' AS name, 40 AS sort, 'c09_ky' AS pcode
UNION ALL
SELECT 'ind_0041' AS code, '公路(万人公里)' AS name, 41 AS sort, 'c09_ky' AS pcode
UNION ALL
SELECT 'ind_0042' AS code, '公路(万吨)' AS name, 42 AS sort, 'c09_hy' AS pcode
UNION ALL
SELECT 'ind_0043' AS code, '公路(万吨公里)' AS name, 43 AS sort, 'c09_hy' AS pcode
UNION ALL
SELECT 'ind_0044' AS code, '其他制造业' AS name, 44 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0045' AS code, '其他服务业' AS name, 45 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0046' AS code, '其他费用' AS name, 46 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0047' AS code, '农、林、牧、渔业' AS name, 47 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0048' AS code, '农、林、牧、渔业投资' AS name, 48 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0049' AS code, '农业总产值(现价)' AS name, 49 AS sort, 'c01_nyzcz' AS pcode
UNION ALL
SELECT 'ind_0050' AS code, '农副食品加工业' AS name, 50 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0051' AS code, '农村居民人均可支配收入' AS name, 51 AS sort, 'c08_nc' AS pcode
UNION ALL
SELECT 'ind_0052' AS code, '农村居民人均可支配收入排名' AS name, 52 AS sort, 'c08_nc' AS pcode
UNION ALL
SELECT 'ind_0053' AS code, '农村居民人均生活消费支出' AS name, 53 AS sort, 'c08_xf' AS pcode
UNION ALL
SELECT 'ind_0054' AS code, '农林水事务' AS name, 54 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0055' AS code, '出口' AS name, 55 AS sort, 'c05_jck' AS pcode
UNION ALL
SELECT 'ind_0056' AS code, '出口交货值' AS name, 56 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0057' AS code, '利润总额' AS name, 57 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0058' AS code, '利税总额' AS name, 58 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0059' AS code, '制造业投资' AS name, 59 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0060' AS code, '化学原料和化学制品制造业' AS name, 60 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0061' AS code, '化学纤维制造业' AS name, 61 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0062' AS code, '医药制造业' AS name, 62 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0063' AS code, '卫生健康' AS name, 63 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0064' AS code, '卫生和社会工作投资' AS name, 64 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0065' AS code, '印刷和记录媒介复制业' AS name, 65 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0066' AS code, '印花税' AS name, 66 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0067' AS code, '各项存款' AS name, 67 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0068' AS code, '各项贷款' AS name, 68 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0069' AS code, '商品房屋销售额' AS name, 69 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0070' AS code, '商品房施工面积' AS name, 70 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0071' AS code, '商品房竣工面积' AS name, 71 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0072' AS code, '商品房销售面积' AS name, 72 AS sort, 'c05_fdc' AS pcode
UNION ALL
SELECT 'ind_0073' AS code, '固定资产投资' AS name, 73 AS sort, 'c03_tzze' AS pcode
UNION ALL
SELECT 'ind_0074' AS code, '固定资产投资排名' AS name, 74 AS sort, 'c03_tzze' AS pcode
UNION ALL
SELECT 'ind_0075' AS code, '国有投资' AS name, 75 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0076' AS code, '国有资源(资产)有偿使用收入' AS name, 76 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0077' AS code, '土地增值税' AS name, 77 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0078' AS code, '地区生产总值' AS name, 78 AS sort, 'c01_gdp' AS pcode
UNION ALL
SELECT 'ind_0079' AS code, '地区生产总值排名' AS name, 79 AS sort, 'c01_gdp' AS pcode
UNION ALL
SELECT 'ind_0080' AS code, '地方项目' AS name, 80 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0081' AS code, '城乡社区事务' AS name, 81 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0082' AS code, '城区零售额' AS name, 82 AS sort, 'c04_fqy' AS pcode
UNION ALL
SELECT 'ind_0083' AS code, '城市维护建设税' AS name, 83 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0084' AS code, '城镇土地使用税' AS name, 84 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0085' AS code, '城镇居民人均可支配收入' AS name, 85 AS sort, 'c08_cz' AS pcode
UNION ALL
SELECT 'ind_0086' AS code, '城镇居民人均可支配收入排名' AS name, 86 AS sort, 'c08_cz' AS pcode
UNION ALL
SELECT 'ind_0087' AS code, '城镇居民人均生活消费支出' AS name, 87 AS sort, 'c08_xf' AS pcode
UNION ALL
SELECT 'ind_0088' AS code, '城镇零售额' AS name, 88 AS sort, 'c04_fqy' AS pcode
UNION ALL
SELECT 'ind_0089' AS code, '基础设施建设投资' AS name, 89 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0090' AS code, '基础设施建设投资排名' AS name, 90 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0091' AS code, '境内存款' AS name, 91 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0092' AS code, '境内贷款' AS name, 92 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0093' AS code, '增值税' AS name, 93 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0094' AS code, '外商直接投资' AS name, 94 AS sort, 'c05_wszj' AS pcode
UNION ALL
SELECT 'ind_0095' AS code, '大湘西地区(45贸外处4)' AS name, 95 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0096' AS code, '大湘西地区(46贸外处5)' AS name, 96 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0097' AS code, '契税' AS name, 97 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0098' AS code, '定期及其他存款' AS name, 98 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0099' AS code, '家具制造业' AS name, 99 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0100' AS code, '居民服务、修理和其他服务业投资' AS name, 100 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0101' AS code, '居民消费价格指数' AS name, 101 AS sort, 'c10_jgzs' AS pcode
UNION ALL
SELECT 'ind_0102' AS code, '工业' AS name, 102 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0103' AS code, '工业产品销售产值(现价)' AS name, 103 AS sort, 'c02_gmcz' AS pcode
UNION ALL
SELECT 'ind_0104' AS code, '工业技改投资' AS name, 104 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0105' AS code, '工业投资' AS name, 105 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0106' AS code, '工业投资排名' AS name, 106 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0107' AS code, '工业用电量' AS name, 107 AS sort, 'c09_ydl' AS pcode
UNION ALL
SELECT 'ind_0108' AS code, '工资性收入' AS name, 108 AS sort, 'c08_jg' AS pcode
UNION ALL
SELECT 'ind_0109' AS code, '广义政府存款' AS name, 109 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0110' AS code, '废弃资源综合利用业' AS name, 110 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0111' AS code, '建筑业' AS name, 111 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0112' AS code, '建筑业投资' AS name, 112 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0113' AS code, '建筑安装工程' AS name, 113 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0114' AS code, '房产税' AS name, 114 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0115' AS code, '房地产业' AS name, 115 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0116' AS code, '房地产业投资' AS name, 116 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0117' AS code, '房地产开发投资' AS name, 117 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0118' AS code, '房地产开发投资排名' AS name, 118 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0119' AS code, '批发和零售业' AS name, 119 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0120' AS code, '批发和零售业投资' AS name, 120 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0121' AS code, '批发和零售业零售额' AS name, 121 AS sort, 'c04_fhy' AS pcode
UNION ALL
SELECT 'ind_0122' AS code, '政府住房基金收入' AS name, 122 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0123' AS code, '教育' AS name, 123 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0124' AS code, '教育投资' AS name, 124 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0125' AS code, '文化、体育和娱乐业投资' AS name, 125 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0126' AS code, '文化旅游体育与传媒' AS name, 126 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0127' AS code, '文教、工美、体育和娱乐用品制造业' AS name, 127 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0128' AS code, '施工项目个数' AS name, 128 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0129' AS code, '有色金属冶炼和压延加工业' AS name, 129 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0130' AS code, '有色金属矿采选业' AS name, 130 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0131' AS code, '服装、鞋帽、针纺织品类零售额' AS name, 131 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0132' AS code, '木材加工和木、竹、藤、棕、草制品业' AS name, 132 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0133' AS code, '本年投产项目个数' AS name, 133 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0134' AS code, '本年新开工' AS name, 134 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0135' AS code, '橡胶和塑料制品业' AS name, 135 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0136' AS code, '民生工程投资' AS name, 136 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0137' AS code, '民间投资' AS name, 137 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0138' AS code, '水利、环境和公共设施管理业投资' AS name, 138 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0139' AS code, '水的生产和供应业' AS name, 139 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0140' AS code, '水运(万人)' AS name, 140 AS sort, 'c09_ky' AS pcode
UNION ALL
SELECT 'ind_0141' AS code, '水运(万人公里)' AS name, 141 AS sort, 'c09_ky' AS pcode
UNION ALL
SELECT 'ind_0142' AS code, '汽车制造业' AS name, 142 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0143' AS code, '汽车类零售额' AS name, 143 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0144' AS code, '洞庭湖地区(45贸外处4)' AS name, 144 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0145' AS code, '洞庭湖地区(46贸外处5)' AS name, 145 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0146' AS code, '活期存款' AS name, 146 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0147' AS code, '消费贷款' AS name, 147 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0148' AS code, '湘南地区(45贸外处4)' AS name, 148 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0149' AS code, '湘南地区(46贸外处5)' AS name, 149 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0150' AS code, '烟酒类零售额' AS name, 150 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0151' AS code, '煤炭开采和洗选业' AS name, 151 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0152' AS code, '燃气生产和供应业' AS name, 152 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0153' AS code, '环境保护税' AS name, 153 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0154' AS code, '环长株潭城市群(45贸外处4)' AS name, 154 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0155' AS code, '环长株潭城市群(46贸外处5)' AS name, 155 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0156' AS code, '生态环境投资' AS name, 156 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0157' AS code, '电力、热力、燃气及水的生产和供应业投资' AS name, 157 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0158' AS code, '电力、热力生产和供应业' AS name, 158 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0159' AS code, '电气机械和器材制造业' AS name, 159 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0160' AS code, '皮革、毛皮、羽毛及其制品和制鞋业' AS name, 160 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0161' AS code, '短期贷款' AS name, 161 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0162' AS code, '石油、煤炭及其他燃料加工业' AS name, 162 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0163' AS code, '石油制品类零售额' AS name, 163 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0164' AS code, '社会保障和就业' AS name, 164 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0165' AS code, '社会消费品零售总额' AS name, 165 AS sort, 'c04_shzp' AS pcode
UNION ALL
SELECT 'ind_0166' AS code, '社会消费品零售总额排名' AS name, 166 AS sort, 'c04_shzp' AS pcode
UNION ALL
SELECT 'ind_0167' AS code, '票据融资' AS name, 167 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0168' AS code, '科学技术' AS name, 168 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0169' AS code, '科学研究和技术服务业投资' AS name, 169 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0170' AS code, '租赁和商务服务业' AS name, 170 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0171' AS code, '租赁和商务服务业投资' AS name, 171 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0172' AS code, '税收收入' AS name, 172 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0173' AS code, '第一产业增加值' AS name, 173 AS sort, 'c01_cyzjz' AS pcode
UNION ALL
SELECT 'ind_0174' AS code, '第一产业投资' AS name, 174 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0175' AS code, '第三产业增加值' AS name, 175 AS sort, 'c01_cyzjz' AS pcode
UNION ALL
SELECT 'ind_0176' AS code, '第三产业投资' AS name, 176 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0177' AS code, '第二产业增加值' AS name, 177 AS sort, 'c01_cyzjz' AS pcode
UNION ALL
SELECT 'ind_0178' AS code, '第二产业投资' AS name, 178 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0179' AS code, '粮油、食品类零售额' AS name, 179 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0180' AS code, '纺织业' AS name, 180 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0181' AS code, '纺织服装、服饰业' AS name, 181 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0182' AS code, '经营净收入' AS name, 182 AS sort, 'c08_jg' AS pcode
UNION ALL
SELECT 'ind_0183' AS code, '经营贷款' AS name, 183 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0184' AS code, '罚没收入' AS name, 184 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0185' AS code, '耕地占用税' AS name, 185 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0186' AS code, '节能环保' AS name, 186 AS sort, 'c06_zc' AS pcode
UNION ALL
SELECT 'ind_0187' AS code, '营业收入' AS name, 187 AS sort, 'c02_xjy' AS pcode
UNION ALL
SELECT 'ind_0188' AS code, '行政性收费' AS name, 188 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0189' AS code, '规模以下工业总产值' AS name, 189 AS sort, 'c02_gmcz' AS pcode
UNION ALL
SELECT 'ind_0190' AS code, '规模工业增加值' AS name, 190 AS sort, 'c02_gmzjz' AS pcode
UNION ALL
SELECT 'ind_0191' AS code, '规模工业增加值排名' AS name, 191 AS sort, 'c02_gmzjz' AS pcode
UNION ALL
SELECT 'ind_0192' AS code, '规模工业总产值' AS name, 192 AS sort, 'c02_gmcz' AS pcode
UNION ALL
SELECT 'ind_0193' AS code, '计算机、通信和其他电子设备制造业' AS name, 193 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0194' AS code, '设备工器具购置' AS name, 194 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0195' AS code, '财产净收入' AS name, 195 AS sort, 'c08_jg' AS pcode
UNION ALL
SELECT 'ind_0196' AS code, '资源税' AS name, 196 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0197' AS code, '车船税' AS name, 197 AS sort, 'c06_ss' AS pcode
UNION ALL
SELECT 'ind_0198' AS code, '转移净收入' AS name, 198 AS sort, 'c08_jg' AS pcode
UNION ALL
SELECT 'ind_0199' AS code, '进出口' AS name, 199 AS sort, 'c05_jck' AS pcode
UNION ALL
SELECT 'ind_0200' AS code, '进口' AS name, 200 AS sort, 'c05_jck' AS pcode
UNION ALL
SELECT 'ind_0201' AS code, '通用设备制造业' AS name, 201 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0202' AS code, '造纸和纸制品业' AS name, 202 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0203' AS code, '酒、饮料和精制茶制造业' AS name, 203 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0204' AS code, '采矿业投资' AS name, 204 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0205' AS code, '金属制品业' AS name, 205 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0206' AS code, '金融业' AS name, 206 AS sort, 'c01_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0207' AS code, '金融业投资' AS name, 207 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0208' AS code, '长株潭地区(45贸外处4)' AS name, 208 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0209' AS code, '长株潭地区(46贸外处5)' AS name, 209 AS sort, 'c05_fqy' AS pcode
UNION ALL
SELECT 'ind_0210' AS code, '限额以上法人单位零售额' AS name, 210 AS sort, 'c04_xes' AS pcode
UNION ALL
SELECT 'ind_0211' AS code, '限额以上零售额' AS name, 211 AS sort, 'c04_xes' AS pcode
UNION ALL
SELECT 'ind_0212' AS code, '非国有投资' AS name, 212 AS sort, 'c03_jg' AS pcode
UNION ALL
SELECT 'ind_0213' AS code, '非税收入' AS name, 213 AS sort, 'c06_sr' AS pcode
UNION ALL
SELECT 'ind_0214' AS code, '非金属矿物制品业' AS name, 214 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0215' AS code, '非金属矿采选业' AS name, 215 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0216' AS code, '非金融企业及机关团体贷款' AS name, 216 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0217' AS code, '非金融企业存款' AS name, 217 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0218' AS code, '非银行业金融机构存款' AS name, 218 AS sort, 'c07_ck' AS pcode
UNION ALL
SELECT 'ind_0219' AS code, '非银行业金融机构贷款' AS name, 219 AS sort, 'c07_dk' AS pcode
UNION ALL
SELECT 'ind_0220' AS code, '食品制造业' AS name, 220 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0221' AS code, '饮料类零售额' AS name, 221 AS sort, 'c04_fpl' AS pcode
UNION ALL
SELECT 'ind_0222' AS code, '高技术产业投资' AS name, 222 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0223' AS code, '高技术产业投资排名' AS name, 223 AS sort, 'c03_cytz' AS pcode
UNION ALL
SELECT 'ind_0224' AS code, '黑色金属冶炼和压延加工业' AS name, 224 AS sort, 'c02_hyzjz' AS pcode
UNION ALL
SELECT 'ind_0225' AS code, '黑色金属矿采选业' AS name, 225 AS sort, 'c02_hyzjz' AS pcode) v
JOIN stat_indicator_category p ON p.code = v.pcode
ON DUPLICATE KEY UPDATE name=VALUES(name), parent_id=VALUES(parent_id), level=3, sort=VALUES(sort), status=1;

