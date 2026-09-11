<template>
  <div>
    <el-tabs v-model="activeTab">
      <!-- ============ 知识片段 ============ -->
      <el-tab-pane label="知识片段" name="chunk">
        <el-card class="mb-16">
          <div class="toolbar">
            <el-input
              v-model="chunkQuery.keyword"
              placeholder="搜索标题 / 关键词"
              clearable
              style="width: 220px"
              @keyup.enter="loadChunks"
            />
            <el-select
              v-model="chunkQuery.categoryId"
              placeholder="全部分类"
              clearable
              style="width: 160px"
              @change="loadChunks"
            >
              <el-option
                v-for="c in categories"
                :key="c.id"
                :label="c.name"
                :value="c.id"
              />
            </el-select>
            <el-select
              v-model="chunkQuery.status"
              placeholder="全部状态"
              clearable
              style="width: 140px"
              @change="loadChunks"
            >
              <el-option label="启用" :value="1" />
              <el-option label="禁用" :value="0" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="loadChunks">查询</el-button>
            <el-button :icon="Refresh" @click="resetChunkQuery">重置</el-button>
            <div style="flex: 1"></div>
            <el-button :icon="Download" @click="onExport">导出</el-button>
            <el-upload :show-file-list="false" accept=".csv" :before-upload="onImport">
              <el-button :icon="Upload">导入 CSV</el-button>
            </el-upload>
            <el-upload
              :show-file-list="false"
              accept=".txt,.md,.doc,.docx,.pdf"
              :before-upload="onDocUpload"
            >
              <el-button :icon="Document" type="primary" plain>上传文档</el-button>
            </el-upload>
            <el-button type="primary" :icon="Plus" @click="openChunkDialog()">新增片段</el-button>
          </div>
        </el-card>

        <el-card>
          <el-table :data="chunkRecords" v-loading="chunkLoading" stripe>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column label="分类" width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ categoryName(row.categoryId) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="向量化" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="vectorTagType(row.vectorStatus)">
                  {{ vectorLabel(row.vectorStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="hitCount" label="命中" width="80" align="center" />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="170" />
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openChunkDialog(row)">编辑</el-button>
                <el-button link type="warning" @click="onReindex(row)">重建向量</el-button>
                <el-button link type="danger" @click="onDeleteChunk(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            background
            layout="total, prev, pager, next"
            :total="chunkTotal"
            :page-size="chunkQuery.pageSize"
            :current-page="chunkQuery.pageNum"
            @current-change="onChunkPageChange"
          />
        </el-card>
      </el-tab-pane>

      <!-- ============ 分类管理 ============ -->
      <el-tab-pane label="分类管理" name="category">
        <el-card>
          <div class="toolbar mb-16">
            <div style="flex: 1"></div>
            <el-button type="primary" :icon="Plus" @click="openCategoryDialog()">新增分类</el-button>
          </div>
          <el-table :data="categories" v-loading="categoryLoading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="name" label="分类名称" min-width="160" />
            <el-table-column prop="parentId" label="父分类ID" width="100" align="center" />
            <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
            <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openCategoryDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="onDeleteCategory(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 片段编辑弹窗 -->
    <el-dialog
      v-model="chunkDialog.visible"
      :title="chunkDialog.id ? '编辑知识片段' : '新增知识片段'"
      width="640px"
      destroy-on-close
    >
      <el-form ref="chunkFormRef" :model="chunkForm" :rules="chunkRules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="chunkForm.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="chunkForm.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input
            v-model="chunkForm.content"
            type="textarea"
            :rows="6"
            placeholder="请输入知识片段内容"
          />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="chunkForm.keywords" placeholder="逗号分隔，如：招生,录取分数线" />
        </el-form-item>
        <el-form-item label="来源链接">
          <el-input v-model="chunkForm.sourceUrl" placeholder="https://..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chunkDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="chunkDialog.saving" @click="saveChunk">保存</el-button>
      </template>
    </el-dialog>

    <!-- 上传文档导入弹窗（解析 → 预览确认 → 入库） -->
    <el-dialog
      v-model="docDialog.visible"
      title="上传文档导入"
      width="880px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-loading="docDialog.parsing">
        <div class="doc-summary">
          <span class="doc-file">📄 {{ docDialog.fileName }}</span>
          <el-tag size="small" type="success">切块 {{ docDialog.chunks.length }} 条</el-tag>
          <el-tag size="small" type="info">共 {{ docCharsTotal }} 字</el-tag>
          <span class="doc-hint">标题已按各段内容自动总结，可直接修改；点正文可查看/编辑全文以删除噪音</span>
        </div>

        <el-form label-width="86px" class="doc-form">
          <el-form-item label="归属分类" required>
            <el-select
              v-model="docDialog.categoryId"
              placeholder="请选择分类"
              style="width: 280px"
            >
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="源标题">
            <el-input
              v-model="docDialog.sourceTitle"
              placeholder="默认取文件名，用于热门知识按源文聚合"
              style="width: 460px"
            />
          </el-form-item>
          <el-form-item label="来源链接">
            <el-input
              v-model="docDialog.sourceUrl"
              placeholder="可选，如 https://www.ysu.edu.cn/info/xxx.htm"
              style="width: 460px"
            />
          </el-form-item>
        </el-form>

        <el-table
          ref="docTableRef"
          :data="docDialog.chunks"
          size="small"
          max-height="340"
          @selection-change="onDocSelectionChange"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column label="标题（可修改）" width="210">
            <template #default="{ row }">
              <el-input
                v-model="row.title"
                size="small"
                placeholder="分段标题"
                maxlength="60"
                @click.stop
              />
            </template>
          </el-table-column>
          <el-table-column label="字数" width="62" align="center">
            <template #default="{ row }">{{ row.content.length }}</template>
          </el-table-column>
          <el-table-column label="内容（点开可删噪音）" min-width="320">
            <template #default="{ row }">
              <div class="doc-preview doc-clickable" @click="openDocContentEdit(row)">
                {{ row.content }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDocContentEdit(row)">
                查看/编辑全文
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <div class="doc-footer">
          <el-button link type="primary" @click="toggleDocSelection">
            {{ allDocSelected ? '取消全选' : '全选' }}
          </el-button>
          <div style="flex: 1"></div>
          <el-button @click="docDialog.visible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="docDialog.importing"
            :disabled="docSelected.length === 0"
            @click="confirmDocImport"
          >
            确认导入 {{ docSelected.length }} 条
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 分段全文查看/编辑（删除内容中的噪音） -->
    <el-dialog
      v-model="docEdit.visible"
      title="编辑分段内容"
      width="680px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="70px">
        <el-form-item label="标题">
          <el-input v-model="docEdit.title" placeholder="分段标题" maxlength="60" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="docEdit.draft"
            type="textarea"
            :rows="14"
            placeholder="可在此删除明显噪音（页眉页脚、落款、无关说明等）后保存"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="docEdit.visible = false">取消</el-button>
        <el-button type="primary" @click="saveDocContentEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分类编辑弹窗 -->
    <el-dialog
      v-model="categoryDialog.visible"
      :title="categoryDialog.id ? '编辑分类' : '新增分类'"
      width="480px"
      destroy-on-close
    >
      <el-form ref="categoryFormRef" :model="categoryForm" :rules="categoryRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="categoryForm.name" placeholder="请输入分类名称" />
        </el-form-item>
        <el-form-item label="父分类ID">
          <el-input-number v-model="categoryForm.parentId" :min="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="categoryForm.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="categoryForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="categoryDialog.saving" @click="saveCategory">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type TableInstance
} from 'element-plus'
import { Plus, Search, Refresh, Upload, Download, Document } from '@element-plus/icons-vue'
import {
  pageChunks,
  addChunk,
  updateChunk,
  deleteChunk,
  reindexChunk,
  listCategories,
  addCategory,
  updateCategory,
  deleteCategory,
  exportUrl,
  importChunks,
  parseDocument,
  importDocumentChunks
} from '@/api/knowledge'
import type { KnowledgeChunk, KnowledgeCategory, ParsedChunk } from '@/types'

const activeTab = ref('chunk')

// ==================== 分类数据 ====================
const categories = ref<KnowledgeCategory[]>([])
const categoryLoading = ref(false)

async function loadCategories() {
  categoryLoading.value = true
  try {
    categories.value = await listCategories()
  } finally {
    categoryLoading.value = false
  }
}

function categoryName(id: number) {
  return categories.value.find((c) => c.id === id)?.name || '-'
}

// ==================== 知识片段 ====================
const chunkLoading = ref(false)
const chunkRecords = ref<KnowledgeChunk[]>([])
const chunkTotal = ref(0)
const chunkQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  categoryId: undefined as number | undefined,
  status: undefined as number | undefined
})

async function loadChunks() {
  chunkLoading.value = true
  try {
    const res = await pageChunks({
      pageNum: chunkQuery.pageNum,
      pageSize: chunkQuery.pageSize,
      keyword: chunkQuery.keyword || undefined,
      categoryId: chunkQuery.categoryId,
      status: chunkQuery.status
    })
    chunkRecords.value = res.records
    chunkTotal.value = res.total
  } finally {
    chunkLoading.value = false
  }
}

function resetChunkQuery() {
  chunkQuery.keyword = ''
  chunkQuery.categoryId = undefined
  chunkQuery.status = undefined
  chunkQuery.pageNum = 1
  loadChunks()
}

function onChunkPageChange(p: number) {
  chunkQuery.pageNum = p
  loadChunks()
}

function vectorLabel(s?: number) {
  if (s === 1) return '已向量化'
  if (s === 2) return '失败'
  return '待向量化'
}

function vectorTagType(s?: number) {
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  return 'info'
}

// 片段弹窗
const chunkFormRef = ref<FormInstance>()
const chunkDialog = reactive({ visible: false, id: null as number | null, saving: false })
const chunkForm = reactive({
  title: '',
  categoryId: undefined as number | undefined,
  content: '',
  keywords: '',
  sourceUrl: ''
})

const chunkRules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }]
}

function openChunkDialog(row?: KnowledgeChunk) {
  chunkDialog.id = row?.id ?? null
  chunkForm.title = row?.title ?? ''
  chunkForm.categoryId = row?.categoryId ?? undefined
  chunkForm.content = row?.content ?? ''
  chunkForm.keywords = row?.keywords ?? ''
  chunkForm.sourceUrl = row?.sourceUrl ?? ''
  chunkDialog.visible = true
}

async function saveChunk() {
  if (!chunkFormRef.value) return
  await chunkFormRef.value.validate(async (valid) => {
    if (!valid) return
    chunkDialog.saving = true
    try {
      const payload = { ...chunkForm }
      if (chunkDialog.id) {
        await updateChunk(chunkDialog.id, payload)
        ElMessage.success('更新成功')
      } else {
        await addChunk(payload)
        ElMessage.success('新增成功，已自动向量化')
      }
      chunkDialog.visible = false
      loadChunks()
    } finally {
      chunkDialog.saving = false
    }
  })
}

async function onReindex(row: KnowledgeChunk) {
  await reindexChunk(row.id)
  ElMessage.success('重建向量已提交')
  loadChunks()
}

async function onDeleteChunk(row: KnowledgeChunk) {
  await ElMessageBox.confirm(`确认删除「${row.title}」？`, '提示', { type: 'warning' })
  await deleteChunk(row.id)
  ElMessage.success('删除成功')
  loadChunks()
}

async function onExport() {
  try {
    const resp = await fetch(exportUrl, {
      headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
    })
    if (!resp.ok) {
      ElMessage.error('导出失败')
      return
    }
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'knowledge_chunks.csv'
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } catch {
    ElMessage.error('导出失败')
  }
}

async function onImport(file: File) {
  try {
    const res = await importChunks(file)
    ElMessage.success(`导入完成：成功 ${res.success} 条，失败 ${res.fail} 条`)
    if (res.fail > 0 && res.errors?.length) {
      ElMessageBox.alert(res.errors.join('\n'), '导入失败明细', { type: 'warning' })
    }
    loadChunks()
  } catch {
    ElMessage.error('导入失败')
  }
  return false
}

// ==================== 上传文档导入（解析 → 预览确认 → 入库） ====================
const docTableRef = ref<TableInstance>()
const docSelected = ref<ParsedChunk[]>([])
const docDialog = reactive({
  visible: false,
  parsing: false,
  importing: false,
  fileName: '',
  totalChars: 0,
  categoryId: undefined as number | undefined,
  sourceTitle: '',
  sourceUrl: '',
  chunks: [] as ParsedChunk[]
})

/** 当前所有分段内容的总字数（随编辑实时变化） */
const docCharsTotal = computed(() =>
  docDialog.chunks.reduce((sum, c) => sum + (c.content ? c.content.length : 0), 0)
)

const allDocSelected = computed(
  () => docDialog.chunks.length > 0 && docSelected.value.length === docDialog.chunks.length
)

// 分段全文查看/编辑
const docEdit = reactive({
  visible: false,
  index: -1,
  title: '',
  draft: ''
})

function openDocContentEdit(row: ParsedChunk) {
  const idx = docDialog.chunks.indexOf(row)
  if (idx < 0) return
  docEdit.index = idx
  docEdit.title = docDialog.chunks[idx].title
  docEdit.draft = docDialog.chunks[idx].content
  docEdit.visible = true
}

function saveDocContentEdit() {
  if (docEdit.index < 0) return
  const row = docDialog.chunks[docEdit.index]
  row.title = docEdit.title.trim()
  row.content = docEdit.draft
  row.charCount = docEdit.draft.length
  docEdit.visible = false
}

/** 选中文件 → 后端解析切块并打开预览（返回 false 阻止 el-upload 自动上传） */
async function onDocUpload(file: File) {
  if (!/\.(txt|md|markdown|doc|docx|pdf)$/i.test(file.name)) {
    ElMessage.error('仅支持 .txt / .md / .doc / .docx / .pdf 文件')
    return false
  }
  docDialog.visible = true
  docDialog.parsing = true
  docDialog.importing = false
  docDialog.fileName = file.name
  docDialog.sourceTitle = file.name.replace(/\.[^.]+$/, '')
  docDialog.sourceUrl = ''
  docDialog.categoryId = undefined
  docDialog.totalChars = 0
  docDialog.chunks = []
  docSelected.value = []
  try {
    const res = await parseDocument(file)
    docDialog.fileName = res.fileName || file.name
    docDialog.chunks = res.chunks || []
    docDialog.totalChars = res.totalChars || 0
    await nextTick()
    docTableRef.value?.toggleAllSelection()
    if (!docDialog.chunks.length) {
      ElMessage.warning('未解析出有效内容，请检查文档是否为空或格式异常')
    }
  } catch {
    docDialog.visible = false // 错误提示由 http 拦截器统一弹出
  } finally {
    docDialog.parsing = false
  }
  return false
}

function onDocSelectionChange(rows: ParsedChunk[]) {
  docSelected.value = rows
}

function toggleDocSelection() {
  docTableRef.value?.toggleAllSelection()
}

async function confirmDocImport() {
  if (!docDialog.categoryId) {
    ElMessage.warning('请先选择归属分类')
    return
  }
  if (!docSelected.value.length) return
  docDialog.importing = true
  try {
    const res = await importDocumentChunks({
      categoryId: docDialog.categoryId,
      sourceTitle: docDialog.sourceTitle || docDialog.fileName,
      sourceUrl: docDialog.sourceUrl || undefined,
      chunks: docSelected.value.map((c) => ({ title: c.title, content: c.content }))
    })
    ElMessage.success(`导入完成：成功 ${res.success} 条，失败 ${res.fail} 条`)
    if (res.vectorFail > 0) {
      ElMessageBox.alert(
        `${res.vectorFail} 条片段向量化失败，可在列表中点击「重建向量」重试`,
        '向量化提示',
        { type: 'warning' }
      )
    }
    if (res.fail > 0 && res.errors?.length) {
      ElMessageBox.alert(res.errors.join('\n'), '导入失败明细', { type: 'warning' })
    }
    docDialog.visible = false
    chunkQuery.pageNum = 1
    loadChunks()
  } finally {
    docDialog.importing = false
  }
}

// ==================== 分类管理 ====================
const categoryFormRef = ref<FormInstance>()
const categoryDialog = reactive({ visible: false, id: null as number | null, saving: false })
const categoryForm = reactive({
  name: '',
  parentId: 0,
  sortOrder: 0,
  description: ''
})

const categoryRules: FormRules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

function openCategoryDialog(row?: KnowledgeCategory) {
  categoryDialog.id = row?.id ?? null
  categoryForm.name = row?.name ?? ''
  categoryForm.parentId = row?.parentId ?? 0
  categoryForm.sortOrder = row?.sortOrder ?? 0
  categoryForm.description = row?.description ?? ''
  categoryDialog.visible = true
}

async function saveCategory() {
  if (!categoryFormRef.value) return
  await categoryFormRef.value.validate(async (valid) => {
    if (!valid) return
    categoryDialog.saving = true
    try {
      const payload = { ...categoryForm }
      if (categoryDialog.id) {
        await updateCategory(categoryDialog.id, payload)
        ElMessage.success('更新成功')
      } else {
        await addCategory(payload)
        ElMessage.success('新增成功')
      }
      categoryDialog.visible = false
      loadCategories()
    } finally {
      categoryDialog.saving = false
    }
  })
}

async function onDeleteCategory(row: KnowledgeCategory) {
  await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, '提示', { type: 'warning' })
  await deleteCategory(row.id)
  ElMessage.success('删除成功')
  loadCategories()
}

onMounted(() => {
  loadCategories()
  loadChunks()
})
</script>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

/* ===== 上传文档导入弹窗 ===== */
.doc-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.doc-file {
  font-weight: 600;
  color: #303133;
}

.doc-hint {
  color: #909399;
  font-size: 12px;
}

.doc-form {
  margin-bottom: 4px;
}

.doc-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.doc-preview {
  color: #606266;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 60px;
  overflow: hidden;
}

.doc-clickable {
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 4px;
  transition: background 0.15s ease;
}

.doc-clickable:hover {
  background: #eef1ff;
  color: #4c6fff;
}

.doc-footer {
  display: flex;
  align-items: center;
  width: 100%;
}
</style>
