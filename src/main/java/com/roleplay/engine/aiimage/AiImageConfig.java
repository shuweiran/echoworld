package com.roleplay.engine.aiimage;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL）：/ai-images/** 静态资源映射。
 *
 * <p>生成图片默认落盘 {@code src/main/resources/static/ai-images/}（classpath 静态目录，
 * URL 直接 /ai-images/...）；但运行时写入的文件在 jar 打包后不在 classpath 内
 * （P-0805-C 真机教训：classpath 静态映射 miss → SPA 兜底返回 index.html），
 * 因此额外注册 file: 资源映射指向同一目录——/ai-images/** 模式比 Spring 默认 /** 更具体，
 * 运行时文件优先走 file: 处理器，dev/classpath 与 jar 运行均可达。
 */
@Configuration
public class AiImageConfig implements WebMvcConfigurer {

    private final AiImageProperties props;

    public AiImageConfig(AiImageProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(props.getOutputDir()).toAbsolutePath();
        registry.addResourceHandler("/ai-images/**")
                .addResourceLocations(dir.toUri().toString());
    }
}
