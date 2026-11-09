package com.flj.controller;

import com.flj.DTO.response.FileUploadResponseDTO;
import com.flj.common.Result;
import com.flj.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传：通用工具端点（预留用户端上传共享面，如头像），
 * 方法级 @PreAuthorize 限管理员——不迁入 /api/admin/** 的原因见设计讨论（Q13）。
 */
@RestController
@RequestMapping("/api/file")
public class FileUploadController {

    @Autowired
    private FileStorageService fileStorageService;

    /**
     * 上传文件（当前消费方：管理端文章封面，businessType=ARTICLE）
     */
    @PreAuthorize("hasRole('2')")
    @PostMapping("/upload")
    public Result<FileUploadResponseDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String businessType,
            @RequestParam String businessId,
            @RequestParam String businessField) {
        return Result.ok(fileStorageService.upload(file, businessType, businessId, businessField));
    }
}
