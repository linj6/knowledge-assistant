package com.lnjecit.knowledge.assistant.infrastructure.web;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public enum Code { INVALID_ARGUMENT, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, INTERNAL_ERROR }
    public record ApiError(Code code, String message, String requestId) {}
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // 404 保留 MVC 默认语义，不扩大到静态资源统一错误处理。
        if (status.value() == 404) return super.handleExceptionInternal(ex, body, headers, status, request);
        Code code = switch (status.value()) {
            case 405 -> Code.METHOD_NOT_ALLOWED;
            case 415 -> Code.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? Code.INTERNAL_ERROR : Code.INVALID_ARGUMENT;
        };
        return new ResponseEntity<>(error(code), headers, status);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception ex) {
        // 不记录异常消息或堆栈：其中可能包含密钥、SQL 或文档正文。
        LOG.error("requestId={} code=INTERNAL_ERROR exceptionType={}", MDC.get("requestId"), ex.getClass().getName());
        return ResponseEntity.status(500).body(error(Code.INTERNAL_ERROR));
    }
    private ApiError error(Code code) {
        String message = switch (code) {
            case INVALID_ARGUMENT -> "请求参数不合法";
            case METHOD_NOT_ALLOWED -> "不支持的 HTTP 方法";
            case UNSUPPORTED_MEDIA_TYPE -> "不支持的媒体类型";
            case INTERNAL_ERROR -> "服务内部错误";
        };
        return new ApiError(code, message, MDC.get("requestId"));
    }
}
