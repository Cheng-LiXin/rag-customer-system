<template>
  <view class="page">
    <view class="hero brand-grad">
      <view class="hero-emoji">🎓</view>
      <view class="hero-title">注册账号</view>
      <view class="hero-sub">手机号即登录账号，注册后可与人工客服对话</view>
    </view>

    <view class="form card">
      <view class="field">
        <text class="label">手机号</text>
        <input
          v-model="phone"
          class="input"
          type="number"
          placeholder="请输入 11 位手机号"
          :maxlength="11"
        />
      </view>
      <view class="field">
        <text class="label">昵称（选填）</text>
        <input
          v-model="nickname"
          class="input"
          placeholder="不填则默认「用户+手机尾号」"
          :maxlength="20"
        />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <view class="pwd-row">
          <input
            v-model="password"
            class="input pwd-input"
            placeholder="6-20 位"
            :password="!showPwd"
            :maxlength="20"
          />
          <text class="eye" @click="showPwd = !showPwd">{{ showPwd ? '🙈' : '👁' }}</text>
        </view>
      </view>
      <view class="field">
        <text class="label">确认密码</text>
        <view class="pwd-row">
          <input
            v-model="confirm"
            class="input pwd-input"
            placeholder="再次输入密码"
            :password="!showConfirm"
            :maxlength="20"
            confirm-type="done"
            @confirm="onSubmit"
          />
          <text class="eye" @click="showConfirm = !showConfirm">{{ showConfirm ? '🙈' : '👁' }}</text>
        </view>
      </view>

      <button class="submit grad-btn" :loading="loading" @click="onSubmit">注 册</button>
      <view class="foot" @click="back">
        <text class="link">已有账号？去登录</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { register as apiRegister } from '@/api/auth'
import { login as doLogin } from '@/stores/auth'

const phone = ref('')
const nickname = ref('')
const password = ref('')
const confirm = ref('')
const showPwd = ref(false)
const showConfirm = ref(false)
const loading = ref(false)

function back() {
  uni.navigateBack({
    fail: () => uni.reLaunch({ url: '/pages/login/login' })
  })
}

async function onSubmit() {
  const ph = phone.value.trim()
  const pwd = password.value
  const pwd2 = confirm.value

  if (!/^1[3-9]\d{9}$/.test(ph)) {
    return uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
  }
  if (pwd.length < 6 || pwd.length > 20) {
    return uni.showToast({ title: '密码长度需为 6-20 位', icon: 'none' })
  }
  if (pwd !== pwd2) {
    return uni.showToast({ title: '两次输入的密码不一致', icon: 'none' })
  }

  loading.value = true
  try {
    const data: Record<string, unknown> = {
      phone: ph,
      password: pwd,
      confirmPassword: pwd2
    }
    if (nickname.value.trim()) data.nickname = nickname.value.trim()
    await apiRegister(data)
    // 注册成功自动登录
    await doLogin(ph, pwd)
    uni.showToast({ title: '注册成功', icon: 'success' })
    setTimeout(() => uni.reLaunch({ url: '/pages/index/index' }), 500)
  } catch (e: any) {
    uni.showToast({ title: e?.message || '注册失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(180deg, #eef3ff 0%, #f6f7fb 30%);
  padding: 0 40rpx 60rpx;
  box-sizing: border-box;
}

.hero {
  margin: 0 -40rpx;
  padding: 80rpx 48rpx 110rpx;
  border-radius: 0 0 48rpx 48rpx;
  color: #fff;
}

.hero-emoji {
  font-size: 80rpx;
}

.hero-title {
  margin-top: 16rpx;
  font-size: 42rpx;
  font-weight: 700;
}

.hero-sub {
  margin-top: 10rpx;
  font-size: 26rpx;
  opacity: 0.85;
}

.form {
  margin-top: -60rpx;
  padding: 44rpx 40rpx 36rpx;
  position: relative;
}

.field {
  margin-bottom: 26rpx;
}

.label {
  display: block;
  font-size: 26rpx;
  color: #6b7280;
  margin-bottom: 12rpx;
}

.input {
  width: 100%;
  height: 88rpx;
  background: #f5f7fb;
  border-radius: 16rpx;
  padding: 0 24rpx;
  box-sizing: border-box;
  font-size: 30rpx;
}

.pwd-row {
  position: relative;
}

.pwd-input {
  padding-right: 80rpx;
}

.eye {
  position: absolute;
  right: 24rpx;
  top: 50%;
  transform: translateY(-50%);
  font-size: 32rpx;
}

.submit {
  margin-top: 10rpx;
  height: 92rpx;
  line-height: 92rpx;
  font-size: 32rpx;
  font-weight: 600;
}

.foot {
  margin-top: 26rpx;
  text-align: center;
  font-size: 26rpx;
}

.link {
  color: #5b7cfa;
  font-weight: 600;
}
</style>
