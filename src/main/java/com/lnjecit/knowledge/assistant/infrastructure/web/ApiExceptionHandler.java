package com.lnjecit.knowledge.assistant.infrastructure.web;

import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.common.error.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        CommonErrorCode code =
                switch (status.value()) {
                    case 404 -> CommonErrorCode.NOT_FOUND;
                    case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
                    case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
                    default -> status.is5xxServerError()
                            ? CommonErrorCode.INTERNAL_ERROR
                            : CommonErrorCode.INVALID_ARGUMENT;
                };
        return new ResponseEntity<>(error(code), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception ex) {
        // 不记录异常消息或堆栈：其中可能包含密钥、SQL 或文档正文。
        LOG.error(
                "requestId={} code=INTERNAL_ERROR exceptionType={}",
                MDC.get("requestId"),
                ex.getClass().getName());
        return ResponseEntity.status(500).body(error(CommonErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        LOG.warn("requestId={} code={}", MDC.get("requestId"), code.getCode());
        return ResponseEntity.status(statusFor(code)).body(error(code));
    }

    private HttpStatus statusFor(ErrorCode code) {
        // 公共码显式映射；领域自定义码默认 400，后续按实际需要在 Web 层扩展。
        if (!(code instanceof CommonErrorCode commonCode)) return HttpStatus.BAD_REQUEST;
        return switch (commonCode) {
            case NOT_FOUND, KNOWLEDGE_BASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
        };
    }

    private Result<Void> error(ErrorCode code) {
        // 只从错误码定义读取安全消息，不读取异常消息。
        return Result.failure(code);
    }
}
