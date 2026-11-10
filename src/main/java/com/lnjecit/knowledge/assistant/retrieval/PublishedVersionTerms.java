package com.lnjecit.knowledge.assistant.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public final class PublishedVersionTerms {
    public static final int MAX_UNIQUE_VERSIONS = 2000;

    private PublishedVersionTerms() {}

    public static List<Long> validate(Collection<Long> ids) {
        TreeSet<Long> unique = new TreeSet<>();
        for (Long id : ids) {
            if (id == null) throw new IllegalArgumentException("TERMS_INVALID_ID");
            unique.add(id);
        }
        if (unique.size() > MAX_UNIQUE_VERSIONS) throw new TermsLimitExceededException();
        return List.copyOf(unique);
    }
}
