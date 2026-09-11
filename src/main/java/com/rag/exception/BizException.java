package com.rag.exception;

/**
 * 业务异常：用于返回可读的业务错误信息
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
