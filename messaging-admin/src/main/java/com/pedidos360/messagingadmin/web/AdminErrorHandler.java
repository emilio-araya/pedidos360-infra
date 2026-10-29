package com.pedidos360.messagingadmin.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.pedidos360.messagingadmin.admin.BrokerUnavailableException;
import com.pedidos360.messagingadmin.admin.ProtectedResourceException;
import com.pedidos360.messagingadmin.admin.QueueNotFoundException;
import com.pedidos360.messagingadmin.admin.ResourceConflictException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Turns every failure into a ProblemDetail with a message a person can act on. */
@RestControllerAdvice
public class AdminErrorHandler {

    private static final String INVALID = "Solicitud inválida";

    /** Covers @Valid bodies (MethodArgumentNotValidException) and query objects (BindException). */
    @ExceptionHandler(BindException.class)
    ProblemDetail invalidFields(BindException exception) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", message(error)))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID, "Revisa los campos indicados en errors");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidPath(HandlerMethodValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, INVALID, "El nombre de la ruta no es válido: " + 
                com.pedidos360.messagingadmin.web.dto.ResourceNames.MESSAGE);
    }

    /** Unknown enum values and malformed JSON end here; enums list their allowed values. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException exception) {
        if (exception.getCause() instanceof InvalidFormatException format && format.getTargetType().isEnum()) {
            String allowed = Arrays.stream(format.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return problem(HttpStatus.BAD_REQUEST, INVALID,
                    "Valor no permitido \"" + format.getValue() + "\"; usa uno de: " + allowed);
        }
        return problem(HttpStatus.BAD_REQUEST, INVALID, "El cuerpo no es un JSON válido");
    }

    @ExceptionHandler(QueueNotFoundException.class)
    ProblemDetail notFound(QueueNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "No encontrado", exception.getMessage());
    }

    @ExceptionHandler({ProtectedResourceException.class, ResourceConflictException.class})
    ProblemDetail conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Conflicto", exception.getMessage());
    }

    @ExceptionHandler(BrokerUnavailableException.class)
    ProblemDetail brokerRefused(BrokerUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ no disponible", exception.getMessage());
    }

    @ExceptionHandler(AmqpConnectException.class)
    ProblemDetail brokerDown(AmqpConnectException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ no disponible",
                "No se pudo conectar con RabbitMQ; intenta de nuevo en unos segundos");
    }

    private static String message(FieldError error) {
        return error.getDefaultMessage() == null ? "valor inválido" : error.getDefaultMessage();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
