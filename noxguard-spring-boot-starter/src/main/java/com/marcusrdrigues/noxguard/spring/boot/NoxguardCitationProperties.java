package com.marcusrdrigues.noxguard.spring.boot;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code noxguard.citations.*} properties: the citation guard for RAG answers.
 *
 * <pre>
 * noxguard:
 *   citations:
 *     allow-names: [Acme, "Acme Assistant"]
 * </pre>
 *
 * @param allowNames Comma-separated list of names an answer may always say without a source: the product,
 *     the assistant, the company. Setting them creates the CitationGuard bean.
 */
@ConfigurationProperties("noxguard.citations")
public record NoxguardCitationProperties(List<String> allowNames) {

    /** An absent list is empty. */
    public NoxguardCitationProperties {
        allowNames = allowNames == null ? List.of() : List.copyOf(allowNames);
    }
}
