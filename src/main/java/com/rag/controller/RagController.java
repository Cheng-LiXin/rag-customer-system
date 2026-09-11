package com.rag.controller;

import com.rag.util.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试用控制器：验证项目是否成功启动
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.success("Hello RAG");
    }
}
