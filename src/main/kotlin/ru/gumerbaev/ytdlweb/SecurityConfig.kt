package ru.gumerbaev.ytdlweb

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

    private fun parseUsers(env: Environment): List<UserDetails> {
        val usersEnv = env.getProperty("APP_USERS") ?: return emptyList()
        return usersEnv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(":") }
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                val username = parts[0].trim()
                val password = parts[1].trim()
                if (username.isNotEmpty()) {
                    @Suppress("DEPRECATION")
                    User.withDefaultPasswordEncoder()
                        .username(username)
                        .password(password)
                        .roles("USER")
                        .build()
                } else null
            }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, env: Environment): SecurityFilterChain {
        val users = parseUsers(env)
        http.csrf { it.disable() }

        if (users.isEmpty()) {
            http.authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }
        } else {
            http
                .authorizeHttpRequests { auth ->
                    auth.requestMatchers("/css/**", "/js/**").permitAll()
                    auth.anyRequest().authenticated()
                }
                .httpBasic(Customizer.withDefaults())
        }

        return http.build()
    }

    @Bean
    fun userDetailsService(env: Environment): UserDetailsService {
        val users = parseUsers(env)
        return InMemoryUserDetailsManager(users)
    }
}
