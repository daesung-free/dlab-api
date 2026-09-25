package com.dlab.common.config;

import com.dlab.domain.audit.AuditChangeInterceptor;
import java.util.Map;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * 감사 로그용 Hibernate 인터셉터 등록.
 *
 * <p>변경 전 값은 JPA 콜백으로는 알 수 없어 Hibernate 쪽에서 받아야 한다
 * ({@link AuditChangeInterceptor}).
 *
 * <p><b>스프링 빈으로 주입하지 않고 인스턴스를 그대로 넘긴다.</b> 인터셉터는
 * 세션팩토리가 만들어질 때 필요한데, 빈으로 두면 초기화 순서에 얽혀
 * {@code EntityManagerFactory} 생성이 자기 자신을 기다리는 일이 생긴다.
 */
@Configuration
public class AuditInterceptorConfig implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.INTERCEPTOR, new AuditChangeInterceptor());
    }
}
