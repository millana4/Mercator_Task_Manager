package com.mercator.taskmanager;

import com.mercator.taskmanager.config.NicheApiKeyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Межсервисный ключ на эндпоинтах ниш. */
class NicheApiKeyFilterTest {

    private MockHttpServletResponse run(NicheApiKeyFilter filter, String path,
                                        String authorization, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    @Test
    void rejectsWithoutKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = run(new NicheApiKeyFilter("secret"), "/api/v1/niches", null, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsWrongKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = run(new NicheApiKeyFilter("secret"), "/api/v1/niches",
                "Bearer wrong", chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesWithRightKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = run(new NicheApiKeyFilter("secret"), "/api/v1/niches/123",
                "Bearer secret", chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void otherPathsNotFiltered() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run(new NicheApiKeyFilter("secret"), "/api/v1/sets/import", null, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingConfiguredKeyClosesEndpoints() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = run(new NicheApiKeyFilter(""), "/api/v1/niches",
                "Bearer anything", chain);
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(chain.getRequest()).isNull();
    }
}