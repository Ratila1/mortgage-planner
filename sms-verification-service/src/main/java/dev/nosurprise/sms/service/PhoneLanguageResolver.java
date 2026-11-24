package dev.nosurprise.sms.service;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.Language;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PhoneLanguageResolver {

    private final List<String> cisPrefixes;

    public PhoneLanguageResolver(SmsProperties props) {
        this.cisPrefixes = props.cisPrefixes();
    }

    public Language resolve(String e164) {
        return cisPrefixes.stream().anyMatch(e164::startsWith)
            ? Language.RU : Language.EN;
    }
}