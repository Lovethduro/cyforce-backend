package com.cyforce.config;

import com.cyforce.model.SalesPlaybookEntry;
import com.cyforce.repository.SalesPlaybookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Order(3)
public class SalesPlaybookSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesPlaybookSeeder.class);

    private final SalesPlaybookRepository playbookRepository;

    public SalesPlaybookSeeder(SalesPlaybookRepository playbookRepository) {
        this.playbookRepository = playbookRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (playbookRepository.count() > 0) {
            return;
        }
        log.info("Seeding sales playbook...");
        LocalDateTime now = LocalDateTime.now();
        playbookRepository.saveAll(List.of(
                entry("discount", null, "Company discount policy", 0, true,
                        "Standard rules for quoting and approving discounts.",
                        "All agents must follow these limits unless a supervisor approves an exception in writing (chat forward or email).\n\n"
                                + "• List price is always the starting point — never open with a discount.\n"
                                + "• Bundle install + hardware when possible; discount on install only if margin allows.\n"
                                + "• Never discount below minimum margin on CCTV cameras or solar inverters.\n"
                                + "• Hot deals on the website are pre-approved — match those exactly, do not exceed.\n"
                                + "• Payment terms: full payment or 70/30 for installs over ₦500k with supervisor sign-off.",
                        10, 10, "discount,policy,approval,margin", now),
                entry("product", "CCTV", "CCTV packages — what to recommend", 1, true,
                        "Match camera count and storage to site size and budget.",
                        "Qualify first:\n"
                                + "1. Indoor, outdoor, or both?\n"
                                + "2. Number of entry points and blind spots?\n"
                                + "3. Do they need remote viewing on phone?\n"
                                + "4. Retention period (7 / 14 / 30 days)?\n\n"
                                + "Sizing guide:\n"
                                + "• Small office / 2-bed home: 4-camera kit + 1TB NVR\n"
                                + "• Medium business: 8–16 cameras, commercial NVR, UPS backup\n"
                                + "• Large site: site survey required before quoting\n\n"
                                + "Always mention installation, cable routing, and optional annual maintenance.",
                        5, 15, "cctv,camera,surveillance,security", now),
                entry("product", "Solar", "Solar systems — qualification checklist", 2, false,
                        "Confirm load, roof, and payback expectations before quoting.",
                        "Ask these questions:\n"
                                + "• Average monthly electricity spend?\n"
                                + "• Critical loads (fridge, AC, equipment)?\n"
                                + "• Roof type and approximate area?\n"
                                + "• Grid-tied vs hybrid vs off-grid preference?\n\n"
                                + "Quote tiers:\n"
                                + "• Entry: partial backup for essentials\n"
                                + "• Standard: hybrid inverter + battery for evening use\n"
                                + "• Premium: full home/office backup with monitoring\n\n"
                                + "Installation timeline: survey → design approval → install (3–10 business days typical).",
                        8, 12, "solar,inverter,battery,installation", now),
                entry("product", "ICT", "ICT & networking services", 3, false,
                        "Structured cabling, Wi‑Fi, and managed IT support.",
                        "Common requests:\n"
                                + "• Office LAN / Wi‑Fi deployment\n"
                                + "• Fiber vs wireless last-mile\n"
                                + "• Firewall and basic cybersecurity setup\n"
                                + "• AMC (annual maintenance contract)\n\n"
                                + "For enterprise quotes, loop in supervisor before committing SLAs or custom hardware lists.",
                        5, 10, "ict,network,wifi,fiber,amc", now),
                entry("objection", null, "“Your price is too high”", 4, false,
                        "Acknowledge, reframe value, then offer scoped options.",
                        "1. Acknowledge: “I understand budget matters.”\n"
                                + "2. Clarify: compare apples-to-apples (install, warranty, support).\n"
                                + "3. Reframe: downtime cost, security risk, or energy savings.\n"
                                + "4. Options: phased install, smaller kit, or payment plan (if approved).\n"
                                + "5. Never bad-mouth competitors — focus on CyForce service and warranty.",
                        null, null, "objection,price,expensive,budget", now),
                entry("objection", null, "“I need to think about it”", 5, false,
                        "Lock in a follow-up and leave one clear next step.",
                        "• Ask what specific concern remains (price, timing, technical).\n"
                                + "• Offer a short summary email or quote PDF.\n"
                                + "• Set a concrete follow-up date in CRM.\n"
                                + "• If hot lead score > 70, supervisor may approve a time-limited offer.",
                        null, null, "objection,follow up,think", now),
                entry("process", null, "New enquiry — first 10 minutes", 6, true,
                        "Standard flow from first message to qualified opportunity.",
                        "1. Greet and confirm what they need (product line).\n"
                                + "2. Ask 3 qualification questions from the product guide.\n"
                                + "3. Log lead in CRM if not already created.\n"
                                + "4. Give a ballpark range only after qualification — not before.\n"
                                + "5. Send formal quote or invoice only after agreed scope.\n"
                                + "6. Forward to supervisor if discount above your limit or custom install.",
                        null, null, "process,enquiry,qualification,workflow", now),
                entry("general", null, "When to escalate to supervisor", 7, false,
                        "Deals that need supervisor review before you commit.",
                        "Escalate when:\n"
                                + "• Discount above your approved limit\n"
                                + "• Contract value estimated above ₦2M\n"
                                + "• Custom SLA or payment terms\n"
                                + "• Government / NGO tender paperwork\n"
                                + "• Customer requests legal or compliance exceptions\n\n"
                                + "Use “Forward to Supervisor” in Customer Messages and note the agreed amount.",
                        null, null, "escalate,supervisor,approval", now)
        ));
        log.info("Sales playbook seeded with {} entries", playbookRepository.count());
    }

    private SalesPlaybookEntry entry(String category, String productCategory, String title, int sortOrder, boolean pinned,
                                     String summary, String body, Integer maxDiscount, Integer supervisorAbove,
                                     String keywords, LocalDateTime now) {
        SalesPlaybookEntry e = new SalesPlaybookEntry();
        e.setCategory(category);
        e.setProductCategory(productCategory);
        e.setTitle(title);
        e.setSummary(summary);
        e.setBody(body);
        e.setMaxDiscountPercent(maxDiscount);
        e.setSupervisorApprovalAbove(supervisorAbove);
        e.setKeywords(keywords);
        e.setPinned(pinned);
        e.setActive(true);
        e.setSortOrder(sortOrder);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
