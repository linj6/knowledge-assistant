package com.lnjecit.knowledge.assistant.auth;

import org.springframework.jdbc.core.JdbcTemplate;

public final class KnowledgeBaseOwnerFixture {
    private KnowledgeBaseOwnerFixture() {}

    public static void insertDefaultOwner(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO app_user(id,username,password_hash,status) VALUES (1,'fixture-owner','test-only','ACTIVE')");
    }
}
