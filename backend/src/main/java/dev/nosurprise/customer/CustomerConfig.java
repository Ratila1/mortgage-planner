package dev.nosurprise.customer;

import dev.nosurprise.customer.kyc.KycProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KycProperties.class)
public class CustomerConfig {
}
