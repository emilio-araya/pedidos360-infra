package com.pedidos360.messagingadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MessagingAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessagingAdminApplication.class, args);
    }
}
