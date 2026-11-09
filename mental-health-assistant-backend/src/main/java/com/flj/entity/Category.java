package com.flj.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文章分类：当前为单级（parent_id 预留层级，恒为 0，接口返回平铺）。
 * 表 knowledge_category 随原项目库导入（含 category_code/sort_order 等本功能未消费的列）。
 */
@Data
@TableName("knowledge_category")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    // 分类ID
    @TableId(type = IdType.AUTO)
    private Long id;

    // 分类名
    @TableField("category_name")
    private String categoryName;

    // 父分类ID（预留层级，当前恒为 NULL）
    @TableField("parent_id")
    private Long parentId;

    // 创建时间
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 更新时间
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
