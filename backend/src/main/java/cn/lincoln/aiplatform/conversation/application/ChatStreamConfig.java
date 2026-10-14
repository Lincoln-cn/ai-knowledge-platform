package cn.lincoln.aiplatform.conversation.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 会话模块的配置装配（第 17 周 · 0.4.5）。
 *
 * <h3>★ 为什么需要这一个只有一行注解的类</h3>
 *
 * <p>{@link ChatStreamProperties} 是一个 <strong>record</strong>（值语义 + 构造即校验），而 record
 * <strong>不能</strong>靠"类级 {@code @Component}"被当成配置属性绑定 —— 那样 Spring 会把它当成一个普通 bean 去
 * <strong>自动装配它的构造器参数</strong>（症状是启动时报 {@code No qualifying bean of type 'long'}， 本批实测踩到过）。
 *
 * <p>所以绑定必须由容器显式启用。放这里而不是放在某个控制器上：控制器是<strong>一个入口</strong>， 而这份配置是<strong>整个链路</strong>的（编排、心跳、落库
 * flush 都读它）—— 挂在入口上会让"换个入口（公开面问答）"时配置悄悄不见。
 */
@Configuration
@EnableConfigurationProperties(ChatStreamProperties.class)
public class ChatStreamConfig {}
