package io.github.osmanbutt.inspector.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the UI from {@code classpath:/agent-inspector/static/} instead of {@code /static/}: on a
 * host application's classpath, files in {@code /static/} would show up on the host's own website.
 */
@Configuration
// Before Boot's own static resource handling, which then leaves /** to us.
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebConfig implements WebMvcConfigurer {

    static final String UI = "classpath:/agent-inspector/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations(UI);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
