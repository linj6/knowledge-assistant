package com.lnjecit.knowledge.assistant.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class OriginalReadLimitTests {
    @Test
    void acceptsLimitAndRejectsLargerObjectWithoutReadingRest() throws Exception {
        assertThat(MinioOriginalFileStore.readLimited(new ByteArrayInputStream(new byte[4]), 4))
                .hasSize(4);
        ByteArrayInputStream oversized = new ByteArrayInputStream(new byte[100]);
        assertThatThrownBy(() -> MinioOriginalFileStore.readLimited(oversized, 4))
                .hasMessage("Object exceeds upload limit");
        assertThat(oversized.available()).isEqualTo(95);
    }
}
