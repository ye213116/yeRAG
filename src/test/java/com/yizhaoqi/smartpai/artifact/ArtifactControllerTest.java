package com.yizhaoqi.smartpai.artifact;

import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArtifactControllerTest {
    final ArtifactService artifacts = mock(ArtifactService.class);
    final UserRepository users = mock(UserRepository.class);
    final String id = UUID.randomUUID().toString();
    MockMvc mvc;
    @BeforeEach void setup() {
        var alice = new User(); alice.setId(1L); alice.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(artifacts, users)).build();
    }
    @Test void requestsUsePrincipalAndReturnImmutableVersionMetadata() throws Exception {
        when(artifacts.read(1, id, 1, SourceMode.AUTO)).thenReturn(new ArtifactStore.Version(id, 1, "标题", "正文", List.of(), "now"));
        mvc.perform(get("/api/v1/artifacts/"+id).param("version","1").param("userId","2").principal(() -> "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        verify(artifacts).read(1, id, 1, SourceMode.AUTO);
    }
    @Test void anonymousCannotReadOrSave() throws Exception {
        mvc.perform(get("/api/v1/artifacts")).andExpect(status().isUnauthorized());
        verifyNoInteractions(artifacts);
    }
    @Test void conflictKeepsHttpStatusAndDoesNotReturnSuccessEnvelope() throws Exception {
        when(artifacts.save(eq(1L), any(), eq(List.of()), eq(SourceMode.AUTO))).thenThrow(ArtifactStore.conflict("版本已更新"));
        mvc.perform(post("/api/v1/artifacts/"+id+"/versions").principal(() -> "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"content\":\"正文\",\"expectedVersion\":1,\"requestId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.message").value("版本已更新"));
    }
}
