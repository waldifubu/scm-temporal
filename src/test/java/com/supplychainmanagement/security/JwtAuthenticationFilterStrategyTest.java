package com.supplychainmanagement.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which store the filter writes the authentication into.
 * <p>
 * It wrote into the static {@code SecurityContextHolder}, a JVM-wide variable, while the filters that
 * <em>read</em> it - the {@code AuthorizationFilter} - use the strategy of their own application
 * context. One context makes them the same object. A second context started in the same JVM replaces
 * the static one (the Vaadin integration installs its strategy into it for every context), and from
 * then on the two disagree: a valid token ended as an unauthenticated 401 with an empty body.
 * <p>
 * Found by a test, not in production: {@code MeEndpointTest} passed or failed depending on whether
 * {@code CorsPreflightTest}, which starts another context, ran in between. That is the symptom - and
 * a result that depends on the order of unrelated tests is not something to rely on to catch it
 * again, so this holds the property directly, with no Spring context at all.
 */
class JwtAuthenticationFilterStrategyTest {

    private final JwtTokenProvider provider = mock(JwtTokenProvider.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider, userDetailsService, resolver);

    /**
     * A store of its own, standing in for the strategy of one application context. Spring's own
     * thread-local implementation is not public, and the interface is small.
     */
    private static final class OwnStore implements SecurityContextHolderStrategy {
        private final ThreadLocal<SecurityContext> holder = new ThreadLocal<>();

        @Override
        public void clearContext() {
            holder.remove();
        }

        @Override
        public SecurityContext getContext() {
            SecurityContext context = holder.get();
            if (context == null) {
                context = createEmptyContext();
                holder.set(context);
            }
            return context;
        }

        @Override
        public void setContext(SecurityContext context) {
            holder.set(context);
        }

        @Override
        public SecurityContext createEmptyContext() {
            return new SecurityContextImpl();
        }
    }

    private final SecurityContextHolderStrategy own = new OwnStore();

    @BeforeEach
    void cleanStores() {
        SecurityContextHolder.clearContext();
        own.clearContext();
    }

    @AfterEach
    void leaveNothingBehind() {
        SecurityContextHolder.clearContext();
        own.clearContext();
    }

    private static MockHttpServletRequest requestWithAToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/1.0/shipments");
        request.addHeader("Authorization", "Bearer some.token");
        return request;
    }

    private void aValidTokenFor(String username) {
        when(provider.getUsername("some.token")).thenReturn(username);
        when(userDetailsService.loadUserByUsername(username)).thenReturn(
                new User(username, "x", List.of(new SimpleGrantedAuthority("WAREHOUSE"))));
    }

    /**
     * The property that matters: the authentication lands in the strategy the filter was given - the
     * one the authorization filters of the same context read - and the static holder is not touched.
     */
    @Test
    void writesTheAuthenticationIntoTheStrategyItWasGiven() throws Exception {
        aValidTokenFor("sam");
        filter.setSecurityContextHolderStrategy(own);
        AtomicReference<Authentication> seenDownstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> seenDownstream.set(own.getContext().getAuthentication());

        filter.doFilter(requestWithAToken(), new MockHttpServletResponse(), chain);

        assertThat(seenDownstream.get()).isNotNull();
        assertThat(seenDownstream.get().getName()).isEqualTo("sam");
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the static holder is another store and must stay as it was")
                .isNull();
    }

    /** Whatever is in the static holder stays there - the filter does not reach past its own store. */
    @Test
    void leavesWhatIsInTheStaticHolderAlone() throws Exception {
        aValidTokenFor("sam");
        filter.setSecurityContextHolderStrategy(own);
        Authentication other = new TestingAuthenticationToken("someone-else", "x", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(other);

        filter.doFilter(requestWithAToken(), new MockHttpServletResponse(), (request, response) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(other);
    }

    /** With no strategy bean in the context - nothing is injected - the static holder is the default, as before. */
    @Test
    void fallsBackToTheStaticHolderWhenNoStrategyWasGiven() throws Exception {
        aValidTokenFor("sam");
        AtomicReference<Authentication> seenDownstream = new AtomicReference<>();
        FilterChain chain = (request, response) ->
                seenDownstream.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(requestWithAToken(), new MockHttpServletResponse(), chain);

        assertThat(seenDownstream.get()).isNotNull();
        assertThat(seenDownstream.get().getName()).isEqualTo("sam");
    }

    /**
     * A failed token clears the same store it would have written to - otherwise a stale authentication
     * left in it by an earlier request on the thread would survive the refusal.
     */
    @Test
    void clearsTheStrategyItWasGivenWhenTheTokenFails() throws Exception {
        filter.setSecurityContextHolderStrategy(own);
        own.getContext().setAuthentication(new TestingAuthenticationToken("stale", "x", "ADMIN"));
        when(provider.getUsername(any())).thenThrow(new IllegalArgumentException("not a token"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(requestWithAToken(), new MockHttpServletResponse(), chain);

        assertThat(own.getContext().getAuthentication()).isNull();
        verify(resolver).resolveException(any(), any(), any(), any());
        verify(chain, never()).doFilter(any(), any());
    }
}
