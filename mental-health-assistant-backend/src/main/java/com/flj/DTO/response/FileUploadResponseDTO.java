package com.flj.DTO.response;

import lombok.Data;

/**
 * 文件上传结果：filePath 为相对路径（如 /upload/ARTICLE/{businessId}/cover_xxx.jpg），
 * 前端以 fileBaseUrl + filePath 拼接完整地址。
 */
@Data
public class FileUploadResponseDTO {
    private String filePath;
}
