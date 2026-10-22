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
}
