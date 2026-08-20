package com.aiagent.service;

import com.aiagent.entity.StatCategory;
import com.aiagent.mapper.StatCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 统计月报指标分类树管理：三级树组装 + 增删改（code 唯一 / 同级重名 / 删除前子节点校验）。 */
@Service
public class StatCategoryService {

    private static final int MAX_LEVEL = 3;

    private final StatCategoryMapper statCategoryMapper;

    public StatCategoryService(StatCategoryMapper statCategoryMapper) {
        this.statCategoryMapper = statCategoryMapper;
    }

    /** 全量三级树：一级大类为根，parentId 挂载子节点。 */
    public List<StatCategory> tree() {
        List<StatCategory> all = statCategoryMapper.selectTree();
        Map<Long, StatCategory> byId = new HashMap<>();
        for (StatCategory node : all) {
            byId.put(node.getId(), node);
        }
        List<StatCategory> roots = new ArrayList<>();
        for (StatCategory node : all) {
            if (node.getParentId() == null) {
                roots.add(node);
            } else {
                StatCategory parent = byId.get(node.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                }
            }
        }
        return roots;
    }

    /** 新增节点：校验名称/编码/层级/父级关系/同级重名，缺省 sort 取同级最大+1。 */
    public StatCategory create(StatCategory node) {
        requireNotBlank(node.getName(), "节点名称不能为空");
        requireNotBlank(node.getCode(), "节点编码不能为空");
        if (statCategoryMapper.selectByCode(node.getCode()) != null) {
            throw new IllegalArgumentException("节点编码已存在: " + node.getCode());
        }
        int level = node.getLevel() == null ? 1 : node.getLevel();
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("层级仅支持 1-3 级");
        }
        if (node.getParentId() != null) {
            StatCategory parent = statCategoryMapper.selectById(node.getParentId());
            if (parent == null) {
                throw new IllegalArgumentException("父节点不存在");
            }
            if (parent.getLevel() != level - 1) {
                throw new IllegalArgumentException("父节点层级必须为上一级");
            }
        } else if (level != 1) {
            throw new IllegalArgumentException("顶级节点必须为一级");
        }
        if (statCategoryMapper.selectByNameAndParent(node.getName(), node.getParentId()) != null) {
            throw new IllegalArgumentException("同级下已存在同名节点");
        }
        if (node.getSort() == null) {
            node.setSort(statCategoryMapper.maxSort(node.getParentId()) + 1);
        }
        if (node.getStatus() == null) {
            node.setStatus(1);
        }
        if (node.getColor() == null && level == 1) {
            node.setColor("#409eff");
        }
        statCategoryMapper.insert(node);
        return node;
    }

    /** 更新节点：仅允许改名称/编码/排序/颜色/状态，不允许移动父级（保持树稳定）。 */
    public void update(Long id, StatCategory node) {
        StatCategory existing = statCategoryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        requireNotBlank(node.getName(), "节点名称不能为空");
        String code = node.getCode() == null || node.getCode().isBlank() ? existing.getCode() : node.getCode();
        StatCategory sameCode = statCategoryMapper.selectByCode(code);
        if (sameCode != null && !sameCode.getId().equals(id)) {
            throw new IllegalArgumentException("节点编码已存在: " + code);
        }
        StatCategory sameName = statCategoryMapper.selectByNameAndParent(node.getName(), existing.getParentId());
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new IllegalArgumentException("同级下已存在同名节点");
        }
        existing.setName(node.getName());
        existing.setCode(code);
        if (node.getSort() != null) {
            existing.setSort(node.getSort());
        }
        if (node.getColor() != null) {
            existing.setColor(node.getColor());
        }
        if (node.getStatus() != null) {
            existing.setStatus(node.getStatus());
        }
        statCategoryMapper.update(existing);
    }

    /** 删除节点：存在子节点时拒绝（先删叶子）。 */
    public void delete(Long id) {
        StatCategory existing = statCategoryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        if (statCategoryMapper.countChildren(id) > 0) {
            throw new IllegalArgumentException("请先删除子节点后再删除该节点");
        }
        statCategoryMapper.delete(id);
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
