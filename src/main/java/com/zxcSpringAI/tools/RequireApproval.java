package com.zxcSpringAI.tools;

import java.lang.annotation.*;

/**
 * 标记需要人工授权才能执行的工具
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireApproval {
    /** 授权原因,中断时展示给用户 */
    String reason() default "";
}
