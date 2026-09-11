package com.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 注意：完整上下文加载需要 MySQL、PostgreSQL、Redis 均已启动，
 * 且已配置 DEEPSEEK_API_KEY 环境变量。
 * 如需仅编译不过上下文，可临时改为 @SpringBootTest(classes = RagApplication.class, webEnvironment = NONE) 并在测试方法上跳过。
 */
@SpringBootTest
class RagApplicationTests {

    @Test
    void contextLoads() {
    }
}
