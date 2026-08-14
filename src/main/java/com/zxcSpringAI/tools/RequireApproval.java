package com.zxcSpringAI.tools;

import java.lang.annotation.*;

/**
 * 标记需要人工授权才能执行的工具
 *
 * <p>贴此注解的 @Tool 方法,LLM 决定调用后 graph 会在 review 节点前中断,
 * 等待用户 approve 后才执行。未贴注解的工具(如只读检索、天气查询)自主直连 tools 节点。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireApproval {
    /** 授权原因,中断时展示给用户 */
    String reason() default "";
}
