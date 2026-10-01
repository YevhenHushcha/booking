package io.github.coworking.problem;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Errors as RFC 7807 {@code application/problem+json}, the same body Boot 3 produces with
 * {@code spring.mvc.problemdetails.enabled}.
 * <p>
 * Covers the exceptions Spring MVC raises itself (validation, missing header, unreadable body) and
 * {@link ResponseStatusException} from the controllers. Anything else is a bug and stays with Boot's
 * error page, which answers 500 without the exception message.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Object> handleResponseStatus(ResponseStatusException e, WebRequest request) {
        return problem(e.getStatus(), e.getReason(), request);
    }

    /** Every Spring MVC exception the base class maps to a status ends up here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatus status, WebRequest request) {
        // The exception message can name internal classes; a fixed text per status is enough.
        return problem(status, null, request);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        if (detail != null) {
            body.put("detail", detail);
        }
        if (request instanceof ServletWebRequest) {
            body.put("instance", ((ServletWebRequest) request).getRequest().getRequestURI());
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
