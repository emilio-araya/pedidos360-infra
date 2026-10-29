package com.pedidos360.messagingadmin.web;

import com.pedidos360.messagingadmin.admin.RabbitAdminService;
import com.pedidos360.messagingadmin.web.dto.BindingRequest;
import com.pedidos360.messagingadmin.web.dto.CreateExchangeRequest;
import com.pedidos360.messagingadmin.web.dto.CreateQueueRequest;
import com.pedidos360.messagingadmin.web.dto.ExchangeResponse;
import com.pedidos360.messagingadmin.web.dto.QueueResponse;
import com.pedidos360.messagingadmin.web.dto.ResourceNames;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API to administer RabbitMQ. Resources are nouns, verbs come from HTTP:
 *
 * <pre>
 * POST   /api/admin/queues                 create a queue (and its DLQ)   201
 * GET    /api/admin/queues/{name}          messages and consumers         200
 * DELETE /api/admin/queues/{name}          delete a queue                 204
 * POST   /api/admin/exchanges              create an exchange             201
 * DELETE /api/admin/exchanges/{name}       delete an exchange             204
 * POST   /api/admin/bindings               bind a queue to an exchange    201
 * DELETE /api/admin/bindings?exchange=&amp;queue=&amp;routingKey=   unbind  204
 * </pre>
 *
 * Errors: 400 invalid input, 404 unknown queue, 409 existing or system resource,
 * 503 broker unreachable. Bodies follow RFC 9457 (ProblemDetail).
 */
@RestController
@RequestMapping("/api/admin")
public class MessagingAdminController {

    private final RabbitAdminService adminService;

    public MessagingAdminController(RabbitAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/queues")
    public ResponseEntity<QueueResponse> createQueue(@Valid @RequestBody CreateQueueRequest request) {
        QueueResponse created = QueueResponse.from(adminService.createQueue(request.toSpec()));
        return ResponseEntity.created(URI.create("/api/admin/queues/" + created.name())).body(created);
    }

    @GetMapping("/queues/{name}")
    public QueueResponse getQueue(@PathVariable @Pattern(regexp = ResourceNames.PATTERN) String name) {
        return QueueResponse.from(adminService.getQueue(name));
    }

    @DeleteMapping("/queues/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQueue(@PathVariable @Pattern(regexp = ResourceNames.PATTERN) String name) {
        adminService.deleteQueue(name);
    }

    @PostMapping("/exchanges")
    public ResponseEntity<ExchangeResponse> createExchange(@Valid @RequestBody CreateExchangeRequest request) {
        adminService.createExchange(request.toSpec());
        return ResponseEntity.created(URI.create("/api/admin/exchanges/" + request.name()))
                .body(ExchangeResponse.from(request.toSpec()));
    }

    @DeleteMapping("/exchanges/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExchange(@PathVariable @Pattern(regexp = ResourceNames.PATTERN) String name) {
        adminService.deleteExchange(name);
    }

    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public void bind(@Valid @RequestBody BindingRequest request) {
        adminService.bind(request.toSpec());
    }

    @DeleteMapping("/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@Valid BindingRequest request) {
        adminService.unbind(request.toSpec());
    }
}
