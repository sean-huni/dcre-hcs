package za.co.fnb.dcre.hcs.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Manual resilience4j core wiring (no resilience4j-spring-boot modules: their
 * Boot 4 compatibility is unverified). Breaker state lives and dies with the
 * ephemeral job JVM; thresholds are sized for one run's handful of fetches.
 */
@Configuration
public class ResilienceConfig {

    static final int SLIDING_WINDOW_SIZE = 4;
    static final int MINIMUM_NUMBER_OF_CALLS = 2;
    static final Duration WAIT_IN_OPEN_STATE = Duration.ofSeconds(30);

    @Bean
    public CircuitBreaker nagerCircuitBreaker() {
        return CircuitBreaker.of("nager", CircuitBreakerConfig.custom()
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .waitDurationInOpenState(WAIT_IN_OPEN_STATE)
                .build());
    }
}
