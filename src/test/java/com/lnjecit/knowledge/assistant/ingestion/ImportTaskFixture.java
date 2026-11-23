package com.lnjecit.knowledge.assistant.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;

final class ImportTaskFixture {
    private ImportTaskFixture() {}

    static void insertPendingTask(JdbcTemplate jdbc, long id, String contentHash) {
        jdbc.update(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(?,1,'a.txt',?,'key')",
                id,
                contentHash);
        jdbc.update("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(?,?, '{}')", id, id);
        jdbc.update("INSERT INTO document_import_task(id,document_id,index_version_id) VALUES(?,?,?)", id, id, id);
    }
}
