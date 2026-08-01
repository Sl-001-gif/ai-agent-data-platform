package com.aiagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 数据源新增/更新/连接测试请求。 */
@Data
public class DataSourceRequest {

    @NotBlank(message = "数据源名称不能为空")
    private String name;

    private String dbType = "MYSQL";

    @NotBlank(message = "主机地址不能为空")
    private String host;

    @NotNull(message = "端口不能为空")
    @Min(value = 1, message = "端口必须在 1-65535 之间")
    @Max(value = 65535, message = "端口必须在 1-65535 之间")
    private Integer port;

    @NotBlank(message = "数据库名不能为空")
    private String databaseName;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String remark;
}