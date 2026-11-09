package com.flj.service;

import cn.hutool.core.util.StrUtil;
import com.flj.DTO.response.FileUploadResponseDTO;
import com.flj.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地磁盘文件存储：不建文件元数据表，businessType/businessId/businessField
 * 仅作为目录结构约定（如 /upload/ARTICLE/{articleId}/cover_123.jpg）。
 * 上传目录由 application.yml 的 file.upload-dir 配置。
 */
@Service
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    // 与前端 ArticleDialog 的封面校验一致：5MB
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    // 路径段白名单（防路径穿越）
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Value("${file.upload-dir}")
    private String uploadDir;

    /**
     * 保存上传文件，返回可被 ResourceHandler（/upload/**）服务的相对路径
     */
    public FileUploadResponseDTO upload(MultipartFile file, String businessType, String businessId, String businessField) {
        // 服务端重校验（前端校验不可信）
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("图片大小不能超过5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("仅支持图片文件");
        }
        String originalFilename = file.getOriginalFilename();
        String extension = StrUtil.isBlank(originalFilename) || !originalFilename.contains(".")
                ? ""
                : originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("仅支持图片文件（jpg/jpeg/png/gif/webp/bmp）");
        }
        if (!SEGMENT_PATTERN.matcher(StrUtil.nullToEmpty(businessType)).matches()
                || !SEGMENT_PATTERN.matcher(StrUtil.nullToEmpty(businessId)).matches()
                || !SEGMENT_PATTERN.matcher(StrUtil.nullToEmpty(businessField)).matches()) {
            throw new BusinessException("非法的业务标识");
        }

        try {
            Path dir = Paths.get(uploadDir, businessType, businessId).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String filename = businessField + "_" + System.currentTimeMillis() + "." + extension;
            Files.copy(file.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

            FileUploadResponseDTO response = new FileUploadResponseDTO();
            response.setFilePath("/upload/" + businessType + "/" + businessId + "/" + filename);
            return response;
        } catch (IOException e) {
            log.error("文件保存失败: businessType={}, businessId={}", businessType, businessId, e);
            throw new BusinessException("文件保存失败，请稍后重试");
        }
    }

    /**
     * 删除本服务保存的文件（仅接受 /upload/ 相对路径；删除失败仅记录日志，不阻断业务）
     */
    public void deleteFile(String filePath) {
        if (StrUtil.isBlank(filePath) || !filePath.startsWith("/upload/")) {
            return;
        }
        try {
            Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path target = base.resolve(filePath.substring("/upload/".length())).normalize();
            // 防路径穿越：解析后必须仍在上传根目录内
            if (!target.startsWith(base)) {
                return;
            }
            Files.deleteIfExists(target);
            // 尝试清理空的业务目录（非空时删除失败，忽略）
            Path parent = target.getParent();
            if (parent != null && parent.startsWith(base)) {
                try {
                    Files.deleteIfExists(parent);
                } catch (IOException ignored) {
                    // 目录非空，正常情况
                }
            }
        } catch (IOException e) {
            log.warn("清理文件失败: {}", filePath);
        }
    }
}
