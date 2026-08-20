# -*- coding: utf-8 -*-
"""指标治理 L1 单元测试：清洗/归一/映射/噪音判定。运行：python -m pytest tools/test_indicator_governance.py"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from indicator_governance import normalize, canonical, OFFICIAL_DICT


class TestNormalize:
    def test_strip_seq_prefix(self):
        assert normalize("1、地方一般公共预算收入") == "地方一般公共预算收入"
        assert normalize("（1)税收收入") == "税收收入"
        assert normalize("#个人所得税") == "个人所得税"
        assert normalize("2.规模以下工业总产值") == "规模以下工业总产值"

    def test_unify_brackets_and_spaces(self):
        assert normalize("社会消费品零售总额（亿元）") == "社会消费品零售总额(亿元)"
        assert normalize("施工项目个数 （个）") == "施工项目个数(个)"


class TestCanonical:
    def test_official_exact(self):
        assert canonical("社会消费品零售总额") == ("社会消费品零售总额", "keep")
        assert canonical("地区生产总值") == ("地区生产总值", "keep")

    def test_alias_remap(self):
        assert canonical("财政收入")[0] == "一般公共预算收入"
        assert canonical("进出口总额")[0] == "进出口"
        assert canonical("居民消费价格指数")[0] == "价格指数"
        assert canonical("人均可支配收入")[0] == "全体居民人均可支配收入"
        assert canonical("第一产业")[0] == "第一产业增加值"
        assert canonical("房地产投资")[0] == "房地产开发投资"
        assert canonical("贷款余额")[0] == "金融机构本外币贷款余额"

    def test_wrapped_remap(self):
        assert canonical("全市居民人均可支配收入")[0] == "全体居民人均可支配收入"
        assert canonical("分县（市、区）社会消费品零售总额")[0] == "社会消费品零售总额"
        assert canonical("一般公共预算支出排名")[0] == "一般公共预算支出"

    def test_typo_remap(self):
        assert canonical("共财政预算支出")[0] == "一般公共预算支出"
        assert canonical("方财政收入")[0] == "一般公共预算收入"

    def test_noise_sentence(self):
        assert canonical("他有印度占")[0] == ""
        assert canonical("占财政总收入的")[0] == ""
        assert canonical("人口达到")[0] == ""

    def test_deverb_remap(self):
        assert canonical("全区完成财政总收入")[0] == "财政总收入"
        assert canonical("企业实现主营业务收入")[0] == "规模工业营业收入"
        assert canonical("全县完成工业增加值")[0] == "规模工业增加值"

    def test_official_dict_sane(self):
        # 官方词典不包含噪音词
        for canon, meta in OFFICIAL_DICT.items():
            assert canon
            assert isinstance(meta.get("aliases"), list)
