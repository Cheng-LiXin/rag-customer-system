<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
// 小程序端不能对已打包的模块做动态 import().then（会被内联为同步对象），
// 改静态引入；auth store 模块加载时即从 storage 同步读 token。
import { auth, fetchProfile } from "@/stores/auth";
onLaunch(() => {
  // 启动即拉取一次登录态 profile（token 存在时）
  if (auth.token) fetchProfile();
});
</script>

<style lang="scss">
/* ===== 全局基础样式（uni-app App.vue 样式为全局） ===== */
page {
  background: #f6f7fb;
  color: #2b2f3a;
  font-size: 28rpx;
}

/* 品牌渐变（与 Web 端 --wb-grad 同源） */
.brand-grad {
  background: linear-gradient(135deg, #5b7cfa 0%, #9a5cf5 60%, #d05cf0 100%);
}

.brand-text {
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.grad-btn {
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  color: #fff;
  border: none;
  border-radius: 44rpx;
}

/* 通用卡片 */
.card {
  background: #fff;
  border-radius: 24rpx;
  box-shadow: 0 6rpx 24rpx rgba(90, 110, 200, 0.08);
}

/* 主按钮通配（wechat button 默认样式清除） */
button::after {
  border: none;
}

/* 顶部小胶囊动作按钮 */
.pill-btn {
  padding: 8rpx 24rpx;
  border-radius: 40rpx;
  font-size: 24rpx;
  line-height: 1;
}

.safe-bottom {
  padding-bottom: env(safe-area-inset-bottom);
}
</style>
