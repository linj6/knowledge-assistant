package com.lnjecit.knowledge.assistant.document;

import com.lnjecit.knowledge.assistant.auth.KnowledgeBaseOwnerFixture;
import org.springframework.jdbc.core.JdbcTemplate;

final class DocumentPersistenceFixture {
    private DocumentPersistenceFixture() {}

    static void insertDocumentWithTwoVersions(JdbcTemplate jdbc, String config) {
        KnowledgeBaseOwnerFixture.insertDefaultOwner(jdbc);
        jdbc.update("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES(1,'test',1)");
        jdbc.update(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(2,1,'a.txt','hash','key')");
        jdbc.update(
                "INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(3,2,?),(4,2,?)",
                config,
                config);
    }
}
