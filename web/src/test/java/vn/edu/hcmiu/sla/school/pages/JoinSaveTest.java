package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSession;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatus;

/**
 * Saving Join… with real commits (no test transaction around it): a Save is one transaction, and the second click
 * of a double click that is written while the first is being saved still ends without an error page
 * (spec 2026-09-28-mailbox-events-design.md, section 4.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
class JoinSaveTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 1, 0); // Mon 28/09 08:00 in Vietnam
    static final String TALK = "a".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    TestClock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    SchoolMailJoinedRepository joined;

    TransactionTemplate transaction;
    AppUser an;

    @BeforeEach
    void anEventJoinedOnSaturday() {
        clock.set(NOW);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        an = transaction.execute(status -> {
            AppUser user = new SchoolTestData(db).user("an-" + UUID.randomUUID() + "@example.com");
            SchoolMail talk = new SchoolMail(user.id(), TALK, "00A1AAAA", null, NOW.minusDays(1), "P.CTSV [OSS]",
                    "oss@hcmiu.edu.vn", "Workshop", List.of("event"), false, List.of(), true, null);
            talk.getSessions().add(new SchoolMailSession(talk, LocalDate.of(2026, 10, 1), LocalTime.of(13, 30), null));
            db.persist(talk);
            db.persist(new SchoolMailStatus(user.id(), LocalDate.of(2026, 8, 1), true, NOW.minusHours(1)));
            db.persist(joinedOn(user, LocalDate.of(2026, 10, 3)));
            return user;
        });
    }

    @AfterEach
    void noTrace() {
        clock.reset();
        transaction.executeWithoutResult(status -> db.createQuery("delete from User u where u.id = :id")
                .setParameter("id", an.id()).executeUpdate());
    }

    static SchoolMailJoined joinedOn(AppUser who, LocalDate day) {
        return new SchoolMailJoined(who.id(), TALK, day, day.getDayOfMonth() == 1 ? LocalTime.of(13, 30)
                : LocalTime.of(18, 0), null, "Workshop", null, false, day.getDayOfMonth() != 1, NOW);
    }

    List<String> saved() {
        return transaction.execute(status -> joined.findAll().stream().filter(j -> j.getUserId().equals(an.id()))
                .map(j -> j.getDay() + " " + j.getStart()).sorted().toList());
    }

    @Test
    void theSecondClickOfADoubleClickStillEndsWithTheTickedSessions() throws Exception {
        // The other click is written between this Save's removal of the old sessions and its writing of the new.
        // (The spy's own answer passes a call on to the real repository.)
        Answer<?> real = mockingDetails(joined).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            transaction.executeWithoutResult(status -> db.persist(joinedOn(an, LocalDate.of(2026, 10, 1))));
            return real.answer(call);
        }).doAnswer(real).when(joined).saveAll(anyList());

        mvc.perform(post("/school/mailbox/" + TALK + "/join").with(user(an)).with(csrf())
                        .param("sessions", "2026-10-01T13:30"))
                .andExpect(redirectedUrl("/school/mailbox#mail-" + TALK));

        assertThat(saved()).containsExactly("2026-10-01 13:30");
    }

    @Test
    void aSaveThatFailsHalfwayKeepsTheSessionsJoinedBefore() {
        doThrow(new IllegalStateException("the database went away")).when(joined).saveAll(anyList());

        assertThatThrownBy(() -> mvc.perform(post("/school/mailbox/" + TALK + "/join").with(user(an)).with(csrf())
                .param("sessions", "2026-10-01T13:30"))).hasRootCauseMessage("the database went away");

        assertThat(saved()).containsExactly("2026-10-03 18:00");
    }
}
