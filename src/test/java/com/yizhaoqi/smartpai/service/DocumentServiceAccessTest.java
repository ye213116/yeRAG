package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceAccessTest {
    @Mock private FileUploadRepository files;
    @Mock private UserRepository users;
    @Mock private OrgTagCacheService tags;
    @Mock private com.yizhaoqi.smartpai.repository.DocumentVectorRepository vectors;
    @Mock private ElasticsearchService elasticsearch;
    @Mock private io.minio.MinioClient minio;
    @Mock private com.yizhaoqi.smartpai.repository.ChunkInfoRepository chunks;
    @InjectMocks private DocumentService documents;

    @Test void ownerWithoutOrganizationIsQueriedByDurableDatabaseId() {
        var user = new User();
        user.setId(17L);
        user.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of());
        documents.getAccessibleFiles("alice", null);
        verify(files).findByUserIdOrIsPublicTrue("17");
    }

    @Test void organizationAccessAlsoKeepsTheCorrectOwnerIdentity() {
        var user = new User();
        user.setId(17L);
        user.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of("engineering"));
        documents.getAccessibleFiles("alice", "untrusted-other-org");
        verify(files).findAccessibleFilesWithTags("17", List.of("engineering"));
    }
    @Test void deletingOneOwnersCopyPreservesOtherOwnersAndSharedObject() throws Exception {
        var own = new com.yizhaoqi.smartpai.model.FileUpload();
        own.setFileMd5("fixture-md5"); own.setUserId("17"); own.setFileName("shared.txt");
        when(files.findByFileMd5AndUserId("fixture-md5", "17")).thenReturn(Optional.of(own));
        lenient().when(files.countByFileMd5("fixture-md5")).thenReturn(2L);
        documents.deleteDocument("fixture-md5", "17");
        verify(minio, never()).removeObject(any(io.minio.RemoveObjectArgs.class));
        verify(files).deleteByFileMd5AndUserId("fixture-md5", "17");
        verify(vectors).deleteByFileMd5AndUserId("fixture-md5", "17");
        verify(elasticsearch).deleteByFileMd5AndUserId("fixture-md5", "17");
        verifyNoInteractions(chunks);
        verify(files, never()).deleteByFileMd5(anyString());
        verify(vectors, never()).deleteByFileMd5(anyString());
        verify(elasticsearch, never()).deleteByFileMd5(anyString());
    }

    @Test void nonOwnerCannotDeleteAnyStorage() {
        when(files.findByFileMd5AndUserId("fixture-md5", "19")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> documents.deleteDocument("fixture-md5", "19"));
        verifyNoInteractions(vectors, elasticsearch, minio);
        verify(files, never()).deleteByFileMd5(anyString());
    }

    @Test void lastOwnerDeletesPhysicalObjectAndStaleUploadMetadata() throws Exception {
        var own = new com.yizhaoqi.smartpai.model.FileUpload();
        own.setFileMd5("fixture-md5"); own.setUserId("17");
        when(files.findByFileMd5AndUserId("fixture-md5", "17")).thenReturn(Optional.of(own));
        when(files.countByFileMd5("fixture-md5")).thenReturn(1L);
        documents.deleteDocument("fixture-md5", "17");
        verify(minio).removeObject(org.mockito.ArgumentMatchers.argThat(args -> args.object().equals("merged/fixture-md5")));
        verify(chunks).deleteByFileMd5("fixture-md5");
        verify(files).deleteByFileMd5AndUserId("fixture-md5", "17");
    }

    @Test void failedIndexDeletionDoesNotReportSuccessOrRemoveMetadata() {
        var own = new com.yizhaoqi.smartpai.model.FileUpload(); own.setFileMd5("fixture-md5");
        when(files.findByFileMd5AndUserId("fixture-md5", "17")).thenReturn(Optional.of(own));
        doThrow(new IllegalStateException("index unavailable")).when(elasticsearch).deleteByFileMd5AndUserId("fixture-md5", "17");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> documents.deleteDocument("fixture-md5", "17"));
        verify(files, never()).deleteByFileMd5AndUserId(anyString(), anyString());
        verifyNoInteractions(vectors, minio, chunks);
    }

}
