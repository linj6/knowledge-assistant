package com.lnjecit.knowledge.assistant.ingestion.upload;

import org.springframework.stereotype.Repository;

@Repository
public class OrphanObjectRepository {
    private final OrphanObjectMapper mapper;

    public OrphanObjectRepository(OrphanObjectMapper mapper) {
        this.mapper = mapper;
    }

    public boolean referenced(String key) {
        return mapper.referenced(key);
    }
}
