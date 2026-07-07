package com.cyforce.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TicketSlaMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(TicketSlaMaintenanceService.class);

    private final TicketService ticketService;

    public TicketSlaMaintenanceService(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void processSlaEscalations() {
        int processed = ticketService.processSlaEscalations();
        if (processed > 0) {
            log.info("Processed {} SLA breach escalation(s)", processed);
        }
    }
}
