package com.lnjecit.knowledge.assistant.document;

import java.util.List;

public record DocumentListResponse(List<DocumentSummaryResponse> items, int page, int pageSize, Long total) {}
