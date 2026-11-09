package com.flj.exception;

import lombok.Getter;

/**
 * 业务异常类
 * @author system
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final String message;
    private final Object data;

    public BusinessException(String message) {
        this("BUSINESS_ERROR", message);
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = null;
    }

}
