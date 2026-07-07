package com.cyforce.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class HotDealFieldMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HotDealFieldMigration.class);

    private final MongoTemplate mongoTemplate;

    public HotDealFieldMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query legacy = Query.query(Criteria.where("isActive").exists(true));
        if (mongoTemplate.count(legacy, "hot_deals") == 0) {
            return;
        }
        int migrated = 0;
        for (Document doc : mongoTemplate.find(legacy, Document.class, "hot_deals")) {
            boolean active = Boolean.TRUE.equals(doc.get("active")) || Boolean.TRUE.equals(doc.get("isActive"));
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(doc.get("_id"))),
                    new Update().set("active", active).unset("isActive"),
                    "hot_deals"
            );
            migrated++;
        }
        if (migrated > 0) {
            log.info("Normalized active flags on {} hot deal(s)", migrated);
        }
    }
}
