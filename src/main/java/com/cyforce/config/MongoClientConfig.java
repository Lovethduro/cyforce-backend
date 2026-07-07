package com.cyforce.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MongoClientConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsBuilderCustomizer() {
        return builder -> builder
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS))
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(60, TimeUnit.SECONDS));
    }
}
