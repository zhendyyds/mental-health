package com.flj.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源映射：/upload/** 指向本地磁盘上传目录（封面图等，
 * 存储方案：本地磁盘 + Spring 直读，不建文件元数据表）。
 * 前端以 fileBaseUrl + filePath 拼接完整地址访问。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toUri() 产出规范的 file:/// 形式（兼容 Windows 路径）；目录 location 必须以 / 结尾
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/upload/**")
                .addResourceLocations(location);
    }
}
