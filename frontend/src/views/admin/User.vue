<template>
  <div>
    <el-card class="mb-16">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名 / 昵称"
          clearable
          style="width: 240px"
          @keyup.enter="load"
        />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
        <div style="flex: 1"></div>
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增用户</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="id" label="账号ID" width="92">
          <template #default="{ row }">
            <span class="acct-id">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
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
      :title="dialog.id ? '编辑用户' : '新增用户'"
      width="540px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!dialog.id" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="dialog.id ? '留空则不修改' : '请输入密码'"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple placeholder="请选择角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
          <div class="role-hint">
            <template v-if="dialog.id">编辑不改账号号</template>
            <template v-else>
              新增时按所选角色自动分配 6 位账号ID：含管理员→13xxxx、否则含客服→12xxxx、否则用户→11xxxx
            </template>
          </div>
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
import { pageUsers, createUser, updateUser, deleteUser, listRoles } from '@/api/admin'
import type { SysUser, SysRole } from '@/types'

const loading = ref(false)
const records = ref<SysUser[]>([])
const total = ref(0)
const roles = ref<SysRole[]>([])

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ''
})

async function load() {
  loading.value = true
  try {
    const res = await pageUsers({
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

async function loadRoles() {
  roles.value = await listRoles()
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
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  phone: '',
  roleIds: [] as number[],
  status: 1
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }]
}

function openDialog(row?: SysUser) {
  dialog.id = row?.id ?? null
  form.username = row?.username ?? ''
  form.password = ''
  form.nickname = row?.nickname ?? ''
  form.email = row?.email ?? ''
  form.phone = row?.phone ?? ''
  form.roleIds = []
  form.status = row?.status ?? 1
  dialog.visible = true
}

async function save() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    dialog.saving = true
    try {
      const payload: Record<string, unknown> = {
        username: form.username,
        nickname: form.nickname,
        email: form.email,
        phone: form.phone,
        status: form.status,
        roleIds: form.roleIds
      }
      if (dialog.id) {
        if (form.password) payload.password = form.password
        await updateUser(dialog.id, payload)
        ElMessage.success('更新成功')
      } else {
        payload.password = form.password || '123456'
        await createUser(payload)
        ElMessage.success('新增成功')
      }
      dialog.visible = false
      load()
    } finally {
      dialog.saving = false
    }
  })
}

async function onDelete(row: SysUser) {
  await ElMessageBox.confirm(`确认删除用户「${row.username}」？`, '提示', { type: 'warning' })
  await deleteUser(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(() => {
  load()
  loadRoles()
})
</script>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.acct-id {
  font-family: var(--el-font-family-mono, Consolas, monospace);
  font-weight: 600;
}

.role-hint {
  width: 100%;
  font-size: 12px;
  color: #a0a6b4;
  line-height: 1.5;
}
</style>
