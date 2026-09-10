package com.wms.web;

import com.wms.filter.JwtAuthFilter;
import com.wms.filter.UserStatusFilter;
import com.wms.dao.UserDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RoleInterceptor roleInterceptor;

    @Autowired
    private UserDao userDao;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }

    /**
     * 显式构造 JwtAuthFilter (不在类上 @Component)。
     * 这样 Spring Boot 不会自动再加一次,且 FilterRegistrationBean 可固定链顺序。
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(com.wms.service.JwtService jwt) {
        JwtAuthFilter f = new JwtAuthFilter();
        f.setJwtService(jwt);
        return f;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter f) {
        FilterRegistrationBean<JwtAuthFilter> r = new FilterRegistrationBean<>(f);
        r.addUrlPatterns("/api/*");
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return r;
    }

    @Bean
    public UserStatusFilter userStatusFilter() {
        return new UserStatusFilter(userDao);
    }

    @Bean
    public FilterRegistrationBean<UserStatusFilter> userStatusFilterRegistration(UserStatusFilter f) {
        FilterRegistrationBean<UserStatusFilter> r = new FilterRegistrationBean<>(f);
        r.addUrlPatterns("/api/*");
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return r;
    }
}