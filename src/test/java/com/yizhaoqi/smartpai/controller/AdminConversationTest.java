package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.config.*;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.service.CustomUserDetailsService;
import com.yizhaoqi.smartpai.service.UserService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.MinioMigrationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OrgTagAuthorizationFilter.class})
class AdminConversationTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean JwtUtils jwt;
    @MockitoBean UserService userService;
    @MockitoBean OrganizationTagRepository orgs;
    @MockitoBean MinioMigrationUtil migration;
    @MockitoBean MemoryStore memory;
    @MockitoBean CustomUserDetailsService details;
    @MockitoBean FileUploadRepository files;

    @BeforeEach void admin() {
        var admin = new User();
        admin.setId(1L); admin.setUsername("admin"); admin.setRole(User.Role.ADMIN);
        when(jwt.extractUsernameFromToken("admin-token")).thenReturn("admin");
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
    }

    @Test void historyPageEndpointExistsForAuthenticatedAdmin() throws Exception {
        var zone = ZoneId.systemDefault();
        var start = LocalDate.parse("2026-10-20").atStartOfDay(zone).toInstant();
        var end = LocalDate.parse("2026-10-29").atStartOfDay(zone).toInstant();
        when(memory.completedHistory(2, start, end)).thenReturn(List.of(
                new Turn(1, 2, "session", "request", "question", "answer", "COMPLETED", start)));
        mvc.perform(history().with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("question"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"))
                .andExpect(jsonPath("$.data[1].content").value("answer"))
                .andExpect(jsonPath("$.data[1].status").value("finished"));
        verify(memory).completedHistory(2, start, end);
    }

    @Test void ordinaryUserCannotReadAnotherUsersHistory() throws Exception {
        mvc.perform(history().with(user("alice").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(memory);
    }

    @Test void anonymousRequestIsUnauthorizedRatherThanForbidden() throws Exception {
        mvc.perform(history()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(memory);
    }

    @Test void missingEndpointKeepsItsNotFoundStatusForAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test void rejectsReversedDatesBeforeReadingHistory() throws Exception {
        mvc.perform(get("/api/v1/admin/conversation").with(user("admin").roles("ADMIN"))
                        .header("Authorization", "Bearer admin-token")
                        .param("userid", "2").param("start_date", "2026-10-28").param("end_date", "2026-10-20"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memory);
    }

    @Test void obsoletePlaceholderEndpointsAreNotPublished() throws Exception {
        var requests = List.of(
                get("/api/v1/admin/system/status"),
                get("/api/v1/admin/user-activities"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/knowledge/unused"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/admin/knowledge/add")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "fixture.txt", "text/plain", "fixture".getBytes()))
                        .param("description", "fixture"));
        for (var request : requests) {
            mvc.perform(request.with(user("admin").roles("ADMIN"))
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isNotFound());
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder history() {
        return get("/api/v1/admin/conversation").header("Authorization", "Bearer admin-token")
                .param("userid", "2").param("start_date", "2026-10-20").param("end_date", "2026-10-28");
    }
}
