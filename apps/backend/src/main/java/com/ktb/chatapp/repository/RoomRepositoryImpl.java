package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.RoomCursor;
import com.ktb.chatapp.model.Room;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@RequiredArgsConstructor
public class RoomRepositoryImpl implements RoomRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Room> findCursorPage(RoomCursor cursor, int limit) {
        Query query = new Query().limit(limit)
            .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));

        if (cursor != null) {
            Criteria olderCreatedAt = Criteria.where("createdAt").lt(cursor.createdAt());
            Criteria sameCreatedAtOlderId = new Criteria().andOperator(
                Criteria.where("createdAt").is(cursor.createdAt()),
                Criteria.where("_id").lt(cursor.id())
            );
            query.addCriteria(new Criteria().orOperator(olderCreatedAt, sameCreatedAtOlderId));
        }

        return mongoTemplate.find(query, Room.class);
    }
}
