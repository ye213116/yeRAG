package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class DocumentDownloadTest {
    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    @Mock private DocumentService documents;
    @InjectMocks private DocumentController controller;
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void citationDownloadUsesAuthenticatedIdentityAndExactMd5() throws Exception {
        var file = new FileUpload();
        file.setFileMd5(MD5);
        file.setFileName("citation.txt");
        file.setTotalSize(42);
        when(documents.getAccessibleFiles("alice", null)).thenReturn(List.of(file));
        when(documents.generateDownloadUrl(MD5)).thenReturn("https://storage.example/signed");

        mvc.perform(get("/api/v1/documents/download-by-md5")
                        .param("fileMd5", MD5).param("userId", "another-user")
                        .param("token", "untrusted-query-token")
                        .principal(new UsernamePasswordAuthenticationToken("alice", "unused", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("citation.txt"))
                .andExpect(jsonPath("$.data.downloadUrl").value("https://storage.example/signed"));
        verify(documents).getAccessibleFiles("alice", null);
    }

    @Test void inaccessibleDocumentDoesNotProduceDownloadUrl() throws Exception {
        when(documents.getAccessibleFiles("bob", null)).thenReturn(List.of());
        mvc.perform(get("/api/v1/documents/download-by-md5").param("fileMd5", MD5)
                        .principal(new UsernamePasswordAuthenticationToken("bob", "unused", List.of())))
                .andExpect(status().isNotFound());
        verify(documents, never()).generateDownloadUrl(anyString());
    }

    @Test void queryTokenCannotReplaceAuthenticatedIdentity() throws Exception {
        mvc.perform(get("/api/v1/documents/download-by-md5").param("fileMd5", MD5)
                        .param("token", "untrusted-query-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(documents);
    }

    @Test void invalidMd5IsRejectedBeforeLookup() throws Exception {
        mvc.perform(get("/api/v1/documents/download-by-md5").param("fileMd5", "not-an-md5")
                        .principal(new UsernamePasswordAuthenticationToken("alice", "unused", List.of())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(documents);
    }
}
