package com.codearena.business.shared.config;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("nacos")
@EnableDiscoveryClient
public class NacosDiscoveryConfig {}
