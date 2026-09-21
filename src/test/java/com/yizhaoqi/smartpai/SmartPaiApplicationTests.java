package com.yizhaoqi.smartpai;

import com.yizhaoqi.smartpai.config.AdminUserInitializer;
import com.yizhaoqi.smartpai.config.EsIndexInitializer;
import com.yizhaoqi.smartpai.config.OrgTagInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class SmartPaiApplicationTests {

    @MockitoBean
    private EsIndexInitializer esIndexInitializer;

    @MockitoBean
    private AdminUserInitializer adminUserInitializer;

    @MockitoBean
    private OrgTagInitializer orgTagInitializer;

    @Test
    void contextLoads() {
    }

}
