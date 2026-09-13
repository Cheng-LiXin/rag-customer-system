<template>
  <div class="login-page">
    <div class="login-box">
      <!-- 左侧品牌区 -->
      <div class="brand">
        <div class="brand-logo">🎓</div>
        <h1 class="brand-title">燕山大学智能客服</h1>
        <p class="brand-sub">基于 RAG 的智能客服系统</p>
        <ul class="brand-features">
          <li><span>📚</span>知识库驱动的精准问答</li>
          <li><span>⚡</span>流式响应，逐字秒级反馈</li>
          <li><span>🎯</span>意图识别，引用溯源</li>
          <li><span>👩‍💼</span>一键转接人工客服</li>
        </ul>
      </div>

      <!-- 右侧表单区 -->
      <div class="form-panel">
        <h2 class="form-title">欢迎登录</h2>
        <p class="form-sub">请使用您的账号登录系统</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          @keyup.enter="onSubmit"
        >
          <el-form-item prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              :prefix-icon="User"
              clearable
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-button
            type="primary"
            class="submit-btn"
            :loading="loading"
            @click="onSubmit"
          >
            登 录
          </el-button>
        </el-form>

        <div class="login-tip">
          <span>还没有账号？</span>
          <el-link type="primary" :underline="false" @click="router.push('/register')">
            立即注册
          </el-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}



function fill(acc: { username: string; password: string }) {
  form.username = acc.username
  form.password = acc.password
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await auth.login(form.username, form.password)
      ElMessage.success('登录成功')
      const redirect = route.query.redirect as string | undefined
      if (redirect) {
        router.push(redirect)
      } else if (auth.isAdmin) {
        router.push('/admin/statistics')
      } else if (auth.isAgent) {
        router.push('/agent')
      } else {
        router.push('/chat')
      }
    } catch {
      // 错误提示已在拦截器中处理
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-gradient);
  padding: 24px;
  position: relative;
  overflow: hidden;
}

.login-page::before {
  content: '';
  position: absolute;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  top: -180px;
  right: -120px;
}

.login-page::after {
  content: '';
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
  bottom: -160px;
  left: -100px;
}

.login-box {
  position: relative;
  z-index: 1;
  display: flex;
  width: 900px;
  max-width: 100%;
  min-height: 520px;
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(31, 45, 110, 0.28);
}

.brand {
  flex: 1;
  padding: 56px 44px;
  background: linear-gradient(160deg, #4c6fff 0%, #6a4df0 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
}

.brand-logo {
  font-size: 52px;
  line-height: 1;
  margin-bottom: 28px;
}

.brand-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 10px;
  letter-spacing: 1px;
}

.brand-sub {
  font-size: 15px;
  opacity: 0.85;
  margin: 0 0 36px;
}

.brand-features {
  list-style: none;
  padding: 0;
  margin: auto 0 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.brand-features li {
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 10px;
  opacity: 0.95;
}

.brand-features li span {
  font-size: 18px;
}

.form-panel {
  flex: 1;
  padding: 56px 48px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-title {
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 6px;
}

.form-sub {
  color: var(--text-sub);
  font-size: 14px;
  margin: 0 0 32px;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 16px;
  letter-spacing: 6px;
  border-radius: 10px;
}

.login-tip {
  margin-top: 18px;
  text-align: center;
  font-size: 14px;
  color: var(--text-sub);
}

.demo-accounts {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px dashed #e5e8f0;
}

.demo-title {
  font-size: 13px;
  color: var(--text-sub);
  margin-bottom: 12px;
}

.demo-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.demo-tag {
  cursor: pointer;
  width: fit-content;
  border-radius: 8px;
}

.demo-tag:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

@media (max-width: 760px) {
  .brand {
    display: none;
  }
  .login-box {
    width: 420px;
  }
}
</style>
