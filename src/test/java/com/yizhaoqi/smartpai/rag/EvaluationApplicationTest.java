package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.eval.EvaluationApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationApplicationTest {
    static final AtomicInteger sideEffects=new AtomicInteger();
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    static class StartupFixture {
        @Bean Object esIndexInitializer() { sideEffects.incrementAndGet(); return new Object(); }
        @Bean Object adminUserInitializer() { sideEffects.incrementAndGet(); return new Object(); }
        @Bean Object orgTagInitializer() { sideEffects.incrementAndGet(); return new Object(); }
        @Bean Object legacyMemoryMigration() { sideEffects.incrementAndGet(); return new Object(); }
        @Bean Object memoryController() { return new Object(); }
        @Bean String sharedPipelineDependency() { return "kept"; }
    }
    @Test void evaluationContextDoesNotInitializeBusinessStateOrStartScheduledWorkers() {
        sideEffects.set(0);
        new ApplicationContextRunner().withPropertyValues("ai.mcp.enabled=true","server.port=8081","spring.jpa.hibernate.ddl-auto=create")
            .withInitializer(EvaluationApplication::isolate).withUserConfiguration(StartupFixture.class).run(context -> {
                assertEquals(0,sideEffects.get());
                assertFalse(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
                assertEquals("kept",context.getBean("sharedPipelineDependency"));
                assertEquals("false",context.getEnvironment().getProperty("ai.mcp.enabled"));
                assertEquals("0",context.getEnvironment().getProperty("server.port"));
                assertEquals("none",context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            });
    }
}
