package com.flj.config;

import cn.hutool.core.text.AntPathMatcher;
import jakarta.servlet.DispatcherType;
import com.flj.util.JwtAuthticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();

    private  static final String[] PUBLIC_PATHS = {
            "/",
            "/api/test",
            "/api/user/login",
            "/api/user/add",
            // Only the two dependency probes are public on the internal backend listener.
            "/internal/health/live",
            "/internal/health/ready",
            // 用户端知识库：公开科普内容，免登录可读（文章分页与详情，单段通配两者）
            "/api/knowledge/article/*",
            // 上传文件静态资源（封面图等）：<img> 标签匿名加载不带 token，必须公开读；上传接口本身仍限管理员
            "/upload/**"
    };

    public static Boolean isPublicPATH(String requestUri) {
        for (String publicPath : PUBLIC_PATHS) {
            if (antPathMatcher.match(publicPath, requestUri)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public JwtAuthticationFilter jwtAuthticationFilter() {
        return new JwtAuthticationFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF保护 （API服务通常不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 配置会话管理为无状态（JWT需要）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求的授权规则
                .authorizeHttpRequests(auth -> auth
                        // 放行 ASYNC 异步派发：SSE 流式接口结束时，容器会以 ASYNC 类型重新派发请求收尾，
                        // 此时 JWT 过滤器被跳过、无 SecurityContext，会被误判为未认证而抛 Access Denied。
                        // ASYNC 派发由容器内部触发、无法被外部伪造，放行是安全的。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 公开的路径，无需登录即可访问
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 放行错误转发路径，让不存在的接口正确返回404而不是被伪装成403
                        .requestMatchers("/error").permitAll()
                        // 管理端接口需要管理员角色（JWT 过滤器设置 ROLE_2 权限）
                        .requestMatchers("/api/admin/**").hasRole("2")
                        // 其他请求都需要认证
                        .anyRequest().authenticated()
                )
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
