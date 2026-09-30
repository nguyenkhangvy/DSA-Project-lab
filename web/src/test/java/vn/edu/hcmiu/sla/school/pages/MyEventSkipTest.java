package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolMyEventRepository;

/**
 * Skipping a day with real commits (no test transaction around it): the second click of a double click on
 * "Skip … only", written while the first is being saved, still ends without an error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyEventSkipTest {

    static final LocalDate TUE = LocalDate.of(2026, 10, 6);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    SchoolMyEventRepository events;

    TransactionTemplate transaction;
    AppUser an;
    Integer eventId;

    @BeforeEach
    void aWeeklyEvent() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            SchoolTestData data = new SchoolTestData(db);
            an = data.user("an-" + UUID.randomUUID() + "@example.com");
            eventId = data.myEvent(an, "Tự học buổi tối", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 20),
                    LocalTime.of(17, 0), LocalTime.of(19, 0), DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY).getId();
        });
    }

    @AfterEach
    void noTrace() {
        transaction.executeWithoutResult(status -> db.createQuery("delete from User u where u.id = :id")
                .setParameter("id", an.id()).executeUpdate());
    }

    List<LocalDate> skipped() {
        return transaction.execute(status -> events.findOfUser(eventId, an.id()).orElseThrow().getSkips().stream()
                .map(skip -> skip.getDay()).toList());
    }

    @Test
    void theSecondClickOfADoubleClickOnSkipEndsWithoutAnErrorPage() throws Exception {
        // The first lookup is the page's ownership check; after the second, the Skip's own, the other click's
        // skip of the same day is written, so this Skip's commit meets it.
        Answer<?> real = mockingDetails(events).getMockCreationSettings().getDefaultAnswer();
        doAnswer(real).doAnswer(call -> {
            Object loaded = real.answer(call);
            transaction.executeWithoutResult(status -> db.createNativeQuery(
                    "insert into school_my_event_skips (event_id, skip_day) values (?1, ?2)")
                    .setParameter(1, eventId).setParameter(2, TUE).executeUpdate());
            return loaded;
        }).doAnswer(real).when(events).findOfUser(any(), any());

        mvc.perform(post("/school/events/" + eventId + "/skip").param("day", TUE.toString()).with(user(an)).with(csrf()))
                .andExpect(redirectedUrl("/school/events/" + eventId + "/edit?day=" + TUE));

        assertThat(skipped()).containsExactly(TUE);
    }
}
