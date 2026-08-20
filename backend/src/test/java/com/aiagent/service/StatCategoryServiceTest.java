package com.aiagent.service;

import com.aiagent.entity.StatCategory;
import com.aiagent.mapper.StatCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1：统计指标分类树服务——三级树组装 / 新增校验（编码、层级、父级、同级重名、sort 递增）/ 更新 / 删除子节点拦截。 */
class StatCategoryServiceTest {

    private StatCategoryMapper mapper;
    private StatCategoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(StatCategoryMapper.class);
        service = new StatCategoryService(mapper);
        when(mapper.insert(any(StatCategory.class))).thenAnswer(inv -> {
            StatCategory node = inv.getArgument(0);
            node.setId(100L);
            return 1;
        });
    }

    private StatCategory node(Long id, Long parentId, String name, String code, int level) {
        StatCategory n = new StatCategory();
        n.setId(id);
        n.setParentId(parentId);
        n.setName(name);
        n.setCode(code);
        n.setLevel(level);
        n.setSort(1);
        n.setStatus(1);
        return n;
    }

    @Test
    void tree_shouldAssembleThreeLevelHierarchy() {
        StatCategory root = node(1L, null, "经济核算", "c01", 1);
        StatCategory mid = node(10L, 1L, "地区生产总值", "c01_01", 2);
        StatCategory leaf = node(100L, 10L, "地区生产总值（GDP）", "010101", 3);
        when(mapper.selectTree()).thenReturn(Arrays.asList(leaf, mid, root));

        var roots = service.tree();

        assertEquals(1, roots.size());
        assertEquals("经济核算", roots.get(0).getName());
        assertEquals(1, roots.get(0).getChildren().size());
        assertEquals("地区生产总值", roots.get(0).getChildren().get(0).getName());
        assertEquals(1, roots.get(0).getChildren().get(0).getChildren().size());
        assertEquals("地区生产总值（GDP）", roots.get(0).getChildren().get(0).getChildren().get(0).getName());
    }

    @Test
    void create_shouldRejectBlankNameOrCode() {
        StatCategory node = node(null, null, "  ", "c01", 1);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
        node.setName("经济核算");
        node.setCode("");
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
    }

    @Test
    void create_shouldRejectDuplicateCode() {
        when(mapper.selectByCode("c01")).thenReturn(node(1L, null, "已有", "c01", 1));
        StatCategory node = node(null, null, "经济核算", "c01", 1);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
    }

    @Test
    void create_shouldRejectLevelOutOfRange() {
        StatCategory node = node(null, null, "经济核算", "c01", 0);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
        node.setLevel(4);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
    }

    @Test
    void create_shouldRejectMissingOrWrongLevelParent() {
        when(mapper.selectById(9L)).thenReturn(null);
        StatCategory node = node(null, 9L, "地区生产总值", "c01_01", 2);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));

        when(mapper.selectById(9L)).thenReturn(node(9L, null, "经济核算", "c01", 1));
        StatCategory wrongLevel = node(null, 9L, "地区生产总值", "c01_01", 3);
        assertThrows(IllegalArgumentException.class, () -> service.create(wrongLevel));
    }

    @Test
    void create_shouldRejectTopLevelWithNonOneLevel() {
        StatCategory node = node(null, null, "经济核算", "c01", 2);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
    }

    @Test
    void create_shouldRejectSameNameUnderSameParent() {
        when(mapper.selectByNameAndParent("地区生产总值", 1L)).thenReturn(node(10L, 1L, "地区生产总值", "c01_01", 2));
        StatCategory node = node(null, 1L, "地区生产总值", "c01_02", 2);
        assertThrows(IllegalArgumentException.class, () -> service.create(node));
    }

    @Test
    void create_shouldDefaultSortToMaxPlusOneAndInsert() {
        when(mapper.selectById(1L)).thenReturn(node(1L, null, "经济核算", "c01", 1));
        when(mapper.maxSort(1L)).thenReturn(3);
        StatCategory node = node(null, 1L, "地区生产总值", "c01_02", 2);
        node.setSort(null);
        node.setStatus(null);

        StatCategory saved = service.create(node);

        assertEquals(4, saved.getSort());
        assertEquals(1, saved.getStatus());
        assertEquals(2, saved.getLevel());
        verify(mapper).insert(node);
    }

    @Test
    void update_shouldRejectMissingNodeAndForeignCode() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, node(null, null, "经济核算", "c01", 1)));

        StatCategory existing = node(1L, null, "经济核算", "c01", 1);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectByCode("c02")).thenReturn(node(2L, null, "工业经济", "c02", 1));
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, node(null, null, "经济核算", "c02", 1)));
    }

    @Test
    void update_shouldKeepParentAndMergeEditableFields() {
        StatCategory existing = node(1L, null, "经济核算", "c01", 1);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectByCode("c01")).thenReturn(existing);
        when(mapper.selectByNameAndParent("经济核算", null)).thenReturn(existing);

        StatCategory patch = new StatCategory();
        patch.setName("国民经济核算");
        patch.setSort(9);
        patch.setColor("#ff0000");
        service.update(1L, patch);

        assertEquals("国民经济核算", existing.getName());
        assertEquals("c01", existing.getCode());
        assertEquals(9, existing.getSort());
        assertEquals("#ff0000", existing.getColor());
        assertEquals(1, existing.getLevel());
        assertEquals(null, existing.getParentId());
        verify(mapper).update(existing);
    }

    @Test
    void delete_shouldRejectWhenChildrenExist() {
        when(mapper.selectById(1L)).thenReturn(node(1L, null, "经济核算", "c01", 1));
        when(mapper.countChildren(1L)).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> service.delete(1L));
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    void delete_shouldRemoveLeafNode() {
        when(mapper.selectById(1L)).thenReturn(node(1L, null, "经济核算", "c01", 1));
        when(mapper.countChildren(1L)).thenReturn(0);
        service.delete(1L);
        verify(mapper).delete(1L);
    }
}
