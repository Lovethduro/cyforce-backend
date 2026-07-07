package com.cyforce.repository;

import com.cyforce.model.SalesPlaybookEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SalesPlaybookRepository extends MongoRepository<SalesPlaybookEntry, String> {
    List<SalesPlaybookEntry> findByActiveTrueOrderByPinnedDescSortOrderAscTitleAsc();
    List<SalesPlaybookEntry> findAllByOrderByPinnedDescSortOrderAscTitleAsc();
    List<SalesPlaybookEntry> findByCategoryAndActiveTrueOrderBySortOrderAsc(String category);
}
