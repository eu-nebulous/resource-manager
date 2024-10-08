package eu.nebulous.resource.discovery;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.security.SecureRandom;
import java.util.Collections;

import static org.springframework.security.config.Customizer.withDefaults;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final static String USERNAME_REQUEST_HEADER = "X-SSO-USER";
    private final static String USERNAME_REQUEST_PARAM = "ssoUser";
    private final static String API_KEY_REQUEST_HEADER = "X-API-KEY";
    private final static String API_KEY_REQUEST_PARAM = "apiKey";

    public final static String SSO_USER_DEFAULT = "anonymous";
    public final static String SSO_USER_PREFIX = "SSO ";
    public final static String SSO_USER_ROLE = "ROLE_SSO_USER";

    private final ResourceDiscoveryProperties properties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .formLogin(withDefaults())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers(
                        "/discovery/**", "/*.html").authenticated())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterAfter(apiKeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.ALWAYS));

        return httpSecurity.build();
    }

    public void configAuthentication(AuthenticationManagerBuilder auth) throws Exception {
        auth.inMemoryAuthentication().passwordEncoder(passwordEncoder());
    }

    @Bean
    public InMemoryUserDetailsManager inMemoryUserDetailsManager() {
        return new InMemoryUserDetailsManager(
                properties.getUsers().stream()
                        .map(userData -> User.builder()
                                .username(userData.getUsername())
                                .password(userData.getPassword())
                                .roles(userData.getRoles().toArray(new String[0]))
                                .build())
                        .toList());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        int strength = 10;  // iterations
        return new BCryptPasswordEncoder(strength, new SecureRandom());
    }

    /*@Bean
    public PasswordEncoder encoder() {
        // Clear-text password encoder
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return rawPassword.toString();
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword.toString().equals(encodedPassword);
            }
        };
    }*/

    public Filter apiKeyAuthenticationFilter() {
        return (servletRequest, servletResponse, filterChain) -> {
            if (properties.isApiKeyAuthenticationEnabled() && StringUtils.isNotBlank(properties.getApiKeyValue())) {
                if (servletRequest instanceof HttpServletRequest request && servletResponse instanceof HttpServletResponse) {

                    String apiKey = request.getHeader(API_KEY_REQUEST_HEADER);
                    if (StringUtils.isBlank(apiKey)) {
                        apiKey = request.getParameter(API_KEY_REQUEST_PARAM);
                    }
                    if (StringUtils.isNotBlank(apiKey)) {
                        log.debug("apiKeyAuthenticationFilter: API Key found");

                        if (properties.getApiKeyValue().equals(apiKey)) {
                            log.debug("apiKeyAuthenticationFilter: API Key is correct");
                            try {
                                // Get SSO username if passed
                                String username = request.getHeader(USERNAME_REQUEST_HEADER);
                                if (StringUtils.isBlank(username)) {
                                    username = request.getParameter(USERNAME_REQUEST_PARAM);
                                }
                                if (StringUtils.isBlank(username)) {
                                    username = SSO_USER_DEFAULT;
                                }
                                username = SSO_USER_PREFIX + username;

                                // construct one of Spring's auth tokens
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(username, properties.getApiKeyValue(),
                                                Collections.singletonList(new SimpleGrantedAuthority(SSO_USER_ROLE)));
                                // store completed authentication in security context
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                                log.info("apiKeyAuthenticationFilter: Successful authentication with API Key. SSO user: {}", username);
                            } catch (Exception e) {
                                log.error("apiKeyAuthenticationFilter: EXCEPTION: ", e);
                            }
                        } else {
                            log.debug("apiKeyAuthenticationFilter: API Key is incorrect");
                        }
                    } else {
                        log.debug("apiKeyAuthenticationFilter: No API Key found in request headers or parameters");
                    }
                } else {
                    throw new IllegalArgumentException("API Key Authentication filter does not support non-HTTP requests and responses. Req-class: "
                            +servletRequest.getClass().getName()+"  Resp-class: "+servletResponse.getClass().getName());
                }
            }

            // continue down the chain
            filterChain.doFilter(servletRequest, servletResponse);
        };
    }
}
