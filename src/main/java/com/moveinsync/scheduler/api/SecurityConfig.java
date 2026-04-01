package com.moveinsync.scheduler.api;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT username,password_hash,role,tenant_id FROM app_user WHERE username=?",rs -> {
            if (!rs.next()) throw new UsernameNotFoundException("Unknown user");
            return new User(rs.getString(1),rs.getString(2),List.of(new SimpleGrantedAuthority("ROLE_"+rs.getString(3))));
        },username);
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).httpBasic(withDefaults()).authorizeHttpRequests(a -> a
            .requestMatchers("/", "/index.html", "/app.css", "/app.js", "/favicon.svg").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/actuator/**").hasRole("ADMIN")
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll()).build();
    }
    @Bean CommandLineRunner bootstrap(JdbcTemplate jdbc,PasswordEncoder encoder,@Value("${app.bootstrap-admin-password}") String password) {
        return args -> jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES ('admin',?,'ADMIN') ON CONFLICT(username) DO NOTHING",encoder.encode(password));
    }
}
