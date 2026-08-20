package com.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 通用分页结果：列表行 + 总数（服务端分页，LIMIT + COUNT）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> rows;
    private long total;

    public static <T> PageResult<T> of(List<T> rows, long total) {
        return new PageResult<>(rows, total);
    }
}
