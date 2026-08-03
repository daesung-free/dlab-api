package com.dlab.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 시각은 반드시 이 Clock을 통해 얻는다.
     * 방화벽 타임아웃 판별·미등원 배치처럼 시각이 곧 로직인 부분을 테스트에서 고정하기 위함.
     */
    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
