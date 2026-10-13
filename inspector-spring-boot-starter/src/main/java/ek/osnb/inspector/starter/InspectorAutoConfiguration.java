package ek.osnb.inspector.starter;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Active only when {@link InspectorEnvironmentPostProcessor} found a running inspector.
 * Reports tool executions and, when the app uses {@code ChatClient}, each call with its
 * advisor chain, from the observations Spring AI emits.
 */
// After Boot's own observation setup, so the registry below only fills in a missing one.
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(ToolCallingObservationContext.class)
@ConditionalOnProperty(name = "agent.inspector.active", havingValue = "true")
public class InspectorAutoConfiguration {

    /** ChatClient is optional: an app may use ChatModel directly. */
    private static final String CHAT_CLIENT = "org.springframework.ai.chat.client.observation.ChatClientObservationContext";

    @Bean
    InspectorClient agentInspectorClient(Environment environment) {
        return new InspectorClient(environment.getProperty("agent.inspector.url", InspectorEnvironmentPostProcessor.DEFAULT_URL)
                .replaceAll("/+$", ""));
    }

    /**
     * Spring AI only emits tool-call observations when there is an {@link ObservationRegistry}.
     * Apps without Actuator have none, so provide one.
     */
    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry agentInspectorObservationRegistry() {
        return ObservationRegistry.create();
    }

    /**
     * Attaches the handler to whichever registry the app ends up with, and wraps the tool
     * exception processor so failing tools are reported as failures.
     */
    @Bean
    static BeanPostProcessor agentInspectorBeanPostProcessor(ObjectProvider<InspectorClient> client,
                                                             Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ObservationRegistry registry) {
                    String app = environment.getProperty("agent.inspector.app", "app");
                    registry.observationConfig().observationHandler(new ToolExecutionObservationHandler(client.getObject(), app));
                    if (ClassUtils.isPresent(CHAT_CLIENT, getClass().getClassLoader())) {
                        registry.observationConfig().observationHandler(new ChatClientObservationHandler(client.getObject(), app));
                    }
                }
                if (bean instanceof ToolExecutionExceptionProcessor processor && !(bean instanceof ToolFailures.Recording)) {
                    return new ToolFailures.Recording(processor);
                }
                return bean;
            }
        };
    }
}
