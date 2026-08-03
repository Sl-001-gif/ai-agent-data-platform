<template>
  <div>
    <el-card class="welcome-card" shadow="never">
      <h2>管理员，欢迎进入数据分析工作台</h2>
      <p style="color: #606266; margin: 8px 0 4px;">
        在这里配置数据集、字段语义、指标口径和 Prompt 模板，让 Agent 能够把业务问题拆解成可执行的数据分析过程
      </p>
      <el-tag type="success" size="small" style="margin-top: 8px;">当前阶段：基础能力</el-tag>
    </el-card>
    <el-row :gutter="16" style="margin-top: 16px;">
      <el-col :span="8">
        <el-card shadow="hover" @click="router.push('/dataset')" style="cursor: pointer;">
          <h3>数据集管理</h3>
          <p style="color: #909399;">维护可分析的业务数据范围</p>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" @click="router.push('/field-semantic')" style="cursor: pointer;">
          <h3>字段语义</h3>
          <p style="color: #909399;">补充字段中文名、类型和业务含义</p>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" @click="router.push('/agent-plan')" style="cursor: pointer;">
          <h3>Agent 分析</h3>
          <p style="color: #909399;">识别意图、生成计划、查询数据并输出报告</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { useRouter } from "vue-router";

const router = useRouter();
</script>