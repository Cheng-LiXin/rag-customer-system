<template>
  <div>
    <el-card class="mb-16">
      <div class="toolbar">
        <el-input
          v-model="query.username"
          placeholder="搜索操作用户名"
          clearable
          style="width: 200px"
          @keyup.enter="load"
        />
        <el-select v-model="query.module" placeholder="全部模块" clearable style="width: 160px" @change="load">
          <el-option v-for="m in modules" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户" width="110" show-overflow-tooltip />
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="action" label="操作" min-width="140" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="uri" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">{{ row.costTime != null ? row.costTime + 'ms' : '-' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPageChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { pageLogs, type OperationLog } from '@/api/log'

const modules = ['知识库', '用户管理', '角色管理', '工单管理', '会话', '人工客服']

const loading = ref(false)
const records = ref<OperationLog[]>([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  username: '',
  module: ''
})

async function load() {
  loading.value = true
  try {
    const res = await pageLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      username: query.username || undefined,
      module: query.module || undefined
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.username = ''
  query.module = ''
  query.pageNum = 1
  load()
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

onMounted(load)
</script>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
