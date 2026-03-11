package com.example.ytdlweb

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/css/**", "/js/**").permitAll()
                auth.anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())

        return http.build()
    }

    @Bean
    fun userDetailsService(env: Environment): UserDetailsService {

        val usersEnv = env.getProperty("APP_USERS") ?: "admin:admin"
        val users: List<UserDetails> = usersEnv.split(",")
            .filter { it.isNotBlank() }
            .map {
                val (username, password) = it.split(":")
                User.withDefaultPasswordEncoder()
                    .username(username)
                    .password(password)
                    .roles("USER")
                    .build()
            }

        return InMemoryUserDetailsManager(users)
    }
}
