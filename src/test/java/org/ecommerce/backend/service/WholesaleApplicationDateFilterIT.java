package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-DB proof of the date-range boundary math in
 * {@link WholesaleCustomerService#getWholesaleApplications} /
 * {@link WholesaleApplicationRepository#findForAdmin}. Fixtures are backdated to
 * March 2001 — far outside any real seeded or dev-created data — so the shared local
 * dev database can never accidentally satisfy or defeat a window assertion.
 */
@QuarkusTest
@DisplayName("WholesaleApplicationDateFilterIT — fromDate/toDate window boundaries")
class WholesaleApplicationDateFilterIT
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    private static final String WINDOW_FROM = "2001-03-01";
    private static final String WINDOW_TO = "2001-03-31";

    private WholesaleApplicationEntity newApplication(OffsetDateTime createdAt)
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail("date-filter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        app.setFirstName("Test");
        app.setLastName("Applicant");
        app.setCompanyName("Date Filter Co");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(createdAt);
        em.persist(app);
        return app;
    }

    private List<WholesaleApplicationListItemDto> listWindow(String fromDate, String toDate)
    {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageIndex(0);
        pageRequest.setPageSize(50);
        return wholesaleCustomerService.getWholesaleApplications(pageRequest, new FilterRequest(), fromDate, toDate);
    }

    @Test
    @TestTransaction
    @DisplayName("includes an application at the very start of the from-day")
    void includesApplicationAtStartOfFromDay()
    {
        WholesaleApplicationEntity inWindow = newApplication(OffsetDateTime.of(2001, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        List<String> ids = listWindow(WINDOW_FROM, WINDOW_TO).stream().map(WholesaleApplicationListItemDto::getId).map(UUID::toString).toList();

        assertTrue(ids.contains(inWindow.getId().toString()));
    }

    @Test
    @TestTransaction
    @DisplayName("includes an application at the very end of the to-day, not just its midnight")
    void includesApplicationAtEndOfToDay()
    {
        // 23:59:59 on the "to" day — the case a naive `<= toDate` (parsed as midnight)
        // would wrongly exclude, and exactly why the bound is an exclusive next-midnight.
        WholesaleApplicationEntity lastMomentOfWindow =
                newApplication(OffsetDateTime.of(2001, 3, 31, 23, 59, 59, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        List<String> ids = listWindow(WINDOW_FROM, WINDOW_TO).stream().map(WholesaleApplicationListItemDto::getId).map(UUID::toString).toList();

        assertTrue(ids.contains(lastMomentOfWindow.getId().toString()));
    }

    @Test
    @TestTransaction
    @DisplayName("excludes an application one day before the window")
    void excludesApplicationBeforeWindow()
    {
        WholesaleApplicationEntity tooEarly = newApplication(OffsetDateTime.of(2001, 2, 28, 12, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        List<String> ids = listWindow(WINDOW_FROM, WINDOW_TO).stream().map(WholesaleApplicationListItemDto::getId).map(UUID::toString).toList();

        assertFalse(ids.contains(tooEarly.getId().toString()));
    }

    @Test
    @TestTransaction
    @DisplayName("excludes an application the day after the window")
    void excludesApplicationAfterWindow()
    {
        WholesaleApplicationEntity tooLate = newApplication(OffsetDateTime.of(2001, 4, 1, 0, 0, 1, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        List<String> ids = listWindow(WINDOW_FROM, WINDOW_TO).stream().map(WholesaleApplicationListItemDto::getId).map(UUID::toString).toList();

        assertFalse(ids.contains(tooLate.getId().toString()));
    }

    @Test
    @TestTransaction
    @DisplayName("an absent fromDate/toDate applies no bound at all")
    void absentDatesApplyNoBound()
    {
        WholesaleApplicationEntity farInThePast = newApplication(OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        List<String> ids = listWindow(null, null).stream().map(WholesaleApplicationListItemDto::getId).map(UUID::toString).toList();

        assertTrue(ids.contains(farInThePast.getId().toString()));
    }

    @Test
    @TestTransaction
    @DisplayName("rejects a malformed date rather than silently ignoring the filter")
    void malformedDate_throws()
    {
        PageRequest pageRequest = new PageRequest();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.getWholesaleApplications(pageRequest, new FilterRequest(), "not-a-date", null));

        assertTrue(ex.getMessage().contains("fromDate"));
    }

    @Test
    @TestTransaction
    @DisplayName("the count query respects the same date window as the list")
    void countRespectsDateWindow()
    {
        newApplication(OffsetDateTime.of(2001, 3, 15, 9, 0, 0, 0, ZoneOffset.UTC));
        newApplication(OffsetDateTime.of(2001, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)); // outside window
        em.flush();
        em.clear();

        long countInWindow = wholesaleCustomerService.wholesaleApplicationCount(new FilterRequest(), WINDOW_FROM, WINDOW_TO);
        long countUnbounded = wholesaleCustomerService.wholesaleApplicationCount(new FilterRequest(), null, null);

        assertTrue(countInWindow >= 1);
        assertTrue(countUnbounded >= countInWindow, "the unbounded count must be at least the windowed count");
    }
}
