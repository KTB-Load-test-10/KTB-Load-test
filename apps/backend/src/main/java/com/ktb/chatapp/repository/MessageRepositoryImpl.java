package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;

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
}
