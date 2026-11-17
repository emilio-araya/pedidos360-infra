package com.pedidos360.messagingadmin.web;

import com.pedidos360.messagingadmin.kafka.ConsumerGroupDetailInfo;
import com.pedidos360.messagingadmin.kafka.ConsumerGroupInfo;
import com.pedidos360.messagingadmin.kafka.KafkaAdminService;
import com.pedidos360.messagingadmin.kafka.TopicInfo;
import com.pedidos360.messagingadmin.kafka.TopicNames;
import com.pedidos360.messagingadmin.web.dto.ConsumerGroupDetailResponse;
import com.pedidos360.messagingadmin.web.dto.ConsumerGroupSummaryResponse;
import com.pedidos360.messagingadmin.web.dto.CreateTopicRequest;
import com.pedidos360.messagingadmin.web.dto.IncreasePartitionsRequest;
import com.pedidos360.messagingadmin.web.dto.OffsetResetRequest;
import com.pedidos360.messagingadmin.web.dto.OffsetResetResponse;
import com.pedidos360.messagingadmin.web.dto.TopicDetailResponse;
import com.pedidos360.messagingadmin.web.dto.TopicSummaryResponse;
import com.pedidos360.messagingadmin.web.dto.UpdateTopicConfigsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kafka administration over HTTP: topics, their configuration and consumer
 * groups. It only validates the shape of the request; every rule lives in
 * {@link KafkaAdminService}.
 */
@RestController
@RequestMapping("/api/admin/kafka")
@Validated
public class KafkaAdminController {

    private static final String NAME_MESSAGE = TopicNames.MESSAGE;

    private final KafkaAdminService adminService;

    public KafkaAdminController(KafkaAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/topics")
    public ResponseEntity<TopicDetailResponse> createTopic(@Valid @RequestBody CreateTopicRequest request) {
        TopicDetailResponse created = TopicDetailResponse.from(adminService.createTopic(request.toSpec()));
        return ResponseEntity
                .created(URI.create("/api/admin/kafka/topics/" + created.name()))
                .body(created);
    }

    @GetMapping("/topics")
    public List<TopicSummaryResponse> listTopics(
            @RequestParam(defaultValue = "false") boolean includeInternal) {
        return adminService.listTopics(includeInternal).stream()
                .map(TopicSummaryResponse::from)
                .toList();
    }

    @GetMapping("/topics/{name}")
    public TopicDetailResponse describeTopic(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String name) {
        return TopicDetailResponse.from(adminService.describeTopic(name));
    }

    @PatchMapping("/topics/{name}/configs")
    public TopicDetailResponse updateConfigs(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String name,
            @Valid @RequestBody UpdateTopicConfigsRequest request) {
        return TopicDetailResponse.from(adminService.alterConfigs(name, request.configs()));
    }

    @PostMapping("/topics/{name}/partitions")
    public TopicDetailResponse increasePartitions(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String name,
            @Valid @RequestBody IncreasePartitionsRequest request) {
        return TopicDetailResponse.from(adminService.increasePartitions(name, request.partitions()));
    }

    @DeleteMapping("/topics/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTopic(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String name) {
        adminService.deleteTopic(name);
    }

    @GetMapping("/consumer-groups")
    public List<ConsumerGroupSummaryResponse> listConsumerGroups() {
        return adminService.listConsumerGroups().stream()
                .map(ConsumerGroupSummaryResponse::from)
                .toList();
    }

    @GetMapping("/consumer-groups/{groupId}")
    public ConsumerGroupDetailResponse describeConsumerGroup(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String groupId) {
        ConsumerGroupDetailInfo detail = adminService.describeConsumerGroup(groupId);
        return ConsumerGroupDetailResponse.from(detail);
    }

    @PostMapping("/consumer-groups/{groupId}/offsets/reset")
    public OffsetResetResponse resetOffsets(
            @PathVariable @Pattern(regexp = TopicNames.PATTERN, message = NAME_MESSAGE) String groupId,
            @Valid @RequestBody OffsetResetRequest request) {
        return OffsetResetResponse.from(adminService.resetOffsets(request.toSpec(groupId)));
    }
}
