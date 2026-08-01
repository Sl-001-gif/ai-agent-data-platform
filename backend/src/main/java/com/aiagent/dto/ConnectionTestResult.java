package com.aiagent.dto;

/** 数据源连接测试结果（失败返回原因，不抛错）。 */
public record ConnectionTestResult(boolean success, String message, Long latencyMs) {
}