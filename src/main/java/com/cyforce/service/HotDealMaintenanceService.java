package com.cyforce.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class HotDealMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(HotDealMaintenanceService.class);

    private final ContentService contentService;

    public HotDealMaintenanceService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Scheduled(cron = "0 * * * * *")
    public void processHotDealWindows() {
        contentService.processHotDealSchedule();
    }
}
