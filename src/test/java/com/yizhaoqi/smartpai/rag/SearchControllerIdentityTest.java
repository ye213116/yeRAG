package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.controller.SearchController;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchControllerIdentityTest {
    @Test void usesAuthenticatedUsernameAndNeverUnrestrictedCompatibilitySearch() {
        var search=mock(HybridSearchService.class); var controller=new SearchController();
        ReflectionTestUtils.setField(controller,"hybridSearchService",search);
        when(search.searchWithPermission("q","name:123",5)).thenReturn(List.of());
        controller.hybridSearch("q",5,() -> "123");
        verify(search).searchWithPermission("q","name:123",5); verify(search,never()).search(anyString(),anyInt());
        assertEquals(401,assertThrows(ResponseStatusException.class,() -> controller.hybridSearch("q",5,null)).getStatusCode().value());
    }
}
