package com.yizhaoqi.smartpai.rag.eval;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import java.util.List;
import java.util.Map;

/** Run the existing application wiring once and close it, including workers and HTTP resources. */
public final class EvaluationApplication {
    private EvaluationApplication() {}
    public static void main(String[] args) {
        SpringApplication app=new SpringApplication(SmartPaiApplication.class);
        app.addInitializers(EvaluationApplication::isolate);
        try(var context=app.run(args)) { SpringApplication.exit(context); }
    }
    /** Only this CLI context: never bootstrap accounts/indexes or process unrelated memory jobs. */
    public static void isolate(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("evaluation-isolation",Map.of(
            "server.port","0","server.address","127.0.0.1","spring.kafka.listener.auto-startup","false","ai.mcp.enabled","false",
            "spring.jpa.hibernate.ddl-auto","none","spring.sql.init.mode","never")));
        context.addBeanFactoryPostProcessor(factory -> {
            if(factory instanceof BeanDefinitionRegistry registry) {
                for(String name:List.of("esIndexInitializer","adminUserInitializer","orgTagInitializer","memoryController","legacyMemoryMigration",
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    if(registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
            }
        });
    }
}
