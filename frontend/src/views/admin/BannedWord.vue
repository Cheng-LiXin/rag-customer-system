<template>
  <div>
    <el-card class="mb-16">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索违禁词"
          clearable
          style="width: 240px"
          @keyup.enter="load"
        />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
        <div style="flex: 1"></div>
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增违禁词</el-button>
      </div>
    </el-card>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="词表用于屏蔽昵称 / 邮箱等个人资料的提交：命中即被后端拒绝。修改后立即生效，无需重启。"
      class="mb-16"
    />

    <el-card>
      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="word" label="违禁词" min-width="160">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">{{ row.word }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
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

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.id ? '编辑违禁词' : '新增违禁词'"
      width="440px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="违禁词" prop="word">
          <el-input v-model="form.word" placeholder="如：垃圾 / 脏话" maxlength="50" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import type { BannedWord } from '@/types'
import {
  pageBannedWords,
  createBannedWord,
  updateBannedWord,
  deleteBannedWord
} from '@/api/admin'

const loading = ref(false)
const records = ref<BannedWord[]>([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ''
})

async function load() {
  loading.value = true
  try {
    const res = await pageBannedWords({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.pageNum = 1
  load()
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

// 编辑弹窗
const formRef = ref<FormInstance>()
const dialog = reactive({ visible: false, id: null as number | null, saving: false })
const form = reactive({ word: '', status: 1 })

const rules: FormRules = {
  word: [{ required: true, message: '请输入违禁词', trigger: 'blur' }]
}

function openDialog(row?: BannedWord) {
  dialog.id = row?.id ?? null
  form.word = row?.word ?? ''
  form.status = row?.status ?? 1
  dialog.visible = true
}

async function save() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    dialog.saving = true
    try {
      if (dialog.id) {
        await updateBannedWord(dialog.id, {
          word: form.word.trim(),
          status: form.status
        })
        ElMessage.success('更新成功')
      } else {
        await createBannedWord({ word: form.word.trim(), status: form.status })
        ElMessage.success('新增成功')
      }
      dialog.visible = false
      load()
    } finally {
      dialog.saving = false
    }
  })
}

async function onDelete(row: BannedWord) {
  await ElMessageBox.confirm(`确认删除违禁词「${row.word}」？删除后该词将不再被拦截。`, '提示', {
    type: 'warning'
  })
  await deleteBannedWord(row.id)
  ElMessage.success('删除成功')
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
