package com.ktb.chatapp.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import static org.assertj.core.api.Assertions.assertThat;

class MessageIndexDefinitionTest {

    @Test
    void messageHistoryIndexMatchesRoomAndDescendingCursorSort() {
        CompoundIndex index = Message.class.getAnnotation(CompoundIndex.class);

        assertThat(index).isNotNull();
        assertThat(index.name()).isEqualTo("room_timestamp_id_desc");
        assertThat(index.def()).contains("'room': 1", "'timestamp': -1", "'_id': -1");
    }
}
