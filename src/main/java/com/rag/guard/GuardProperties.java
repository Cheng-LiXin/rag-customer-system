package com.rag.guard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注入防护配置（rag.security.*）。
 *
 * <p>开关分两层：{@code injection.enabled} 是配置打底的默认值，
 * 运行时可由 Redis 键 {@code rag:guard:enabled} 覆盖（见 {@link GuardSwitchService}），
 * 以便答辩现场「关防护 → 复现漏洞 → 开防护 → 拦截」一气做完，无需重启服务。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.security")
public class GuardProperties {

    private Injection injection = new Injection();

    @Data
    public static class Injection {

        /** 配置打底的默认开关（Redis 运行时开关存在时以 Redis 为准） */
        private boolean enabled = true;

        /** 是否允许运行时切换；false 时忽略 Redis 覆盖，只用上面的配置值 */
        private boolean runtimeSwitch = true;

        /** 输入层动作：block 拦截 | log 只记录不拦截 */
        private String inputLevel = "block";

        /** 上下文层动作（间接注入）：block 拦截 | log 只记录 */
        private String contextLevel = "block";

        /** 输出层动作（承诺检测）：block 拦截 | log 只记录 */
        private String outputLevel = "block";

        /**
         * 是否再用大模型做一次二次判定。默认关闭：
         * 同模型判同文本是已知弱点（判官本身可被注入），且会引入额外时延与不确定性。
         */
        private boolean llmSecondOpinion = false;
    }
}
