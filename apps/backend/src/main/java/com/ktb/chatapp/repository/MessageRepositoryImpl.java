package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@RequiredArgsConstructor
public class MessageRepositoryImpl implements MessageRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public Map<String, Integer> countRecentMessagesByRoomIds(Set<String> roomIds, LocalDateTime since) {
        if (roomIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("room").in(roomIds).and("timestamp").gte(since)),
            Aggregation.group("room").count().as("count")
        );
        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, Message.class, Document.class);
        return results.getMappedResults().stream().collect(Collectors.toMap(
            document -> document.getString("_id"),
            document -> ((Number) document.get("count")).intValue()
        ));
    }

    @Override
    public long markMessagesAsRead(
            String roomId,
            List<String> messageIds,
            String userId,
            LocalDateTime readAt) {
        if (roomId == null || messageIds.isEmpty()) {
            return 0;
        }

        // 중요: 다른 방의 ID와 이미 읽은 문서는 조건에서 제외해 한 번만 갱신한다.
        Query query = Query.query(Criteria.where("_id").in(messageIds)
                .and("room").is(roomId)
                .and("readers.userId").ne(userId));
        Document reader = new Document("userId", userId).append("readAt", readAt);
        return mongoTemplate.updateMulti(query, new Update().push("readers", reader), Message.class)
                .getModifiedCount();
    }
}
