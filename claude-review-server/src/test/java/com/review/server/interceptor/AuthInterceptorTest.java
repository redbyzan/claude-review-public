package com.review.server.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.review.server.service.MonitorService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    private AuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @Mock
    private MonitorService monitorService;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor("test-secret-123", monitorService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Nested
    class PathFiltering {
        @Test
        void skipsAuthForNonReviewPaths() throws Exception {
            request.setServletPath("/health");
            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }

        @Test
        void skipsAuthForRootPath() throws Exception {
            request.setServletPath("/");
            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }

        @Test
        void appliesAuthForReviewPath() throws Exception {
            request.setServletPath("/review");
            request.setMethod("POST");
            request.setContentType("application/json");
            request.addHeader("x-review-secret", "test-secret-123");
            request.addHeader("x-github-repo", "any-owner/repo");

            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }
    }

    @Nested
    class SecretValidation {
        @BeforeEach
        void configureReviewPath() {
            request.setServletPath("/review");
            request.setMethod("POST");
            request.setContentType("application/json");
            request.addHeader("x-github-repo", "any-owner/repo");
        }

        @Test
        void rejectsMissingSecret() throws Exception {
            assertThat(interceptor.preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void rejectsWrongSecret() throws Exception {
            request.addHeader("x-review-secret", "wrong-secret");
            assertThat(interceptor.preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void acceptsCorrectSecret() throws Exception {
            request.addHeader("x-review-secret", "test-secret-123");
            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }
    }

    @Nested
    class RepoHeaderValidation {
        @BeforeEach
        void configureRequest() {
            request.setServletPath("/review");
            request.setMethod("POST");
            request.setContentType("application/json");
            request.addHeader("x-review-secret", "test-secret-123");
        }

        @Test
        void rejectsMissingRepoHeader() throws Exception {
            assertThat(interceptor.preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void acceptsAnyOwnerWhenRepoHeaderPresent() throws Exception {
            request.addHeader("x-github-repo", "stranger/repo");
            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }
    }

    @Nested
    class ConversationPath {
        @Test
        void appliesAuthForConversationPath() throws Exception {
            request.setServletPath("/review/conversation");
            request.setMethod("POST");
            request.setContentType("application/json");
            request.addHeader("x-review-secret", "test-secret-123");
            request.addHeader("x-github-repo", "any-owner/repo");

            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }

        @Test
        void rejectsConversationWithoutSecret() throws Exception {
            request.setServletPath("/review/conversation");
            request.setMethod("POST");
            request.setContentType("application/json");
            request.addHeader("x-github-repo", "any-owner/repo");

            assertThat(interceptor.preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }
}
