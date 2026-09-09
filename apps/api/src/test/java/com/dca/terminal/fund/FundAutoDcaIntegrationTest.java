package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Transactional
class FundAutoDcaIntegrationTest {
    @Autowired
    FundService fundService;

    @Autowired
    AutoDcaService autoDcaService;

    @Autowired
    FundPurchaseService purchaseService;

    @Test
    void createsCnyFundStoresNavAndRebuildsMonthlyProjectionFromRule() {
        String code = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        FundDtos.FundResponse fund = fundService.create(new FundDtos.FundRequest(
                code, "China Fund", new BigDecimal("0.012"), 1, "A"));

        fundService.putNav(fund.id(), new FundDtos.NavRequest(
                LocalDate.of(2026, 7, 1), new BigDecimal("1.0000"), "MANUAL"));
        fundService.putNav(fund.id(), new FundDtos.NavRequest(
                LocalDate.of(2026, 7, 2), new BigDecimal("1.1000"), "MANUAL"));
        fundService.putNav(fund.id(), new FundDtos.NavRequest(
                LocalDate.of(2026, 7, 3), new BigDecimal("1.2000"), "MANUAL"));

        AutoDcaDtos.RuleResponse rule = autoDcaService.create(new AutoDcaDtos.RuleRequest(
                code, new BigDecimal("100"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3),
                new BigDecimal("0.0015"), true));
        AutoDcaDtos.ProjectionResponse original = autoDcaService.projection(
                rule.id(), AutoDcaProjectionEngine.GroupBy.MONTH, false);

        assertEquals("CNY", fund.currency());
        assertEquals(1, original.summaries().size());
        assertEquals(3, original.summaries().getFirst().executionCount());
        assertEquals(2, original.summaries().getFirst().confirmedCount());
        assertEquals(0, new BigDecimal("300").compareTo(original.summaries().getFirst().grossAmount()));
        assertTrue(original.daily().isEmpty());

        autoDcaService.update(rule.id(), new AutoDcaDtos.RuleRequest(
                code, new BigDecimal("200"), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3),
                new BigDecimal("0.0015"), true));
        AutoDcaDtos.ProjectionResponse rewritten = autoDcaService.projection(
                rule.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true);

        assertEquals(2, rewritten.summaries().getFirst().executionCount());
        assertEquals(0, new BigDecimal("400").compareTo(rewritten.summaries().getFirst().grossAmount()));
        assertEquals(LocalDate.of(2026, 7, 2), rewritten.daily().getFirst().navDate());
    }

    @Test
    void recordsEditsAndDeletesOneTimePurchaseUsingExactNav() {
        String code = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        FundDtos.FundResponse fund = fundService.create(new FundDtos.FundRequest(
                code, "One Time Fund", new BigDecimal("0.006"), 1, "A"));
        LocalDate purchaseDate = LocalDate.of(2026, 8, 18);
        fundService.putNav(fund.id(), new FundDtos.NavRequest(purchaseDate, new BigDecimal("1.2500"), "MANUAL"));

        FundDtos.FundPurchaseResponse created = purchaseService.create(fund.id(), new FundDtos.FundPurchaseRequest(
                purchaseDate, new BigDecimal("10000"), new BigDecimal("0.0015"), "initial position"));

        assertEquals(fund.id(), created.fundId());
        assertEquals(0, new BigDecimal("1.2500").compareTo(created.nav()));
        assertEquals(0, new BigDecimal("10000.000000").compareTo(created.grossAmount()));
        assertTrue(created.shares().signum() > 0);
        assertEquals(1, purchaseService.list(fund.id()).size());

        FundDtos.FundPurchaseResponse edited = purchaseService.update(fund.id(), created.id(),
                new FundDtos.FundPurchaseRequest(purchaseDate, new BigDecimal("12000"), BigDecimal.ZERO, "edited"));
        assertEquals(0, new BigDecimal("12000.000000").compareTo(edited.grossAmount()));
        assertEquals("edited", edited.notes());

        assertEquals(created.id(), purchaseService.delete(fund.id(), created.id()));
        assertTrue(purchaseService.list(fund.id()).isEmpty());
    }

    @Test
    void rejectsOneTimePurchaseWhenExactNavIsMissing() {
        String code = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        FundDtos.FundResponse fund = fundService.create(new FundDtos.FundRequest(
                code, "Missing NAV Fund", new BigDecimal("0.006"), 1, "A"));

        assertThrows(DomainException.class, () -> purchaseService.create(fund.id(), new FundDtos.FundPurchaseRequest(
                LocalDate.of(2026, 8, 18), new BigDecimal("1000"), BigDecimal.ZERO, null)));
    }

    @Test
    void deletesFundNavPurchasesAndAutoDcaRulesTogether() {
        String code = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        FundDtos.FundResponse fund = fundService.create(new FundDtos.FundRequest(
                code, "Delete Me", new BigDecimal("0.006"), 1, "A"));
        LocalDate date = LocalDate.of(2026, 9, 1);
        fundService.putNav(fund.id(), new FundDtos.NavRequest(date, new BigDecimal("1.2345"), "MANUAL"));
        FundDtos.FundPurchaseResponse purchase = purchaseService.create(fund.id(), new FundDtos.FundPurchaseRequest(
                date, new BigDecimal("5000"), BigDecimal.ZERO, null));
        AutoDcaDtos.RuleResponse rule = autoDcaService.create(new AutoDcaDtos.RuleRequest(
                code, new BigDecimal("100"), date, null, BigDecimal.ZERO, true));

        assertEquals(fund.id(), fundService.delete(fund.id()));

        assertThrows(DomainException.class, () -> fundService.get(fund.id()));
        assertTrue(fundService.list().stream().noneMatch(item -> item.id().equals(fund.id())));
        assertTrue(autoDcaService.list().stream().noneMatch(item -> item.id().equals(rule.id())));
        assertThrows(DomainException.class, () -> purchaseService.update(fund.id(), purchase.id(),
                new FundDtos.FundPurchaseRequest(date, new BigDecimal("6000"), BigDecimal.ZERO, null)));
    }
}
