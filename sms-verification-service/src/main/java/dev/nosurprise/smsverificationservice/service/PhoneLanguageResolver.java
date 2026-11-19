package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import dev.nosurprise.smsverificationservice.domain.Language;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class PhoneLanguageResolver {

    private final List<String> cisPrefixes;

    public PhoneLanguageResolver(SmsProperties props) {
        this.cisPrefixes = props.cisPrefixes().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    }

    public Language resolve(String e164) {
        return cisPrefixes.stream().anyMatch(e164::startsWith)
            ? Language.RU : Language.EN;
    }
}