package ru.gumerbaev.ytdlweb

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class SecurityConfigTest {

    @Nested
    @SpringBootTest
    inner class NoUsersSpecifiedTest {

        @Autowired
        private lateinit var context: WebApplicationContext

        @MockitoBean
        private lateinit var ytDlpService: YtDlpService

        private lateinit var mockMvc: MockMvc

        @BeforeEach
        fun setup() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        }

        @Test
        fun `app is accessible without credentials when APP_USERS is not set`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isOk)
        }
    }

    @Nested
    @SpringBootTest(properties = ["APP_USERS="])
    inner class EmptyUsersSpecifiedTest {

        @Autowired
        private lateinit var context: WebApplicationContext

        @MockitoBean
        private lateinit var ytDlpService: YtDlpService

        private lateinit var mockMvc: MockMvc

        @BeforeEach
        fun setup() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        }

        @Test
        fun `app is accessible without credentials when APP_USERS is empty`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isOk)
        }
    }

    @Nested
    @SpringBootTest(properties = ["APP_USERS=   "])
    inner class WhitespaceUsersSpecifiedTest {

        @Autowired
        private lateinit var context: WebApplicationContext

        @MockitoBean
        private lateinit var ytDlpService: YtDlpService

        private lateinit var mockMvc: MockMvc

        @BeforeEach
        fun setup() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        }

        @Test
        fun `app is accessible without credentials when APP_USERS is whitespace only`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isOk)
        }
    }

    @Nested
    @SpringBootTest(properties = ["APP_USERS=alice:secret,bob:pass"])
    inner class UsersSpecifiedTest {

        @Autowired
        private lateinit var context: WebApplicationContext

        @MockitoBean
        private lateinit var ytDlpService: YtDlpService

        private lateinit var mockMvc: MockMvc

        @BeforeEach
        fun setup() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        }

        @Test
        fun `unauthenticated request to root is unauthorized when APP_USERS is set`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `authenticated request with valid credentials succeeds`() {
            mockMvc.perform(get("/").with(httpBasic("alice", "secret")))
                .andExpect(status().isOk)

            mockMvc.perform(get("/").with(httpBasic("bob", "pass")))
                .andExpect(status().isOk)
        }

        @Test
        fun `authenticated request with invalid credentials fails`() {
            mockMvc.perform(get("/").with(httpBasic("alice", "wrongpass")))
                .andExpect(status().isUnauthorized)

            mockMvc.perform(get("/").with(httpBasic("admin", "admin")))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `static resources are accessible without authentication`() {
            mockMvc.perform(get("/css/style.css"))
                .andExpect(status().isNotFound) // static file not found, but not 401 Unauthorized
        }
    }
}
