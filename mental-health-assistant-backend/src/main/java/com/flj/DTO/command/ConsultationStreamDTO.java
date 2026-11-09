package com.flj.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ConsultationStreamDTO {
    @NotBlank(message = "clientRequestId不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]{8,128}", message = "clientRequestId格式错误")
    private String clientRequestId;

    @NotBlank(message = "sessionId不能为空")
    private String sessionId;

    @NotBlank(message = "初始消息不能为空")
    @Size(max = 2000, message = "初始消息长度不能超过2000个字符")
    private String userMessage;
}
