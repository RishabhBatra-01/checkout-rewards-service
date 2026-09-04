package com.uniblox.store.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/report")
class ReportAdminController {

    private final ReportService reportService;

    ReportAdminController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    ReportResponse report() {
        return reportService.buildReport();
    }
}
