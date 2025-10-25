package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * redisson 客户端配置； 也可以配置文件去配置
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置 RedissonClient连接
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.83.123:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
