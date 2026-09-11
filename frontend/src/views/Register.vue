<template>
  <div class="register-page">
    <div class="register-box">
      <!-- 左侧品牌区 -->
      <div class="brand">
        <div class="brand-logo">🎓</div>
        <h1 class="brand-title">加入燕山大学智能客服</h1>
        <p class="brand-sub">注册手机号账号，随时随地提问</p>
        <ul class="brand-features">
          <li><span>📱</span>手机号即账号，无需短信验证</li>
          <li><span>🔐</span>密码加密存储，小眼睛可显隐</li>
          <li><span>🎯</span>问答溯源，转接人工客服</li>
          <li><span>🪪</span>注册自动分配 6 位账号ID（11 开头）</li>
        </ul>
      </div>

      <!-- 右侧表单区 -->
      <div class="form-panel">
        <h2 class="form-title">注册新账号</h2>
        <p class="form-sub">使用手机号注册普通用户账号</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          @keyup.enter="onSubmit"
        >
          <el-form-item prop="phone">
            <el-input
              v-model="form.phone"
              placeholder="请输入手机号（将作为登录账号）"
              :prefix-icon="Iphone"
              clearable
              maxlength="11"
            />
          </el-form-item>
          <el-form-item prop="nickname">
            <el-input
              v-model="form.nickname"
              placeholder="昵称（选填，默认 用户+尾号）"
              :prefix-icon="User"
              clearable
              maxlength="20"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="设置密码（6-20位）"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入密码"
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
            注 册
          </el-button>
        </el-form>

        <div class="register-tip">
          <span>已有账号？</span>
          <el-link type="primary" :underline="false" @click="router.push('/login')">
            立即登录
          </el-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock, Iphone } from '@element-plus/icons-vue'
import { register } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  phone: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})

const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的11位手机号',
      trigger: 'blur'
    }
  ],
  nickname: [{ max: 20, message: '昵称不能超过20个字符', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度须在6-20位之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (!value) callback(new Error('请再次输入密码'))
        else if (value !== form.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await register({
      phone: form.phone,
      nickname: form.nickname.trim() || undefined,
      password: form.password,
      confirmPassword: form.confirmPassword
    })
    ElMessage.success(`注册成功，账号ID ${res.id}`)
    // 注册成功后自动登录并进入对话
    await auth.login(form.phone, form.password)
    router.push('/chat')
  } catch {
    // 手机号已注册/违禁词等错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-gradient);
  padding: 24px;
  position: relative;
  overflow: hidden;
}

.register-page::before {
  content: '';
  position: absolute;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  top: -180px;
  right: -120px;
}

.register-page::after {
  content: '';
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
  bottom: -160px;
  left: -100px;
}

.register-box {
  position: relative;
  z-index: 1;
  display: flex;
  width: 900px;
  max-width: 100%;
  min-height: 560px;
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
  font-size: 25px;
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
  margin: 0 0 28px;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 16px;
  letter-spacing: 6px;
  border-radius: 10px;
}

.register-tip {
  margin-top: 26px;
  text-align: center;
  font-size: 14px;
  color: var(--text-sub);
}

@media (max-width: 760px) {
  .brand {
    display: none;
  }
  .register-box {
    width: 420px;
  }
}
</style>
